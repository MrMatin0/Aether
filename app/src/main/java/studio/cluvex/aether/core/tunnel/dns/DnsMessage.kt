package studio.cluvex.aether.core.tunnel.dns

import studio.cluvex.aether.core.tunnel.net.Bytes

/**
 * A bounds-safe, allocation-light DNS wire-format reader.
 *
 * The original code had THREE separate, subtly different hand-rolled label
 * walkers (`extractDomainName`, the question skip inside `sniffDnsResponse`, and
 * `findDnsQuestionEnd`). Each handled compression pointers differently and two
 * of them could walk off the end of the buffer, which is why the sniffer was
 * wrapped in a bare `catch (_: Exception) {}` that swallowed real parse bugs.
 * There is now exactly one walker, and it never throws.
 */
internal object DnsMessage {

    const val HEADER_SIZE = 12

    private const val POINTER_MASK = 0xC0
    private const val MAX_POINTER_JUMPS = 8
    private const val MAX_NAME_LENGTH = 255

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /** True for a response (QR bit set). */
    fun isResponse(data: ByteArray): Boolean =
        data.size >= HEADER_SIZE && (Bytes.short(data, 2) and 0x8000) != 0

    fun questionCount(data: ByteArray): Int =
        if (data.size >= HEADER_SIZE) Bytes.short(data, 4) else 0

    fun answerCount(data: ByteArray): Int =
        if (data.size >= HEADER_SIZE) Bytes.short(data, 6) else 0

    /** Result of reading one name: its text plus the offset just past it. */
    class Name(val value: String, val next: Int)

    /**
     * Reads the name at [at], following compression pointers.
     *
     * [next] always points past the name AS ENCODED AT [at] (i.e. past the
     * 2-byte pointer, not past whatever it referenced) — getting this wrong is
     * what made the old answer-section walk land on arbitrary offsets and invent
     * bogus IP -> domain mappings.
     */
    fun readName(data: ByteArray, at: Int): Name? {
        val sb = StringBuilder()
        var pos = at
        var next = -1
        var jumps = 0

        while (true) {
            if (pos < 0 || pos >= data.size) return null
            val length = data[pos].toInt() and 0xFF

            if (length == 0) {
                if (next == -1) next = pos + 1
                break
            }

            if (length and POINTER_MASK == POINTER_MASK) {
                if (pos + 1 >= data.size) return null
                if (next == -1) next = pos + 2
                if (++jumps > MAX_POINTER_JUMPS) return null
                pos = ((length and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                continue
            }

            // A label length with only the top bit set is reserved/invalid.
            if (length > 63) return null
            if (pos + 1 + length > data.size) return null
            if (sb.length + length > MAX_NAME_LENGTH) return null

            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos + 1, length, Charsets.US_ASCII))
            pos += length + 1
        }

        return Name(sb.toString().lowercase(), next)
    }

    /** The QNAME of the first question, or null when there is not one. */
    fun firstQueryName(data: ByteArray): String? {
        if (data.size < HEADER_SIZE) return null
        return readName(data, HEADER_SIZE)?.value?.takeIf { it.isNotEmpty() }
    }

    /** Offset just past the question section, or -1 when it cannot be parsed. */
    fun questionSectionEnd(data: ByteArray): Int {
        if (data.size < HEADER_SIZE) return -1
        var pos = HEADER_SIZE
        repeat(questionCount(data).coerceAtMost(MAX_QUESTIONS)) {
            val name = readName(data, pos) ?: return -1
            // QTYPE + QCLASS. The old code advanced by 4 with NO bounds check, so
            // a truncated question left `pos` past the end of the buffer and every
            // subsequent read was garbage.
            pos = name.next + 4
            if (pos > data.size) return -1
        }
        return pos
    }

    /** One resource record from the answer section. */
    class Record(val type: Int, val dataOffset: Int, val dataLength: Int, val next: Int)

    /**
     * Walks the answer section, invoking [onRecord] per record. Stops silently at
     * the first malformed record rather than throwing.
     */
    inline fun forEachAnswer(data: ByteArray, onRecord: (Record) -> Unit) {
        val start = questionSectionEnd(data)
        if (start < 0) return

        var pos = start
        var remaining = answerCount(data)
        while (remaining-- > 0) {
            val name = readName(data, pos) ?: return
            // TYPE(2) CLASS(2) TTL(4) RDLENGTH(2)
            val fixed = name.next
            if (fixed + 10 > data.size) return

            val type = Bytes.short(data, fixed)
            val rdLength = Bytes.short(data, fixed + 8)
            val rdStart = fixed + 10
            if (rdStart + rdLength > data.size) return

            onRecord(Record(type, rdStart, rdLength, rdStart + rdLength))
            pos = rdStart + rdLength
        }
    }

    /**
     * Turns a query into an NXDOMAIN response, in place on a copy.
     *
     * Keeps the ID, the question, and the client's RD bit; sets QR + RA, forces
     * RCODE 3, and zeroes every count except QDCOUNT.
     */
    fun nxDomainResponse(query: ByteArray): ByteArray? {
        val questionEnd = questionSectionEnd(query)
        if (questionEnd < HEADER_SIZE || questionEnd > query.size) return null

        val response = query.copyOfRange(0, questionEnd)
        response[2] = (response[2].toInt() or 0x80).toByte()          // QR = response
        response[3] = ((response[3].toInt() and 0x10) or 0x80 or 0x03).toByte() // RA + NXDOMAIN
        for (i in 6..11) response[i] = 0                              // AN/NS/AR counts
        return response
    }

    const val MAX_QUESTIONS = 4
}