package com.crypto.cryptowatch.settings

import com.crypto.cryptowatch.model.MarketCategory
import com.crypto.cryptowatch.util.Symbols
import com.crypto.cryptowatch.util.upper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 自选列表（扁平，与「不再区分现货/合约」的扁平列表保持一致）。
 *
 * 内部序列化格式仍为 "CATEGORY|BASE/QUOTE"，避免依赖 Map 的 XML 序列化细节；
 * 但键在读写时都会经过 [normalize] 规范化：**类目不参与匹配**，且 **USD 与 USDT 视为同一计价**。
 *
 * 原因：市值榜（CoinLore/CoinGecko）返回 BTC/USD 且类目为 TOP，而交易所与 WebSocket 返回
 * BTC/USDT 且类目为 SPOT。若不统一，同一币种在「列表」与「自选」里是两个不同的键，
 * 于是出现「列表里有这一行，但星星不亮、只看自选为空、加入自选没反应」的错位。
 */
@Service(Service.Level.APP)
@State(name = "CryptoWatchWatchlist", storages = [Storage("cryptoWatch.xml")])
class WatchlistStore : PersistentStateComponent<WatchlistStore.State> {

    data class State(
        var entries: MutableList<String> = defaultEntries().toMutableList()
    )

    private var myState = State()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        sanitize()
        notifyChanged()
    }

    /**
     * 清理自选里的非法条目。
     *
     * 早期版本对自选内容不做任何校验，于是配置里可能残留 `000`、`BTC/USDT/test`
     * 这类脏数据。它会被 [MarketStreamService] 翻译成 `000usdt@trade` 之类的**非法流名**
     * 发给币安，服务端随即断开连接，客户端自动重连后又下发同一批非法流——
     * 表现就是「加入自选后一直卡在重连中」。
     *
     * 只在加载阶段做还不够，因为写入口也要堵住（见 [add]）；但**必须**在这里做一次，
     * 因为历史配置是既成事实，光靠输入校验无法挽救已经写入磁盘的脏数据。
     *
     * @return 是否发生了删除（用于决定是否需要落盘/通知）
     */
    private fun sanitize(): Boolean =
        myState.entries.removeIf { entry -> parse(entry)?.second?.let { !Symbols.isValidKey(it) } ?: true }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyChanged() = listeners.forEach { runCatching { it() } }

    /** 全部自选键（已规范化，例如 "BTC/USDT"）。 */
    fun allKeys(): Set<String> = myState.entries.asSequence().mapNotNull { parse(it)?.second }.toSet()

    /**
     * 是否已收藏。
     *
     * 注意：`category` 仅用于写入时的标记，**匹配时不参与比较**——
     * 同一币种会随数据源不同而落在 TOP/SPOT 等类目上，用类目比较必然匹配不上。
     */
    @Suppress("UNUSED_PARAMETER")
    fun contains(category: MarketCategory, key: String): Boolean {
        val target = normalize(key)
        return myState.entries.any { parse(it)?.second == target }
    }

    /**
     * 加入自选。
     *
     * **非法交易对一律拒收**（返回 false）：让脏数据根本进不了自选，
     * 是「加入自选后一直重连」的第一道防线。调用方应先用 [Symbols.isValidKey]
     * 区分「格式非法」与「已在自选中」，以给出不同的提示文案。
     */
    fun add(category: MarketCategory, key: String): Boolean {
        val normalized = normalize(key)
        if (!Symbols.isValidKey(normalized)) return false
        if (contains(category, normalized)) return false
        myState.entries.add(encode(category, normalized))
        notifyChanged()
        return true
    }

    /** 与 [contains] 同理：类目只用于写入标记，删除时按规范化后的键匹配。 */
    @Suppress("UNUSED_PARAMETER")
    fun remove(category: MarketCategory, key: String): Boolean {
        val target = normalize(key)
        val removed = myState.entries.removeIf { parse(it)?.second == target }
        if (removed) notifyChanged()
        return removed
    }

    fun toggle(category: MarketCategory, key: String): Boolean {
        return if (contains(category, key)) { remove(category, key); false } else { add(category, key); true }
    }

    fun clear(category: MarketCategory) {
        myState.entries.removeIf { parse(it)?.first == category }
        notifyChanged()
    }

    private fun encode(category: MarketCategory, key: String) = "${category.name}|$key"

    private fun parse(raw: String): Pair<MarketCategory, String>? {
        val idx = raw.indexOf('|')
        if (idx <= 0) return null
        val category = MarketCategory.values().firstOrNull { it.name == raw.substring(0, idx) } ?: return null
        return category to normalize(raw.substring(idx + 1))
    }

    companion object {
        fun getInstance(): WatchlistStore =
            ApplicationManager.getApplication().getService(WatchlistStore::class.java)

        /**
         * 规范化自选键。
         *
         * - 计价币统一为 USDT：市值榜用 USD、交易所与 WebSocket 用 USDT，二者是同一币种；
         * - 统一大小写，去除空白。
         */
        fun normalize(key: String): String {
            val up = key.trim().upper()
            val idx = up.indexOf('/')
            if (idx <= 0) return up
            val base = up.substring(0, idx)
            val quote = up.substring(idx + 1).let { if (it == "USD") "USDT" else it }
            return "$base/$quote"
        }

        /**
         * 开箱即用的默认自选，避免用户第一次打开看到空白面板；
         * 当所有数据源都不可达时，这些自选还会以占位行形式保证列表不为空。
         */
        fun defaultEntries(): List<String> = listOf(
            MarketCategory.SPOT to "BTC/USDT",
            MarketCategory.SPOT to "ETH/USDT",
            MarketCategory.SPOT to "SOL/USDT",
            MarketCategory.SPOT to "BNB/USDT"
        ).map { (c, k) -> "${c.name}|$k" }
    }
}
