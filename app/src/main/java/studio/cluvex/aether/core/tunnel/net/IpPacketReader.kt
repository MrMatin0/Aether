package studio.cluvex.aether.core.tunnel.net

/**
 * Turns a raw TUN frame into a version-agnostic [L3Header].
 *
 * WHY THIS EXISTS: `processIpv4` and `processIpv6` were ~120 lines EACH and
 * about 85% identical — the same session lookup, the same stale-SYN recycling,
 * the same UID check, the same DNS guard, written twice. Any fix had to be
 * applied twice too, and in practice one of the two copies always drifted.
 * Parsing down to a common shape here lets the bridge keep exactly one copy of
 * the flow logic.
 */
internal object IpPacketReader {

    /** Result of parsing the network layer. [transportOffset] points at the L4 header. */
    class L3Header(
        val version: Int,
        val proto: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val transportOffset: Int,
        /** Pre-computed int form of the addresses, IPv4 only (avoids re-packing per packet). */
        val srcIp4: Int = 0,
        val dstIp4: Int = 0
    )

    fun parse(packet: ByteArray, len: Int): L3Header? {
        if (len < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parseIpv4(packet, len)
            6 -> parseIpv6(packet, len)
            else -> null
        }
    }

    private fun parseIpv4(packet: ByteArray, len: Int): L3Header? {
        if (len < IpProto.IPV4_MIN_HEADER) return null

        val proto = packet[9].toInt() and 0xFF
        if (proto != IpProto.TCP && proto != IpProto.UDP) return null

        val headerLen = (packet[0].toInt() and 0x0F) * 4
        if (headerLen < IpProto.IPV4_MIN_HEADER || len < headerLen) return null

        // FRAGMENT GUARD: only an unfragmented datagram carries a transport
        // header. A later fragment starts with payload bytes, and reading those
        // as ports/flags invented a flow — complete with its own session and two
        // threads — out of arbitrary data.
        val fragmentOffset = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        val moreFragments = (packet[6].toInt() and 0x20) != 0
        if (fragmentOffset != 0 || moreFragments) return null

        val srcIp4 = Bytes.int(packet, 12)
        val dstIp4 = Bytes.int(packet, 16)
        return L3Header(
            version = 4,
            proto = proto,
            srcIp = Bytes.ofInt(srcIp4),
            dstIp = Bytes.ofInt(dstIp4),
            transportOffset = headerLen,
            srcIp4 = srcIp4,
            dstIp4 = dstIp4
        )
    }

    private fun parseIpv6(packet: ByteArray, len: Int): L3Header? {
        if (len < IpProto.IPV6_HEADER) return null

        var nextHeader = packet[6].toInt() and 0xFF
        var offset = IpProto.IPV6_HEADER
        var hops = 0

        // BOUNDED WALK: the old loop was `while (true)` with no iteration cap and
        // no handling for the Authentication Header (51), whose length field is
        // measured in 4-byte units rather than 8. A crafted chain could either
        // spin the parser or land it on a bogus offset.
        while (hops++ < IpProto.MAX_EXT_HEADERS) {
            when (nextHeader) {
                IpProto.TCP, IpProto.UDP -> return L3Header(
                    version = 6,
                    proto = nextHeader,
                    srcIp = packet.copyOfRange(8, 24),
                    dstIp = packet.copyOfRange(24, 40),
                    transportOffset = offset
                )
                // No reassembly here, so a fragmented IPv6 datagram is dropped
                // for the same reason as an IPv4 one.
                IpProto.EXT_FRAGMENT -> return null
                51 -> {
                    if (offset + 2 > len) return null
                    val extLen = ((packet[offset + 1].toInt() and 0xFF) + 2) * 4
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += extLen
                }
                0, 43, 60, 135 -> {
                    if (offset + 2 > len) return null
                    val extLen = ((packet[offset + 1].toInt() and 0xFF) + 1) * 8
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += extLen
                }
                else -> return null
            }
            if (offset >= len) return null
        }
        return null
    }

    /** Parsed TCP header fields, or null when the segment is malformed. */
    class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val flags: TcpFlags,
        val payload: ByteArray?
    )

    fun tcp(packet: ByteArray, len: Int, l3: IpPacketReader.L3Header): TcpHeader? {
        val at = l3.transportOffset
        if (len < at + IpProto.TCP_MIN_HEADER) return null

        // DATA-OFFSET VALIDATION (bug fix): the raw nibble was trusted, so a
        // segment claiming a header shorter than the mandatory 20 bytes made the
        // payload slice start INSIDE the TCP header — and those header bytes were
        // then injected into the application's byte stream as if they were data.
        val headerWords = (packet[at + 12].toInt() and 0xF0) shr 4
        if (headerWords < 5) return null

        val dataStart = at + headerWords * 4
        val payload =
            if (len > dataStart) packet.copyOfRange(dataStart, len) else null

        return TcpHeader(
            srcPort = Bytes.short(packet, at),
            dstPort = Bytes.short(packet, at + 2),
            seq = Bytes.u32(packet, at + 4),
            flags = TcpFlags(packet[at + 13].toInt() and 0xFF),
            payload = payload
        )
    }

    /** Parsed UDP header. The payload is copied exactly ONCE per datagram. */
    class UdpHeader(val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    fun udp(packet: ByteArray, len: Int, l3: IpPacketReader.L3Header): UdpHeader? {
        val at = l3.transportOffset
        if (len < at + IpProto.UDP_HEADER) return null
        return UdpHeader(
            srcPort = Bytes.short(packet, at),
            dstPort = Bytes.short(packet, at + 2),
            payload = packet.copyOfRange(at + IpProto.UDP_HEADER, len)
        )
    }
}