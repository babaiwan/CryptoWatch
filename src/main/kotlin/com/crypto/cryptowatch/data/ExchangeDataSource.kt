package com.crypto.cryptowatch.data

import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.upper

/**
 * 一个行情数据源的抽象。
 *
 * 所有实现都应当：
 * - 使用 [com.crypto.cryptowatch.util.Http]，从而自动继承 IDE 的代理配置；
 * - 网络失败时抛异常，由聚合层捕获并降级到下一个数据源；
 * - 不缓存，缓存策略统一放在 [MarketService]。
 */
interface ExchangeDataSource {

    /** 唯一 id，例如 "binance-spot"。 */
    val id: String

    /** 人类可读名称，用于设置界面与状态栏（按当前界面语言取值）。 */
    val displayName: String

    /** 该数据源所属类目。 */
    val category: MarketCategory

    /**
     * 拉取该数据源下的全部行情。返回数量可能很大（数千条），
     * 聚合层会在合并后只保留用户关心的部分，因此这里尽量只请求必要的字段。
     */
    fun fetch(): List<Quote>

    /** 该数据源上是否支持指定币种。用于"按币种优先顺序"降级时判断。 */
    fun supports(symbol: String): Boolean = true

    /** 把 base/quote 转换成数据源需要的符号格式。 */
    fun toNativeSymbol(base: String, quote: String): String = "${base.upper()}${quote.upper()}"

    /** 把数据源符号还原为基础/计价币种。 */
    fun splitSymbol(symbol: String): Pair<String, String>? {
        var up = symbol.upper()
        for (suffix in listOf("-SWAP", "-FUTURES", "_SWAP", "-PERP", "PERP")) {
            if (up.endsWith(suffix)) up = up.removeSuffix(suffix)
        }
        up = up.replace("-", "").replace("_", "").replace("/", "")
        for (q in COMMON_QUOTES) {
            if (up.endsWith(q) && up.length > q.length) return up.dropLast(q.length) to q
        }
        return null
    }

    companion object {
        /** 按长度倒序，避免 "USDT" 被 "USD" 抢先匹配。 */
        val COMMON_QUOTES: List<String> =
            listOf("USDT", "USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USD", "BTC", "ETH", "EUR", "TRY", "BRL")
                .sortedByDescending { it.length }
    }
}

/** 数据源抛出的可读异常。 */
class DataSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
