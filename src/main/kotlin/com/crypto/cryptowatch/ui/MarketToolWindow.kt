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

    /** 工具栏按钮需要留存引用，语言切换时才能重设文字与提示。 */
    private val refreshButton = JButton(I18n.text("toolbar.refresh"))
    private val addButton = JButton(I18n.text("toolbar.add"))

    private var lastQuotes: List<Quote> = emptyList()
    private var liveCount: Int = 0

    /**
     * 最近一次快照。
     *
     * 语言切换时状态栏需要按新语言重画，而状态栏的文案（连接状态、"无实时行情"数量）
     * 都来自快照而非当前选中行，因此必须留住最后一次快照；否则切换语言后状态栏
     * 只能退化成默认文案。
     */
    private var lastSnapshot: MarketStreamService.DataSnapshot? = null

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

        refreshButton.apply {
            toolTipText = I18n.text("toolbar.refresh.tip")
            addActionListener {
                statusLabel.text = I18n.text("status.refreshing")
                streamService.refreshNow()
            }
        }
        addButton.apply {
            toolTipText = I18n.text("toolbar.add.tip")
            addActionListener { tickerPanel.promptAddToWatchlist() }
        }
        panel.add(refreshButton)
        panel.add(addButton)

        return panel
    }

    // ------------------------------------------------------------------ 数据流

    private fun applySnapshot(snapshot: MarketStreamService.DataSnapshot) {
        lastSnapshot = snapshot
        lastQuotes = snapshot.quotes
        liveCount = snapshot.liveCount
        tickerPanel.applyQuotes(snapshot.quotes)
        // 完全没有行情时，把失败原因直接显示在列表上方，避免只看到空白/自选占位
        tickerPanel.setEmptyHint(if (snapshot.quotes.isEmpty()) buildEmptyHint(snapshot) else null)
        detailPanel.refreshQuoteSnapshot(snapshot.quotes)
        updateStatusLine(snapshot)
        // 失败原因改为悬停查看，不再在界面上堆一排来源名
        sourceStatusLabel.toolTipText =
            snapshot.failures.joinToString("\n") { "${it.sourceId}: ${it.message}" }.ifEmpty { null }
    }

    /** 全部数据源都失败时的可读提示。 */
    private fun buildEmptyHint(snapshot: MarketStreamService.DataSnapshot): String {
        val failures = snapshot.failures
        if (failures.isEmpty()) return I18n.text("empty.noData")
        val detail = failures.joinToString("; ") { "${it.sourceId}: ${it.message}" }
        return I18n.text("empty.fetchFailed", detail)
    }

    private fun onQuoteSelected(quote: Quote?) {
        detailPanel.select(quote)
    }

    private fun updateStatusLine(snapshot: MarketStreamService.DataSnapshot? = null) {
        val settings = CryptoSettings.getInstance()

        val state = snapshot?.wsState
        val live = snapshot?.liveCount ?: liveCount

        sourceStatusLabel.text = buildSourceText(snapshot, state)

        val mode = I18n.text(if (settings.wsEnabled) "status.mode.live" else "status.mode.snapshot")
        // 注意：自选数量取自选本身，而不是 lastQuotes（那是全量行情，约 400 条）
        val watchCount = WatchlistStore.getInstance().allKeys().size
        // 「无实时行情」的币种必须显式提示：这些币种不会进入订阅，因此推送数会少于自选数，
        // 若不解释，用户会误以为「订阅漏了」；而它恰恰是此前「一直卡在重连中」的可视化出口。
        val unsupported = snapshot?.unsupportedCount ?: 0
        val suffix = if (unsupported > 0) I18n.text("status.unsupported", unsupported) else ""
        statusLabel.text = I18n.text("status.line", mode, watchCount, live) + suffix
    }

    /**
     * 左侧状态：只保留一句最简状态。
     *
     * 连接状态具体原因（重连次数、失败来源等）一律放进悬停提示，
     * 避免状态栏出现一长串文字把界面挤乱。
     */
    private fun buildSourceText(
        snapshot: MarketStreamService.DataSnapshot?,
        state: BinanceWsClient.ConnectionState?
    ): String {
        if (snapshot == null) return I18n.text("ws.state.launching")

        return when (state) {
            null -> restText(snapshot)
            BinanceWsClient.ConnectionState.CONNECTED -> I18n.text("ws.state.connected")
            BinanceWsClient.ConnectionState.CONNECTING -> I18n.text("ws.state.connecting")
            BinanceWsClient.ConnectionState.RECONNECTING -> I18n.text("ws.state.reconnecting")
            BinanceWsClient.ConnectionState.STOPPED -> restText(snapshot)
        }
    }

    /** 未启用 WebSocket 时，退化为只显示数据源健康度。 */
    private fun restText(snapshot: MarketStreamService.DataSnapshot): String {
        val total = snapshot.sourceResults.size
        if (total == 0) return I18n.text("ws.state.loading")
        return I18n.text("source.health", snapshot.successCount, total)
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

    /**
     * 供语言切换时调用（由 [SettingsNotifier.fireLanguageChanged] 广播）。
     *
     * 与 [refreshTheme] 的区别：主题切换只影响颜色，而语言切换要重设所有**文字**，
     * 包括工具栏按钮、表格列名、搜索框占位符与状态栏。
     * 这里刻意不重连行情——语言与数据无关，重连只会让状态栏闪一下。
     */
    fun refreshTexts() {
        refreshButton.text = I18n.text("toolbar.refresh")
        refreshButton.toolTipText = I18n.text("toolbar.refresh.tip")
        addButton.text = I18n.text("toolbar.add")
        addButton.toolTipText = I18n.text("toolbar.add.tip")

        tickerPanel.refreshTexts()
        detailPanel.refreshTexts()

        // 状态栏文案由快照驱动，这里用最后一次快照重画一遍即可
        updateStatusLine(lastSnapshot)
    }
}
