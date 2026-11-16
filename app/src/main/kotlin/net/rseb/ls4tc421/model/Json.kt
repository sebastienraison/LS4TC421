package net.rseb.ls4tc421.model

/**
 * Minimal JSON reader and writer, enough for the program file (objects, arrays, strings, numbers,
 * booleans, null). Pure Kotlin so that the file format is tested on the JVM; no library needed.
 * Objects are read as [Map] (keys in file order), arrays as [List], numbers as [Long] when they are
 * integers and [Double] otherwise.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    /** Nesting deeper than this is refused (a program file needs 4 levels). */
    private const val MAX_DEPTH = 32

    @Throws(ParseException::class)
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.skipSpace()
        if (!reader.atEnd()) throw ParseException("unexpected text at offset ${reader.pos}")
        return value
    }

    private class Reader(val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length

        fun skipSpace() {
            // Whitespace, and a byte order mark (U+FEFF) left by some editors at the start of the file.
            while (pos < text.length && (text[pos] in " \t\r\n" || text[pos].code == 0xFEFF)) pos++
        }

        fun fail(what: String): Nothing = throw ParseException("$what at offset $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deeply nested")
            skipSpace()
            if (atEnd()) fail("unexpected end")
            return when (val c = text[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("unexpected text")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipSpace()
                if (atEnd() || text[pos] != '"') fail("expected a key")
                val key = str()
                skipSpace()
                if (atEnd() || text[pos] != ':') fail("expected ':'")
                pos++
                if (key in out) fail("duplicate key \"$key\"")
                out[key] = value(depth + 1)
                skipSpace()
                if (atEnd()) fail("unexpected end")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> { pos--; fail("expected ',' or '}'") }
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipSpace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out += value(depth + 1)
                skipSpace()
                if (atEnd()) fail("unexpected end")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> { pos--; fail("expected ',' or ']'") }
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated string")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("bad escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad number '$raw'")
        }
    }

    /** A JSON string literal, quotes included. */
    fun quote(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
