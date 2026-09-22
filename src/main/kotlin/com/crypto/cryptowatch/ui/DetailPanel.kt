package com.crypto.cryptowatch.ui

import com.crypto.cryptowatch.data.MarketDataSources
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.settings.WatchlistStore
import com.crypto.cryptowatch.util.Format
import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 选中币种的精简信息框。
 *
 * 按需求去掉了 K 线图与周期切换，只保留核心数字，占用高度很小。
 */
class DetailPanel : JBPanel<DetailPanel>(BorderLayout()) {

    private val titleLabel = JBLabel("—").apply {
        font = font.deriveFont(Font.BOLD, font.size + 2f)
        horizontalAlignment = SwingConstants.LEFT
    }
    private val priceLabel = JBLabel("—").apply {
        font = font.deriveFont(Font.BOLD, font.size + 4f)
    }
    private val changeLabel = JBLabel("—")
    private val marketCapLabel = JBLabel("市值: —")
    private val sourceLabel = JBLabel("来源: —")

    private val watchButton = JButton("加入自选")
    private val tradeButton = JButton("前去交易")

    private var currentQuote: Quote? = null

    init {
        border = JBUI.Borders.empty(4, 8)
        add(buildContent(), BorderLayout.CENTER)
    }

    // ------------------------------------------------------------------ 布局

    private fun buildContent(): JComponent {
        val root = JPanel(BorderLayout())

        val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        topRow.add(titleLabel)
        topRow.add(priceLabel)
        topRow.add(changeLabel)
        topRow.add(watchButton)
        topRow.add(tradeButton)
        root.add(topRow, BorderLayout.NORTH)

        // 统计文字统一使用主题前景色（不再单独设灰），随 IDE 主题自动适配
        val statsPanel = JPanel(GridLayout(1, 2, 8, 0))
        listOf(marketCapLabel, sourceLabel).forEach {
            statsPanel.add(it)
        }
        root.add(statsPanel, BorderLayout.SOUTH)

        watchButton.addActionListener { toggleWatchlist() }
        tradeButton.toolTipText = "前往交易平台"
        tradeButton.addActionListener { BrowserUtil.browse(TRADE_URL) }
        return root
    }

    // ------------------------------------------------------------------ 数据

    /** 由外部（表格选中/刷新）调用。 */
    fun select(quote: Quote?) {
        if (quote == null) return
        currentQuote = quote
        renderQuote(quote)
    }

    /** 只刷新数字，用于定时刷新。 */
    fun refreshQuoteSnapshot(quotes: List<Quote>) {
        val current = currentQuote ?: return
        val updated = quotes.firstOrNull { it.base.equals(current.base, ignoreCase = true) } ?: return
        currentQuote = updated
        renderQuote(updated)
    }

    private fun renderQuote(quote: Quote) {
        titleLabel.text = quote.displaySymbol
        val inWatchlist = WatchlistStore.getInstance().contains(quote.category, quote.canonicalKey)
        watchButton.text = if (inWatchlist) "移出自选" else "加入自选"

        if (quote.placeholder) {
            priceLabel.text = "—"
            changeLabel.text = "—"
            marketCapLabel.text = "市值: —"
            sourceLabel.text = "来源: 无数据"
            sourceLabel.toolTipText = "该币种暂无数据源返回，可能因网络不可达"
            return
        }

        priceLabel.text = Format.price(quote.price)
        changeLabel.text = Format.pct(quote.changePct)

        marketCapLabel.text = "市值: ${Format.compact(quote.marketCap)}"
        sourceLabel.text = "来源: ${MarketDataSources.displayNameOf(quote.sourceId)}"
        sourceLabel.toolTipText = "更新时间: ${Format.time(quote.updatedAt)}"
    }

    private fun toggleWatchlist() {
        val quote = currentQuote ?: return
        WatchlistStore.getInstance().toggle(quote.category, quote.canonicalKey)
        renderQuote(quote)
    }

    /** 主题切换时刷新配色。 */
    fun refreshTheme() {
        currentQuote?.let { renderQuote(it) }
    }

    companion object {
        /** 「前去交易」按钮跳转地址。 */
        private const val TRADE_URL = "https://www.bsmkweb.cc/register?ref=141682651"
    }
}
