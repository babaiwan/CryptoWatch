package com.crypto.cryptowatch.data.source

import com.crypto.cryptowatch.data.ExchangeDataSource
import com.crypto.cryptowatch.data.Json
import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.Http
import com.crypto.cryptowatch.util.upper

/**
 * Gate.io 现货与合约。
 *
 * Gate 的 tickers 接口一次返回全量交易对，非常适合作为兜底数据源。
 */
class GateSource(override val category: MarketCategory) : ExchangeDataSource {

    override val id: String =
        if (category == MarketCategory.FUTURES) "gate-futures" else "gate-spot"

    override val displayName: String =
        if (category == MarketCategory.FUTURES) "Gate 合约" else "Gate 现货"

    private val hosts = listOf(
        "https://api.gateio.ws",
        "https://api.gate.io"
    )

    /** 现货用 BTC_USDT，合约用 BTC_USDT 形式的永续。 */
    override fun toNativeSymbol(base: String, quote: String): String =
        "${base.upper()}_${quote.upper()}"

    override fun fetch(): List<Quote> {
        val path = if (category == MarketCategory.FUTURES) {
            "/api/v4/futures/usdt/tickers"
        } else {
            "/api/v4/spot/tickers"
        }
        val body = Http.firstSuccess(hosts) { "$it$path" }
        val array = Json.arr(Json.parse(body)) ?: return emptyList()
        return array.mapNotNull { raw ->
            val map = Json.obj(raw) ?: return@mapNotNull null
            val contract = Json.str(map, "currency_pair", "contract") ?: return@mapNotNull null
            if (!contract.endsWith("_USDT")) return@mapNotNull null
            val base = contract.removeSuffix("_USDT")
            if (base.isEmpty()) return@mapNotNull null
            val price = Json.num(map, "last") ?: return@mapNotNull null
            if (price <= 0.0) return@mapNotNull null
            Quote(
                id = contract,
                symbol = contract,
                base = base,
                quote = "USDT",
                price = price,
                changePct = Json.num(map, "change_percentage", "change_utc0") ?: 0.0,
                high24h = Json.num(map, "high_24h"),
                low24h = Json.num(map, "low_24h"),
                // 合约接口只提供 quote 计价成交额，标记后由聚合层还原统一语义
                volume24h = Json.num(map, "volume_24h_usd", "volume_24h_settle", "base_volume"),
                volumeIsQuote = true,
                sourceId = id,
                category = category
            )
        }
    }
}
