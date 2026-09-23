package com.crypto.cryptowatch.data.source

import com.crypto.cryptowatch.data.ExchangeDataSource
import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.ui.I18n
import com.crypto.cryptowatch.util.Http
import com.crypto.cryptowatch.util.upper

/**
 * CoinCap 市值榜。作为 CoinGecko 的补充源，无频率限制、可达性更好。
 */
class CoinCapSource(private val limit: Int = 150) : ExchangeDataSource {

    override val id = "coincap"
    override val displayName: String get() = I18n.text("source.coincap")
    override val category = MarketCategory.TOP

    override fun fetch(): List<Quote> {
        val body = Http.getText("https://rest.coincap.io/v3/assets?limit=$limit")
        val data = Json.arr(Json.obj(Json.parse(body))?.get("data")) ?: return emptyList()
        return data.mapNotNull { raw ->
            val map = Json.obj(raw) ?: return@mapNotNull null
            val symbol = Json.str(map, "symbol")?.upper() ?: return@mapNotNull null
            val id = Json.str(map, "id") ?: symbol
            val price = Json.num(map, "priceUsd") ?: return@mapNotNull null
            Quote(
                id = id,
                symbol = "${symbol}USD",
                base = symbol,
                quote = "USD",
                price = price,
                changePct = Json.num(map, "changePercent24Hr") ?: 0.0,
                quoteVolume = Json.num(map, "volumeUsd24Hr"),
                volumeIsQuote = true,
                marketCap = Json.num(map, "marketCapUsd"),
                marketCapRank = Json.num(map, "rank")?.toInt(),
                sourceId = id,
                category = category
            )
        }
    }
}
