package com.crypto.cryptowatch.settings

import com.crypto.cryptowatch.data.MarketDataSources
import com.crypto.cryptowatch.ui.I18n
import com.crypto.cryptowatch.ui.Language
import com.crypto.cryptowatch.util.Http
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * 设置页：设置 > 工具 > CryptoWatch。
 *
 * 说明：
 * - 价格改由 WebSocket 推送，这里不再提供「刷新间隔 / 自动刷新」；
 * - 列表固定为「只看自选」，因此也不再有「只看自选 / 列表条数」选项；
 * - 为兼容较老版本的 IDE（如 2020.3），本页刻意只使用基础的 Swing 组件与
 *   [com.intellij.openapi.options.Configurable] 接口，不依赖高版本的 UI DSL。
 *
 * 文案全部通过 [I18n] 获取。语言本身也在这里切换（下拉框），因此
 * [applyTexts] 需要能**在不重建面板的前提下**把本页所有文字刷新一遍——
 * 否则用户切到英文后，设置页自身仍是旧语言，会显得"没生效"。
 */
class CryptoSettingsConfigurable : Configurable {

    private var rootPanel: JPanel? = null

    private val wsEnabledBox = JCheckBox(I18n.text("settings.ws.enabled"), true)

    private val languageCombo = JComboBox(Language.values()).apply {
        // 默认渲染器会直接调用 toString()，也就是显示枚举名（SYSTEM / EN / ZH）；
        // 这里换成 [Language.displayName]，展示为 English / 简体中文 / 跟随 IDE。
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component = super.getListCellRendererComponent(
                list,
                (value as? Language)?.displayName() ?: "",
                index,
                isSelected,
                cellHasFocus
            )
        }
    }

    private val proxyHostField = JBTextField(24)
    private val proxyPortField = JBTextField(6)

    /** 需要在语言切换时重设文字的说明标签（顺序与 [buildPanel] 中的添加顺序一致）。 */
    private val comments = mutableListOf<JLabel>()

    private val proxyHostLabel = JLabel(I18n.text("settings.proxy.host"))
    private val proxyPortLabel = JLabel(I18n.text("settings.proxy.port"))
    private val languageLabel = JLabel(I18n.text("settings.language.label"))

    private val sourceBoxes: Map<String, JCheckBox> =
        MarketDataSources.all.associate { source ->
            source.id to JCheckBox(sourceOptionText(source.id), true)
        }

    override fun getDisplayName(): String = "CryptoWatch"

    override fun createComponent(): JComponent = buildPanel().also { rootPanel = it }

    private fun buildPanel(): JPanel {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(8)

        root.add(row(languageLabel, languageCombo))
        root.add(comment("settings.language.comment"))

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(row(wsEnabledBox))
        root.add(comment("settings.ws.comment"))

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(comment("settings.sources.comment"))
        root.add(buildSourcePanel())

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(comment("settings.proxy.comment"))
        root.add(row(proxyHostLabel, proxyHostField, proxyPortLabel, proxyPortField))

        applyTexts()
        return root
    }

    /**
     * 把本页所有文字刷新为当前语言的文案。
     *
     * 数据源复选框的文字也在这里重建：它们的名字取自 [MarketDataSources.displayNameOf]，
     * 而那是按语言动态计算的，因此切换语言后必须重新赋值，不能沿用构造时的那份文字。
     */
    private fun applyTexts() {
        languageLabel.text = I18n.text("settings.language.label")
        proxyHostLabel.text = I18n.text("settings.proxy.host")
        proxyPortLabel.text = I18n.text("settings.proxy.port")
        wsEnabledBox.text = I18n.text("settings.ws.enabled")

        val keys = listOf(
            "settings.language.comment",
            "settings.ws.comment",
            "settings.sources.comment",
            "settings.proxy.comment"
        )
        comments.forEachIndexed { index, label ->
            keys.getOrNull(index)?.let { label.text = "<html>${I18n.text(it)}</html>" }
        }

        sourceBoxes.forEach { (id, box) -> box.text = sourceOptionText(id) }

        // 下拉项的文字随语言变化（"跟随 IDE" 是中/英不同的），重建模型以免显示陈旧
        rebuildLanguageModel()
    }

    /**
     * 重建语言下拉的选项。
     *
     * 必须"先记下选中项、清空、再填回"，因为 [Language.displayName] 依赖当前语言，
     * 不重建就会一直显示切换前那一套名字。
     */
    private fun rebuildLanguageModel() {
        val selected = languageCombo.selectedItem as? Language ?: Language.SYSTEM
        languageCombo.removeAllItems()
        Language.values().forEach { languageCombo.addItem(it) }
        languageCombo.selectedItem = selected
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
    private fun comment(key: String): JPanel {
        val label = JLabel("<html>${I18n.text(key)}</html>")
        comments += label
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.alignmentX = 0f
        panel.add(label, BorderLayout.WEST)
        return panel
    }

    private fun buildSourcePanel(): JPanel = JPanel(GridLayout(0, 2, JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = 0f
        sourceBoxes.values.forEach { add(it) }
    }

    /** 单个数据源复选框的文字：`CoinLore 免费源（市值榜）`。 */
    private fun sourceOptionText(id: String): String {
        val source = MarketDataSources.byId(id) ?: return id
        return I18n.text("settings.source.option", source.displayName, source.category.displayName)
    }

    override fun isModified(): Boolean {
        val settings = CryptoSettings.getInstance()
        return settings.wsEnabledMutable != wsEnabledBox.isSelected ||
            settings.proxyHostMutable != proxyHostField.text.trim() ||
            settings.proxyPortMutable != (proxyPortField.text.trim().toIntOrNull() ?: 0) ||
            settings.enabledSources().toSet() != selectedSourceIds() ||
            settings.languageMutable != selectedLanguage().name
    }

    override fun apply() {
        val settings = CryptoSettings.getInstance()
        val languageChanged = settings.languageMutable != selectedLanguage().name

        settings.wsEnabledMutable = wsEnabledBox.isSelected
        settings.proxyHostMutable = proxyHostField.text.trim()
        settings.proxyPortMutable = proxyPortField.text.trim().toIntOrNull() ?: 0
        settings.setEnabledSources(selectedSourceIds())
        settings.languageMutable = selectedLanguage().name

        // 让新的代理与数据源配置立即生效
        Http.reset()
        SettingsNotifier.fireChanged()

        if (languageChanged) {
            // 语言切换是"影响所有界面"的变更：先把本页文字切成新语言，
            // 再广播给所有已打开的工具窗口重设文字（无需重启，也没有需要失效的缓存）。
            applyTexts()
            I18n.notifyLanguageChanged()
        } else {
            // 即使语言没变也要刷新本页：数据源名称会随语言变化，重建一次保证一致
            applyTexts()
        }
    }

    override fun reset() {
        val settings = CryptoSettings.getInstance()
        wsEnabledBox.isSelected = settings.wsEnabledMutable
        proxyHostField.text = settings.proxyHostMutable
        proxyPortField.text = settings.proxyPortMutable.takeIf { it > 0 }?.toString() ?: ""

        val enabled = settings.enabledSources().toSet()
        sourceBoxes.forEach { (id, box) -> box.isSelected = id in enabled }

        rebuildLanguageModel()
        languageCombo.selectedItem = Language.fromPersisted(settings.language)
    }

    override fun disposeUIResources() {
        rootPanel = null
        comments.clear()
    }

    private fun selectedSourceIds(): Set<String> =
        sourceBoxes.filterValues { it.isSelected }.keys

    private fun selectedLanguage(): Language =
        languageCombo.selectedItem as? Language ?: Language.SYSTEM
}
