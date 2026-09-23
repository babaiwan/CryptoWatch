package com.crypto.cryptowatch.model

import com.crypto.cryptowatch.ui.I18n
import com.crypto.cryptowatch.util.upper

/**
 * 行情类目。同一个 [Quote] 可以被多个类目同时包含（例如 BTCUSDT 同时出现在现货与合约里）。
 *
 * 展示名不放在枚举构造参数里，而是在 [displayName] 中按当前语言实时获取：
 * 枚举是单例，一旦把文案固化进枚举常量，运行时切换语言就永远看不到新文字。
 */
enum class MarketCategory {
    SPOT,
    FUTURES,
    TOP;

    /** 当前语言下的类目名（现货 / 合约 / 市值榜）。 */
    val displayName: String
        get() = when (this) {
            SPOT -> I18n.text("category.spot")
            FUTURES -> I18n.text("category.futures")
            TOP -> I18n.text("category.top")
        }

    companion object {
        /**
         * 刻意使用 `values()` 而不是 `entries`。
         *
         * `Enum.entries` 依赖 Kotlin 1.9 才引入的 `kotlin.enums.EnumEntries`，
         * 在 2020.3 这类老平台（内置 Kotlin 1.4）上会直接抛 `NoSuchMethodError`；
         * 更糟的是它出现在枚举的 `<clinit>` 里，会导致枚举类本身初始化失败。
         */
        fun fromName(raw: String?): MarketCategory =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SPOT
    }
}

/**
 * 一条实时行情快照。
 *
 * @param id          数据源内的唯一标识（例如 "BTCUSDT"），跨源去重时使用 [canonicalKey]。
 * @param symbol      展示用交易对，例如 BTCUSDT / BTC-USDT
 * @param base        基础币种，例如 BTC
 * @param quote       计价币种，例如 USDT
 * @param price       最新价
 * @param changePct   24 小时涨跌幅（百分比，例如 -1.23 表示 -1.23%）
 * @param high24h     24 小时最高价，未知为 null
 * @param low24h      24 小时最低价
 * @param volume24h   24 小时成交量（基础币种数量）
 * @param quoteVolume 24 小时成交额（计价币种金额）
 * @param marketCap   市值，仅市值榜类目提供；合并时会用其它源的值补全
 * @param marketCapRank 市值排名，仅市值榜类目提供
 * @param sourceId    数据源 id
 * @param updatedAt   本地接收到该数据的毫秒时间戳
 */
data class Quote(
    val id: String,
    val symbol: String,
    val base: String,
    val quote: String,
    val price: Double,
    val changePct: Double,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val volume24h: Double? = null,
    val quoteVolume: Double? = null,
    val volumeIsQuote: Boolean = false,
    /** 占位行：自选里有该币种，但当前没有任何数据源返回它（例如网络不可达）。 */
    val placeholder: Boolean = false,
    val marketCap: Double? = null,
    val marketCapRank: Int? = null,
    val sourceId: String = "",
    val category: MarketCategory = MarketCategory.SPOT,
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 用另一个源的补充信息（市值、24h 统计等）补全当前快照。**主价格始终以当前源的为准**。
     *
     * [changePct] 为 NaN 表示「本帧不携带 24h 开盘价，因此无法计算涨跌幅」——
     * 币安的 `bookTicker` 就是这种帧（只有买卖一价，毫秒级推送），
     * 此时必须沿用另一条数据里的涨跌幅，否则实时价会把涨跌幅冲成 0。
     */
    fun mergeSupplementary(other: Quote): Quote = copy(
        marketCap = marketCap ?: other.marketCap,
        marketCapRank = marketCapRank ?: other.marketCapRank,
        changePct = if (changePct.isNaN()) other.changePct else changePct,
        high24h = high24h ?: other.high24h,
        low24h = low24h ?: other.low24h,
        quoteVolume = quoteVolume ?: other.quoteVolume,
        volume24h = volume24h ?: other.volume24h
    )

    /**
     * 把本行（WebSocket 实时推送）覆盖到 REST 快照行上。
     *
     * 价格、涨跌等实时字段一律以本行为准；REST 快照独有的市值、市值排名等字段则补齐过来，
     * 这样「WS 提供实时价 + REST 提供币种全貌」两者兼得。
     */
    fun overlayOnRest(rest: Quote): Quote = copy(
        marketCap = marketCap ?: rest.marketCap,
        marketCapRank = marketCapRank ?: rest.marketCapRank,
        changePct = if (changePct.isNaN()) rest.changePct else changePct,
        high24h = high24h ?: rest.high24h,
        low24h = low24h ?: rest.low24h,
        quoteVolume = quoteVolume ?: rest.quoteVolume,
        volume24h = volume24h ?: rest.volume24h
    )

    /** 跨交易所去重用的规范化键，例如 "BTC/USDT"。 */
    val canonicalKey: String get() = canonical(base, quote)

    /** 用于展示的短名，例如 "BTC/USDT"。 */
    val displaySymbol: String get() = "$base/$quote"

    companion object {
        fun canonical(base: String, quote: String) = "${base.upper()}/${quote.upper()}"
    }
}

/** 单个数据源的拉取结果。 */
sealed interface SourceResult {
    data class Success(val quotes: List<Quote>, val sourceId: String) : SourceResult
    data class Failure(val sourceId: String, val message: String) : SourceResult
}
