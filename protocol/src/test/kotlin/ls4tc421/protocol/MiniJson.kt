package ls4tc421.protocol

/** Minimal JSON reader for test fixtures (objects, arrays, strings, integers, booleans, null). */
internal class MiniJson(private val text: String) {
    private var pos = 0

    companion object {
        fun parse(text: String): Any? {
            val reader = MiniJson(text)
            val value = reader.value()
            reader.skipSpace()
            check(reader.pos == text.length) { "Trailing characters at ${reader.pos}" }
            return value
        }
    }

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun value(): Any? {
        skipSpace()
        return when (val c = text[pos]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $pos")
        }
    }

    private fun literal(word: String, result: Any?): Any? {
        check(text.startsWith(word, pos)) { "Bad literal at $pos" }
        pos += word.length
        return result
    }

    private fun num(): Long {
        val start = pos
        if (text[pos] == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        return text.substring(start, pos).toLong()
    }

    private fun str(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (text[pos] != '"') {
            var c = text[pos++]
            if (c == '\\') {
                c = text[pos++]
                when (c) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(text.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                    else -> sb.append(c) // \" \\ \/
                }
            } else {
                sb.append(c)
            }
        }
        pos++
        return sb.toString()
    }

    private fun arr(): List<Any?> {
        pos++
        val out = ArrayList<Any?>()
        skipSpace()
        if (text[pos] == ']') { pos++; return out }
        while (true) {
            out += value()
            skipSpace()
            if (text[pos++] == ']') return out
        }
    }

    private fun obj(): Map<String, Any?> {
        pos++
        val out = LinkedHashMap<String, Any?>()
        skipSpace()
        if (text[pos] == '}') { pos++; return out }
        while (true) {
            skipSpace()
            val key = str()
            skipSpace()
            check(text[pos++] == ':') { "Expected ':' at $pos" }
            out[key] = value()
            skipSpace()
            if (text[pos++] == '}') return out
        }
    }
}
