package com.crypto.cryptowatch.data.source

import com.crypto.cryptowatch.data.ExchangeDataSource
import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.Http
import com.crypto.cryptowatch.util.upper

/**
 * CoinGecko 市值榜（按市值排序的 Top N）。
 *
 * 作为 CoinLore 的备源使用：CoinLore 已经提供「币种 + 最新价 + 涨跌 + 成交额 + 市值」，
 * 这里的作用是在 CoinLore 抖动时提供第二份数据，并在合并时补全市值与排名。
 *
 * 免费接口限流较紧（约 5~15 次/分钟），因此：
 * - 只保留 `api.coingecko.com` 一个地址，不再尝试需要付费 Key 的 `pro-api` 域名
 *   （它对本插件永远是 401/403，只会白白浪费一次超时）；
 * - 若带 `price_change_percentage=24h` 的完整接口失败，降级到精简接口，
 *   牺牲涨跌幅也要先保证「币种列表 + 最新价」可用。
 */
class CoinGeckoSource(private val perPage: Int = 250) : ExchangeDataSource {

    override val id = "coingecko"
    override val displayName = "CoinGecko 市值榜"
    override val category = MarketCategory.TOP

    /** 首选：字段完整（含 24h 涨跌）；次选：字段精简，仅保证价格与市值。 */
    private val urls = listOf(
        "$BASE/coins/markets?vs_currency=usd&order=market_cap_desc" +
            "&per_page=$perPage&page=1&sparkline=false&price_change_percentage=24h",
        "$BASE/coins/markets?vs_currency=usd&order=market_cap_desc" +
            "&per_page=$perPage&page=1&sparkline=false"
    )

    override fun fetch(): List<Quote> {
        val body = Http.firstSuccess(urls) { it }
        val array = Json.arr(Json.parse(body)) ?: return emptyList()
        return array.mapNotNull { raw ->
            val map = Json.obj(raw) ?: return@mapNotNull null
            val symbol = Json.str(map, "symbol")?.upper()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = Json.str(map, "name") ?: symbol
            val price = Json.num(map, "current_price") ?: return@mapNotNull null
            if (price <= 0.0) return@mapNotNull null
            val id = Json.str(map, "id") ?: name
            Quote(
                id = id,
                symbol = "${symbol}USD",
                base = symbol,
                quote = "USD",
                price = price,
                changePct = Json.num(map, "price_change_percentage_24h") ?: 0.0,
                high24h = Json.num(map, "high_24h"),
                low24h = Json.num(map, "low_24h"),
                quoteVolume = Json.num(map, "total_volume"),
                volumeIsQuote = true,
                marketCap = Json.num(map, "market_cap"),
                marketCapRank = Json.num(map, "market_cap_rank")?.toInt(),
                sourceId = this.id,
                category = category
            )
        }
    }

    companion object {
        private const val BASE = "https://api.coingecko.com/api/v3"
    }
}
