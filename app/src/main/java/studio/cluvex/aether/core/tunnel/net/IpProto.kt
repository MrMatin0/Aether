package studio.cluvex.aether.core.tunnel.net

/**
 * Header offsets, protocol numbers and TCP flag bits.
 *
 * The original file used bare magic numbers everywhere (`packet[hLen + 13]`,
 * `flags and 0x02`, `0x18`, `44`). Naming them is the single biggest
 * readability win in the whole refactor and it costs nothing at runtime.
 */
internal object IpProto {
    const val TCP = 6
    const val UDP = 17

    const val IPV4_MIN_HEADER = 20
    const val IPV6_HEADER = 40
    const val TCP_MIN_HEADER = 20
    const val UDP_HEADER = 8

    const val DNS_PORT = 53
    const val HTTP_PORT = 80
    const val HTTPS_PORT = 443

    const val EXT_FRAGMENT = 44

    /** Hard cap on the IPv6 extension-header chain walk (anti-DoS). */
    const val MAX_EXT_HEADERS = 8
}

/** TCP control bits, as a value class so they cannot be confused with a length. */
@JvmInline
internal value class TcpFlags(val bits: Int) {
    val fin: Boolean get() = bits and 0x01 != 0
    val syn: Boolean get() = bits and 0x02 != 0
    val rst: Boolean get() = bits and 0x04 != 0
    val ack: Boolean get() = bits and 0x10 != 0

    /** A connection request: SYN with no ACK. */
    val isInitialSyn: Boolean get() = syn && !ack

    companion object {
        const val ACK = 0x10
        const val SYN_ACK = 0x12
        const val FIN_ACK = 0x11
        const val RST_ACK = 0x14
        const val PSH_ACK = 0x18
    }
}