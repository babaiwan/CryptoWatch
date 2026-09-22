package com.crypto.cryptowatch.data

import com.crypto.cryptowatch.data.source.CoinGeckoSource
import com.crypto.cryptowatch.data.source.CoinLoreSource

/**
 * 内置数据源注册表。
 *
 * 策略：只保留「第三方聚合接口」，不再默认启用交易所源。
 *
 * 原因：币安 / OKX / Gate 等交易所的 API 域名在国内多数网络下不可达，而且每个源还配了
 * 多个镜像域名与备用接口，逐个串行尝试会让单个源轻易超过整体超时；结果是用户等了很久，
 * 却只看到「所有候选地址均不可用」和一连串 "-"。
 *
 * 聚合类接口（尤其 `api.coinlore.net`）属于普通公网站点，通常无需任何代理即可访问，
 * 能稳定提供「币种列表 + 最新价 + 24h 涨跌 + 成交额 + 市值」，正好覆盖看盘需求。
 *
 * 列表顺序即优先级：同一币种在多源出现时保留靠前源的价格，用靠后源补全市值等字段。
 */
object MarketDataSources {

    /** 全量数据源，按优先级排列（越靠前越可信）。 */
    val all: List<ExchangeDataSource> = listOf(
        // 主源：CoinLore，普通公网接口、无需 token，可达性最好
        CoinLoreSource(),
        // 备源：CoinGecko，用于补全市值与排名，免费接口限流较紧
        CoinGeckoSource()
    )

    /**
     * 默认启用的数据源 id。
     *
     * 只启用 CoinLore：实测在当前网络下它是唯一稳定可达的接口，而 CoinGecko 会直接超时——
     * 若默认启用，聚合层必须等它跑满单次超时才能出结果，白白拖慢首屏并让状态栏常驻一条失败。
     * 需要第二份数据时，可在设置中手动勾选 CoinGecko 或交易所源。
     */
    val defaultEnabledIds: List<String> = listOf("coinlore")

    fun byId(id: String): ExchangeDataSource? = all.firstOrNull { it.id == id }

    /**
     * WebSocket 实时价的来源名称。
     *
     * 它不是 [ExchangeDataSource]（不是通过 REST 批量拉取的源，而是长连接推送），
     * 因此不出现在 [all] 中，但同样需要在「来源」列展示可读名称。
     */
    private const val WS_SOURCE_ID = "binance-ws"
    private const val WS_SOURCE_NAME = "Binance 实时(WS)"

    /**
     * 把数据源 id 映射为可读名称，供「来源」列与信息框展示。
     *
     * 未知 id（例如历史配置里残留的交易所源）原样返回，避免展示为空。
     */
    fun displayNameOf(id: String): String = when (id) {
        WS_SOURCE_ID -> WS_SOURCE_NAME
        else -> byId(id)?.displayName ?: id
    }

    fun enabled(ids: Collection<String>): List<ExchangeDataSource> {
        val idSet = ids.toSet()
        return all.filter { it.id in idSet }
    }
}
