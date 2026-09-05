package studio.cluvex.aether.core.tunnel.net

import java.util.concurrent.atomic.AtomicInteger

/**
 * Emits inbound UDP datagrams towards the TUN, fragmenting whatever does not
 * fit the interface MTU.
 *
 * Oversized datagrams used to be DROPPED outright (`if (payload.size >
 * maxPayload) continue`), which silently broke every response larger than the
 * MTU: big DNS answers, QUIC, game traffic, anything a server sends as one
 * datagram. Fragments carry the checksum of the WHOLE datagram, so the
 * receiving app's kernel reassembles them transparently.
 */
internal class UdpEmitter(
    private val mtu: Int,
    private val packets: PacketFactory,
    private val tun: TunPipe
) {
    /** Identification field for the IP fragments this bridge emits. */
    private val ipId = AtomicInteger((0..0xFFFF).random())

    fun emit4(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray) {
        // 20 bytes of IP header + 8 of UDP header have to fit the 16-bit
        // total-length field; a bigger datagram cannot be represented at all.
        if (payload.size > MAX_UDP4_PAYLOAD) return

        val packet = packets.udp4(srcIp, dstIp, srcPort, dstPort, payload)
        if (packet.size <= mtu) {
            tun.offer(packet)
            return
        }

        // Fragment payloads must be multiples of 8 bytes (the offset field counts
        // 8-byte units); only the last fragment may be shorter.
        val maxChunk = ((mtu - 20) / 8) * 8
        if (maxChunk <= 0) return

        val body = packet.size - 20
        val id = ipId.incrementAndGet() and 0xFFFF
        var offset = 0
        while (offset < body) {
            val chunk = minOf(maxChunk, body - offset)
            val more = offset + chunk < body
            val fragment = ByteArray(20 + chunk)

            System.arraycopy(packet, 0, fragment, 0, 20)
            System.arraycopy(packet, 20 + offset, fragment, 20, chunk)
            Bytes.setShort(fragment, 2, 20 + chunk)
            Bytes.setShort(fragment, 4, id)
            Bytes.setShort(fragment, 6, (if (more) 0x2000 else 0) or (offset / 8))
            // Zero the inherited checksum before recomputing it for the fragment.
            Bytes.setShort(fragment, 10, 0)
            Bytes.setShort(fragment, 10, Checksum.ipv4Header(fragment))

            // A datagram is only usable once EVERY fragment arrives, so there is
            // no point pushing the rest after one of them could not be queued.
            if (!tun.offer(fragment)) return
            offset += chunk
        }
    }

    /** IPv6 counterpart: oversized datagrams get a real fragment header (NH 44). */
    fun emit6(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray) {
        if (payload.size > MAX_UDP6_PAYLOAD) return

        val packet = packets.udp6(srcIp, dstIp, srcPort, dstPort, payload)
        if (packet.size <= mtu) {
            tun.offer(packet)
            return
        }

        // 40 bytes of base header + 8 bytes of fragment header per fragment.
        val maxChunk = ((mtu - 48) / 8) * 8
        if (maxChunk <= 0) return

        val body = packet.size - 40
        val id = ipId.incrementAndGet()
        var offset = 0
        while (offset < body) {
            val chunk = minOf(maxChunk, body - offset)
            val more = offset + chunk < body
            val fragment = ByteArray(48 + chunk)

            System.arraycopy(packet, 0, fragment, 0, 40)
            Bytes.setShort(fragment, 4, 8 + chunk)
            fragment[6] = IpProto.EXT_FRAGMENT.toByte()
            fragment[40] = IpProto.UDP.toByte()
            fragment[41] = 0
            Bytes.setShort(fragment, 42, ((offset / 8) shl 3) or (if (more) 1 else 0))
            Bytes.setInt(fragment, 44, id)
            System.arraycopy(packet, 40 + offset, fragment, 48, chunk)

            if (!tun.offer(fragment)) return
            offset += chunk
        }
    }

    private companion object {
        /** Largest payload that still fits the 16-bit length fields. */
        const val MAX_UDP4_PAYLOAD = 65535 - 20 - 8
        const val MAX_UDP6_PAYLOAD = 65535 - 8
    }
}