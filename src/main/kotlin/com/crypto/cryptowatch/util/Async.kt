package com.crypto.cryptowatch.util

import com.intellij.openapi.application.ApplicationManager

/**
 * 线程调度助手。
 *
 * 约定：一切网络请求走 [bg]，一切 Swing 组件更新走 [ui]。
 * 这样 UI 层代码不会因为忘记切线程而出现随机崩溃。
 */
object Async {

    fun bg(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread(block)
    }

    fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }
}
