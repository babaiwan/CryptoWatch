package com.crypto.cryptowatch.util

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

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(title, content, type)
                .notify(project)
        }
    }
}
