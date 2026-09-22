package com.crypto.cryptowatch.util

import java.util.Locale

/**
 * 大小写转换的跨版本安全封装。
 *
 * 插件需要兼容 IntelliJ Platform 2020.3（build 203，运行在 JBR 11 上，内置 Kotlin stdlib 1.4），
 * 而 `String.lowercase()` / `String.uppercase()` 是 Kotlin 1.5 才新增的 API，
 * 在老平台上调用会直接抛 `NoSuchMethodError`。
 *
 * 这里显式转型为 `java.lang.String` 来调用 JDK 自带的 `toUpperCase(Locale)` /
 * `toLowerCase(Locale)`：它们在任何 JDK 上都存在，同时又避开了 Kotlin stdlib 里
 * 同名旧扩展函数（在 Kotlin 2.x 中已被标记为 error 级弃用）。
 */
@Suppress("USELESS_CAST")
fun String.upper(): String = (this as java.lang.String).toUpperCase(Locale.ROOT)

@Suppress("USELESS_CAST")
fun String.lower(): String = (this as java.lang.String).toLowerCase(Locale.ROOT)
