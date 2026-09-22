package com.crypto.cryptowatch.data.ws

import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.IdeProxyAware
import com.crypto.cryptowatch.util.lower
import com.crypto.cryptowatch.util.proxyIfPresent
import com.crypto.cryptowatch.util.upper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
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
 * 实测得到的两个关键约束（直接决定了本类的实现方式）：
 * 1. **握手会被随机重置**：同一地址连续建连可能失败（同一 host 上 3 次里失败 2 次很常见），
 *    因此必须自动重连，这里采用指数退避（1→2→4→8→16→20 秒）并在成功后清零；
 * 2. **订阅数越大越容易静默失活**：订阅几十个流时可能"握手成功、订阅回执正常，
 *    但此后不再推送任何数据帧"，只靠 onError/onClose 无法察觉，
 *    因此额外设有静默看门狗（[SILENCE_TIMEOUT_SECONDS] 内没有数据帧即主动断线重连）。
 *
 * 端点选择：组合流入口 `/stream` 实测比 `/ws` 稳定（4/4 vs 2/4），
 * 且与之一样支持连接后发送 SUBSCRIBE 消息，所以统一走 `/stream`：
 * 订阅集合变化时只发送增量 SUBSCRIBE / UNSUBSCRIBE，无需重建连接。
 *
 * 线程模型：HTTP 客户端回调线程只负责解析与回调 [listener]，不做阻塞操作；
 * 重连与看门狗由独立的单线程调度器驱动。
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
            Thread(r, "CryptoWatch-WS").apply { isDaemon = true }
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

    /**
     * 最近一次 24h 精简行情（按交易对索引）。
     *
     * 逐笔成交帧只带成交价，需要用它补齐涨跌幅、24h 高低与成交额列。
     */
    private val lastStats = ConcurrentHashMap<String, Quote>()

    val isRunning: Boolean get() = running.get()

    // ------------------------------------------------------------------ 生命周期

    fun start() {
        if (!running.compareAndSet(false, true)) return
        attempt.set(0)
        connect()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        silenceWatchdog?.cancel(false)
        silenceWatchdog = null
        scheduledReconnect?.cancel(false)
        scheduledReconnect = null

        val ws = socket
        socket = null
        subscribed.clear()
        if (ws != null) {
            runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
            scheduler.schedule({ runCatching { ws.abort() } }, 1, TimeUnit.SECONDS)
        }
        listener.onState(ConnectionState.STOPPED, "已停止")
    }

    /**
     * 更新订阅集合。
     *
     * 连接已建立时立即发送增量 SUBSCRIBE / UNSUBSCRIBE；未建立时仅记录，
     * 待本次或下次连接成功后统一订阅。
     */
    fun updateStreams(streams: Collection<String>) {
        val target = streams.map { it.lower() }.toSet()
        desired.clear()
        desired.addAll(target)

        val current = socket ?: return
        val toAdd = desired.filter { it !in subscribed }
        val toRemove = subscribed.filter { it !in desired }

        if (toAdd.isNotEmpty()) {
            subscribed.addAll(toAdd)
            send(current, subscribeCommand(toAdd, id = 1))
        }
        if (toRemove.isNotEmpty()) {
            subscribed.removeAll(toRemove.toSet())
            send(current, subscribeCommand(toRemove, id = 2, method = "UNSUBSCRIBE"))
        }
    }

    // ------------------------------------------------------------------ 连接

    private fun connect() {
        if (!running.get()) return
        if (socket != null) return

        attempt.incrementAndGet()
        val first = attempt.get() <= 1
        listener.onState(
            if (first) ConnectionState.CONNECTING else ConnectionState.RECONNECTING,
            if (first) "连接中…" else "重连中（第 ${attempt.get()} 次）"
        )

        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .buildAsync(URI.create(STREAM_URL), Handler())
            .orTimeout(CONNECT_TIMEOUT_SECONDS + 4, TimeUnit.SECONDS)
            .whenComplete { ws, error ->
                if (error != null) {
                    listener.onState(ConnectionState.RECONNECTING, "连接失败：${rootMessage(error)}")
                    scheduleReconnect()
                    return@whenComplete
                }
                socket = ws
                attempt.set(0)
                lastFrameAt = System.currentTimeMillis()
                listener.onState(ConnectionState.CONNECTED, "已连接")

                // 重连后服务端不保留订阅状态，需要重新订阅全部流
                subscribed.clear()
                val all = desired.toList()
                if (all.isNotEmpty()) {
                    subscribed.addAll(all)
                    send(ws, subscribeCommand(all, id = 1))
                }
                startSilenceWatchdog()
            }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = minOf(BASE_BACKOFF_SECONDS shl minOf(attempt.get(), 4), MAX_BACKOFF_SECONDS)
        scheduledReconnect?.cancel(false)
        scheduledReconnect = runCatching {
            scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
        }.getOrNull()
    }

    /**
     * 静默看门狗。
     *
     * 实测订阅数量较多时会出现"连接正常但长时间不推送"的情况，必须主动重连。
     */
    private fun startSilenceWatchdog() {
        silenceWatchdog?.cancel(false)
        silenceWatchdog = scheduler.scheduleWithFixedDelay({
            if (running.get() && socket != null) {
                val idle = System.currentTimeMillis() - lastFrameAt
                if (idle > SILENCE_TIMEOUT_SECONDS * 1000L) {
                    listener.onState(ConnectionState.RECONNECTING, "长时间无推送，重连中…")
                    dropConnection()
                }
            }
        }, SILENCE_TIMEOUT_SECONDS, SILENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** 主动丢弃当前连接并触发重连（订阅集合保持不变）。 */
    private fun dropConnection() {
        val ws = socket
        socket = null
        subscribed.clear()
        if (ws != null) runCatching { ws.abort() }
        attempt.incrementAndGet()
        scheduleReconnect()
    }

    private fun send(ws: WebSocket, payload: String) {
        runCatching { ws.sendText(payload, true) }
            .onFailure {
                listener.onState(ConnectionState.RECONNECTING, "发送订阅失败：${rootMessage(it)}")
                dropConnection()
            }
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
                listener.onState(ConnectionState.RECONNECTING, "连接关闭（$statusCode）")
                scheduleReconnect()
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (socket === webSocket || socket == null) {
                socket = null
                subscribed.clear()
                listener.onState(ConnectionState.RECONNECTING, "连接异常：${rootMessage(error)}")
                scheduleReconnect()
            }
        }
    }

    /**
     * 解析组合流 payload。
     *
     * 行情帧形如 `{"stream":"btcusdt@miniTicker","data":{...}}`；
     * 订阅回执形如 `{"result":null,"id":1}`，不含行情字段，会被 [parseTicker] 自然忽略。
     */
    private fun handlePayload(payload: String) {
        lastFrameAt = System.currentTimeMillis()

        val quotes = ArrayList<Quote>(2)
        when (val parsed = Json.parse(payload)) {
            is List<*> -> parsed.forEach { item ->
                Json.obj(item)?.let { parseTicker(it)?.let(quotes::add) }
            }

            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = parsed as Map<String, Any?>
                val data = Json.obj(map["data"])
                if (data != null) {
                    parseTicker(data)?.let(quotes::add)
                } else {
                    parseTicker(map)?.let(quotes::add)
                }
            }

            else -> Unit
        }

        if (quotes.isNotEmpty()) listener.onQuotes(quotes)
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
     */
    private fun parseTicker(map: Map<String, Any?>): Quote? {
        val symbol = Json.str(map, "s")?.upper() ?: return null
        if (!symbol.endsWith("USDT")) return null
        val base = symbol.removeSuffix("USDT")
        if (base.isEmpty()) return null

        // 先尝试按 24h 精简行情解析（含最新价 c 与 24h 统计）
        val last = Json.num(map, "c")
        if (last != null) {
            if (last <= 0.0) return null
            val open = Json.num(map, "o")
            val quote = Quote(
                id = symbol,
                symbol = symbol,
                base = base,
                quote = "USDT",
                price = last,
                changePct = if (open != null && open > 0.0) (last - open) / open * 100.0 else Double.NaN,
                high24h = Json.num(map, "h"),
                low24h = Json.num(map, "l"),
                volume24h = Json.num(map, "v"),
                quoteVolume = Json.num(map, "q"),
                volumeIsQuote = false,
                sourceId = SOURCE_ID,
                category = MarketCategory.SPOT
            )
            lastStats[symbol] = quote
            return quote
        }

        // 逐笔成交帧：只有成交价 p，用缓存的 24h 统计补齐其余列
        val traded = Json.num(map, "p") ?: return null
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

        private const val CONNECT_TIMEOUT_SECONDS = 8L

        /** 超过该时长没有收到任何数据帧，即判定连接静默失效并重连。 */
        private const val SILENCE_TIMEOUT_SECONDS = 20L

        private const val BASE_BACKOFF_SECONDS = 1L
        private const val MAX_BACKOFF_SECONDS = 20L
    }
}
