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
 * CoinLore 免费聚合行情源（本插件的主数据源）。
 *
 * 选择它的理由：`api.coinlore.net` 属于普通公网站点，在国内多数网络下**无需代理即可访问**，
 * 而交易所 API（Binance / OKX / Gate）与 CoinGecko / CoinCap 的域名常常不可达。
 *
 * 它按市值排序返回币种列表，字段包含 USD 价格、24h 涨跌、成交额、市值与排名，
 * 正好覆盖「币种列表 + 当前价格」这一核心需求；无需注册、无需 token。
 *
 * 接口说明：每页最多 100 条，通过 `start` 参数翻页。
 *
 * **默认只取 1 页（100 条）**：翻页是**串行**请求，页数越多首次出数据越慢
 * （4 页最坏 4×3=12 秒，而 1 页最快仅数百毫秒）。而「自选里的冷门币不在榜内」
 * 这个问题并不依赖页数——[com.crypto.cryptowatch.data.MarketService.overlayLive]
 * 会把「WebSocket 收到但 REST 列表没有」的币种自动补进来，因此自选币的实时价不受影响。
 * 用 1 页换取显著更快的首屏与更短的阻塞时间，是更划算的取舍。
 *
 * 单页超时被压到 [PAGE_TIMEOUT_SECONDS]（3 秒）：正常网络下单页响应在数百毫秒级。
 */
class CoinLoreSource(private val pages: Int = 1) : ExchangeDataSource {

    override val id = "coinlore"
    override val displayName: String get() = I18n.text("source.coinlore")
    override val category = MarketCategory.TOP

    private val host = "https://api.coinlore.net"

    override fun fetch(): List<Quote> {
        val result = LinkedHashMap<String, Quote>()
        var start = 0
        var page = 0

        while (page < pages) {
            val url = "$host/api/tickers/?start=$start&limit=$PAGE_SIZE"
            val root = try {
                Json.obj(Json.parse(Http.getText(url, PAGE_TIMEOUT_SECONDS)))
            } catch (t: Throwable) {
                // 第一页就失败：说明源真的不可达，抛出可读异常让聚合层与状态栏明确展示原因。
                // 后续页失败：保留已拿到的数据，避免前功尽弃。
                if (result.isEmpty()) {
                    throw DataSourceException("CoinLore 不可达: ${t.message ?: t.javaClass.simpleName}", t)
                }
                break
            } ?: break

            val data = Json.arr(root["data"]) ?: break
            if (data.isEmpty()) break

            for (raw in data) {
                val map = Json.obj(raw) ?: continue
                val base = Json.str(map, "symbol")?.upper()?.takeIf { it.isNotBlank() } ?: continue
                val price = Json.num(map, "price_usd") ?: continue
                if (price <= 0.0) continue

                val quote = Quote(
                    id = Json.str(map, "nameid") ?: base,
                    symbol = "${base}USD",
                    base = base,
                    quote = "USD",
                    price = price,
                    changePct = Json.num(map, "percent_change_24h") ?: 0.0,
                    // volume24a 为 24h 成交额（USD），volume24 为其别名
                    quoteVolume = Json.num(map, "volume24a", "volume24"),
                    volumeIsQuote = true,
                    marketCap = Json.num(map, "market_cap_usd"),
                    marketCapRank = Json.num(map, "rank")?.toInt(),
                    sourceId = this.id,
                    category = category
                )
                // 同一基础币种只保留首次出现（市值排名更靠前）的一条
                result.putIfAbsent(base, quote)
            }

            if (data.size < PAGE_SIZE) break
            start += PAGE_SIZE
            page++
        }

        // 响应结构异常（例如接口变更导致 data 字段缺失）时同样明确报错，而不是静默显示空白列表
        if (result.isEmpty()) throw DataSourceException("CoinLore 返回的数据为空或结构异常")
        return result.values.toList()
    }

    companion object {
        /** CoinLore 单页上限固定为 100。 */
        private const val PAGE_SIZE = 100

        /** 单页请求超时。翻页是串行的，超时必须紧凑。 */
        private const val PAGE_TIMEOUT_SECONDS = 3L
    }
}
