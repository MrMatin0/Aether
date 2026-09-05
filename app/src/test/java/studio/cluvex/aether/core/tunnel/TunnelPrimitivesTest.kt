package studio.cluvex.aether.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.core.tunnel.dns.DnsMessage
import studio.cluvex.aether.core.tunnel.net.Bytes
import studio.cluvex.aether.core.tunnel.net.Checksum
import studio.cluvex.aether.core.tunnel.net.FlowKey6
import studio.cluvex.aether.core.tunnel.net.IpPacketReader
import studio.cluvex.aether.core.tunnel.net.PacketFactory
import studio.cluvex.aether.core.tunnel.socks.Socks5

/**
 * These tests are the point of the refactor.
 *
 * NONE of this was reachable before: checksums, the DNS parser, the SOCKS codec
 * and the header reader were all private methods on a class whose constructor
 * demands a live `VpnService` and an open TUN file descriptor, so the only way to
 * exercise any of it was to install the VPN on a device and watch a browser.
 */
class TunnelPrimitivesTest {

    // ------------------------------------------------------------- regressions

    /**
     * REGRESSION: a segment declaring a TCP header shorter than the mandatory 20
     * bytes made the payload slice start INSIDE the header, injecting header bytes
     * into the application's byte stream. Such a segment is now rejected.
     */
    @Test
    fun `tcp segment with an undersized data offset is rejected`() {
        val packet = ipv4TcpPacket(dataOffsetWords = 3, payload = byteArrayOf(1, 2, 3))
        val l3 = checkNotNull(IpPacketReader.parse(packet, packet.size))
        assertNull(IpPacketReader.tcp(packet, packet.size, l3))
    }

