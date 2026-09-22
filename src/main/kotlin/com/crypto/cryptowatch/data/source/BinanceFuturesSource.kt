package com.crypto.cryptowatch.data.source

import com.crypto.cryptowatch.data.ExchangeDataSource
import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.Http

/** 币安 USDT 本位永续合约行情。 */
class BinanceFuturesSource : ExchangeDataSource {

    override val id = "binance-futures"
    override val displayName = "Binance 合约"
    override val category = MarketCategory.FUTURES

    private val hosts = listOf(
        "https://fapi.binance.com",
        "https://fapi1.binance.com",
        "https://fapi2.binance.com"
    )

    override fun fetch(): List<Quote> {
        // 24hr 接口不可用时退化为只有最新价的 price 接口
        val body = Http.firstSuccessPaths(
            hosts,
            listOf("/fapi/v1/ticker/24hr", "/fapi/v1/ticker/price")
        )
        val array = Json.arr(Json.parse(body)) ?: return emptyList()
        return array.mapNotNull { raw ->
            val map = Json.obj(raw) ?: return@mapNotNull null
            val symbol = Json.str(map, "symbol") ?: return@mapNotNull null
            // 只保留 USDT 本位永续，币本位(dapi)与交割合约不在此接口内
            if (!symbol.endsWith("USDT")) return@mapNotNull null
            val base = symbol.removeSuffix("USDT")
            if (base.isEmpty()) return@mapNotNull null
            val price = Json.num(map, "lastPrice", "price") ?: return@mapNotNull null
            if (price <= 0.0) return@mapNotNull null
            Quote(
                id = symbol,
                symbol = symbol,
                base = base,
                quote = "USDT",
                price = price,
                changePct = Json.num(map, "priceChangePercent") ?: 0.0,
                high24h = Json.num(map, "highPrice"),
                low24h = Json.num(map, "lowPrice"),
                volume24h = Json.num(map, "volume"),
                quoteVolume = Json.num(map, "quoteVolume"),
                sourceId = id,
                category = category
            )
        }
    }
}
