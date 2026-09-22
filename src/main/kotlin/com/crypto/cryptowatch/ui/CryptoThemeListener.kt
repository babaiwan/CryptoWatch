package com.crypto.cryptowatch.ui

import com.crypto.cryptowatch.settings.SettingsNotifier
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener

/**
 * 监听 IDE 主题（Light/Dark/高对比）切换，让自绘图表与表格立即重绘。
 *
 * 之所以必须处理：自绘组件里缓存的颜色在主题切换后不会自动失效，
 * [com.intellij.ui.JBColor] 虽然能取到新值，但组件仍需一次 repaint 才会体现。
 *
 * 这里不再用 `ToolWindowManager` 反查窗口，而是交由 [SettingsNotifier] 广播给登记在册的实例：
 * 既避免了平台静态入口的跨版本差异，也不需要遍历全部 Project。
 */
class CryptoThemeListener : LafManagerListener {

    override fun lookAndFeelChanged(source: LafManager) {
        SettingsNotifier.fireThemeChanged()
    }
}
