package com.crypto.cryptowatch.settings

import com.crypto.cryptowatch.data.MarketDataSources
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 插件全局设置，通过 IDE 的 [com.intellij.openapi.components.PersistentStateComponent] 落盘，
 * 保存在 IDE 配置目录下，升级插件不丢失。
 */
@Service(Service.Level.APP)
@State(name = "CryptoWatchSettings", storages = [Storage("cryptoWatch.xml")])
class CryptoSettings : PersistentStateComponent<CryptoSettings.State> {

    data class State(
        /**
         * 是否用 WebSocket 推送实时价格。
         *
         * 开启后价格由币安 WS 长连接推送，不再需要轮询；关闭则退化为只用 REST 快照。
         */
        var wsEnabled: Boolean = true,
        /**
         * 除自选之外，额外订阅的「按市值排序前 N 个」币种数量。
         *
         * 自选币种始终会被订阅，这个值只决定热门币的覆盖面。
         */
        var subscribeTopN: Int = 40,
        /** 启用的 REST 数据源 id 集合，用于拉取币种列表与市值。 */
        var enabledSources: MutableList<String> =
            MarketDataSources.defaultEnabledIds.toMutableList(),
        /** 插件自有代理（IDE 代理优先）。 */
        var proxyHost: String = "",
        var proxyPort: Int = 0,
        /** 配置结构版本，用于升级时做一次性迁移。 */
        var stateVersion: Int = CURRENT_STATE_VERSION
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        // 旧版本配置里的自动刷新、刷新间隔、只看自选等字段会被自动忽略，不会导致加载失败
        XmlSerializerUtil.copyBean(state, myState)
        migrate()
        myState.subscribeTopN = myState.subscribeTopN.coerceIn(MIN_TOP_N, MAX_TOP_N)
    }

    /**
     * 一次性迁移。
     *
     * v2：默认数据源由「全量交易所 + 聚合源」收敛为「只启用第三方聚合源」，
     *     因为受限网络下交易所 REST 域名全不可达，逐个串行重试会耗尽整体超时；
     * v3：取消自动刷新轮询，改由 WebSocket 推送实时价格，因此把历史配置里
     *     默认全开的数据源一并收敛，并把订阅数量初始化到合理值；
     * v4：列表固定为「只看自选」，不再提供开关，历史配置里的相关字段被忽略。
     */
    private fun migrate() {
        if (myState.stateVersion >= CURRENT_STATE_VERSION) return
        if (myState.enabledSources.isNotEmpty()) {
            myState.enabledSources = MarketDataSources.defaultEnabledIds.toMutableList()
        }
        if (myState.subscribeTopN <= 0) myState.subscribeTopN = DEFAULT_TOP_N
        myState.stateVersion = CURRENT_STATE_VERSION
    }

    val wsEnabled: Boolean get() = myState.wsEnabled

    /** 订阅的币种数量（自选之外的热门币），已做边界收敛。 */
    val subscribeTopN: Int get() = myState.subscribeTopN.coerceIn(MIN_TOP_N, MAX_TOP_N)

    val proxyHost: String get() = myState.proxyHost
    val proxyPort: Int get() = myState.proxyPort

    fun enabledSources(): List<String> = myState.enabledSources

    fun setEnabledSources(ids: Collection<String>) {
        myState.enabledSources = ids.toMutableList()
    }

    var wsEnabledMutable: Boolean
        get() = myState.wsEnabled
        set(value) { myState.wsEnabled = value }

    var subscribeTopNMutable: Int
        get() = myState.subscribeTopN
        set(value) { myState.subscribeTopN = value.coerceIn(MIN_TOP_N, MAX_TOP_N) }

    var proxyHostMutable: String
        get() = myState.proxyHost
        set(value) { myState.proxyHost = value }

    var proxyPortMutable: Int
        get() = myState.proxyPort
        set(value) { myState.proxyPort = value }

    companion object {
        /**
         * 当前配置结构版本。
         *
         * v2：默认数据源收敛为第三方聚合源；
         * v3：用 WebSocket 推送替代自动刷新轮询；
         * v4：列表固定为只看自选。
         */
        const val CURRENT_STATE_VERSION: Int = 4

        /** 订阅数量默认值与边界。 */
        const val DEFAULT_TOP_N: Int = 40
        const val MIN_TOP_N: Int = 0
        const val MAX_TOP_N: Int = 80

        fun getInstance(): CryptoSettings =
            ApplicationManager.getApplication().getService(CryptoSettings::class.java)
    }
}
