package com.crypto.cryptowatch.ui

import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.settings.WatchlistStore
import com.crypto.cryptowatch.util.Format
import com.crypto.cryptowatch.util.lower
import com.crypto.cryptowatch.util.upper
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * 行情列表（扁平，不再区分现货/合约）+ 搜索 + 自选标记。
 *
 * 交互约定：
 * - 单击行 → 下方小框联动；
 * - 双击行 → 切换自选；
 * - 右键 → 上下文菜单（加自选 / 复制币种 / 复制最新价）。
 */
class TickerPanel(
    private val project: Project,
    private val onSelect: (Quote?) -> Unit
) : JPanel(BorderLayout()) {

    private val model = TickerTableModel()
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)

    private val searchField = JBTextField().apply {
        emptyText.text = "搜索币种，如 BTC / ETH"
    }
    private val hintLabel = JLabel("")

    private var allQuotes: List<Quote> = emptyList()

    /** 固定为「只看自选」：列表只展示自选币种（不再提供开关）。 */
    private val watchlistOnly: Boolean = true

    /** 没有任何行情时的提示（来自聚合层的失败原因），用于替代一片空白。 */
    private var emptyHint: String? = null

    init {
        layout = BorderLayout()
        border = EmptyBorder(0, 0, 0, 0)

        configureTable()

        WatchlistStore.getInstance().addListener { repaintTable() }

        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(table).apply {
            border = JBUI.Borders.empty()
            viewport.background = table.background
        }, BorderLayout.CENTER)
    }

    // ------------------------------------------------------------------ 初始化

    private fun configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 22
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.fillsViewportHeight = true
        table.rowSorter = sorter
        table.autoCreateColumnsFromModel = true

        TickerTableModel.COLUMNS.forEachIndexed { index, column ->
            if (!column.numeric) {
                sorter.setComparator(index, TickerTableModel.TEXT_COMPARATOR)
            } else {
                sorter.setComparator(index, TickerTableModel.NUMERIC_COMPARATOR)
            }
            table.columnModel.getColumn(index).apply {
                preferredWidth = column.width
                minWidth = 60
            }
        }
        sorter.setSortKeys(
            listOf(RowSorter.SortKey(TickerTableModel.Column.QUOTE_VOLUME.ordinal, SortOrder.DESCENDING))
        )

        table.setDefaultRenderer(java.lang.Double::class.java, QuoteRenderer())
        table.setDefaultRenderer(Any::class.java, QuoteRenderer())
        table.setDefaultRenderer(String::class.java, QuoteRenderer())

        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) onSelect(selectedQuote())
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    toggleWatchlist(selectedQuote() ?: return)
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
    }

    private fun buildHeader(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(4, 4, 4, 4)

        panel.add(hintLabel, BorderLayout.WEST)

        searchField.preferredSize = Dimension(200, 26)
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = reapply()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = reapply()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = reapply()
        })
        val searchPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        searchPanel.add(JLabel("搜索:"))
        searchPanel.add(searchField)
        panel.add(searchPanel, BorderLayout.EAST)

        return panel
    }

    // ------------------------------------------------------------------ 数据更新

    fun applyQuotes(quotes: List<Quote>) {
        allQuotes = quotes
        reapply()
    }

    /**
     * 设置「完全没有行情」时的提示文案（通常为数据源失败原因）。
     *
     * 传 null 表示有行情，恢复常规的计数提示。
     */
    fun setEmptyHint(hint: String?) {
        emptyHint = hint
        reapply()
    }

    private fun reapply() {
        val keyword = searchField.text.trim().lower()
        val watchlist = WatchlistStore.getInstance()

        var filtered = universe()
        if (watchlistOnly) {
            filtered = filtered.filter { watchlist.contains(it.category, it.canonicalKey) }
        }
        if (keyword.isNotEmpty()) {
            filtered = filtered.filter {
                it.base.lower().contains(keyword) ||
                    it.symbol.lower().contains(keyword) ||
                    it.displaySymbol.lower().contains(keyword)
            }
        }

        val displayed = filtered
        model.setQuotes(displayed)

        hintLabel.text = when {
            allQuotes.isEmpty() && filtered.isNotEmpty() -> "未获取到行情，以下为自选占位"
            allQuotes.isEmpty() && emptyHint != null -> emptyHint
            else -> "自选 ${displayed.size} 个 · 实时 ${allQuotes.size} 条行情"
        }
        hintLabel.toolTipText = if (allQuotes.isEmpty()) emptyHint else null
    }

    /**
     * 扁平币种集合：
     * - 只保留稳定币计价（USDT/USD）的币对，并过滤掉稳定币本身的币对，避免列表被上千条噪音淹没；
     * - 跨数据源/跨类目按基础币种去重（同币种在现货与合约出现时只保留一条）；
     * - 若完全没有行情（例如所有数据源都不可达），用自选键生成占位行，保证列表与搜索不为空。
     */
    private fun universe(): List<Quote> {
        val live = allQuotes.asSequence()
            .filter { it.quote.upper() in USD_QUOTES }
            .filter { it.base.upper() !in STABLE_BASES }
            .distinctBy { it.base.upper() }
            .toList()
        if (live.isNotEmpty()) return live

        return WatchlistStore.getInstance().allKeys().map { key ->
            val base = key.substringBefore('/')
            val quote = key.substringAfter('/', "USDT")
            Quote(
                id = key,
                symbol = key,
                base = base,
                quote = quote,
                price = 0.0,
                changePct = 0.0,
                sourceId = "-",
                placeholder = true
            )
        }.sortedBy { it.displaySymbol }
    }

    fun repaintTable() {
        table.repaint()
        reapply()
    }

    private fun selectedQuote(): Quote? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return model.quoteAt(modelRow)
    }

    // ------------------------------------------------------------------ 自选操作

    private fun toggleWatchlist(quote: Quote) {
        val added = WatchlistStore.getInstance().toggle(quote.category, quote.canonicalKey)
        notifyStatus(if (added) "${quote.displaySymbol} 已加入自选" else "${quote.displaySymbol} 已移出自选")
        onSelect(quote)
    }

    /** 通过输入框添加任意币种到自选（允许尚未被数据源返回的币种）。 */
    fun promptAddToWatchlist() {
        val input = Messages.showInputDialog(
            project,
            "输入币种，例如 BTC/USDT 或 BTCUSDT。",
            "添加自选",
            null
        )?.trim() ?: return
        if (input.isEmpty()) return

        val key = normalizeSymbol(input) ?: run {
            notifyStatus("无法识别的币种格式: $input")
            return
        }
        WatchlistStore.getInstance().add(MarketCategory.SPOT, key)
        notifyStatus("$key 已加入自选")
    }

    private fun normalizeSymbol(input: String): String? {
        val cleaned = input.upper().replace(" ", "")
        if (cleaned.contains('/')) {
            val parts = cleaned.split('/')
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                return "${parts[0]}/${parts[1]}"
            }
            return null
        }
        val quotes = listOf("USDT", "USDC", "USD", "FDUSD", "TUSD", "BTC", "ETH", "EUR")
            .sortedByDescending { it.length }
        for (q in quotes) {
            if (cleaned.endsWith(q) && cleaned.length > q.length) {
                return "${cleaned.dropLast(q.length)}/$q"
            }
        }
        return "$cleaned/USDT"
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row >= 0) table.setRowSelectionInterval(row, row)
        val quote = selectedQuote() ?: return

        val inWatchlist = WatchlistStore.getInstance().contains(quote.category, quote.canonicalKey)
        val menu = JPopupMenu()
        menu.add(JMenuItem(if (inWatchlist) "移出自选" else "加入自选").apply {
            addActionListener { toggleWatchlist(quote) }
        })
        menu.add(JMenuItem("复制币种").apply {
            addActionListener { copyToClipboard(quote.displaySymbol) }
        })
        menu.add(JMenuItem("复制最新价").apply {
            addActionListener { copyToClipboard(Format.price(quote.price)) }
        })
        menu.show(table, e.x, e.y)
    }

    private fun copyToClipboard(text: String) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    // ------------------------------------------------------------------ 状态回调

    var statusConsumer: ((String) -> Unit)? = null

    private fun notifyStatus(message: String) = statusConsumer?.invoke(message)

    /** 单元格渲染：自选加星 + 加粗。文字统一使用主题前景色，不做涨跌着色。 */
    private inner class QuoteRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as DefaultTableCellRenderer

            val modelRow = table.convertRowIndexToModel(row)
            val modelColumn = table.convertColumnIndexToModel(column)
            val col = TickerTableModel.COLUMNS[modelColumn]
            val quote = model.quoteAt(modelRow)

            component.text = when {
                col == TickerTableModel.Column.SYMBOL && quote != null && isInWatchlist(quote) ->
                    "★ ${model.rendered(modelRow, modelColumn)}"
                else -> model.rendered(modelRow, modelColumn)
            }
            component.horizontalAlignment =
                if (col.numeric) SwingConstants.RIGHT else SwingConstants.LEFT
            component.border = JBUI.Borders.empty(0, 6)

            // 不设置 foreground：super 已按选中态/主题设好前景色。
            // 刻意去掉「红涨绿跌」着色，避免与用户自定义配色冲突，也免去主题切换时的重绘依赖。
            component.font = if (quote != null && isInWatchlist(quote)) {
                table.font.deriveFont(java.awt.Font.BOLD)
            } else {
                table.font
            }
            return component
        }

        private fun isInWatchlist(quote: Quote): Boolean =
            WatchlistStore.getInstance().contains(quote.category, quote.canonicalKey)
    }

    companion object {
        /** 计价币种白名单：市值榜用 USD，交易所用 USDT。 */
        private val USD_QUOTES = setOf("USDT", "USD")

        /** 稳定币自身不作为观察对象，否则列表里会塞满 USDC/USDT 之类的币对。 */
        private val STABLE_BASES = setOf(
            "USDT", "USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USD", "USDE", "USDD",
            "PYUSD", "EUR", "TRY", "BRL", "AEUR", "EURI"
        )
    }
}
