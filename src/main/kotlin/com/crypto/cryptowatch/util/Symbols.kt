package com.crypto.cryptowatch.util

/**
 * 交易对符号的合法性校验与规范化。
 *
 * 存在的理由：币安 WebSocket 在收到**不存在的交易对**时会直接断开连接
 * （服务端报 `Invalid symbol` 并关闭连接）。而本插件把「自选」直接映射为订阅流，
 * 一旦自选里混进了非法币种（例如手动输入了 `000`、`ZEC/USDT (test)`、带空格的
 * ` BTC/USDT`，或点了未同步的「加入自选」写入的历史脏数据），就会表现为：
 *
 * 连接建立 → 下发非法订阅 → 服务端断开 → 客户端自动重连 → 再次下发同一非法订阅……
 * 也就是用户看到的**「加入自选之后一直卡在重连中」**。
 *
 * 因此自选写入与订阅下发两处都必须先过一遍这里的校验。
 */
object Symbols {

    /** 基础币种：1~15 位大写字母或数字，且必须以字母开头（排除 "000" 这类纯数字脏数据）。 */
    private val BASE = Regex("^[A-Z][A-Z0-9]{0,14}$")

    /** 计价币种：同上，但限定为白名单（与 [com.crypto.cryptowatch.data.ExchangeDataSource.COMMON_QUOTES] 同源）。 */
    private val QUOTES = Regex("^[A-Z][A-Z0-9]{1,9}$")

    /**
     * 是否是合法的**基础币种**名。
     *
     * 币安允许数字出现在币种名中（例如 `1INCH`、`1000SHIB`），因此不能简单地
     * 要求「全字母」；但必须排除纯数字（`000`）——那是典型的占位/脏数据。
     */
    fun isBaseName(raw: String?): Boolean {
        val value = raw?.trim()?.upper() ?: return false
        return BASE.matches(value)
    }

    /** 是否是可用于组合交易对的计价币种名。 */
    fun isQuoteName(raw: String?): Boolean {
        val value = raw?.trim()?.upper() ?: return false
        return QUOTES.matches(value)
    }

    /** 校验规范化后的自选键（形如 `BTC/USDT`）。 */
    fun isValidKey(key: String?): Boolean {
        val value = key?.trim() ?: return false
        val idx = value.indexOf('/')
        if (idx <= 0 || idx == value.length - 1) return false
        return isBaseName(value.substring(0, idx)) && isQuoteName(value.substring(idx + 1))
    }

    /**
     * 由基础币种构造币安现货流名称，例如 `BTC` → `btcusdt@trade`。
     *
     * 返回 null 表示该币种不合法，调用方必须跳过它——绝不能把非法流名发给服务端，
     * 否则整条连接都会被服务端断开（并触发无限重连）。
     */
    fun binanceStream(base: String, quote: String = "USDT", suffix: String = "trade"): String? {
        if (!isBaseName(base) || !isQuoteName(quote)) return null
        return "${base.trim().lower()}${quote.trim().lower()}@$suffix"
    }
}
