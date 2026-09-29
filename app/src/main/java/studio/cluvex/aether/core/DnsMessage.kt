package studio.cluvex.aether.core

import java.io.ByteArrayOutputStream

/**
 * The few DNS wire-format questions this app has to answer for itself.
 *
 * Deliberately NOT a DNS library. [SocksFront] forwards queries verbatim and
 * only ever needs to know two things about one: what was asked, and how to say
 * "that name exists, but not in the family you asked for". The Smart DNS front
 * adds three more: building a query of its own, reading the IPv4 answers out
 * of a response, and reading its RCODE. Everything here is pure Kotlin with no
 * Android dependency, which is what makes it testable on the JVM - and these
 * bytes decide whether an app gets an address it can actually connect to, so
 * they are worth testing.
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

    /** QTYPE SVCB (RFC 9460). */
    const val TYPE_SVCB = 64

    /**
     * QTYPE HTTPS (RFC 9460). Its answers can carry `ipv4hint` / `ipv6hint`
     * addresses, which is a second way for a client to learn an address that
     * is not the one the A record gave it.
     */
    const val TYPE_HTTPS = 65

    /** QCLASS IN. */
    const val CLASS_IN = 1

    /** RCODE SERVFAIL. */
    const val RCODE_SERVFAIL = 2

    /** RCODE REFUSED: what a resolver that does not serve this client says. */
    const val RCODE_REFUSED = 5

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
     * The same shape as [nodataResponse], with RCODE=SERVFAIL: "I could not
     * answer this right now". Used when every Smart DNS server failed, so the
     * client moves on at once instead of waiting out its timeout.
     */
    fun servfailResponse(query: ByteArray): ByteArray? {
        val out = nodataResponse(query) ?: return null
        out[3] = (0x80 or RCODE_SERVFAIL).toByte()
        return out
    }

    /** RCODE of [message], or null when it is too short to have one. */
    fun rcode(message: ByteArray): Int? {
        if (message.size < HEADER_BYTES) return null
        return message[3].toInt() and 0x0F
    }

    /** True when [message] is a response with QR=1. */
    fun isResponse(message: ByteArray): Boolean =
        message.size >= HEADER_BYTES && (message[2].toInt() and 0x80) != 0

    /** True when the TC bit is set: the answer did not fit and must be re-asked over TCP. */
    fun isTruncated(message: ByteArray): Boolean =
        message.size >= HEADER_BYTES && (message[2].toInt() and 0x02) != 0

    /**
     * A one-question recursive query for [name] / [type] with transaction id
     * [id], or null when [name] cannot be encoded (non-ASCII, an empty label, a
     * label over 63 bytes, or more than 255 bytes in total).
     */
    fun buildQuery(name: String, type: Int, id: Int): ByteArray? {
        val trimmed = name.trim().trimEnd('.')
        if (trimmed.isEmpty() || trimmed.any { it.code > 127 }) return null
        val labels = trimmed.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        val out = ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // RD
        out.write(0x00)
        out.write(0)
        out.write(1) // QDCOUNT
        repeat(6) { out.write(0) }
        var nameBytes = 0
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
            nameBytes += bytes.size + 1
        }
        out.write(0)
        nameBytes += 1
        if (nameBytes > 255) return null
        out.write((type shr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write(0)
        out.write(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Every IN A address in the answer section of [message], in order, as
     * dotted quads. Bounds-checked throughout: a truncated or malformed message
     * yields whatever was read before the damage, never an exception.
     */
    fun answerIpv4s(message: ByteArray): List<String> {
        if (message.size < HEADER_BYTES) return emptyList()
        val questions = u16(message, 4)
        val answers = u16(message, 6)
        var index = HEADER_BYTES
        for (q in 0 until questions) {
            index = skipName(message, index) ?: return emptyList()
            index += 4
            if (index > message.size) return emptyList()
        }
        val out = mutableListOf<String>()
        for (a in 0 until answers) {
            index = skipName(message, index) ?: break
            if (index + 10 > message.size) break
            val type = u16(message, index)
            val klass = u16(message, index + 2)
            val length = u16(message, index + 8)
            index += 10
            if (index + length > message.size) break
            if (type == TYPE_A && klass == CLASS_IN && length == 4) {
                out += Socks5Wire.ipv4Text(message.copyOfRange(index, index + 4))
            }
            index += length
        }
        return out
    }

    /** Index just past the (possibly compressed) name at [start], or null. */
    private fun skipName(message: ByteArray, start: Int): Int? {
        var index = start
        var labels = 0
        while (labels <= MAX_LABELS) {
            if (index >= message.size) return null
            val length = message[index].toInt() and 0xFF
            if ((length and 0xC0) == 0xC0) {
                return if (index + 2 <= message.size) index + 2 else null
            }
            if ((length and 0xC0) != 0) return null
            index += 1
            if (length == 0) return index
            index += length
            labels += 1
        }
        return null
    }

    private fun u16(message: ByteArray, offset: Int): Int =
        ((message[offset].toInt() and 0xFF) shl 8) or (message[offset + 1].toInt() and 0xFF)

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
