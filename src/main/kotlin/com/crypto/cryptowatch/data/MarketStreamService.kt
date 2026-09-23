package com.crypto.cryptowatch.data

import com.crypto.cryptowatch.data.ws.BinanceWsClient
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.model.SourceResult
import com.crypto.cryptowatch.settings.CryptoSettings
import com.crypto.cryptowatch.settings.WatchlistStore
import com.crypto.cryptowatch.ui.I18n
import com.crypto.cryptowatch.util.Symbols
import com.crypto.cryptowatch.util.upper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 行情流服务（应用级单例）：把「REST 币种列表」与「WebSocket 实时价」合成一份可持续推送的快照。
 *
 * 职责划分：
 * - [MarketService.fetchREST] 负责低频、全量的币种列表与市值（启动时 + 每 [REST_REFRESH_SECONDS] 秒兜底一次）；
 * - [BinanceWsClient] 负责所选币种的实时价格推送，断开时自动重连；
 * - 本服务负责把两者合成 [DataSnapshot] 并广播给订阅者，同时对高频推送做**合并节流**，
 *   避免每秒几十次地刷新 Swing 表格。
 *
 * 订阅集合的推导规则（只订阅自选币种）：
 * - 只订阅自选列表里的币种，保证自选一定有实时价，同时把 WS 流量压到最低；
 * - 总订阅数受 [MAX_BASES] 上限约束——实测订阅数越多，币安 WS 越容易出现
 *   "连上但收不到数据"的静默失效，因此必须有上限。
 *
 * 连接与生命周期：只在工具窗口需要时（[start] / [stop]）建连，没有订阅者时立即断开，
 * 不留任何后台连接，这与插件"不打扰用户"的定位一致。
 */
@Service(Service.Level.APP)
class MarketStreamService {

    /** 快照订阅者（通常是工具窗口）。 */
    fun interface Listener {
        fun onSnapshot(snapshot: DataSnapshot)
    }

