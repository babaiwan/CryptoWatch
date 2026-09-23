package com.crypto.cryptowatch.ui

/**
 * 界面文案表（英文 / 简体中文）。
 *
 * **为什么不放在 `.properties` 资源文件里？**
 * 最初的实现把文案放在 `resources/messages/CryptoWatchBundle*.properties`，
 * 结果打包后所有中文都变成了 `?`：`.properties` 的写入/转码链路会把非 ASCII 字符
 * 替换掉（实测文件里出现 788 个 `?` 字节、仅剩 4 个高位字节），
 * 而同一批写入的 Kotlin 源文件编码完全正常（高位字节数千个）。
 * 把文案直接放在 Kotlin 源码里可以完全绕开这个坑，且有以下附带好处：
 *
 * - **无 IO、无查表开销**：`ResourceBundle` 每次都要走候选路径查找，而这里的 Map 是静态常量；
 * - **改文案有编译期保障**：常量名写错会编译失败，而不是运行时静默取不到值；
 * - **缺键行为明确**：缺失的键回退到英文，再回退到键名本身（见 [I18n.text]），界面不会出现空白。
 *
 * 新增文案的步骤：先在 [EN] 中加一条，再在 [ZH] 中对应加一条即可。
 */
internal object Strings {

    /** 英文（基语言，同时作为其它语言的兜底）。 */
    val EN: Map<String, String> = mapOf(
        // ------------------------------------------------------------ 语言名称
        "lang.display.en" to "English",
        "lang.display.zh" to "简体中文",
        "settings.language.system" to "Follow IDE",

        // ------------------------------------------------------------ 类目
        "category.spot" to "Spot",
        "category.futures" to "Futures",
        "category.top" to "Market cap",

        // ------------------------------------------------------------ 数据源
        "source.ws" to "Binance Futures (WS)",
        "source.binance.spot" to "Binance Spot",
        "source.binance.futures" to "Binance Futures",
        "source.okx.spot" to "OKX Spot",
        "source.okx.futures" to "OKX Futures",
        "source.gate.spot" to "Gate Spot",
        "source.gate.futures" to "Gate Futures",
        "source.coinlore" to "CoinLore (free)",
        "source.coingecko" to "CoinGecko (market cap)",
        "source.coincap" to "CoinCap (market cap)",

        // ------------------------------------------------------------ 表格列名
        "column.symbol" to "Coin",
        "column.price" to "Price",
        "column.change" to "24h %",
        "column.volume24h" to "24h Volume",

        // ------------------------------------------------------------ 行情列表
        "ticker.search.empty" to "Search coins, e.g. BTC / ETH",
        "ticker.search.label" to "Search:",
        "ticker.hint.placeholder" to "No quotes yet, showing watchlist placeholders",
        "ticker.hint.summary" to "Watchlist {0} · Quotes {1}",
        "ticker.status.added" to "{0} added to watchlist",
        "ticker.status.removed" to "{0} removed from watchlist",
        "ticker.add.title" to "Add to Watchlist",
        "ticker.add.prompt" to "Enter a coin, e.g. ZEC (treated as ZEC/USDT), or ZEC/USDT directly.",
        "ticker.add.invalid" to "Unrecognized symbol format: {0}",
        "ticker.add.already" to "{0} is already in the watchlist",
        "ticker.add.notFound" to "Coin not found: {0}. Check the spelling; the data source does not return it yet.",
        "ticker.add.ok" to "{0} added to watchlist",
        "ticker.add.okNoLive" to "{0} added to watchlist (no live quote yet)",
        "ticker.ctx.add" to "Add to Watchlist",
        "ticker.ctx.remove" to "Remove from Watchlist",
        "ticker.ctx.copySymbol" to "Copy symbol",
        "ticker.ctx.copyPrice" to "Copy price",

        // ------------------------------------------------------------ 信息小框
        "detail.button.watch" to "Watch",
        "detail.button.watched" to "Watching",
        "detail.tip.addWatch" to "Click to add to watchlist",
        "detail.tip.removeWatch" to "Click to remove from watchlist",
        "detail.button.trade" to "Trade",
        "detail.tip.trade" to "Open the exchange",
        "detail.placeholder.tip" to "No data source returns this coin (the network may be unreachable)",
        "detail.tip.updated" to "Updated: {0}",

        // ------------------------------------------------------------ 工具栏 / 状态栏
        "toolbar.refresh" to "Refresh list",
        "toolbar.refresh.tip" to
            "Re-subscribe live quotes for the current watchlist and reload the coin list and market caps",
        "toolbar.add" to "Add to Watchlist",
        "toolbar.add.tip" to
            "Type any coin to add it to the watchlist; watchlist coins are subscribed automatically. " +
            "Double-click a row to remove it.",
        "status.refreshing" to "Refreshing…",
        "status.mode.live" to "Live",
        "status.mode.snapshot" to "Snapshot",
        "status.line" to "{0} · Watchlist {1} · Pushed {2}",
        "status.unsupported" to " · {0} unsupported",
        "ws.state.connected" to "Connected",
        "ws.state.connecting" to "Connecting",
        "ws.state.reconnecting" to "Reconnecting",
        "ws.state.launching" to "Starting",
        "ws.state.loading" to "Loading",
        "source.health" to "Sources {0}/{1}",
        "empty.noData" to "No quote data",
        "empty.fetchFailed" to "Failed to load quotes. Check your network or proxy settings ({0})",

        // ------------------------------------------------------------ WebSocket 提示
        "ws.connecting" to "Connecting…",
        "ws.reconnecting" to "Reconnecting (attempt {0})",
        "ws.connected" to "Connected",
        "ws.stopped" to "Stopped",
        "ws.connectFailed" to "Connect failed: {0}",
        "ws.silent" to "No data received for a while, reconnecting…",
        "ws.sendFailed" to "Failed to send subscription: {0}",
        "ws.streamRejected" to "The server rejected this stream (unknown symbol?)",
        "ws.closed" to "Connection closed ({0})",
        "ws.error" to "Connection error: {0}",

        // ------------------------------------------------------------ 设置页
        "settings.ws.enabled" to "Enable WebSocket live prices",
        "settings.ws.comment" to
            "Prices are pushed over the Binance WebSocket, no polling needed. " +
            "When disabled only snapshot prices are shown. Only watchlist coins are subscribed.",
        "settings.sources.comment" to
            "The REST sources below provide the <b>coin list and market caps</b> " +
            "(prices come from the WebSocket).<br>By default only the CoinLore aggregate API is enabled " +
            "(a regular public site, usually reachable without a proxy). " +
            "Exchange APIs (Binance / OKX / Gate) often time out on restricted networks, " +
            "which slows down list refreshes; enable them manually if your network can reach them.",
        "settings.proxy.comment" to
            "The plugin prefers the IDE's own proxy settings " +
            "(Settings > Appearance & Behavior > System Settings > HTTP Proxy); " +
            "the WebSocket connection follows them as well. " +
            "The settings below only apply when the IDE has no proxy configured.",
        "settings.proxy.host" to "Proxy host:",
        "settings.proxy.port" to "Port:",
        "settings.language.label" to "Language:",
        "settings.language.comment" to
            "Interface language. \"Follow IDE\" uses the language reported by the IDE/JVM. " +
            "Changes apply immediately, no restart needed.",
        "settings.source.option" to "{0} ({1})"
    )

