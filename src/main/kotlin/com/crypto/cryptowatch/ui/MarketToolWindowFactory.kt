package com.crypto.cryptowatch.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * 工具窗口工厂。插件通过 plugin.xml 中的 toolWindow 扩展点注册它。
 *
 * 注意：这里通过 `ContentManager.factory` 创建内容，而不是
 * `ContentFactory.getInstance()`——后者是较新平台才提供的静态入口，
 * 老版本 IDE（2020.3）上并不存在。
 */
class MarketToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MarketToolWindow(project)
        Disposer.register(project, panel)
        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(panel, null, false)
        contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
