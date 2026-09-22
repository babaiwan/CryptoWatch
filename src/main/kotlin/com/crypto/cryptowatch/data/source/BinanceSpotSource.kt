package com.crypto.cryptowatch.data.source

import com.crypto.cryptowatch.data.ExchangeDataSource
import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.Http

/**
 * 币安现货公开行情。
 *
 * 主域名 api.binance.com 在国内多数网络下不可达，因此把多个镜像域名依次尝试，
 * 只要有一个通就能出数据。这也是整个插件"多源互备"思路的缩影。
 *
 * 另外，`/api/v3/ticker/24hr` 在部分镜像上可能被限流或直接不可用，
 * 此时会降级到 `/api/v3/ticker/price`：虽然只有最新价、没有涨跌幅与成交额，
 * 但至少能出数据，用户不会看到空白列表。
 */
class BinanceSpotSource : ExchangeDataSource {

    override val id = "binance-spot"
    override val displayName = "Binance 现货"
    override val category = MarketCategory.SPOT

    private val hosts = listOf(
        "https://api.binance.com",
        // 以下为主流域名的备用入口，可用性随时间变化，失败会自动跳过
        "https://data-api.binance.vision",
        "https://api1.binance.com",
        "https://api-gcp.binance.com"
    )

    override fun fetch(): List<Quote> {
        val body = Http.firstSuccessPaths(
            hosts,
            listOf("/api/v3/ticker/24hr", "/api/v3/ticker/price")
        )
        val array = Json.arr(Json.parse(body)) ?: return emptyList()
        return array.mapNotNull { raw ->
            val map = Json.obj(raw) ?: return@mapNotNull null
            val symbol = Json.str(map, "symbol") ?: return@mapNotNull null
            val split = splitSymbol(symbol) ?: return@mapNotNull null
            val (base, quote) = split
            val price = Json.num(map, "lastPrice", "price") ?: return@mapNotNull null
            if (price <= 0.0) return@mapNotNull null
            Quote(
                id = symbol,
                symbol = symbol,
                base = base,
                quote = quote,
                price = price,
                changePct = Json.num(map, "priceChangePercent") ?: 0.0,
                high24h = Json.num(map, "highPrice"),
                low24h = Json.num(map, "lowPrice"),
                volume24h = Json.num(map, "volume"),
                quoteVolume = Json.num(map, "quoteVolume"),
                volumeIsQuote = false,
                sourceId = id,
                category = category
            )
        }
    }
}
