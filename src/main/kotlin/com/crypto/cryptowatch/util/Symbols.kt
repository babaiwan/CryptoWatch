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
     * 由基础币种构造**币安合约（U 本位永续）**流名称，例如：
     * - `binanceStream("TAKE")` → `takeusdt@aggTrade`；
     * - `binanceStream("TAKE", suffix = "miniTicker")` → `takeusdt@miniTicker`。
     *
     * 计价币种的拼接规则与现货不同，按币安合约官方约定分两种：
     * - `USDT` / `USDC` 这类**稳定币计价**直接拼接：`BTC` + `USDT` → `btcusdt`；
     * - `USD` 表示**币本位（COIN-M）**，用 `_` 连接并补 `perp` 后缀：`BTC` → `btcusd_perp`。
     *
     * 之所以要专门做这一层，是因为返回值会被直接拼进 SUBSCRIBE 报文：合约网关对
     * 不存在的流虽然不会像现货那样**断开连接**，但会静默不推送，表现为「有连接、没数据」，
     * 比断连更难排查，因此必须在源头剔除非法组合（返回 null）。
     *
     * @param suffix 流名后缀。逐笔成交在合约里叫 `aggTrade`（不是现货的 `trade`）。
     */
    fun binanceStream(base: String, quote: String = "USDT", suffix: String = "aggTrade"): String? {
        if (!isBaseName(base) || !isQuoteName(quote)) return null
        val b = base.trim().lower()
        val q = quote.trim().lower()
        val pair = if (q == "usd") "${b}${q}_perp" else "$b$q"
        return "$pair@$suffix"
    }

    /**
     * 把合约流名反解析为 `(基础币种, 计价币种)`，例如：
     * - `takeusdt@aggTrade` → `TAKE` / `USDT`
     * - `btcusd_perp@miniTicker` → `BTC` / `USD`
     *
     * 供「被服务端隔离的流」反推回币种使用（[com.crypto.cryptowatch.data.MarketStreamService]
     * 据此统计"无实时行情"的币种数），因此**顺序很重要**：必须先在 `USDT`/`USDC` 里匹配，
     * 否则 `BTCUSDT` 会被误判成计价 `USD` 的 `BTCUSDT`。
     *
     * @return 无法识别时返回 null，调用方应当跳过。
     */
    fun splitStream(stream: String?): Pair<String, String>? {
        val raw = stream?.substringBefore('@')?.trim()?.lower() ?: return null
        if (raw.isEmpty()) return null

        // 币本位：btcusd_perp → btc / usd
        if (raw.length > PERP_SUFFIX.length + 3 && raw.endsWith(PERP_SUFFIX)) {
            val head = raw.dropLast(PERP_SUFFIX.length)
            if (head.length > 3 && head.endsWith("usd")) {
                return head.dropLast(3).upper() to "USD"
            }
        }

        // U 本位：按计价币种长度倒序匹配，避免 USDT 被 USD 抢先
        for (q in STREAM_QUOTES) {
            if (raw.length > q.length && raw.endsWith(q)) {
                return raw.dropLast(q.length).upper() to q.upper()
            }
        }
        return null
    }

    /**
     * 规范化流名：只把 `@` 之前的交易对部分转为小写，**后缀保持原样**。
     *
     * 币安组合流订阅的后缀**区分大小写**，这是实测踩到的坑：
     * - `btcusdt@aggTrade` → 正常推送数据帧；
     * - `btcusdt@aggtrade` → 只回 `{"result":null}` 的 ack，之后**永远不推任何数据**
     *   （既无 error 也不断连），界面表现为「已连接 + 推送 0 + 价格不跳」。
     *
     * 交易对部分（`btcusdt`）本身大小写无关，因此只对该部分归一，便于去重与比较。
     */
    fun normalizeStreamName(stream: String): String {
        val trimmed = stream.trim()
        val at = trimmed.indexOf('@')
        if (at < 0) return trimmed.lower()
        return trimmed.substring(0, at).lower() + "@" + trimmed.substring(at + 1)
    }

    /** 币本位合约流名的固定后缀（`btcusd` + `_perp`）。 */
    private const val PERP_SUFFIX = "_perp"

    /** 从流名里识别计价币种时使用的候选表，必须按长度倒序。 */
    private val STREAM_QUOTES = listOf("usdt", "usdc", "fdusd", "busd", "usd").sortedByDescending { it.length }
}
