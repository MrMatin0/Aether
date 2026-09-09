package studio.cluvex.aether.core.moat

/**
 * A very small, strict JSON reader.
 *
 * ### Why not org.json, and why not a library
 *
 * `org.json` is on the Android boot classpath but is a STUB in the local unit
 * test JVM: every call throws "Stub!", so a parser built on it cannot be tested
 * without Robolectric. The moat payloads are the only JSON this app consumes,
 * they arrive from the network on a censored connection, and the interesting
 * failure mode is a captive portal serving HTML with a 200 - i.e. exactly the
 * thing that must be REJECTED rather than half-parsed. So the reader is 150
 * lines here and is unit-tested against real payloads, and the app keeps its
 * zero-dependency rule (see NetProbe for the same argument about HTTP).
 *
 * ### Why regexes were not enough
 *
 * Go's `encoding/json` HTML-escapes by default, so `url=https://x?a=1&b=2`
 * arrives as `...\u0026b=2` and `\u003c` shows up in front domains. A pattern
 * that grabs quoted strings would hand those escapes to tor verbatim and the
 * bridge would simply not work. Unescaping is part of parsing, so parsing it
 * properly is the smaller job.
 */
internal object JsonLite {

    /** Hard ceiling on nesting, so a hostile payload cannot recurse the stack away. */
    private const val MAX_DEPTH = 24

    class JsonException(message: String) : Exception(message)

    /**
     * Parses [text] into `Map<String, Any?>`, `List<Any?>`, [String], [Double],
     * [Boolean] and null.
     *
     * @throws JsonException on anything that is not one well-formed value.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd) throw JsonException("trailing data at ${reader.position}")
        return value
    }

    /** [parse], returning the object at the root, or throwing. */
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw JsonException("root is not an object")
    }

    // ------------------------------------------------------------- accessors
    //
    // Typed, null-returning readers. A moat response that is missing a field or
    // carries the wrong type is a response we do not use, and every caller in
    // this package treats "absent" and "wrong shape" identically.

    fun map(value: Any?): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
    }

    fun list(value: Any?): List<Any?>? = value as? List<Any?>

    fun string(value: Any?): String? = value as? String

    fun int(value: Any?): Int? = (value as? Double)?.toInt()

    /** Every element of [value] that is a non-blank string. */
    fun strings(value: Any?): List<String> =
        list(value)?.mapNotNull { string(it)?.takeIf { text -> text.isNotBlank() } } ?: emptyList()

    /**
     * A field that is EITHER a string or an array of them.
     *
     * moat's `/fetch` does exactly this with `transport`: an array when no
     * transports were requested, a bare string when one was (see doc/moat.md).
     */
    fun stringOrStrings(value: Any?): List<String> {
        string(value)?.let { return listOf(it) }
        return strings(value)
    }

    // ---------------------------------------------------------------- reader

    private class Reader(private val text: String) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isJsonWhitespace()) position++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("nested too deeply")
            if (atEnd) throw JsonException("unexpected end of input")
            return when (val c = text[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else ->
                    if (c == '-' || c in '0'..'9') readNumber()
                    else throw JsonException("unexpected '$c' at $position")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { position++; return result }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> { position++; return result }
                    else -> throw JsonException("expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { position++; return result }
            while (true) {
                skipWhitespace()
                result += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> { position++; return result }
                    else -> throw JsonException("expected ',' or ']' at $position")
                }
            }
        }

        /** The whole reason this file exists: escapes, including `\uXXXX`. */
        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd) throw JsonException("unterminated string")
                when (val c = text[position++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd) throw JsonException("unterminated escape")
                        when (val escape = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) {
                                    throw JsonException("truncated \\u escape")
                                }
                                val hex = text.substring(position, position + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("bad \\u escape '$hex'")
                                out.append(code.toChar())
                                position += 4
                            }
                            else -> throw JsonException("bad escape '\\$escape'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (!atEnd && (text[position] in '0'..'9' || text[position] in ".eE+-")) position++
            val raw = text.substring(start, position)
            return raw.toDoubleOrNull() ?: throw JsonException("bad number '$raw'")
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) throw JsonException("bad literal at $position")
            position += word.length
            return value
        }

        private fun peek(): Char =
            if (atEnd) throw JsonException("unexpected end of input") else text[position]

        private fun expect(c: Char) {
            if (atEnd || text[position] != c) throw JsonException("expected '$c' at $position")
            position++
        }

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r'
    }
}
