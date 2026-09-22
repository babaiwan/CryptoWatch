package com.crypto.cryptowatch.util

import com.crypto.cryptowatch.settings.CryptoSettings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一的 HTTP 访问层。
 *
 * 设计要点：
 * 1. 复用 IDE 自身的代理配置（[com.intellij.util.net.HttpConfigurable]）。这是国内使用场景下
 *    最关键的一环——很多开发者的 IDE 已经配好了代理，插件不该再让用户配一遍。
 * 2. 如果 IDE 未配置代理，但用户在插件设置里手填了代理，则以后者为准。
 * 3. 每个 host 一个 HttpClient，方便按 host 缓存连接池。
 *
 * 超时被刻意压得比较短（默认 4 秒）：数据源往往有多个镜像域名，逐个尝试时
 * 单次超时过长会让首屏等待变得不可接受；宁可快速失败换下一个候选。
 */
object Http {

    private val clients = ConcurrentHashMap<String, HttpClient>()

    /** 默认 UA，CoinGecko 等接口对空 UA 不友好。 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0 Safari/537.36 CryptoWatch/1.0"

    fun getText(
        url: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        headers: Map<String, String> = emptyMap()
    ): String {
        val uri = URI.create(url)
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client(uri).send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} @ $url")
        }
        return response.body()
    }

    /**
     * 依次尝试多个候选地址，返回第一个成功响应的 body。
     *
     * 同一接口往往存在多个等价入口（不同域名或不同字段粒度的路径），可用性随时间变化，
     * 用这种方式可以在不增加用户负担的前提下提高成功率。
     *
     * 失败信息只保留"最后一次失败原因"，不再拼接冗长的 URL，便于在状态栏 tooltip 中阅读。
     *
     * @param candidates 候选地址列表（完整 URL 或域名前缀）
     * @param pathBuilder 由候选地址拼出完整 URL
     */
    fun firstSuccess(
        candidates: List<String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        pathBuilder: (String) -> String
    ): String {
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                return getText(pathBuilder(candidate), timeoutSeconds)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IOException("候选地址均不可用（${lastError?.message ?: "未知错误"}）", lastError)
    }

    /**
     * 在「host × path」的笛卡尔积上依次尝试，返回第一个成功响应。
     *
     * 用于同一 host 下存在多个可用接口的场景：例如币安的 24h 行情接口
     * `ticker/24hr` 在部分镜像上被限流或不可用，但 `ticker/price` 仍然可用，
     * 此时会自动降级到字段更少但仍能出数据的接口。
     */
    fun firstSuccessPaths(
        hosts: List<String>,
        paths: List<String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): String {
        var lastError: Throwable? = null
        for (host in hosts) {
            for (path in paths) {
                try {
                    return getText(host + path, timeoutSeconds)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
        }
        throw IOException("候选地址均不可用（${lastError?.message ?: "未知错误"}）", lastError)
    }

    private fun client(uri: URI): HttpClient = clients.computeIfAbsent(uri.host ?: "default") {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxyIfPresent(proxySelector())
            .build()
    }

    /** 首选 IDE 代理；其次插件自有代理；都没有则直连。 */
    private fun proxySelector(): ProxySelector? {
        runCatching { IdeProxyAware.selector()?.let { return it } }
        val cfg = CryptoSettings.getInstance()
        if (cfg.proxyHost.isNotBlank() && cfg.proxyPort > 0) {
            return ProxySelector.of(InetSocketAddress(cfg.proxyHost.trim(), cfg.proxyPort))
        }
        return null
    }

    /** 清空客户端缓存，供用户改完代理设置后立即生效。 */
    fun reset() = clients.clear()

    /** 单次请求默认超时。 */
    const val DEFAULT_TIMEOUT_SECONDS: Long = 4
}

/**
 * 通过反射小范围隔离 IntelliJ API，避免 Http 层与平台强耦合、方便单元测试替换。
 *
 * 公开给 WebSocket 客户端复用：行情长连接同样需要继承 IDE 的代理配置，
 * 否则「REST 走了代理、WS 直连」会出现数据源可达性不一致的怪现象。
 */
object IdeProxyAware {
    /**
     * 返回 IDE 的代理选择器；IDE 未配置代理、或不在 IDE 环境（反射失败）时返回 null。
     *
     * 约定：null 表示「直连」。本方法保证不抛异常，调用方必须通过 [proxyIfPresent]
     * 使用它，切勿把 null 直接传给 [HttpClient.Builder.proxy]。
     */
    fun selector(): ProxySelector? = runCatching {
        val clazz = Class.forName("com.intellij.util.net.HttpConfigurable")
        val instance = clazz.getMethod("getInstance").invoke(null)
        // 如果 IDE 侧没有可用代理，getProxySelector 通常返回 ProxySelector 的直连实现，
        // 这里不再做额外判断，交给 JDK 处理即可。
        instance as? ProxySelector
    }.getOrNull()
}

/**
 * 仅当存在代理时才调用 [HttpClient.Builder.proxy]。
 *
 * JDK 的实现是 `Objects.requireNonNull(proxy)`，传入 null 会抛 NullPointerException；
 * 因此「无代理/直连」必须表现为「不调用该方法」，而不是「传入 null」。
 */
internal fun HttpClient.Builder.proxyIfPresent(selector: ProxySelector?): HttpClient.Builder =
    if (selector != null) proxy(selector) else this
