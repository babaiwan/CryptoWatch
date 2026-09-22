package com.crypto.cryptowatch.data

/**
 * 极简、零依赖的 JSON 解析器。
 *
 * 交易所的行情 payload 都是良构 JSON，因此这里不需要完整的 RFC 实现，
 * 只要保证：正确跳过字符串/转义、支持数字与科学计数法、支持 null/true/false、
 * 以及嵌套的对象和数组即可。
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        return v
    }

    /** 解析并断言根节点是对象。 */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        (parse(text) as? Map<String, Any?>) ?: error("JSON 根节点不是 object")

    /** 解析并断言根节点是数组。 */
    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?> =
        (parse(text) as? List<Any?>) ?: error("JSON 根节点不是 array")

    // ---------------------------------------------------------------- 取值助手

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(value: Any?): List<Any?>? = value as? List<Any?>

    fun str(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    /** 宽松地把数字（含数字字符串）转成 Double。 */
    fun num(value: Any?): Double? = when (value) {
        null -> null
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        else -> null
    }

    /** 读取对象里的字段并转成 Double。 */
    fun num(map: Map<String, Any?>, vararg keys: String): Double? {
        for (k in keys) {
            num(map[k])?.let { return it }
        }
        return null
    }

    fun str(map: Map<String, Any?>, vararg keys: String): String? {
        for (k in keys) {
            str(map[k])?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- 解析实现

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) error("JSON 意外结束")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "非法字面量，位置 $i" }
            i += word.length
        }

        private fun readObject(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (i < s.length) {
                skipWs()
                val key = readString()
                skipWs()
                require(i < s.length && s[i] == ':') { "期望 ':'，位置 $i" }
                i++
                val value = readValue()
                map[key] = value
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                error("对象语法错误，位置 $i")
            }
            return map
        }

        private fun readArray(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (i < s.length) {
                list.add(readValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                error("数组语法错误，位置 $i")
            }
            return list
        }

        private fun readString(): String {
            require(s[i] == '"') { "期望字符串，位置 $i" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            error("字符串未闭合")
        }

        private fun readNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))) {
                i++
            }
            return s.substring(start, i).toDoubleOrNull() ?: error("非法数字: ${s.substring(start, i)}")
        }
    }
}
