package com.crypto.cryptowatch.data.ws

import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.ui.I18n
import com.crypto.cryptowatch.util.IdeProxyAware
import com.crypto.cryptowatch.util.lower
import com.crypto.cryptowatch.util.proxyIfPresent
import com.crypto.cryptowatch.util.upper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 币安 WebSocket 行情客户端（只读公开行情通道）。
 *
 * 为什么用 WebSocket：受限网络下币安的 REST 域名（`api.binance.com`）不可达，
 * 但其市场数据专用域名 `data-stream.binance.vision` 的 WebSocket 可以连通，
 * 于是用「一条长连接 + 服务端推送」替代轮询，价格更实时且几乎不产生额外流量。
 *
 * 实测得到的三个关键约束（直接决定了本类的实现方式）：
 * 1. **握手会被随机重置**：同一地址连续建连可能失败（同一 host 上 3 次里失败 2 次很常见），
 *    因此必须自动重连，这里采用指数退避（1→2→4→8→16→20 秒）并在成功后清零；
 * 2. **订阅数越大越容易静默失活**：订阅几十个流时可能"握手成功、订阅回执正常，
 *    但此后不再推送任何数据帧"，只靠 onError/onClose 无法察觉，
 *    因此额外设有静默看门狗（[SILENCE_TIMEOUT_SECONDS] 内没有数据帧即主动断线重连）；
 * 3. **非法交易对会让服务端直接断开连接**：若把不存在的交易对（例如 `000USDT`）
 *    写进 SUBSCRIBE，服务端回一条 error 回执后即关闭连接；客户端自动重连后又下发同一批
 *    非法流，于是形成「连上→断开→重连」的死循环，界面上就永久停在「重连中」。
 *    这里通过三步根治：解析订阅回执（[handleSubscriptionResult]）识别被拒流、
 *    把被拒流加入 [rejected] 并在后续订阅中永久跳过（[quarantine]），
 *    并主动触发一次"干净"的重连让剩余合法流立即恢复。
 *
 * 端点选择：组合流入口 `/stream` 实测比 `/ws` 稳定（4/4 vs 2/4），
 * 且与之一样支持连接后发送 SUBSCRIBE 消息，所以统一走 `/stream`：
 * 订阅集合变化时只发送增量 SUBSCRIBE / UNSUBSCRIBE，无需重建连接。
 *
 * 线程模型：HTTP 客户端回调线程只负责解析与回调 [listener]，不做阻塞操作；
 * 重连、看门狗与**所有 WebSocket 写操作**都由独立的单线程调度器驱动——
 * JDK 的 `WebSocket.sendText` 不是线程安全的，从多个线程并发写会抛
 * `IllegalStateException: Send pending`，因此所有发送都先入队再串行下发。
 */
class BinanceWsClient(private val listener: Listener) {

    interface Listener {
        /** 每收到一条行情就回调一次（一帧可能包含多个币种）。 */
        fun onQuotes(quotes: List<Quote>)

        /** 连接状态变化，用于状态栏展示。 */
        fun onState(state: ConnectionState, message: String)
    }

    enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, STOPPED }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, SEND_THREAD_NAME).apply { isDaemon = true }
        }

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .proxyIfPresent(IdeProxyAware.selector())
            .build()
    }

    /** 期望订阅的流（形如 "btcusdt@miniTicker"）。 */
    private val desired = CopyOnWriteArraySet<String>()

    /** 已发送过订阅、且连接未中断的流，用于计算增量命令。 */
    private val subscribed = CopyOnWriteArraySet<String>()

    /**
     * 被服务端明确拒绝过的流（通常是因为交易对不存在）。
     *
     * 这是「加入自选后卡在重连中」的根治手段：只要某个流被拒绝过一次，
     * 就不再把它放进 [desired]，从而打破「重连 → 下发非法流 → 被断开 → 再重连」的死循环。
     */
    private val rejected = CopyOnWriteArraySet<String>()

    private val running = AtomicBoolean(false)
    private val attempt = AtomicInteger(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var silenceWatchdog: ScheduledFuture<*>? = null

    @Volatile
    private var scheduledReconnect: ScheduledFuture<*>? = null

    @Volatile
    private var lastFrameAt: Long = 0L

    /** 是否成功连上过（用于区分「连接中」与「重连中」的文案）。 */
    @Volatile
    private var everConnected: Boolean = false

    /**
     * 连接世代号。
     *
     * 连接一旦被丢弃就自增，所有延迟任务（重连、看门狗）在启动时记录当时的世代号，
     * 执行时世代号不一致就直接放弃。这样「旧连接的看门狗」与「已排队的重连任务」
     * 都不会再去动新连接——否则一次网络抖动可能把刚建好的连接又拆掉。
     */
    @Volatile
    private var connectionGen: Int = 0

    private val connectLock = Any()
    private val sendLock = Any()

    /** 待发送队列；只在 [SEND_THREAD_NAME] 线程上被消费。 */
    private val sendQueue = ArrayDeque<Pair<WebSocket, String>>()

    @Volatile
    private var drainScheduled: Boolean = false

    /**
     * 最近一次 24h 精简行情（按交易对索引）。
     *
     * 逐笔成交帧只带成交价，需要用它补齐涨跌幅、24h 高低与成交额列。
     */
    private val lastStats = ConcurrentHashMap<String, Quote>()

    val isRunning: Boolean get() = running.get()

    /**
     * 当前被服务端拒绝（隔离）的流名，例如 `["000usdt@trade", "000usdt@miniTicker"]`。
     *
     * 由 [com.crypto.cryptowatch.data.MarketStreamService] 换算成"无法订阅的币种数"
     * 展示在状态栏，让用户能直接看出是哪个自选拖住了连接。
     */
    fun rejectedStreams(): Set<String> = rejected.toSet()

    /**
     * 清空隔离记录（下次连接会重新尝试这些流）。
     *
     * 仅在「服务重新启用」与「用户手动刷新」时调用：两种情况都表达同一语义——
     * 用户希望按当前自选从头再订阅一次，而不是永久沿用上一次的失败结果。
     */
    fun resetRejections() = rejected.clear()

    // ------------------------------------------------------------------ 生命周期

    fun start() {
        if (!running.compareAndSet(false, true)) return
        attempt.set(0)
        everConnected = false
        rejected.clear()
        connect()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        connectionGen++
        silenceWatchdog?.cancel(false)
        silenceWatchdog = null
        scheduledReconnect?.cancel(false)
        scheduledReconnect = null

        val ws = socket
        socket = null
        subscribed.clear()
        desired.clear()
        synchronized(sendLock) {
            sendQueue.clear()
            drainScheduled = false
        }
        if (ws != null) runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
        if (ws != null) runCatching { scheduler.schedule({ runCatching { ws.abort() } }, 1, TimeUnit.SECONDS) }
        listener.onState(ConnectionState.STOPPED, I18n.text("ws.stopped"))
    }

    /**
     * 更新订阅集合。
     *
     * 连接已建立时立即发送增量 SUBSCRIBE / UNSUBSCRIBE；未建立时仅记录，
     * 待本次或下次连接成功后统一订阅。
     *
     * @param force 为 true 时丢弃本地「已订阅」记录并重发全部订阅，
     *              用于「刷新列表」这类希望严格按当前自选重新订阅的场景。
     *              目标集合为空时保持普通增量语义（正好退化为取消全部旧订阅），
     *              否则会清掉本地记录却不下发 UNSUBSCRIBE，与服务端状态不一致。
     */
    fun updateStreams(streams: Collection<String>, force: Boolean = false) {
        // 被服务端拒绝过的流不再尝试，否则每次重连都会把连接重新打断
        val target = streams.map { it.lower() }.filterNot { it in rejected }.toSet()
        desired.clear()
        desired.addAll(target)

        val current = socket ?: return
        if (force && target.isNotEmpty()) subscribed.clear()
        val toAdd = desired.filter { it !in subscribed }
        val toRemove = subscribed.filter { it !in desired }

        if (toAdd.isNotEmpty()) {
            subscribed.addAll(toAdd)
            send(current, subscribeCommand(toAdd, id = SUBSCRIBE_ID))
        }
        if (toRemove.isNotEmpty()) {
            subscribed.removeAll(toRemove.toSet())
            send(current, subscribeCommand(toRemove, id = UNSUBSCRIBE_ID, method = "UNSUBSCRIBE"))
        }
    }

    // ------------------------------------------------------------------ 连接

    private fun connect() {
        if (!running.get()) return
        synchronized(connectLock) {
            if (socket != null) return
        }

        attempt.incrementAndGet()
        val first = !everConnected
        listener.onState(
            if (first) ConnectionState.CONNECTING else ConnectionState.RECONNECTING,
            if (first) I18n.text("ws.connecting") else I18n.text("ws.reconnecting", attempt.get())
        )

        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .buildAsync(URI.create(STREAM_URL), Handler())
            .orTimeout(CONNECT_TIMEOUT_SECONDS + 4, TimeUnit.SECONDS)
            .whenComplete { ws, error ->
                if (error != null) {
                    listener.onState(ConnectionState.RECONNECTING, I18n.text("ws.connectFailed", rootMessage(error)))
                    scheduleReconnect()
                    return@whenComplete
                }
                // connect() 可能被并发调用（看门狗 + 退避定时器），这里必须保证
                // 只有第一个到达的握手结果能成为当前连接，其余一律丢弃，
                // 否则会出现"多条连接同时存活"，且后建的那条会覆盖 socket 引用。
                synchronized(connectLock) {
                    if (!running.get() || socket != null) {
                        runCatching { ws.abort() }
                        return@whenComplete
                    }
                    socket = ws
                }
                connectionGen++
                everConnected = true
                attempt.set(0)
                lastFrameAt = System.currentTimeMillis()
                listener.onState(ConnectionState.CONNECTED, I18n.text("ws.connected"))

                // 重连后服务端不保留订阅状态，需要重新订阅全部流
                subscribed.clear()
                val all = desired.toList()
                if (all.isNotEmpty()) {
                    subscribed.addAll(all)
                    send(ws, subscribeCommand(all, id = SUBSCRIBE_ID))
                }
                startSilenceWatchdog()
            }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val gen = connectionGen
        val delay = minOf(BASE_BACKOFF_SECONDS shl minOf(attempt.get(), 4), MAX_BACKOFF_SECONDS)
        scheduledReconnect?.cancel(false)
        scheduledReconnect = runCatching {
            // 世代号校验：若期间已经成功建立了新连接，这次排队中的重连直接作废
            scheduler.schedule({ if (gen == connectionGen) connect() }, delay, TimeUnit.SECONDS)
        }.getOrNull()
    }

    /**
     * 静默看门狗。
     *
     * 实测订阅数量较多时会出现"连接正常但长时间不推送"的情况，必须主动重连。
     */
    private fun startSilenceWatchdog() {
        silenceWatchdog?.cancel(false)
        val gen = connectionGen
        silenceWatchdog = scheduler.scheduleWithFixedDelay({
            // 只对"我负责的那一代连接"生效，避免旧看门狗拆掉新连接
            if (!running.get() || gen != connectionGen) return@scheduleWithFixedDelay
            if (socket != null) {
                val idle = System.currentTimeMillis() - lastFrameAt
                if (idle > SILENCE_TIMEOUT_SECONDS * 1000L) {
                    dropConnection(I18n.text("ws.silent"))
                }
            }
        }, SILENCE_TIMEOUT_SECONDS, SILENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * 主动丢弃当前连接并触发重连（订阅集合保持不变）。
     *
     * @param reason 非空时先上报一次状态文案；为 null 表示调用方自己已经报过状态，
     *               不希望产生两条互相覆盖的消息。
     */
    private fun dropConnection(reason: String?) {
        val ws = socket
        if (ws != null) {
            socket = null
            subscribed.clear()
            closeAndDrop(ws)
        }
        if (reason != null) listener.onState(ConnectionState.RECONNECTING, reason)
        attempt.incrementAndGet()
        scheduleReconnect()
    }

    /**
     * 关闭并强制释放一条连接。
     *
     * 在 [SEND_THREAD_NAME] 线程上必须直接 [WebSocket.abort]：
     * `sendClose` 与 `abort` 都属于"发送"，从非发送线程发起的 abort 会被 JDK
     * 丢弃甚至抛 `IllegalStateException`，结果是连接表面上被丢弃、实际仍未关闭，
     * 而重连又因为端口/连接数被占用而持续失败——这正是"一直重连不上"的常见原因。
     */
    private fun closeAndDrop(ws: WebSocket) {
        if (Thread.currentThread().name == SEND_THREAD_NAME) {
            runCatching { ws.abort() }
            return
        }
        runCatching {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "drop").whenComplete { _, _ ->
                runCatching { ws.abort() }
            }
        }
        // 兜底：1 秒内没关掉就强拆，保证不会残留半开连接
        runCatching { scheduler.schedule({ runCatching { ws.abort() } }, 1, TimeUnit.SECONDS) }
    }

    // ------------------------------------------------------------------ 发送（串行化）

    /**
     * 发送一条文本帧。
     *
     * JDK 的 [WebSocket.sendText] 不是线程安全的：如果上一条发送尚未完成就再次调用，
     * 会抛 `IllegalStateException: Send pending`。而信号来自两处互不相关的线程
     * （调用方直接订阅、以及 HTTP 回调里的重连订阅），因此统一改为"入队 + 单线程串行下发"。
     */
    private fun send(ws: WebSocket, payload: String) {
        if (Thread.currentThread().name == SEND_THREAD_NAME) {
            performSend(ws, payload)
            return
        }
        synchronized(sendLock) {
            sendQueue.addLast(ws to payload)
            if (drainScheduled) return
            drainScheduled = true
        }
        runCatching { scheduler.execute { drainSends() } }
            .onFailure { synchronized(sendLock) { drainScheduled = false } }
    }

    private fun drainSends() {
        while (true) {
            val next = synchronized(sendLock) {
                val head = sendQueue.pollFirst()
                if (head == null) drainScheduled = false
                head
            } ?: return
            performSend(next.first, next.second)
        }
    }

    private fun performSend(ws: WebSocket, payload: String) {
        runCatching { ws.sendText(payload, true) }
            .onFailure { failConnection(ws, I18n.text("ws.sendFailed", rootMessage(it))) }
    }

    /** 发送失败即视为该连接不可用：拆掉它并走正常重连流程。 */
    private fun failConnection(ws: WebSocket, message: String) {
        if (socket !== ws) return
        socket = null
        subscribed.clear()
        runCatching { ws.abort() }
        listener.onState(ConnectionState.RECONNECTING, message)
        attempt.incrementAndGet()
        scheduleReconnect()
    }

    private fun subscribeCommand(streams: List<String>, id: Int, method: String = "SUBSCRIBE"): String =
        "{\"method\":\"$method\",\"params\":[${streams.joinToString(",") { "\"$it\"" }}],\"id\":$id}"

    // ------------------------------------------------------------------ WebSocket 回调

    private inner class Handler : WebSocket.Listener {

        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val payload = buffer.toString()
                buffer.setLength(0)
                runCatching { handlePayload(payload) }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (socket === webSocket) {
                socket = null
                subscribed.clear()
                listener.onState(ConnectionState.RECONNECTING, I18n.text("ws.closed", statusCode))
                scheduleReconnect()
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (socket === webSocket || socket == null) {
                socket = null
                subscribed.clear()
                listener.onState(ConnectionState.RECONNECTING, I18n.text("ws.error", rootMessage(error)))
                scheduleReconnect()
            }
        }
    }

    /**
     * 解析组合流 payload。
     *
     * 三类帧需要区分处理：
     * - 行情帧：`{"stream":"btcusdt@miniTicker","data":{...}}`；
     * - 订阅成功回执：`{"result":null,"id":1}`；
     * - **订阅失败回执**：`{"error":{"code":2,"msg":"Invalid request: ...","data":"000usdt@trade"},"id":1}`
     *   —— 必须专门识别，见 [handleSubscriptionResult]。
     */
    private fun handlePayload(payload: String) {
        lastFrameAt = System.currentTimeMillis()

        val quotes = ArrayList<Quote>(2)
        when (val parsed = Json.parse(payload)) {
            is List<*> -> parsed.forEach { item ->
                Json.obj(item)?.let { parseFrame(it, quotes) }
            }

            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = parsed as Map<String, Any?>
                val data = Json.obj(map["data"])
                if (data != null) {
                    parseFrame(data, quotes)
                } else {
                    parseFrame(map, quotes)
                }
            }

            else -> Unit
        }

        if (quotes.isNotEmpty()) listener.onQuotes(quotes)
    }

    /** 处理单帧：订阅回执走 [handleSubscriptionResult]，其余按行情解析。 */
    private fun parseFrame(map: Map<String, Any?>, out: MutableList<Quote>) {
        if (map.containsKey("error") && !map.containsKey("c") && !map.containsKey("p")) {
            handleSubscriptionResult(map)
            return
        }
        parseTicker(map)?.let(out::add)
    }

    /**
     * 处理订阅回执中的 error 分支——这是「加入自选后一直重连」的直接凶手。
     *
     * 服务端对不存在的交易对会返回：
     * `{"error":{"code":2,"msg":"Invalid request: ...","data":"000usdt@trade"},"id":1}`，
     * 并**随即关闭连接**。客户端原本只是"无脑重连"，重连后又下发同一批非法流，
     * 于是连接永远建不起来、状态栏永远停在「重连中」。
     *
     * 这里把被拒绝的流从 [desired] / [subscribed] 中剔除并记入 [rejected]，
     * 再主动重连一次——重连时后续订阅里已经不再包含非法流，因此能立刻恢复推送。
     */
    private fun handleSubscriptionResult(map: Map<String, Any?>) {
        val error = Json.obj(map["error"]) ?: return
        val msg = Json.str(error, "msg") ?: "unknown error"
        val data = Json.str(error, "data")?.lower().orEmpty()

        val offending = desired.filter { it.lower() == data || (data.isNotEmpty() && data.contains(it.lower())) }
        quarantine(offending, msg)
    }

    /** 把（因符号非法而被拒的）流隔离出去，并做一次干净的重连。 */
    private fun quarantine(streams: Collection<String>, reason: String) {
        if (streams.isEmpty()) {
            // 与具体流无关的错误（例如超出订阅上限），保留连接、只上报原因
            listener.onState(ConnectionState.RECONNECTING, reason)
            return
        }
        rejected.addAll(streams)
        desired.removeAll(streams.toSet())
        subscribed.removeAll(streams.toSet())
        // 隔离后必须重连：被拒的流可能已经让服务端决定断开当前连接，
        // 主动重连一次可以让剩余合法流立刻恢复，而不是等看门狗超时。
        dropConnection("${I18n.text("ws.streamRejected")} (${streams.joinToString(", ")})")
    }

    /**
     * 把行情帧解析成 [Quote]。
     *
     * 支持两类帧，两者分工不同：
     * - **`@trade`（逐笔成交）**：字段 `s`/`p`(成交价)。每次成交即推送，**毫秒级**，
     *   是价格「实时跳动」的来源；
     * - **`@miniTicker`（24h 精简行情）**：字段 `c`(最新价)/`o`(开盘价)/`h`/`l`/`v`/`q`。
     *   币安对该流**固定每秒只推一次**，因此不能只靠它做实时价，但它是唯一能给出
     *   24h 涨跌幅与成交额的口径（miniTicker 不含涨跌幅字段，用 (c-o)/o 计算，
     *   与交易所展示一致）。
     *
     * 由于 `@trade` 帧不含 24h 统计，这里把最近一次 miniTicker 的统计值缓存到
     * [lastStats]，在成交帧上补齐涨跌幅与成交额——否则高频价格会把 24h 列冲成空白。
     *
     * 注意字段名大小写：组合流 `/stream` 在部分网关下会把字段名首字母**小写**
     * （`s`/`c`/`p` 变成 `s`/`c`/`p` 的大写形式则始终一致），因此下面统一做大小写无关取值。
     */
    private fun parseTicker(map: Map<String, Any?>): Quote? {
        val symbol = field(map, "s")?.upper() ?: return null
        if (!symbol.endsWith("USDT")) return null
        val base = symbol.removeSuffix("USDT")
        if (base.isEmpty()) return null

        // 先尝试按 24h 精简行情解析（含最新价 c 与 24h 统计）
        val last = fieldNum(map, "c")
        if (last != null) {
            if (last <= 0.0) return null
            val open = fieldNum(map, "o")
            val quote = Quote(
                id = symbol,
                symbol = symbol,
                base = base,
                quote = "USDT",
                price = last,
                changePct = if (open != null && open > 0.0) (last - open) / open * 100.0 else Double.NaN,
                high24h = fieldNum(map, "h"),
                low24h = fieldNum(map, "l"),
                volume24h = fieldNum(map, "v"),
                quoteVolume = fieldNum(map, "q"),
                volumeIsQuote = false,
                sourceId = SOURCE_ID,
                category = MarketCategory.SPOT
            )
            lastStats[symbol] = quote
            return quote
        }

        // 逐笔成交帧：只有成交价 p，用缓存的 24h 统计补齐其余列
        val traded = fieldNum(map, "p") ?: return null
        if (traded <= 0.0) return null
        val stats = lastStats[symbol]
        return Quote(
            id = symbol,
            symbol = symbol,
            base = base,
            quote = "USDT",
            price = traded,
            changePct = stats?.changePct ?: Double.NaN,
            high24h = stats?.high24h,
            low24h = stats?.low24h,
            volume24h = stats?.volume24h,
            quoteVolume = stats?.quoteVolume,
            volumeIsQuote = false,
            sourceId = SOURCE_ID,
            category = MarketCategory.SPOT
        )
    }

    /** 大小写无关地取单个字符串字段（币安个别网关会下发首字母小写的字段名）。 */
    private fun field(map: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            Json.str(map[key])?.let { return it }
            Json.str(map[key.lower()])?.let { return it }
            Json.str(map[key.upper()])?.let { return it }
        }
        return null
    }

    /** 大小写无关地取单个数值字段。 */
    private fun fieldNum(map: Map<String, Any?>, vararg keys: String): Double? {
        for (key in keys) {
            Json.num(map[key])?.let { return it }
            Json.num(map[key.lower()])?.let { return it }
            Json.num(map[key.upper()])?.let { return it }
        }
        return null
    }

    private fun rootMessage(t: Throwable): String {
        var cur: Throwable? = t
        while (cur?.cause != null && cur.cause !== cur) cur = cur.cause
        return cur?.message ?: t.javaClass.simpleName
    }

    companion object {
        /**
         * 币安市场数据专用 WebSocket 入口。
         *
         * `stream.binance.com` 与 `fstream.binance.com` 在受限网络下不可达，
         * 实测仅 `data-stream.binance.vision` 可连通（该域名的官方定位就是公开市场数据）。
         */
        const val STREAM_URL: String = "wss://data-stream.binance.vision/stream"

        /** 数据源标识，UI「来源」列会映射为可读名称。 */
        const val SOURCE_ID: String = "binance-ws"

        /** 发送线程名，用于判断「当前是否已在发送线程上」以及全局串行发送。 */
        private const val SEND_THREAD_NAME = "CryptoWatch-WS"

        private const val SUBSCRIBE_ID = 1
        private const val UNSUBSCRIBE_ID = 2

        private const val CONNECT_TIMEOUT_SECONDS = 8L

        /** 超过该时长没有收到任何数据帧，即判定连接静默失效并重连。 */
        private const val SILENCE_TIMEOUT_SECONDS = 20L

        private const val BASE_BACKOFF_SECONDS = 1L
        private const val MAX_BACKOFF_SECONDS = 20L
    }
}
