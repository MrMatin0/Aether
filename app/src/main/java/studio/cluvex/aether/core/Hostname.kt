package studio.cluvex.aether.core

import java.util.Locale

/**
 * Validation for hostnames that are about to be put ON THE WIRE.
 *
 * WHY THIS EXISTS
 * ---------------
 * A SOCKS5 request with ATYP=0x03 encodes the host length in exactly ONE byte:
 *
 *     +----+-----+-------+------+----------+----------+
 *     |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
 *     +----+-----+-------+------+----------+----------+
 *                                ^ 1 length byte + that many bytes
 *
 * Both proxy paths in this app build that request from a name they did not
 * choose:
 *
 *  - the LAN share bridge takes it from an HTTP CONNECT line / Host header,
 *  - the userspace TUN bridge takes it from a sniffed TLS SNI, a sniffed HTTP
 *    Host header, or an observed DNS answer (see [TrafficSniffer], [DnsMap]).
 *
 * All of those are PEER-CONTROLLED. `length.toByte()` keeps only the low 8
 * bits, so a 300-byte name wrote a length of 44 and the proxy then read 44
 * bytes of host followed by 256 bytes of "port" - a corrupted request that
 * comes back as a generic "CONNECT rejected", i.e. a lie about whose fault it
 * was, and a byte pattern an attacker gets to choose.
 *
 * Non-ASCII is refused for the same reason: the field is specified as a
 * hostname, IDN belongs in punycode, and `String.toByteArray()` would
 * otherwise emit UTF-8 continuation bytes into a length-prefixed field.
 *
 * Validating at the BOUNDARY (where a name is sniffed or cached) keeps every
 * consumer downstream honest without each of them having to remember to check.
 */
object Hostname {

    /** Hard limit of the SOCKS5 / DNS length prefix. */
    const val MAX_WIRE_LENGTH = 255

    /** RFC 1035 label limit. */
    const val MAX_LABEL_LENGTH = 63

    /**
     * True when [host] can be encoded into a one-byte-length hostname field
     * without loss and without smuggling anything into the surrounding bytes.
     */
    fun isRepresentable(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_WIRE_LENGTH) return false
        var labelLength = 0
        for (ch in host) {
            if (ch == '.') {
                // Rejects a leading dot and an empty label ("a..b"); a single
                // TRAILING dot is stripped by [sanitize] before we get here.
                if (labelLength == 0) return false
                labelLength = 0
                continue
            }
            val allowed = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_'
            if (!allowed) return false
            if (++labelLength > MAX_LABEL_LENGTH) return false
        }
        return labelLength != 0
    }

    /**
     * Normalises [host] the way every consumer wants it (trimmed, no trailing
     * root dot, lower-case) and returns null when the result is not something
     * that can be put on the wire.
     */
    fun sanitize(host: String?): String? =
        host?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { isRepresentable(it) }
}
