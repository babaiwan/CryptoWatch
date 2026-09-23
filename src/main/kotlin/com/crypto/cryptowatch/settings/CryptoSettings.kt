package com.crypto.cryptowatch.settings

import com.crypto.cryptowatch.data.MarketDataSources
import com.crypto.cryptowatch.ui.Language
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
        /** 启用的 REST 数据源 id 集合，用于拉取币种列表与市值。 */
        var enabledSources: MutableList<String> =
            MarketDataSources.defaultEnabledIds.toMutableList(),
        /** 插件自有代理（IDE 代理优先）。 */
        var proxyHost: String = "",
        var proxyPort: Int = 0,
        /**
         * 界面语言，保存的是 [com.crypto.cryptowatch.ui.Language] 的枚举名
         * （"SYSTEM" / "EN" / "ZH"）。
         *
         * 之所以存枚举名而不是 locale 字符串：枚举名与代码一一对应，
         * 名字改了编译器就会报错，而 locale 字符串错了只会静默回退，
         * 排查成本高得多。
         */
        var language: String = Language.SYSTEM.name,
        /** 配置结构版本，用于升级时做一次性迁移。 */
        var stateVersion: Int = CURRENT_STATE_VERSION
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        // 旧版本配置里的自动刷新、刷新间隔、只看自选、订阅热门币数量等字段会被自动忽略，不会导致加载失败
        XmlSerializerUtil.copyBean(state, myState)
        migrate()
    }

    /**
     * 一次性迁移。
     *
     * v2：默认数据源由「全量交易所 + 聚合源」收敛为「只启用第三方聚合源」，
     *     因为受限网络下交易所 REST 域名全不可达，逐个串行重试会耗尽整体超时；
     * v3：取消自动刷新轮询，改由 WebSocket 推送实时价格，因此把历史配置里
     *     默认全开的数据源一并收敛，并把订阅数量初始化到合理值；
     * v4：列表固定为「只看自选」，不再提供开关，历史配置里的相关字段被忽略；
     * v5：WebSocket 只订阅自选币种，不再订阅热门币，故移除订阅数量配置；
     * v6：新增界面语言配置（默认跟随 IDE）。
     */
    private fun migrate() {
        if (myState.stateVersion >= CURRENT_STATE_VERSION) return
        if (myState.enabledSources.isNotEmpty()) {
            myState.enabledSources = MarketDataSources.defaultEnabledIds.toMutableList()
        }
        // 旧版本没有 language 字段，XmlSerializer 会保留 data class 的默认值（SYSTEM）。
        // 但若配置里出现了无法识别的值（手改过 xml），这里统一收敛一次。
        if (Language.values().none { it.name.equals(myState.language, ignoreCase = true) }) {
            myState.language = Language.SYSTEM.name
        }
        myState.stateVersion = CURRENT_STATE_VERSION
    }

    val wsEnabled: Boolean get() = myState.wsEnabled

    /** 当前界面语言（持久化的枚举名，可能为空）。 */
    val language: String get() = myState.language

    val proxyHost: String get() = myState.proxyHost
    val proxyPort: Int get() = myState.proxyPort

    fun enabledSources(): List<String> = myState.enabledSources

    fun setEnabledSources(ids: Collection<String>) {
        myState.enabledSources = ids.toMutableList()
    }

    var wsEnabledMutable: Boolean
        get() = myState.wsEnabled
        set(value) { myState.wsEnabled = value }

    var languageMutable: String
        get() = myState.language
        set(value) { myState.language = value }

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
         * v4：列表固定为只看自选；
         * v5：WebSocket 只订阅自选币种；
         * v6：新增界面语言。
         */
        const val CURRENT_STATE_VERSION: Int = 6

        fun getInstance(): CryptoSettings =
            ApplicationManager.getApplication().getService(CryptoSettings::class.java)
    }
}
