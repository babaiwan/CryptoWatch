package com.crypto.cryptowatch.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 轻量通知封装。所有调用都做了兜底，通知失败绝不能影响行情本身。
 */
object CryptoNotifier {

    private const val GROUP = "CryptoWatch"

    fun warn(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.WARNING)

    fun info(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.INFORMATION)

    fun error(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.ERROR)

    /**
     * 发送通知。
     *
     * 刻意不使用 `NotificationGroup.createNotification(title, content, type)` 这个三参重载：
     * Plugin Verifier 针对最低支持版本 2020.3 校验时会报
     * `Invocation of unresolved method ... createNotification(String, String, NotificationType)`，
     * 因为该重载在 2020.3 上并不存在——用新平台编译、在老平台运行会直接抛 `NoSuchMethodError`。
     *
     * 改为「取 group 的 displayId，直接用 [Notification] 构造函数」：
     * 这条路径在 2020.3 到最新版平台上都存在，行为也完全一致。
     */
    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        runCatching {
            val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            Notification(group.displayId, title, content, type).notify(project)
        }
    }
}
