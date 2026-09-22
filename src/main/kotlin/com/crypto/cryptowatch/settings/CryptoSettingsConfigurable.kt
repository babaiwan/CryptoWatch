package com.crypto.cryptowatch.settings

import com.crypto.cryptowatch.data.MarketDataSources
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.util.Http
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 设置页：设置 > 工具 > CryptoWatch。
 *
 * 说明：
 * - 价格改由 WebSocket 推送，这里不再提供「刷新间隔 / 自动刷新」；
 * - 列表固定为「只看自选」，因此也不再有「只看自选 / 列表条数」选项；
 * - 为兼容较老版本的 IDE（如 2020.3），本页刻意只使用基础的 Swing 组件与
 *   [com.intellij.openapi.options.Configurable] 接口，不依赖高版本的 UI DSL。
 */
class CryptoSettingsConfigurable : Configurable {

    private var rootPanel: JPanel? = null

    private val wsEnabledBox = JCheckBox("启用 WebSocket 实时价格", true)

    private val topNSpinner = JSpinner(
        SpinnerNumberModel(
            CryptoSettings.DEFAULT_TOP_N,
            CryptoSettings.MIN_TOP_N,
            CryptoSettings.MAX_TOP_N,
            10
        )
    )

    private val proxyHostField = JBTextField(24)
    private val proxyPortField = JBTextField(6)

    private val sourceBoxes: Map<String, JCheckBox> =
        MarketDataSources.all.associate { source ->
            val type = when (source.category) {
                MarketCategory.FUTURES -> "合约"
                MarketCategory.TOP -> "榜单"
                MarketCategory.SPOT -> "现货"
            }
            source.id to JCheckBox("${source.displayName}（$type）", true)
        }

    override fun getDisplayName(): String = "CryptoWatch"

    override fun createComponent(): JComponent = buildPanel().also { rootPanel = it }

    private fun buildPanel(): JPanel {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(8)

        root.add(row(wsEnabledBox))
        root.add(comment("价格由币安 WebSocket 推送，无需轮询；关闭后只显示快照价"))

        root.add(
            row(
                JLabel("订阅热门币数量："),
                topNSpinner
            )
        )
        root.add(
            comment(
                "自选币种始终会被订阅，此外再按市值排名订阅前 N 个（${CryptoSettings.MIN_TOP_N} ~ " +
                    "${CryptoSettings.MAX_TOP_N}）。数量越大越容易出现连接不稳，建议保持默认"
            )
        )

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(
            comment(
                "以下是 REST 数据源，用于拉取<b>币种列表与市值</b>（价格来自 WebSocket）。<br>" +
                    "默认只启用 CoinLore 聚合接口（普通公网站点，受限网络下通常无需代理即可访问）。" +
                    "交易所接口（Binance / OKX / Gate）在国内网络下常常超时，启用会拖慢列表刷新；" +
                    "如你的网络可直连，可手动勾选以获得更多字段。"
            )
        )
        root.add(buildSourcePanel())

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(
            comment(
                "插件会优先复用 IDE 自身的代理设置（设置 > 外观与行为 > 系统设置 > HTTP 代理），" +
                    "WebSocket 长连接同样遵循该配置。只有当 IDE 未配置代理时，下面的配置才会生效。"
            )
        )
        root.add(
            row(
                JLabel("代理主机："),
                proxyHostField,
                JLabel("端口："),
                proxyPortField
            )
        )

        return root
    }

    /** 一行水平布局，左对齐。 */
    private fun row(vararg components: JComponent): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.isOpaque = false
        components.forEach { panel.add(it) }
        panel.add(Box.createHorizontalGlue())
        panel.alignmentX = 0f
        return panel
    }

    /** 说明文字（支持简单 HTML）。 */
    private fun comment(html: String): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.alignmentX = 0f
        panel.add(JLabel("<html>$html</html>"), BorderLayout.WEST)
        return panel
    }

    private fun buildSourcePanel(): JPanel = JPanel(GridLayout(0, 2, JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = 0f
        sourceBoxes.values.forEach { add(it) }
    }

    override fun isModified(): Boolean {
        val settings = CryptoSettings.getInstance()
        return settings.wsEnabledMutable != wsEnabledBox.isSelected ||
            settings.subscribeTopNMutable != (topNSpinner.value as Number).toInt() ||
            settings.proxyHostMutable != proxyHostField.text.trim() ||
            settings.proxyPortMutable != (proxyPortField.text.trim().toIntOrNull() ?: 0) ||
            settings.enabledSources().toSet() != selectedSourceIds()
    }

    override fun apply() {
        val settings = CryptoSettings.getInstance()
        settings.wsEnabledMutable = wsEnabledBox.isSelected
        settings.subscribeTopNMutable = (topNSpinner.value as Number).toInt()
        settings.proxyHostMutable = proxyHostField.text.trim()
        settings.proxyPortMutable = proxyPortField.text.trim().toIntOrNull() ?: 0
        settings.setEnabledSources(selectedSourceIds())

        // 让新的代理与数据源配置立即生效
        Http.reset()
        SettingsNotifier.fireChanged()
    }

    override fun reset() {
        val settings = CryptoSettings.getInstance()
        wsEnabledBox.isSelected = settings.wsEnabledMutable
        topNSpinner.value = settings.subscribeTopNMutable
        proxyHostField.text = settings.proxyHostMutable
        proxyPortField.text = settings.proxyPortMutable.takeIf { it > 0 }?.toString() ?: ""

        val enabled = settings.enabledSources().toSet()
        sourceBoxes.forEach { (id, box) -> box.isSelected = id in enabled }
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    private fun selectedSourceIds(): Set<String> =
        sourceBoxes.filterValues { it.isSelected }.keys
}