    /** 一份可展示的行情快照。 */
    data class DataSnapshot(
        val quotes: List<Quote>,
        val sourceResults: List<SourceResult>,
        /** REST 列表的拉取时间（0 表示还没拉过）。 */
        val restFetchedAt: Long,
        /** 当前生效的实时报价数量。 */
        val liveCount: Int,
        /** WebSocket 状态；未启用时为 null。 */
        val wsState: BinanceWsClient.ConnectionState?,
        val wsMessage: String,
        /** 当前订阅的流数量。 */
        val streamCount: Int,
        /**
         * 自选中无法订阅实时行情的币种数量。
         *
         * 两种来源：符号本身非法（脏数据），或曾被币安以 `Invalid symbol` 拒绝。
         * 这类币种不会再出现在订阅里，只在列表中显示为占位行，必须显式告知用户，
         * 否则界面会永远停在「重连中」而看不出是哪个币种的问题。
         */
        val unsupportedCount: Int
    ) {
        val failures: List<SourceResult.Failure> get() = sourceResults.filterIsInstance<SourceResult.Failure>()
        val successCount: Int get() = sourceResults.count { it is SourceResult.Success }
        val usingWebSocket: Boolean get() = wsState != null
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "CryptoWatch-Stream").apply { isDaemon = true }
        }

    /**
     * REST 拉取专用执行器——**必须独立于 [scheduler]**。
     *
     * [scheduler] 是**单线程**，而 [MarketService.fetchREST] 会**阻塞数秒**
     * （多源并发 + 整体超时 12 秒，CoinLore 还要串行翻 4 页）。
     * 一旦把它丢进 [scheduler]，这个单线程就被占满，排在它后面的
     * [applySubscriptions]（下发订阅）与 [scheduleEmit]（广播快照）会全部被推迟。
     *
     * 由此产生的症状极具迷惑性：**WebSocket 已经连接（状态栏显示「已连接」），
     * 但「推送」长期为 0**。原因是 [subscribedBases] 还没被写入，
     * [onQuotes] 会把收到的每一帧都当成"不在订阅范围内"直接丢弃。
     *
     * 这里把阻塞 IO 移出调度线程，[scheduler] 只保留轻量的状态更新与派发。
     */
    private val restExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "CryptoWatch-Rest").apply { isDaemon = true }
        }

    /** REST 单飞标记：为 true 表示已有一次拉取在路上。 */
    private val restInFlight = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val wsClient = BinanceWsClient(object : BinanceWsClient.Listener {
        override fun onQuotes(quotes: List<Quote>) {
            // 只接受「当前正在订阅」的币种：服务端对 UNSUBSCRIBE 的处理存在延迟，
            // 若不过滤，退订后的残留推送会重新写回缓存，让实时数再次虚高。
            val allowed = subscribedBases
            if (allowed.isEmpty()) return
            quotes.forEach {
                val base = it.base.upper()
                if (base in allowed) liveQuotes[base] = it
            }
            scheduleEmit()
        }

        override fun onState(state: BinanceWsClient.ConnectionState, message: String) {
            wsState = state
            wsMessage = message
            scheduleEmit()
        }
    })

    /** base（大写）→ 最新实时报价。 */
    private val liveQuotes = ConcurrentHashMap<String, Quote>()

    /**
     * 当前生效的订阅币种集合（等于 [liveQuotes] 的允许范围）。
     *
     * 由 [applySubscriptions] 写入，[onQuotes] 只接受其中的币种，
     * 保证「实时条数」与「当前自选」永远一一对应。
     */
    @Volatile
    private var subscribedBases: Set<String> = emptySet()

    /** REST 拉到的币种列表（保持源返回顺序，即市值排名）。 */
    @Volatile
    private var restQuotes: List<Quote> = emptyList()

    @Volatile
    private var restResults: List<SourceResult> = emptyList()

    @Volatile
    private var restFetchedAt: Long = 0L

    @Volatile
    private var wsState: BinanceWsClient.ConnectionState? = null

    @Volatile
    private var wsMessage: String = ""

    @Volatile
    private var streamCount: Int = 0

    @Volatile
    private var unsupportedCount: Int = 0

    /** 保护 [started] / [refCount] 的锁。 */
    private val lock = Any()

    private var started = false

    /** 当前有多少个工具窗口在使用本服务。 */
    private var refCount = 0
    private var pendingEmit: ScheduledFuture<*>? = null
    private var pendingResubscribe: ScheduledFuture<*>? = null
    private var restTimer: ScheduledFuture<*>? = null

    private val watchlistListener: () -> Unit = { scheduleResubscribe() }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 由 UI 在需要展示行情时调用（工具窗口可见）。
     *
     * 服务是应用级单例，而同一时刻可能有多个项目窗口在展示行情，
     * 因此这里用引用计数：只有**第一个**窗口触发真正的建连，
     * 也只有**最后一个**窗口关闭时才断开，避免彼此互相打断。
     */
    fun start() {
        synchronized(lock) {
            refCount++
            if (started) {
                // 仍然主动重新算一次订阅，覆盖"窗口重开时设置已变化"的情况
                scheduleResubscribe()
                emitNow()
                return
            }
            started = true
        }

        WatchlistStore.getInstance().addListener(watchlistListener)

        restTimer = scheduler.scheduleWithFixedDelay(
            { refreshRest() },
            REST_REFRESH_SECONDS,
            REST_REFRESH_SECONDS,
            TimeUnit.SECONDS
        )

        // 每次启用服务都清掉历史「被拒流」记录：币安对同一交易对的接受度会变化，
        // 且用户可能已经改正了自选，重新尝试一次比永久拉黑更符合预期。
        wsClient.resetRejections()

        refreshRest()
        if (CryptoSettings.getInstance().wsEnabled) startWs()
        emitNow()
    }

    /** 由 UI 在工具窗口不可见/关闭时调用：最后一个使用者离开时才断开并清空缓存。 */
    fun stop() {
        synchronized(lock) {
            refCount = (refCount - 1).coerceAtLeast(0)
            if (refCount > 0) return
            if (!started) return
            started = false
        }

        runCatching { WatchlistStore.getInstance().removeListener(watchlistListener) }
        restTimer?.cancel(false)
        restTimer = null
        restInFlight.set(false)
        pendingResubscribe?.cancel(false)
        pendingResubscribe = null
        pendingEmit?.cancel(false)
        pendingEmit = null
        stopWs()
        liveQuotes.clear()
        restQuotes = emptyList()
        restResults = emptyList()
        restFetchedAt = 0L
        streamCount = 0
        unsupportedCount = 0
    }

    /** 设置变更时调用：同步 WS 开关、订阅数量与数据源集合。 */
    fun onSettingsChanged() {
        synchronized(lock) {
            if (!started) return
        }
        val settings = CryptoSettings.getInstance()
        if (settings.wsEnabled) {
            startWs()
        } else {
            stopWs()
        }
        refreshRest()
        scheduleResubscribe()
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        emitTo(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * 手动刷新：先按当前自选**重新下发订阅**，再拉一次 REST 列表。
     *
     * 两步都要做，且顺序固定：
     * - 重新订阅保证「刷新后订阅的正是当前几个自选币种」，而不是沿用历史订阅；
     * - 再拉 REST 则让列表与市值也刷新一遍。
     */
    fun refreshNow() {
        // 手动刷新是用户明确的「重试」意图：清空历史被拒记录，
        // 让此前被隔离的币种再试一次（若仍非法，会被再次隔离，不会形成死循环，
        // 因为非法符号在 [desiredBases] 阶段就已经被剔除）。
        wsClient.resetRejections()
        refreshSubscription(force = true)
        refreshRest()
    }

    /**
     * 立即（跳过去抖）按当前自选重算订阅集合。
     *
     * @param force 见 [BinanceWsClient.updateStreams]：清空本地已订阅记录后全量重发。
     */
    fun refreshSubscription(force: Boolean = false) {
        if (!started) return
        pendingResubscribe?.cancel(false)
        pendingResubscribe = null
        applySubscriptions(force)
    }

    // ------------------------------------------------------------------ REST（币种列表 + 市值）

    private fun refreshRest() {
        if (!started) return
        // 单飞：若已有一次 REST 拉取在路上，跳过本次，避免重复请求
        if (!restInFlight.compareAndSet(false, true)) return

        // 阻塞的拉取放在 [restExecutor]，绝不能在 [scheduler] 上执行（见 restExecutor 注释）
        restExecutor.execute rest@{
            val snapshot = try {
                MarketService.getInstance().fetchREST()
            } catch (t: Throwable) {
                null
            } finally {
                restInFlight.set(false)
            }
            if (snapshot == null) return@rest
            // 结果并回状态时再切到 [scheduler]，保证与订阅/广播串行、无竞态
            scheduler.execute {
                if (!started) return@execute
                restQuotes = snapshot.quotes
                restResults = snapshot.sourceResults
                restFetchedAt = snapshot.fetchedAt
                // 新的列表可能带来新的热门币，需要重新推导订阅集合
                scheduleResubscribe()
                emitNow()
            }
        }
    }

    // ------------------------------------------------------------------ WebSocket（实时价格）

    private fun startWs() {
        wsState = BinanceWsClient.ConnectionState.CONNECTING
        wsMessage = I18n.text("ws.connecting")
        wsClient.start()
        scheduleResubscribe()
    }

    private fun stopWs() {
        wsClient.stop()
        wsState = null
        wsMessage = ""
        // 同步清空「已订阅」与实时缓存，避免关闭 WS 后状态栏仍残留旧的实时条数
        subscribedBases = emptySet()
        liveQuotes.clear()
        streamCount = 0
    }

    // ------------------------------------------------------------------ 订阅集合推导

    private fun scheduleResubscribe(force: Boolean = false) {
        if (!started) return
        pendingResubscribe?.cancel(false)
        pendingResubscribe = scheduler.schedule(
            { applySubscriptions(force) },
            RESUBSCRIBE_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * 当前应当订阅的基础币种：由自选推导（统一大写、去重、受 [MAX_BASES] 上限约束）。
     *
     * 注意上限约束的是「币种数」——每个币种会占 2 条流（trade + miniTicker）。
     *
     * 关键前置过滤：只保留**符号合法**的自选键。把不存在的交易对（脏数据）写进 SUBSCRIBE
     * 会让币安直接断开连接，客户端随即重连、又下发同一批非法流，于是界面永久停在「重连中」。
     * 这里剔除后，非法自选只会表现为列表里的占位行，不再影响连接。
     */
    private fun desiredBases(limit: Int = MAX_BASES): Set<String> =
        WatchlistStore.getInstance().allKeys()
            .asSequence()
            .filter { Symbols.isValidKey(it) }
            .mapNotNull { baseOf(it) }
            .take(limit)
            .toSet()

    /**
     * 自选里「订阅不上」的币种数量 = 符号非法 + 被服务端拒绝。
     *
     * 仅用于状态栏提示。用户最困惑的场景就是「明明只有一个币有问题，界面却一直重连」，
     * 把这个数量显示出来，问题就从"玄学"变成了"这一条数据有问题"。
     */
    private fun countUnsupported(): Int {
        val keys = WatchlistStore.getInstance().allKeys()
        val rejected = rejectedBases()
        return keys.count { key ->
            !Symbols.isValidKey(key) || (baseOf(key)?.let { it in rejected } == true)
        }
    }

    /**
     * 从 WS 客户端隔离的流名（形如 `takeusdt@aggTrade`、`btcusd_perp@miniTicker`）
     * 反推出基础币种。
     *
     * 统一交给 [Symbols.splitStream] 处理，而不是在这里硬编 `indexOf("usdt@")`：
     * 合约存在币本位代码（`BTCUSD_PERP`），用固定字符串反推会把计价部分算进币种名，
     * 导致状态栏的"无实时行情"计数与实际对不上。
     */
    private fun rejectedBases(): Set<String> =
        wsClient.rejectedStreams().mapNotNullTo(HashSet()) { stream ->
            Symbols.splitStream(stream)?.first
        }

    /**
     * 按「自选」推导订阅流，并把结果推给 WS 客户端（只订阅自选币种）。
     *
     * 这里是订阅集合的**唯一事实来源**：除了下发订阅，还负责把实时缓存 [liveQuotes]
     * 收敛到「当前自选」范围内。早先的实现只订阅、从不收缩缓存，于是曾经订阅过的
     * 币种会永久留在缓存里——状态栏显示的「已推送 N 个」只增不减，
     * 列表里也会冒出早已不再订阅的幽灵行。
     */
    private fun applySubscriptions(force: Boolean = false) {
        if (!started || !CryptoSettings.getInstance().wsEnabled) return

        // 只订阅自选，保证自选一定有实时价，同时避免无谓的 WS 流量
        val bases = desiredBases()
        streamCount = bases.size

        if (bases.isEmpty()) {
            // 自选为空时必须显式下发取消订阅：否则服务端仍会持续推送旧流，
            // 缓存里那些币种也就永远清不掉。
            subscribedBases = emptySet()
            wsClient.updateStreams(emptyList())
            syncLiveQuotes(emptySet())
            emitNow()
            return
        }

        // 每个币种订阅两条流，各司其职：
        // - `@aggTrade`：逐笔成交，毫秒级推送，负责「价格实时跳动」。
        //   注意合约的成交流名是 **aggTrade**，现货才叫 trade，写错会静默收不到数据；
        // - `@miniTicker`：每秒一次，提供 24h 涨跌幅与成交额（成交帧不含这些字段）。
        // 之所以不能只订阅 miniTicker：币安对该流固定 1 秒推送一次，1 秒内多数币种价格不变，
        // 看起来就像「价格几乎不动」。
        val streams = ArrayList<String>(bases.size * 2)
        bases.forEach { base ->
            // 统一由 [Symbols.binanceStream] 生成流名：它在拼装前会再校验一次符号，
            // 任何非法组合都返回 null 并被跳过，绝不让脏数据到达服务端。
            Symbols.binanceStream(base, suffix = "aggTrade")?.let(streams::add)
            Symbols.binanceStream(base, suffix = "miniTicker")?.let(streams::add)
        }
        // 先收窄允许范围，再下发订阅：这样即便服务端对 UNSUBSCRIBE 有延迟，
        // 残留推送也会在 [onQuotes] 里被丢弃。
        subscribedBases = bases
        wsClient.updateStreams(streams, force)
        syncLiveQuotes(bases)
        emitNow()
    }

    /**
     * 把实时缓存收敛到 [bases]：不再订阅的币种立即从缓存移除。
     *
     * 不这样做的话缓存只增不减，`liveCount` 会随使用时间持续变大，
     * 用户就会看到「只自选了几个币，却提示实时 200 条」这种自相矛盾的状态。
     */
    private fun syncLiveQuotes(bases: Set<String>) {
        liveQuotes.keys.retainAll { it in bases }
    }

    /** 从 "BTC/USDT" 之类的键里取出基础币种。 */
    private fun baseOf(key: String): String? =
        key.substringBefore('/').trim().upper().takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ 广播

    /** 合并节流：WS 每秒会推很多帧，统一压到最多每 [EMIT_INTERVAL_MS] 毫秒一次。 */
    private fun scheduleEmit() {
        if (!started) return
        if (pendingEmit?.isDone == false) return
        pendingEmit = scheduler.schedule({ emitNow() }, EMIT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun emitNow() {
        val snapshot = buildSnapshot()
        listeners.forEach { runCatching { it.onSnapshot(snapshot) } }
    }

    private fun emitTo(listener: Listener) {
        runCatching { listener.onSnapshot(buildSnapshot()) }
    }

    private fun buildSnapshot(): DataSnapshot {
        val merged = MarketService.overlayLive(restQuotes, liveQuotes)
        val unsupported = countUnsupported()
        unsupportedCount = unsupported
        return DataSnapshot(
            quotes = merged,
            sourceResults = restResults,
            restFetchedAt = restFetchedAt,
            liveCount = liveQuotes.size,
            wsState = wsState,
            wsMessage = wsMessage,
            streamCount = streamCount,
            unsupportedCount = unsupported
        )
    }

    companion object {
        /** REST 兜底刷新间隔：列表与市值变化很慢，低频即可，且断线期间价格也靠它兜底。 */
        private const val REST_REFRESH_SECONDS = 60L

        /** 订阅集合变化后的去抖时间，避免连续操作触发多次订阅命令。 */
        private const val RESUBSCRIBE_DEBOUNCE_MS = 400L

        /**
         * 快照广播的最小间隔。
         *
         * 逐笔成交是毫秒级推送，必须有节流，否则 Swing 会被刷爆；
         * 但也不能太大——这正是「价格看着不动」的原因之一，故取 200ms（约 5 帧/秒）。
         */
        private const val EMIT_INTERVAL_MS = 200L

        /**
         * 单连接订阅的**币种数**上限（每条币种对应 2 条流，即最多约 200 条流）。
         *
         * 实测订阅数量越大，币安 WS 越容易出现"握手成功但不再推送"的静默失效，故设上限。
         */
        private const val MAX_BASES = 100

        fun getInstance(): MarketStreamService =
            ApplicationManager.getApplication().getService(MarketStreamService::class.java)
    }
}
