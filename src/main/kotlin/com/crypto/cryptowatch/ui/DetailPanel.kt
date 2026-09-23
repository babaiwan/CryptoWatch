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

    private val watchButton = JButton(I18n.text("detail.button.watch"))
    private val tradeButton = JButton(I18n.text("detail.button.trade"))

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
        tradeButton.toolTipText = I18n.text("detail.tip.trade")
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
        watchButton.text = I18n.text(if (inWatchlist) "detail.button.watched" else "detail.button.watch")
        watchButton.toolTipText = I18n.text(if (inWatchlist) "detail.tip.removeWatch" else "detail.tip.addWatch")

        if (quote.placeholder) {
            priceLabel.text = "—"
            changeLabel.text = "—"
            titleLabel.toolTipText = I18n.text("detail.placeholder.tip")
            priceLabel.toolTipText = null
            return
        }

        priceLabel.text = Format.price(quote.price)
        changeLabel.text = Format.pct(quote.changePct)
        // 更新时间不再占据界面空间，改为悬停查看
        val updated = I18n.text("detail.tip.updated", Format.time(quote.updatedAt))
        titleLabel.toolTipText = updated
        priceLabel.toolTipText = updated
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

    /**
     * 语言切换后刷新文案。
     *
     * 没有选中币种时也要刷按钮文字（否则按钮会一直停留在旧语言）；
     * 但一旦有选中行，[renderQuote] 会顺带把按钮与提示一并按新语言重画。
     */
    fun refreshTexts() {
        tradeButton.text = I18n.text("detail.button.trade")
        tradeButton.toolTipText = I18n.text("detail.tip.trade")
        currentQuote?.let { renderQuote(it) } ?: run {
            watchButton.text = I18n.text("detail.button.watch")
            watchButton.toolTipText = I18n.text("detail.tip.addWatch")
        }
    }

    companion object {
        /** 「交易」按钮跳转地址。 */
        private const val TRADE_URL = "https://www.bsmkweb.cc/register?ref=141682651"
    }
}