    @Test
    fun `tcp segment with a valid data offset yields exactly the payload`() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val packet = ipv4TcpPacket(dataOffsetWords = 5, payload = payload)
        val l3 = checkNotNull(IpPacketReader.parse(packet, packet.size))
        val tcp = checkNotNull(IpPacketReader.tcp(packet, packet.size, l3))
        assertTrue(payload.contentEquals(tcp.payload))
    }

    /** REGRESSION: fragments carry no transport header and must never open a flow. */
    @Test
    fun `ipv4 fragments are dropped`() {
        val packet = ipv4TcpPacket(dataOffsetWords = 5, payload = byteArrayOf(1))
        packet[6] = 0x20 // MORE_FRAGMENTS
        assertNull(IpPacketReader.parse(packet, packet.size))
    }

    /**
     * REGRESSION: a compression pointer appearing AFTER one or more labels was
     * read as a label length, landing the answer walk on an arbitrary offset and
     * recording bogus IP -> domain mappings.
     */
    @Test
    fun `answer names ending in a compression pointer are walked correctly`() {
        val response = dnsResponseWithCompressedAnswer()
        var seen = 0
        DnsMessage.forEachAnswer(response) { record ->
            seen++
            assertEquals(DnsMessage.TYPE_A, record.type)
            assertEquals(4, record.dataLength)
            assertEquals(93, response[record.dataOffset].toInt() and 0xFF)
        }
        assertEquals(1, seen)
    }

    /** A pointer loop used to be possible; the walker now gives up instead. */
    @Test
    fun `self referential compression pointer terminates`() {
        val data = ByteArray(16)
        data[12] = 0xC0.toByte()
        data[13] = 12 // points at itself
        assertNull(DnsMessage.readName(data, 12))
    }

    @Test
    fun `truncated answer section does not throw`() {
        val response = dnsResponseWithCompressedAnswer().copyOfRange(0, 20)
        DnsMessage.forEachAnswer(response) { error("should not yield a record") }
    }

    /**
     * REGRESSION: `FlowKey6` was a `data class`, so its generated equals/hashCode
     * compared address ByteArrays by REFERENCE — every packet of the same IPv6
     * flow missed the session map and opened a brand new session with two threads.
     */
    @Test
    fun `ipv6 flow keys compare by address content`() {
        val a = FlowKey6(6, ByteArray(16) { 1 }, 1234, ByteArray(16) { 2 }, 443)
        val b = FlowKey6(6, ByteArray(16) { 1 }, 1234, ByteArray(16) { 2 }, 443)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ------------------------------------------------------------- protocol

    @Test
    fun `nxdomain response keeps the question and sets rcode 3`() {
        val query = dnsQuery("blocked.example")
        val response = checkNotNull(DnsMessage.nxDomainResponse(query))

        assertTrue(DnsMessage.isResponse(response))
        assertEquals(3, response[3].toInt() and 0x0F)
        assertEquals(1, DnsMessage.questionCount(response))
        assertEquals(0, DnsMessage.answerCount(response))
        assertEquals("blocked.example", DnsMessage.firstQueryName(response))
        // The transaction ID must survive or the client ignores the answer.
        assertEquals(Bytes.short(query, 0), Bytes.short(response, 0))
    }

    @Test
    fun `socks5 connect request encodes a domain target`() {
        val request = Socks5.request(Socks5.CMD_CONNECT, "example.com", ByteArray(4), 443)
        assertEquals(5, request[0].toInt())
        assertEquals(Socks5.CMD_CONNECT, request[1].toInt())
        assertEquals(3, request[3].toInt())              // ATYP = domain
        assertEquals(11, request[4].toInt())             // "example.com".length
        assertEquals(443, Bytes.short(request, request.size - 2))
    }

    /**
     * A DOMAIN reply is returned as a hostname rather than being resolved. The old
     * code called `InetAddress.getByName()` here, which was a blocking,
     * out-of-tunnel system DNS lookup on the session thread.
     */
    @Test
    fun `socks5 domain reply is not resolved`() {
        val reply = byteArrayOf(5, 0, 0, 3, 5) + "relay".toByteArray() + byteArrayOf(0x1F, 0x90)
        val bound = Socks5.readReply(reply.inputStream())
        assertTrue(bound is Socks5.Bound.Hostname)
        assertEquals("relay", (bound as Socks5.Bound.Hostname).host)
        assertEquals(8080, bound.port)
    }

    @Test
    fun `fragmented socks5 datagrams are refused`() {
        val datagram = byteArrayOf(0, 0, 1, 1, 8, 8, 8, 8, 0, 53, 42)
        assertNull(Socks5.parseDatagram(datagram, datagram.size))
    }

    /** A built packet must checksum to zero when re-summed with the field in place. */
    @Test
    fun `built ipv4 tcp packet has a valid header and transport checksum`() {
        val packet = PacketFactory(1280).tcp4(
            srcIp = 0x0A000001, dstIp = 0x0A000002,
            srcPort = 443, dstPort = 51000,
            data = byteArrayOf(1, 2, 3, 4), seq = 1000, ack = 2000, flags = 0x18
        )
        assertEquals(0, Checksum.ipv4Header(packet))
        assertEquals(
            0,
            Checksum.transport4(0x0A000001, 0x0A000002, packet, 20, packet.size - 20, 6)
        )
    }

    @Test
    fun `syn ack carries an mss option sized to the mtu`() {
        val packet = PacketFactory(1280).tcp4(1, 2, 443, 51000, null, 0, 0, 0x12)
        assertEquals(44, packet.size)                    // 20 IP + 20 TCP + 4 option
        assertEquals(6, (packet[32].toInt() and 0xF0) shr 4) // 6 words = 24 bytes
        assertEquals(2, packet[40].toInt())              // kind = MSS
        assertEquals(1240, Bytes.short(packet, 42))      // 1280 - 40
    }

    // ------------------------------------------------------------- fixtures

    private fun ipv4TcpPacket(dataOffsetWords: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(20 + 20 + payload.size)
        packet[0] = 0x45
        Bytes.setShort(packet, 2, packet.size)
        packet[9] = 6
        Bytes.setInt(packet, 12, 0x0A000001)
        Bytes.setInt(packet, 16, 0x0A000002)
        Bytes.setShort(packet, 20, 51000)
        Bytes.setShort(packet, 22, 443)
        Bytes.setU32(packet, 24, 1234)
        packet[32] = (dataOffsetWords shl 4).toByte()
        packet[33] = 0x18
        payload.copyInto(packet, 40)
        return packet
    }

    private fun dnsQuery(name: String): ByteArray {
        val labels = name.split('.')
        val body = ByteArray(labels.sumOf { it.length + 1 } + 1 + 4)
        var at = 0
        labels.forEach { label ->
            body[at++] = label.length.toByte()
            label.toByteArray().copyInto(body, at)
            at += label.length
        }
        body[at++] = 0
        Bytes.setShort(body, at, 1)     // QTYPE = A
        Bytes.setShort(body, at + 2, 1) // QCLASS = IN
        return ByteArray(12).also {
            Bytes.setShort(it, 0, 0xBEEF)
            Bytes.setShort(it, 2, 0x0100)
            Bytes.setShort(it, 4, 1)
        } + body
    }

    /** One question plus one A answer whose NAME is a pointer back to it. */
    private fun dnsResponseWithCompressedAnswer(): ByteArray {
        val query = dnsQuery("cdn.example.com")
        val header = query.copyOfRange(0, 12)
        Bytes.setShort(header, 2, 0x8180) // QR + RA
        Bytes.setShort(header, 6, 1)      // ANCOUNT
        val answer = byteArrayOf(
            0xC0.toByte(), 12,           // NAME -> offset 12
            0, 1,                        // TYPE A
            0, 1,                        // CLASS IN
            0, 0, 0, 60,                 // TTL
            0, 4,                        // RDLENGTH
            93, 184, 216, 34             // RDATA
        )
        return header + query.copyOfRange(12, query.size) + answer
    }
}