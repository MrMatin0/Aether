package studio.cluvex.aether.core.tunnel.net

/**
 * Builds the IPv4/IPv6 TCP and UDP packets the bridge injects into the TUN.
 *
 * Was four ~60-line private methods on `SocksTunBridge`. They only ever needed
 * the MTU, so they are now a tiny standalone collaborator that can be unit
 * tested without an Android `VpnService` in sight.
 */
internal class PacketFactory(private val mtu: Int) {

    /** Largest TCP payload that still fits the MTU, per IP version. */
    fun maxTcpPayload(version: Int): Int =
        if (version == 4) (mtu - 40).coerceAtLeast(20) else (mtu - 60).coerceAtLeast(20)

    /**
     * One IPv4/TCP packet.
     *
     * [dataOffset]/[dataLen] describe the slice of [data] to send, so a caller
     * holding a big read buffer never has to cut a fresh array out of it per
     * segment (the zero-copy download path).
     */
    fun tcp4(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray?,
        seq: Long,
        ack: Long,
        flags: Int,
        dataOffset: Int = 0,
        dataLen: Int = data?.size ?: 0
    ): ByteArray {
        val isSynAck = flags == TcpFlags.SYN_ACK
        val optLen = if (isSynAck) 4 else 0
        val body = if (data == null) 0 else dataLen
        val total = 40 + optLen + body
        val p = ByteArray(total)

        p[0] = 0x45
        Bytes.setShort(p, 2, total)
        p[8] = 64
        p[9] = IpProto.TCP.toByte()
        Bytes.setInt(p, 12, srcIp)
        Bytes.setInt(p, 16, dstIp)
        Bytes.setShort(p, 10, Checksum.ipv4Header(p))

        writeTcpHeader(p, 20, srcPort, dstPort, seq, ack, flags, optLen)
        if (isSynAck) writeMssOption(p, 40, mtu - 40)
        if (data != null && body > 0) System.arraycopy(data, dataOffset, p, 40 + optLen, body)

        Bytes.setShort(
            p, 36,
            Checksum.nonZero(Checksum.transport4(srcIp, dstIp, p, 20, total - 20, IpProto.TCP))
        )
        return p
    }

    fun tcp6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray?,
        seq: Long,
        ack: Long,
        flags: Int,
        dataOffset: Int = 0,
        dataLen: Int = data?.size ?: 0
    ): ByteArray {
        val isSynAck = flags == TcpFlags.SYN_ACK
        val optLen = if (isSynAck) 4 else 0
        val body = if (data == null) 0 else dataLen
        val tcpLen = 20 + optLen + body
        val p = ByteArray(40 + tcpLen)

        p[0] = 0x60
        Bytes.setShort(p, 4, tcpLen)
        p[6] = IpProto.TCP.toByte()
        p[7] = 64
        System.arraycopy(srcIp, 0, p, 8, 16)
        System.arraycopy(dstIp, 0, p, 24, 16)

        writeTcpHeader(p, 40, srcPort, dstPort, seq, ack, flags, optLen)
        if (isSynAck) writeMssOption(p, 60, mtu - 60)
        if (data != null && body > 0) System.arraycopy(data, dataOffset, p, 60 + optLen, body)

        Bytes.setShort(
            p, 56,
            Checksum.nonZero(Checksum.transport6(srcIp, dstIp, p, 40, tcpLen, IpProto.TCP))
        )
        return p
    }

    fun udp4(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
        val udpLen = IpProto.UDP_HEADER + data.size
        val total = 20 + udpLen
        val p = ByteArray(total)

        p[0] = 0x45
        Bytes.setShort(p, 2, total)
        p[8] = 64
        p[9] = IpProto.UDP.toByte()
        Bytes.setInt(p, 12, srcIp)
        Bytes.setInt(p, 16, dstIp)
        Bytes.setShort(p, 10, Checksum.ipv4Header(p))

        Bytes.setShort(p, 20, srcPort)
        Bytes.setShort(p, 22, dstPort)
        Bytes.setShort(p, 24, udpLen)
        System.arraycopy(data, 0, p, 28, data.size)

        Bytes.setShort(
            p, 26,
            Checksum.nonZero(Checksum.transport4(srcIp, dstIp, p, 20, udpLen, IpProto.UDP))
        )
        return p
    }

    fun udp6(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
        val udpLen = IpProto.UDP_HEADER + data.size
        val p = ByteArray(40 + udpLen)

        p[0] = 0x60
        Bytes.setShort(p, 4, udpLen)
        p[6] = IpProto.UDP.toByte()
        p[7] = 64
        System.arraycopy(srcIp, 0, p, 8, 16)
        System.arraycopy(dstIp, 0, p, 24, 16)

        Bytes.setShort(p, 40, srcPort)
        Bytes.setShort(p, 42, dstPort)
        Bytes.setShort(p, 44, udpLen)
        System.arraycopy(data, 0, p, 48, data.size)

        Bytes.setShort(
            p, 46,
            Checksum.nonZero(Checksum.transport6(srcIp, dstIp, p, 40, udpLen, IpProto.UDP))
        )
        return p
    }

    private fun writeTcpHeader(
        p: ByteArray,
        at: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        optLen: Int
    ) {
        Bytes.setShort(p, at, srcPort)
        Bytes.setShort(p, at + 2, dstPort)
        Bytes.setU32(p, at + 4, seq)
        Bytes.setU32(p, at + 8, ack)
        p[at + 12] = ((5 + optLen / 4) shl 4).toByte()
        p[at + 13] = flags.toByte()
        // Advertised window. Fixed at 65535: there is no window scaling here and
        // the session queues apply the real back-pressure.
        Bytes.setShort(p, at + 14, 0xFFFF)
    }

    private fun writeMssOption(p: ByteArray, at: Int, mss: Int) {
        p[at] = 2
        p[at + 1] = 4
        Bytes.setShort(p, at + 2, mss.coerceAtLeast(536))
    }
}