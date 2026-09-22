package com.crypto.cryptowatch.ui

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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 选中币种的精简信息框。
 *
 * 按需求去掉了 K 线图、周期切换、市值与来源，只保留「币种 / 最新价 / 24h 涨跌 + 操作按钮」
 * 一行内容，占用高度最小；更新时间改为悬停提示，不再占用界面空间。
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

    private val watchButton = JButton("自选")
    private val tradeButton = JButton("交易")

    private var currentQuote: Quote? = null

    init {
        border = JBUI.Borders.empty(4, 8)
        add(buildContent(), BorderLayout.CENTER)
    }

    // ------------------------------------------------------------------ 布局

    /** 单行布局：币种 / 最新价 / 24h 涨跌 / 操作按钮，尽量不占高度。 */
    private fun buildContent(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))

        row.add(titleLabel)
        row.add(priceLabel)
        row.add(changeLabel)
        row.add(watchButton)
        row.add(tradeButton)

        watchButton.addActionListener { toggleWatchlist() }
        tradeButton.toolTipText = "前往交易平台"
        tradeButton.addActionListener { BrowserUtil.browse(TRADE_URL) }
        return row
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
        watchButton.text = if (inWatchlist) "已自选" else "自选"
        watchButton.toolTipText = if (inWatchlist) "点击移出自选" else "点击加入自选"

        if (quote.placeholder) {
            priceLabel.text = "—"
            changeLabel.text = "—"
            titleLabel.toolTipText = "该币种暂无数据源返回，可能因网络不可达"
            priceLabel.toolTipText = null
            return
        }

        priceLabel.text = Format.price(quote.price)
        changeLabel.text = Format.pct(quote.changePct)
        // 更新时间不再占据界面空间，改为悬停查看
        titleLabel.toolTipText = "更新时间: ${Format.time(quote.updatedAt)}"
        priceLabel.toolTipText = "更新时间: ${Format.time(quote.updatedAt)}"
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
        /** 「交易」按钮跳转地址。 */
        private const val TRADE_URL = "https://www.bsmkweb.cc/register?ref=141682651"
    }
}
