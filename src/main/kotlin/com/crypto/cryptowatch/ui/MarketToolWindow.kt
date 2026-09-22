package com.crypto.cryptowatch.ui

import com.crypto.cryptowatch.data.MarketStreamService
import com.crypto.cryptowatch.data.ws.BinanceWsClient
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.settings.CryptoSettings
import com.crypto.cryptowatch.settings.SettingsNotifier
import com.crypto.cryptowatch.settings.WatchlistStore
import com.crypto.cryptowatch.util.Async
import com.crypto.cryptowatch.util.Http
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 工具窗口内容：上方币种列表 + 下方精简信息小框 + 状态栏。
 *
 * 数据为**推送驱动**，不再有轮询定时器：
 * - 币种列表与市值由 [MarketStreamService] 低频拉取（REST）；
 * - 价格由币安 WebSocket 实时推送，断线由客户端自动重连；
 * - 窗口不可见时立即 [MarketStreamService.stop]，不留任何后台长连接。
 */
class MarketToolWindow(
    private val project: Project
) : SimpleToolWindowPanel(true, true), Disposable {

    private val tickerPanel = TickerPanel(project) { quote -> onQuoteSelected(quote) }
    private val detailPanel = DetailPanel()
    private val statusLabel = JBLabel("")
    private val sourceStatusLabel = JBLabel("")

    private var lastQuotes: List<Quote> = emptyList()
    private var liveCount: Int = 0

    /** 是否已向 [MarketStreamService] 注册（保证 addNotify/removeNotify 幂等）。 */
    private var streamRegistered = false

    /** 是否已登记到 [SettingsNotifier]（同样需要幂等，避免重复登记）。 */
    private var notifierRegistered = false

    private val streamService = MarketStreamService.getInstance()

    private val streamListener = MarketStreamService.Listener { snapshot ->
        // 回调来自后台线程，切回 EDT 更新 Swing
        Async.ui { applySnapshot(snapshot) }
    }

    init {
        tickerPanel.statusConsumer = { message -> statusLabel.text = message }

        // 下方：精简信息小框 + 状态行
        val south = JBPanel<JBPanel<*>>(BorderLayout())
        south.add(detailPanel, BorderLayout.CENTER)
        val statusRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(sourceStatusLabel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }
        south.add(statusRow, BorderLayout.SOUTH)

        val content = JBPanel<JBPanel<*>>(BorderLayout())
        content.add(tickerPanel, BorderLayout.CENTER)
        content.add(south, BorderLayout.SOUTH)

        setContent(content)
        setToolbar(buildToolbar())

        // 自选变化时同步刷新表格，并让订阅集合跟着变化（自选始终订阅）
        WatchlistStore.getInstance().addListener { tickerPanel.repaintTable() }
    }

    // ------------------------------------------------------------------ 工具栏

    private fun buildToolbar(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

        panel.add(JButton("刷新列表").apply {
            toolTipText = "重新拉取币种列表与市值（价格由 WebSocket 实时推送，无需手动刷新）"
            addActionListener {
                statusLabel.text = "刷新中…"
                streamService.refreshNow()
            }
        })
        panel.add(JButton("加入自选").apply {
            toolTipText = "手动输入任意币种加入自选，自选币种会被自动订阅实时价；双击列表行可移出自选"
            addActionListener { tickerPanel.promptAddToWatchlist() }
        })

        return panel
    }

    // ------------------------------------------------------------------ 数据流

    private fun applySnapshot(snapshot: MarketStreamService.DataSnapshot) {
        lastQuotes = snapshot.quotes
        liveCount = snapshot.liveCount
        tickerPanel.applyQuotes(snapshot.quotes)
        // 完全没有行情时，把失败原因直接显示在列表上方，避免只看到空白/自选占位
        tickerPanel.setEmptyHint(if (snapshot.quotes.isEmpty()) buildEmptyHint(snapshot) else null)
        detailPanel.refreshQuoteSnapshot(snapshot.quotes)
        updateStatusLine(snapshot)
    }

    /** 全部数据源都失败时的可读提示。 */
    private fun buildEmptyHint(snapshot: MarketStreamService.DataSnapshot): String {
        val failures = snapshot.failures
        if (failures.isEmpty()) return "暂无行情数据"
        val detail = failures.joinToString("；") { "${it.sourceId}: ${it.message}" }
        return "获取行情失败，请检查网络或代理设置（$detail）"
    }

    private fun onQuoteSelected(quote: Quote?) {
        detailPanel.select(quote)
    }

    private fun updateStatusLine(snapshot: MarketStreamService.DataSnapshot? = null) {
        val settings = CryptoSettings.getInstance()

        val state = snapshot?.wsState
        val live = snapshot?.liveCount ?: liveCount

        sourceStatusLabel.text = buildSourceText(snapshot, state, live)
        sourceStatusLabel.toolTipText = snapshot?.failures?.joinToString("\n") { "${it.sourceId}: ${it.message}" }

        val updated = snapshot?.restFetchedAt?.takeIf { it > 0 }
            ?.let { "${(System.currentTimeMillis() - it) / 1000}s 前" } ?: "-"
        val mode = if (settings.wsEnabled) "WebSocket 实时" else "REST 快照"
        statusLabel.text = "$mode · 实时 $live 个 · 列表更新于 $updated · 共 ${lastQuotes.size} 条"
    }

    /** 左侧状态：优先展示 WS 连接状态，其次展示 REST 数据源健康度。 */
    private fun buildSourceText(
        snapshot: MarketStreamService.DataSnapshot?,
        state: BinanceWsClient.ConnectionState?,
        live: Int
    ): String {
        if (snapshot == null) return "启动中…"

        val wsText = when (state) {
            null -> return restText(snapshot)
            BinanceWsClient.ConnectionState.CONNECTED -> "WS 已连接"
            BinanceWsClient.ConnectionState.CONNECTING -> "WS 连接中"
            BinanceWsClient.ConnectionState.RECONNECTING ->
                "WS ${snapshot.wsMessage.ifBlank { "重连中" }}（断线期间用快照价兜底）"

            BinanceWsClient.ConnectionState.STOPPED -> return restText(snapshot)
        }
        return "$wsText · 订阅 ${snapshot.streamCount} 个 · 已推送 $live 个"
    }

    private fun restText(snapshot: MarketStreamService.DataSnapshot): String {
        val total = snapshot.sourceResults.size
        if (total == 0) return "正在拉取币种列表…"
        val ok = snapshot.successCount
        val text = "数据源 $ok/$total 正常"
        val failed = snapshot.failures
        return if (failed.isEmpty()) text else "$text（${failed.joinToString { it.sourceId }} 失败）"
    }

    // ------------------------------------------------------------------ 设置 / 生命周期

    /** 设置变更后调用：代理、数据源、WS 开关与订阅数量都需要即时生效。 */
    fun onSettingsChanged() {
        // 代理变更后清空连接池，让新请求走新代理
        Http.reset()
        streamService.onSettingsChanged()
        updateStatusLine()
    }

    override fun addNotify() {
        super.addNotify()
        // 窗口显示才开始连接，避免后台常驻；多次 addNotify 不应重复注册
        if (!streamRegistered) {
            streamRegistered = true
            streamService.addListener(streamListener)
            streamService.start()
        }
        if (!notifierRegistered) {
            notifierRegistered = true
            SettingsNotifier.register(this)
        }
    }

    override fun removeNotify() {
        // 窗口隐藏即断开，插件不再占用任何连接
        unregisterNotifier()
        unregisterStream()
        super.removeNotify()
    }

    override fun dispose() {
        unregisterNotifier()
        unregisterStream()
    }

    /** 幂等注销：确保「注册 + 注销」严格配对，不会漏减引用计数。 */
    private fun unregisterStream() {
        if (!streamRegistered) return
        streamRegistered = false
        streamService.removeListener(streamListener)
        streamService.stop()
    }

    private fun unregisterNotifier() {
        if (!notifierRegistered) return
        notifierRegistered = false
        SettingsNotifier.unregister(this)
    }

    /** 供 UI 主题变更时调用。 */
    fun refreshTheme() {
        detailPanel.refreshTheme()
        tickerPanel.repaintTable()
        updateStatusLine()
    }
}
