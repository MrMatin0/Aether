package studio.cluvex.aether.core.probe

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * "Does a UDP round trip survive on this network?" - asked by sending a REAL
 * DNS query and looking at what comes back.
 *
 * Silence from every resolver means UDP is dropped or throttled, which starves
 * WireGuard and QUIC and is why Smart Auto reaches for a TCP-shaped transport
 * (see DpiClass.UDP_THROTTLED).
 *
 * TWO CORRECTNESS FIXES over the version that lived inside SmartAuto:
 *
 *  1. The socket is CONNECTED to the resolver, so the kernel drops datagrams
 *     from anybody else. An unconnected socket accepts the first packet from
 *     ANY source, so an unrelated or injected datagram could be read as proof
 *     that UDP works here.
 *  2. The QR bit is checked, not just the transaction id. Our own query echoed
 *     back by a middlebox carries the same id and used to count as an answer.
 *
 * The verdict stays deliberately generous: any well-formed RESPONSE means UDP
 * round-trips, even a poisoned or empty one. Whether DNS is honest is a
 * different question, asked later by the end-to-end self-test.
 */
internal object UdpDnsProbe {

    /** What came back, and a phrase for the log. */
    class Outcome(val answered: Boolean, val detail: String)

    private const val TXID_HI = 0x1A
    private const val TXID_LO = 0x2B
    private const val HEADER_BYTES = 12
    private const val FLAG_QR_RESPONSE = 0x80
    private const val REPLY_BUFFER_BYTES = 512
    private const val QUESTION_HOST = "example.com"

    fun probe(server: String, timeoutMs: Int): Outcome = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val resolver = InetAddress.getByName(server)
            socket.connect(InetSocketAddress(resolver, ProbeDefaults.DNS_PORT))
            val query = query()
            socket.send(DatagramPacket(query, query.size, resolver, ProbeDefaults.DNS_PORT))
            val buffer = ByteArray(REPLY_BUFFER_BYTES)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            inspect(buffer, reply.length)
        }
    } catch (e: Exception) {
        Outcome(false, "no answer (${e.message ?: e.javaClass.simpleName})")
    }

    /** A standard recursive A query for [QUESTION_HOST], built label by label. */
    fun query(): ByteArray {
        val out = ByteArrayOutputStream(32)
        out.write(TXID_HI)
        out.write(TXID_LO)
        out.write(0x01) // standard query, recursion desired
        out.write(0x00)
        out.write(0x00) // QDCOUNT = 1
        out.write(0x01)
        repeat(6) { out.write(0x00) } // ANCOUNT + NSCOUNT + ARCOUNT = 0
        for (label in QUESTION_HOST.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00) // root label
        out.write(0x00) // QTYPE = A
        out.write(0x01)
        out.write(0x00) // QCLASS = IN
        out.write(0x01)
        return out.toByteArray()
    }

    /** Judges a reply. Split out so it is testable without a socket. */
    fun inspect(reply: ByteArray, length: Int): Outcome {
        if (length < HEADER_BYTES) return Outcome(false, "truncated reply ($length bytes)")
        val idMatches = (reply[0].toInt() and 0xFF) == TXID_HI && (reply[1].toInt() and 0xFF) == TXID_LO
        if (!idMatches) return Outcome(false, "transaction id mismatch")
        if ((reply[2].toInt() and FLAG_QR_RESPONSE) == 0) return Outcome(false, "query echoed, not answered")
        val rcode = reply[3].toInt() and 0x0F
        val answers = ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)
        return Outcome(true, "answered (rcode=$rcode, answers=$answers)")
    }
}
