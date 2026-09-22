package com.crypto.cryptowatch.data

import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.model.SourceResult
import com.crypto.cryptowatch.settings.CryptoSettings
import com.crypto.cryptowatch.util.upper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 行情聚合服务（应用级单例）。
 *
 * 数据来源被拆成两半，各司其职：
 * - **REST（本类的 [fetchREST]）**：只负责「币种列表 + 市值榜」这类低频、全量的数据，
 *   默认走 [com.crypto.cryptowatch.data.source.CoinLoreSource]；
 * - **WebSocket（[com.crypto.cryptowatch.data.ws.BinanceWsClient]）**：负责「当前价格」的实时推送。
 *
 * 之所以这样分工，是因为受限网络下币安的 REST 域名不可达、而 WS 可连通，
 * 反之聚合接口（CoinLore）的 REST 稳定好用。让两者各做擅长的事，
 * 就能在「无代理」的前提下同时拿到**完整币种列表**与**实时价格**。
 *
 * 本类只做纯计算（并发拉取 + 合并去重），不持有连接、不持有定时器；
 * 长连接与快照缓存由 [MarketStreamService] 负责。
 */
@Service(Service.Level.APP)
class MarketService {

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "CryptoWatch-Fetcher").apply { isDaemon = true }
    }

    /**
     * 拉取一次 REST 快照（币种列表 + 市值），结果同时携带每个源的健康状态。
     *
     * 该方法会被 [MarketStreamService] 在启动时以及低频兜底时调用。
     */
    fun fetchREST(): MarketSnapshot {
        val settings = CryptoSettings.getInstance()
        val sources = MarketDataSources.enabled(settings.enabledSources())
        if (sources.isEmpty()) {
            return MarketSnapshot(emptyList(), emptyList(), System.currentTimeMillis())
        }

        val tasks = sources.map { source ->
            Callable {
                try {
                    SourceResult.Success(source.fetch(), source.id)
                } catch (t: Throwable) {
                    SourceResult.Failure(source.id, t.message ?: t.javaClass.simpleName)
                }
            }
        }

        // 各源内部已有自己的镜像重试，这里再兜一层总超时，避免个别源把整体拖死
        val futures = pool.invokeAll(tasks, OVERALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val results = futures.map { future ->
            try {
                future.get() as SourceResult
            } catch (t: Throwable) {
                SourceResult.Failure("unknown", t.message ?: "timeout")
            }
        }

        val order = sources.map { it.id }
        return MarketSnapshot(merge(results, order), results, System.currentTimeMillis())
    }

    /**
     * 合并同币种的多个快照。
     *
     * 去重键为 "BASE/QUOTE"，但市值榜使用 USD 计价、交易所使用 USDT，
     * 因此把 USD 与 USDT 视为等价，避免出现 BTC/USDT 与 BTC/USD 两行。
     */
    private fun merge(results: List<SourceResult>, order: List<String>): List<Quote> {
        val priority = order.withIndex().associate { (i, id) -> id to i }
        val byKey = LinkedHashMap<String, Quote>()

        results.filterIsInstance<SourceResult.Success>()
            .sortedBy { priority[it.sourceId] ?: Int.MAX_VALUE }
            .forEach { result ->
                result.quotes.forEach { quote ->
                    val key = mergeKey(quote)
                    val existing = byKey[key]
                    byKey[key] = when (existing) {
                        null -> quote
                        // 已有更高优先级的价格：仅用新数据补全市值等缺口字段
                        else -> existing.mergeSupplementary(quote)
                    }
                }
            }
        return byKey.values.toList()
    }

    /** 稳定币计价视为同一币种；不区分类目，同币种只保留优先级最高的一条。 */
    private fun mergeKey(q: Quote): String {
        val quote = when (q.quote.upper()) {
            "USD" -> "USDT"
            else -> q.quote.upper()
        }
        return "${q.base.upper()}|$quote"
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    companion object {
        /**
         * 整体超时。
         *
         * 主源 CoinLore 需要按 `start` 翻页，多个请求串行（每页单次超时 3s），
         * 因此这里留出 12s 余量；多个数据源本身仍是并行请求，最慢的那个决定总耗时。
         */
        private const val OVERALL_TIMEOUT_SECONDS = 12L

        fun getInstance(): MarketService =
            ApplicationManager.getApplication().getService(MarketService::class.java)

        /**
         * 用 WebSocket 推送的实时报价覆盖 REST 快照。
         *
         * @param rest       REST 快照（币种列表 + 市值），可能为空
         * @param liveQuotes WS 实时报价，按 base 索引
         * @return 覆盖后的列表：REST 中的币种若已有实时价则替换价格字段，其余保持原样；
         *         REST 中不存在但 WS 收到的币种（例如新上币种）会被追加进来。
         */
        fun overlayLive(rest: List<Quote>, liveQuotes: Map<String, Quote>): List<Quote> {
            if (liveQuotes.isEmpty()) return rest

            val result = ArrayList<Quote>(rest.size + 4)
            val usedKeys = HashSet<String>(liveQuotes.size * 2)

            rest.forEach { quote ->
                val key = quote.base.upper()
                val live = liveQuotes[key]
                if (live == null) {
                    result += quote
                } else {
                    usedKeys += key
                    result += live.overlayOnRest(quote)
                }
            }

            // WS 已收到但 REST 列表里没有的币种（新上线/榜单未覆盖），补充为独立行情
            liveQuotes.forEach { (key, live) ->
                if (key !in usedKeys) result += live
            }
            return result
        }
    }
}

/** 一次 REST 快照的拉取结果。 */
data class MarketSnapshot(
    val quotes: List<Quote>,
    val sourceResults: List<SourceResult>,
    val fetchedAt: Long
) {
    val failures: List<SourceResult.Failure> get() = sourceResults.filterIsInstance<SourceResult.Failure>()
    val successCount: Int get() = sourceResults.count { it is SourceResult.Success }
}
