package com.crypto.cryptowatch.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 数值/时间的展示格式化工具。 */
object Format {

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * 按数量级自适应小数位，加密货币价格差异极大（SHIB 与 BTC 相差 10 个数量级）。
     *
     * 注意：≥1000 的价格刻意保留 4 位小数。若只保留 2 位，BTC/ETH 这类
     * 四位数的币种在盘中几分之一美元的波动会被四舍五入抹掉，
     * 即使 WebSocket 每秒推送多次，看起来也是「价格一动不动」。
     */
    fun price(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "-"
        val abs = kotlin.math.abs(v)
        return when {
            abs == 0.0 -> "0"
            abs >= 1000 -> "%,.4f".format(v)
            abs >= 1 -> "%.4f".format(v)
            abs >= 0.01 -> "%.6f".format(v)
            abs >= 0.0001 -> "%.8f".format(v)
            else -> "%.10f".format(v)
        }
    }

    /** 大数字缩写：12.3K / 4.56M / 7.89B。 */
    fun compact(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "-"
        val abs = kotlin.math.abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            abs >= 1_000_000_000_000 -> "$sign%.2fT".format(abs / 1_000_000_000_000)
            abs >= 1_000_000_000 -> "$sign%.2fB".format(abs / 1_000_000_000)
            abs >= 1_000_000 -> "$sign%.2fM".format(abs / 1_000_000)
            abs >= 1_000 -> "$sign%.2fK".format(abs / 1_000)
            else -> "$sign%.2f".format(abs)
        }
    }

    /** 涨跌幅展示，例如 "+1.23%" / "-4.56%"。 */
    fun pct(v: Double?): String =
        if (v == null || v.isNaN()) "-" else "%+.2f%%".format(v)

    fun time(millis: Long): String =
        timeFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
