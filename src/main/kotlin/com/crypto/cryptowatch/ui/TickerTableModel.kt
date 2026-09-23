package com.crypto.cryptowatch.ui

import com.crypto.cryptowatch.model.Quote
import com.crypto.cryptowatch.util.Format
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel

/**
 * 行情表格模型（扁平列表，不区分现货/合约）。
 *
 * 数值列直接返回 [Number]（而不是格式化后的字符串），这样 [javax.swing.RowSorter] 才能
 * 按数值大小排序；格式化只在渲染器里做。缺失值以 null 表示，由比较器统一排到最后。
 */
class TickerTableModel : AbstractTableModel() {

    /**
     * 列定义。
     *
     * 列名不再硬编码：这里只保存**文案键**，实际文字在 [getColumnName] 里按当前语言取，
     * 这样切换语言时只需触发一次表头刷新（[refreshHeaders]），无需重建模型与排序器。
     */
    enum class Column(val titleKey: String, val width: Int, val numeric: Boolean) {
        SYMBOL("column.symbol", 110, false),
        PRICE("column.price", 110, true),
        CHANGE("column.change", 90, true),
        QUOTE_VOLUME("column.volume24h", 120, true)
    }

    private var rows: List<Quote> = emptyList()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = I18n.text(COLUMNS[column].titleKey)

    /**
     * 语言切换后刷新表头。
     *
     * 刻意发送 `HEADER_ROW` 事件而不是 `fireTableStructureChanged()`：后者的副作用是
     * 重建所有 TableColumn，会把用户调整过的列宽与当前排序键一并重置；
     * 而这里只是列名文字变了，结构并没有变化。
     */
    fun refreshHeaders() = fireTableChanged(TableModelEvent(this, TableModelEvent.HEADER_ROW))

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (COLUMNS[columnIndex].numeric) java.lang.Double::class.java else String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val quote = rows[rowIndex]
        return when (COLUMNS[columnIndex]) {
            Column.SYMBOL -> quote.displaySymbol
            Column.PRICE -> quote.price
            Column.CHANGE -> quote.changePct
            Column.QUOTE_VOLUME -> quote.quoteVolume
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    fun setQuotes(quotes: List<Quote>) {
        rows = quotes
        fireTableDataChanged()
    }

    fun quoteAt(rowIndex: Int): Quote? = rows.getOrNull(rowIndex)

    /** 用于格式化展示的统一入口。 */
    fun rendered(rowIndex: Int, columnIndex: Int): String {
        val quote = rows.getOrNull(rowIndex) ?: return "-"
        // 占位行：自选里存在但当前没有任何数据源返回该币种
        if (quote.placeholder) {
            return when (COLUMNS[columnIndex]) {
                Column.SYMBOL -> quote.displaySymbol
                else -> "-"
            }
        }
        return when (COLUMNS[columnIndex]) {
            Column.SYMBOL -> quote.displaySymbol
            Column.PRICE -> Format.price(quote.price)
            Column.CHANGE -> Format.pct(quote.changePct)
            Column.QUOTE_VOLUME -> Format.compact(quote.quoteVolume)
        }
    }

    companion object {
        /**
         * 列定义列表。
         *
         * 刻意使用 `values()` 而不是 `entries`：`Enum.entries` 依赖新版 Kotlin stdlib，
         * 在 2020.3 这类老平台（内置 Kotlin 1.4）上会抛 `NoSuchMethodError`。
         */
        val COLUMNS: List<Column> = Column.values().toList()

        /** 数值比较器，null 排最后；供 RowSorter 使用。 */
        val NUMERIC_COMPARATOR: Comparator<Any?> = Comparator { a, b ->
            val da = (a as? Number)?.toDouble()
            val db = (b as? Number)?.toDouble()
            when {
                da == null && db == null -> 0
                da == null -> 1
                db == null -> -1
                else -> da.compareTo(db)
            }
        }

        val TEXT_COMPARATOR: Comparator<Any?> = Comparator { a, b ->
            (a as? String ?: "").compareTo(b as? String ?: "", ignoreCase = true)
        }
    }
}
