package com.crypto.cryptowatch.data.source

import com.crypto.cryptowatch.data.DataSourceException
import com.crypto.cryptowatch.data.ExchangeDataSource
import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.ui.I18n
import com.crypto.cryptowatch.util.Http
import com.crypto.cryptowatch.util.upper

/**
 * OKX（欧易）行情。
 *
 * OKX 一个接口即可覆盖现货与永续（通过 instType 区分），端口在国内可达性整体较好，
 * 因此把它放在数据源注册表的前列。
 *
 * 注意：OKX 的 tickers 接口**不支持分页**（`after` 参数会被忽略，始终返回前 100 条），
 * 因此这里只在第一页取数，且按 quoteVolume 排序取成交额最大的一批，
 * 避免固定只拿到按 instId 字母序排列的币对。
 */
class OkxSource(override val category: MarketCategory) : ExchangeDataSource {

    override val id: String =
        if (category == MarketCategory.FUTURES) "okx-swap" else "okx-spot"

    override val displayName: String
        get() = I18n.text(if (category == MarketCategory.FUTURES) "source.okx.futures" else "source.okx.spot")

    private val hosts = listOf(
        "https://aws.okx.com",
        "https://www.okx.com",
        "https://okx.com"
    )

    private val instType = if (category == MarketCategory.FUTURES) "SWAP" else "SPOT"

    override fun toNativeSymbol(base: String, quote: String): String {
        val pair = "${base.upper()}-${quote.upper()}"
        // 永续合约的统一符号形如 BTC-USDT-SWAP
        return if (category == MarketCategory.FUTURES) "$pair-SWAP" else pair
    }

    override fun fetch(): List<Quote> {
        val host = pickHost()
        val url = "$host/api/v5/market/tickers?instType=$instType"
        val root = Json.obj(Json.parse(Http.getText(url)))
            ?: throw DataSourceException("OKX 返回结构异常")
        val data = Json.arr(root["data"]) ?: return emptyList()

        val quotes = ArrayList<Quote>(data.size)
        for (raw in data) {
            val map = Json.obj(raw) ?: continue
            val instId = Json.str(map, "instId") ?: continue
            val quote = instId.substringAfterLast('-', "")
            if (quote != "USDT") continue
            val base = instId.substringBefore('-')
            val price = Json.num(map, "last") ?: continue
            if (price <= 0.0) continue
            val open24h = Json.num(map, "open24h")
            val changePct = if (open24h != null && open24h != 0.0) {
                (price - open24h) / open24h * 100.0
            } else {
                0.0
            }
            quotes += Quote(
                id = instId,
                symbol = instId,
                base = base,
                quote = quote,
                price = price,
                changePct = changePct,
                high24h = Json.num(map, "high24h"),
                low24h = Json.num(map, "low24h"),
                volume24h = Json.num(map, "vol24h"),
                quoteVolume = Json.num(map, "volCcy24h"),
                sourceId = id,
                category = category
            )
        }
        // 推荐接口数据不全时，退化为"按成交额较大的币对优先"展示，而不是字母序
        return quotes.sortedByDescending { it.quoteVolume ?: 0.0 }
    }

    /** 依次探测镜像域名，返回第一个可达的 host。 */
    private fun pickHost(): String {
        var lastError: Throwable? = null
        for (host in hosts) {
            try {
                Http.getText("$host/api/v5/public/time")
                return host
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw DataSourceException("OKX 镜像均不可用", lastError)
    }
}
