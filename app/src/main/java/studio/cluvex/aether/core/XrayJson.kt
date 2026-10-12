package studio.cluvex.aether.core

import java.util.Locale

/**
 * A small JSON reader / writer for the Xray hop.
 *
 * Why not org.json: it is part of the Android framework, so on the JVM the
 * unit tests would only see the stubs - and the whole point of
 * [XrayLink] / [XrayConfig] being pure is that "did we chain the handshake
 * through the right port" is checked on the JVM, not on a phone. A config is a
 * few kilobytes, so a straightforward recursive-descent parser is plenty.
 *
 * Values map to: object -> [LinkedHashMap] (key order kept), array -> [List],
 * string -> [String], integer -> [Long], other number -> [Double],
 * true/false -> [Boolean], null -> null.
 */
internal object XrayJson {

    class ParseException(message: String) : IllegalArgumentException(message)

    private const val MAX_DEPTH = 64

    /**
     * What [Reader.peek] returns past the last character. No JSON token starts
     * with it, so every caller that peeks at the end of a truncated document
     * falls through to its own "expected ..." ParseException.
     *
     * Deliberately a named constant and not an escaped NUL literal: a NUL
     * escape does not survive every editor and pipeline, and an empty char
     * literal - which is what it degrades to - does not compile.
     */
    private const val END = Char.MAX_VALUE

    /** Parses [text]; throws [ParseException] on anything that is not one JSON value. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.value(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw ParseException("trailing data at ${reader.pos}")
        return value
    }

    /** [parse], or null for anything malformed. */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (_: ParseException) {
        null
    }

    /** Serialises maps, lists, strings, numbers, booleans and null, compactly. */
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    /** A deep, mutable copy of [map] with every key as a String. */
    fun copyObject(map: Map<*, *>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in map) out[key.toString()] = copyValue(value)
        return out
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> copyObject(value)
        is List<*> -> value.map(::copyValue)
        else -> value
    }

    // ------------------------------------------------------------- writer

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value.toString())
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> appendDouble(out, value)
            is Float -> appendDouble(out, value.toDouble())
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, item)
                }
                out.append(']')
            }
            is Array<*> -> append(out, value.asList())
            else -> quote(out, value.toString())
        }
    }

    private fun appendDouble(out: StringBuilder, value: Double) {
        when {
            value.isNaN() || value.isInfinite() -> out.append("null")
            value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15 -> out.append(value.toLong().toString())
            else -> out.append(value.toString())
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------- reader

    private class Reader(private val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') pos++ else return
            }
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("nested too deeply")
            skipWhitespace()
            if (atEnd()) throw ParseException("unexpected end of input")
            val c = text[pos]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> array(depth)
                c == '"' -> string()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> number()
                else -> throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else END

        private fun expect(c: Char) {
            if (peek() != c) throw ParseException("expected '$c' at $pos")
            pos++
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw ParseException("expected a key at $pos")
                val key = string()
                skipWhitespace()
                expect(':')
                map[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw ParseException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(value(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw ParseException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("bad escape")
                        val e = text[pos++]
                        when (e) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw ParseException("bad unicode escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw ParseException("bad unicode escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length) {
                val c = text[pos]
                if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
            }
            val lexeme = text.substring(start, pos)
            if (lexeme.none { it == '.' || it == 'e' || it == 'E' }) {
                lexeme.toLongOrNull()?.let { return it }
            }
            return lexeme.toDoubleOrNull() ?: throw ParseException("bad number '$lexeme'")
        }
    }
}
