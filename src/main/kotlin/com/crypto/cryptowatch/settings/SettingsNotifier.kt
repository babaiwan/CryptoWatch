package com.crypto.cryptowatch.settings

import com.crypto.cryptowatch.ui.MarketToolWindow
import com.intellij.openapi.application.ApplicationManager
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 把「设置已变更 / 主题已切换」广播给所有已打开的工具窗口。
 *
 * 实现上刻意**不通过 `ToolWindowManager.getInstance(project)` 反查窗口**：
 * 那种写法会用到一个平台静态入口，而该入口在较新平台（Kotlin 伴生对象 / `@JvmStatic`）
 * 与老平台（纯 Java 静态方法）上的字节码形态并不一致——用新平台编译、在老平台上加载时，
 * 一旦方法分派落到不存在的 `Companion` 类上，就会抛 `NoClassDefFoundError`，
 * 而本类是由 plugin.xml 的监听器扩展点实例化的，异常会直接导致监听器加载失败。
 *
 * 改为「窗口自行登记」后，这里只依赖 JDK 的弱引用集合，跨版本行为完全一致；
 * 同时也不再需要遍历所有 Project，逻辑更简单、也不会漏掉后台未激活的窗口。
 */
object SettingsNotifier {

    /** 与 plugin.xml 中 toolWindow 的 id 保持一致。 */
    const val NAME: String = "CryptoWatch"

    /** 已打开工具窗口的弱引用，避免登记表阻碍窗口实例被回收。 */
    private val windows = CopyOnWriteArrayList<WeakReference<MarketToolWindow>>()

    /** 由 [MarketToolWindow] 在 `addNotify` 时调用（幂等）。 */
    fun register(window: MarketToolWindow) {
        windows.removeAll { it.get() == null || it.get() === window }
        windows.add(WeakReference(window))
    }

    /** 由 [MarketToolWindow] 在 `removeNotify` / `dispose` 时调用。 */
    fun unregister(window: MarketToolWindow) {
        windows.removeAll { it.get() == null || it.get() === window }
    }

    /** 设置变更后调用：代理、数据源、WS 开关与订阅数量都需要即时生效。 */
    fun fireChanged() {
        ApplicationManager.getApplication().invokeLater {
            forEachWindow { it.onSettingsChanged() }
        }
    }

    /**
     * 主题切换后调用。
     *
     * 自绘组件里缓存的颜色在主题切换后不会自动失效，必须显式重绘，
     * 否则暗色/亮色来回切换后表格与信息框会残留上一套配色。
     */
    fun fireThemeChanged() {
        ApplicationManager.getApplication().invokeLater {
            forEachWindow { it.refreshTheme() }
        }
    }

    private inline fun forEachWindow(action: (MarketToolWindow) -> Unit) {
        windows.removeAll { it.get() == null }
        windows.forEach { ref -> ref.get()?.let { runCatching { action(it) } } }
    }
}