    /** 简体中文。 */
    val ZH: Map<String, String> = mapOf(
        // ------------------------------------------------------------ 语言名称
        "lang.display.en" to "English",
        "lang.display.zh" to "简体中文",
        "settings.language.system" to "跟随 IDE",

        // ------------------------------------------------------------ 类目
        "category.spot" to "现货",
        "category.futures" to "合约",
        "category.top" to "市值榜",

        // ------------------------------------------------------------ 数据源
        "source.ws" to "Binance 合约(WS)",
        "source.binance.spot" to "Binance 现货",
        "source.binance.futures" to "Binance 合约",
        "source.okx.spot" to "OKX 现货",
        "source.okx.futures" to "OKX 合约",
        "source.gate.spot" to "Gate 现货",
        "source.gate.futures" to "Gate 合约",
        "source.coinlore" to "CoinLore 免费源",
        "source.coingecko" to "CoinGecko 市值榜",
        "source.coincap" to "CoinCap 市值榜",

        // ------------------------------------------------------------ 表格列名
        "column.symbol" to "币种",
        "column.price" to "最新价",
        "column.change" to "24h涨跌",
        "column.volume24h" to "24h成交额",

        // ------------------------------------------------------------ 行情列表
        "ticker.search.empty" to "搜索币种，如 BTC / ETH",
        "ticker.search.label" to "搜索:",
        "ticker.hint.placeholder" to "未获取到行情，以下为自选占位",
        "ticker.hint.summary" to "自选 {0} 个 · 行情 {1} 条",
        "ticker.status.added" to "{0} 已加入自选",
        "ticker.status.removed" to "{0} 已移出自选",
        "ticker.add.title" to "添加自选",
        "ticker.add.prompt" to "输入币种，例如 ZEC（默认按 ZEC/USDT 计价），也可直接写 ZEC/USDT。",
        "ticker.add.invalid" to "无法识别的币种格式: {0}",
        "ticker.add.already" to "{0} 已在自选中",
        "ticker.add.notFound" to "未找到币种 {0}：请检查拼写，或该币种尚未被数据源返回。",
        "ticker.add.ok" to "{0} 已加入自选",
        "ticker.add.okNoLive" to "{0} 已加入自选（暂无实时行情，等待数据源返回）",
        "ticker.ctx.add" to "加入自选",
        "ticker.ctx.remove" to "移出自选",
        "ticker.ctx.copySymbol" to "复制币种",
        "ticker.ctx.copyPrice" to "复制最新价",

        // ------------------------------------------------------------ 信息小框
        "detail.button.watch" to "自选",
        "detail.button.watched" to "已自选",
        "detail.tip.addWatch" to "点击加入自选",
        "detail.tip.removeWatch" to "点击移出自选",
        "detail.button.trade" to "交易",
        "detail.tip.trade" to "前往交易平台",
        "detail.placeholder.tip" to "该币种暂无数据源返回，可能因网络不可达",
        "detail.tip.updated" to "更新时间: {0}",

        // ------------------------------------------------------------ 工具栏 / 状态栏
        "toolbar.refresh" to "刷新列表",
        "toolbar.refresh.tip" to "按当前自选重新订阅实时行情，并重新拉取币种列表与市值",
        "toolbar.add" to "加入自选",
        "toolbar.add.tip" to
            "手动输入任意币种加入自选，自选币种会被自动订阅实时价；双击列表行可移出自选",
        "status.refreshing" to "刷新中…",
        "status.mode.live" to "实时",
        "status.mode.snapshot" to "快照",
        "status.line" to "{0} · 自选 {1} · 推送 {2}",
        "status.unsupported" to " · {0} 个无实时行情",
        "ws.state.connected" to "已连接",
        "ws.state.connecting" to "连接中",
        "ws.state.reconnecting" to "重连中",
        "ws.state.launching" to "启动中",
        "ws.state.loading" to "加载中",
        "source.health" to "数据源 {0}/{1}",
        "empty.noData" to "暂无行情数据",
        "empty.fetchFailed" to "获取行情失败，请检查网络或代理设置（{0}）",

        // ------------------------------------------------------------ WebSocket 提示
        "ws.connecting" to "连接中…",
        "ws.reconnecting" to "重连中（第 {0} 次）",
        "ws.connected" to "已连接",
        "ws.stopped" to "已停止",
        "ws.connectFailed" to "连接失败：{0}",
        "ws.silent" to "长时间无推送，重连中…",
        "ws.sendFailed" to "发送订阅失败：{0}",
        "ws.streamRejected" to "该币种不被服务端接受（交易对可能不存在）",
        "ws.closed" to "连接关闭（{0}）",
        "ws.error" to "连接异常：{0}",

        // ------------------------------------------------------------ 设置页
        "settings.ws.enabled" to "启用 WebSocket 实时价格",
        "settings.ws.comment" to "价格由币安 WebSocket 推送，无需轮询；关闭后只显示快照价。仅订阅自选币种。",
        "settings.sources.comment" to
            "以下是 REST 数据源，用于拉取<b>币种列表与市值</b>（价格来自 WebSocket）。<br>" +
            "默认只启用 CoinLore 聚合接口（普通公网站点，受限网络下通常无需代理即可访问）。" +
            "交易所接口（Binance / OKX / Gate）在国内网络下常常超时，启用会拖慢列表刷新；" +
            "如你的网络可直连，可手动勾选以获得更多字段。",
        "settings.proxy.comment" to
            "插件会优先复用 IDE 自身的代理设置（设置 > 外观与行为 > 系统设置 > HTTP 代理），" +
            "WebSocket 长连接同样遵循该配置。只有当 IDE 未配置代理时，下面的配置才会生效。",
        "settings.proxy.host" to "代理主机：",
        "settings.proxy.port" to "端口：",
        "settings.language.label" to "界面语言：",
        "settings.language.comment" to
            "界面语言。「跟随 IDE」使用 IDE/JVM 报告的语言。切换后立即生效，无需重启。",
        "settings.source.option" to "{0}（{1}）"
    )
}
