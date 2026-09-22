package com.crypto.cryptowatch.data

import com.crypto.cryptowatch.data.ws.BinanceWsClient
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.model.SourceResult
import com.crypto.cryptowatch.settings.CryptoSettings
import com.crypto.cryptowatch.settings.WatchlistStore
import com.crypto.cryptowatch.util.lower
import com.crypto.cryptowatch.util.upper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
        val streamCount: Int
    ) {
        val failures: List<SourceResult.Failure> get() = sourceResults.filterIsInstance<SourceResult.Failure>()
        val successCount: Int get() = sourceResults.count { it is SourceResult.Success }
        val usingWebSocket: Boolean get() = wsState != null
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "CryptoWatch-Stream").apply { isDaemon = true }
        }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val wsClient = BinanceWsClient(object : BinanceWsClient.Listener {
        override fun onQuotes(quotes: List<Quote>) {
            quotes.forEach { liveQuotes[it.base.upper()] = it }
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

    /** 保护 [started] / [refCount] 的锁。 */
    private val lock = Any()

    private var started = false

    /** 当前有多少个工具窗口在使用本服务。 */
    private var refCount = 0
    private var pendingEmit: ScheduledFuture<*>? = null
    private var pendingRest: ScheduledFuture<*>? = null
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
        pendingRest?.cancel(false)
        pendingRest = null
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

    /** 手动刷新：重新拉一次 REST 列表（用于"刷新"按钮）。 */
    fun refreshNow() {
        refreshRest()
    }

    // ------------------------------------------------------------------ REST（币种列表 + 市值）

    private fun refreshRest() {
        if (!started) return
        // 单飞：若已有一次 REST 拉取在路上，跳过本次，避免重复请求
        if (pendingRest?.isDone == false) return

        pendingRest = scheduler.schedule({
            val snapshot = try {
                MarketService.getInstance().fetchREST()
            } catch (t: Throwable) {
                return@schedule
            }
            if (!started) return@schedule
            restQuotes = snapshot.quotes
            restResults = snapshot.sourceResults
            restFetchedAt = snapshot.fetchedAt
            // 新的列表可能带来新的热门币，需要重新推导订阅集合
            scheduleResubscribe()
            emitNow()
        }, 0, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------ WebSocket（实时价格）

    private fun startWs() {
        wsState = BinanceWsClient.ConnectionState.CONNECTING
        wsMessage = "连接中…"
        wsClient.start()
        scheduleResubscribe()
    }

    private fun stopWs() {
        wsClient.stop()
        wsState = null
        wsMessage = ""
        liveQuotes.clear()
        streamCount = 0
    }

    // ------------------------------------------------------------------ 订阅集合推导

    private fun scheduleResubscribe() {
        if (!started) return
        pendingResubscribe?.cancel(false)
        pendingResubscribe = scheduler.schedule({ applySubscriptions() }, RESUBSCRIBE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /** 按「自选」推导订阅流，并把结果推给 WS 客户端（只订阅自选币种）。 */
    private fun applySubscriptions() {
        if (!started || !CryptoSettings.getInstance().wsEnabled) return

        val bases = LinkedHashSet<String>()

        // 只订阅自选，保证自选一定有实时价，同时避免无谓的 WS 流量
        WatchlistStore.getInstance().allKeys().forEach { key ->
            baseOf(key)?.let(bases::add)
        }

        // 注意：每个币种会占 2 条流（trade + miniTicker），因此这里限制的是「币种数」。
        val limited = bases.filter { it.isNotBlank() }.take(MAX_BASES)
        streamCount = limited.size
        if (limited.isEmpty()) {
            // REST 还没回来时不发订阅，等列表到位后会自动重算
            emitNow()
            return
        }

        // 每个币种订阅两条流，各司其职：
        // - `@trade`：逐笔成交，毫秒级推送，负责「价格实时跳动」；
        // - `@miniTicker`：每秒一次，提供 24h 涨跌幅与成交额（trade 帧不含这些字段）。
        // 之所以不能只订阅 miniTicker：币安对该流固定 1 秒推送一次，1 秒内多数币种价格不变，
        // 看起来就像「价格几乎不动」。
        val streams = ArrayList<String>(limited.size * 2)
        limited.forEach { base ->
            val symbol = "${base.lower()}usdt"
            streams += "$symbol@trade"
            streams += "$symbol@miniTicker"
        }
        wsClient.updateStreams(streams)
        emitNow()
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
        return DataSnapshot(
            quotes = merged,
            sourceResults = restResults,
            restFetchedAt = restFetchedAt,
            liveCount = liveQuotes.size,
            wsState = wsState,
            wsMessage = wsMessage,
            streamCount = streamCount
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
