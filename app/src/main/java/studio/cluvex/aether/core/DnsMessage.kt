package studio.cluvex.aether.core

/**
 * The few DNS wire-format questions this app has to answer for itself.
 *
 * Deliberately NOT a DNS library. [SocksFront] forwards queries verbatim and
 * only ever needs to know two things about one: what was asked, and how to say
 * "that name exists, but not in the family you asked for". Everything here is
 * pure Kotlin with no Android dependency, which is what makes it testable on
 * the JVM - and these bytes decide whether an app gets an address it can
 * actually connect to, so they are worth testing.
 *
 * ### Why NODATA and not NXDOMAIN, and not a dropped packet
 *
 * When the tunnel's exit cannot dial IPv6 at all, an AAAA answer is a list of
 * addresses that are guaranteed to fail. The correct reply is not "no such
 * name" (NXDOMAIN would make the client give up on the A lookup too, and it is
 * a lie about the name) and not silence (the client retries, then stalls, and
 * Happy Eyeballs waits out its whole timer before trying IPv4). It is NODATA:
 * a successful, empty answer, which is exactly what a dual-stack resolver on an
 * IPv4-only network returns and what every resolver on every platform already
 * knows how to handle.
 */
internal object DnsMessage {

    /** QTYPE A: an IPv4 address. */
    const val TYPE_A = 1

    /** QTYPE AAAA: an IPv6 address. */
    const val TYPE_AAAA = 28

    /** ID, flags, and the four section counts. */
    private const val HEADER_BYTES = 12

    /** A name is at most 127 labels; anything longer is malformed, not slow. */
    private const val MAX_LABELS = 128

    /**
     * The message's transaction id, or null when [message] is too short to be a
     * DNS message at all.
     *
     * Used to match an answer to its question on a REUSED connection, which is
     * the one place where getting this wrong hands a caller somebody else's
     * answer.
     */
    fun transactionId(message: ByteArray): Int? {
        if (message.size < HEADER_BYTES) return null
        return ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
    }

    /**
     * The QTYPE of the single question in [query], or null when [query] is not a
     * well-formed one-question QUERY.
     */
    fun questionType(query: ByteArray): Int? {
        val end = questionEnd(query) ?: return null
        return ((query[end - 4].toInt() and 0xFF) shl 8) or (query[end - 3].toInt() and 0xFF)
    }

    /** True when [query] asks for an IPv6 address and nothing else. */
    fun isAaaaQuery(query: ByteArray): Boolean = questionType(query) == TYPE_AAAA

    /**
     * Turns [query] into its own empty, successful answer: QR=1, RA=1, RCODE=0,
     * every section count zero, the question echoed back unchanged.
     *
     * Returns null when [query] is not a well-formed one-question query, because
     * the honest thing to do with a message this code does not understand is to
     * leave it to the real resolver.
     */
    fun nodataResponse(query: ByteArray): ByteArray? {
        val end = questionEnd(query) ?: return null
        val out = query.copyOf(end)
        // QR=1 and RA=1, keeping the client's own OPCODE and RD bit. Building
        // the flags from the query rather than writing a constant is what makes
        // this a reply to THIS message instead of a plausible-looking one.
        val flags = out[2].toInt()
        val opcode = flags and 0x78
        val recursionDesired = flags and 0x01
        out[2] = (0x80 or opcode or recursionDesired).toByte()
        out[3] = 0x80.toByte()
        // ANCOUNT / NSCOUNT / ARCOUNT = 0. QDCOUNT is left as it was, because
        // the question section is still there and still has to match it.
        out[6] = 0
        out[7] = 0
        out[8] = 0
        out[9] = 0
        out[10] = 0
        out[11] = 0
        return out
    }

    /**
     * Index just past the question's QTYPE and QCLASS, or null when [query] is
     * not a single-question query this code is willing to reason about.
     *
     * Rejected on purpose: a message with QR set (that is an answer, not a
     * query), anything but exactly one question, a compression pointer inside
     * the question (illegal there, and the classic way a hand-written parser is
     * walked off the end of a buffer), and any length that does not fit.
     */
    private fun questionEnd(query: ByteArray): Int? {
        // Header + the root label + QTYPE + QCLASS is the shortest legal query.
        if (query.size < HEADER_BYTES + 5) return null
        if ((query[2].toInt() and 0x80) != 0) return null
        val questionCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (questionCount != 1) return null

        var index = HEADER_BYTES
        var labels = 0
        while (true) {
            if (index >= query.size) return null
            val length = query[index].toInt() and 0xFF
            if ((length and 0xC0) != 0) return null
            index += 1
            if (length == 0) break
            index += length
            labels += 1
            if (labels > MAX_LABELS) return null
        }
        val end = index + 4
        return if (end <= query.size) end else null
    }
}
