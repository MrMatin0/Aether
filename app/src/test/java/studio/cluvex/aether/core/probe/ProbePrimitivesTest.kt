package studio.cluvex.aether.core.probe

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The probe layer without a network (except one loopback socket).
 *
 * Everything here used to be a private member of NetProbe or SmartAuto, i.e.
 * reachable only by installing the app on a phone, standing on a filtered
 * network and reading the log.
 */
class ProbePrimitivesTest {

    // ---- IpInfoParser ------------------------------------------------------

    @Test
    fun `ip api json yields address and country`() {
        val info = IpInfoParser.parse("""{"status":"success","query":"5.6.7.8","countryCode":"DE"}""")
        assertEquals("5.6.7.8", info?.ip)
        assertEquals("DE", info?.countryCode)
    }

    @Test
    fun `cloudflare trace yields address and upper case country`() {
        val info = IpInfoParser.parse("fl=1f2\nip=203.0.113.9\nts=1.0\nloc=nl\n")
        assertEquals("203.0.113.9", info?.ip)
        assertEquals("NL", info?.countryCode)
    }

    @Test
    fun `a captive portal page is not an address`() {
        assertNull(IpInfoParser.parse("<html><body>Login to continue</body></html>"))
        assertNull(IpInfoParser.parse(""))
    }

    // ---- Socks5Probe -------------------------------------------------------

    @Test
    fun `ipv4 literals are validated octet by octet`() {
        assertTrue(intArrayOf(1, 1, 1, 1).contentEquals(Socks5Probe.ipv4Octets("1.1.1.1")))
        assertTrue(intArrayOf(188, 114, 96, 255).contentEquals(Socks5Probe.ipv4Octets("188.114.96.255")))
    }

    @Test
    fun `an out of range octet is refused instead of truncated`() {
        // `and 0xFF` would have turned this into a probe against 1.1.1.231 and
        // then reported a PASS for the wrong host.
        assertFailsWith<IOException> { Socks5Probe.ipv4Octets("1.1.1.999") }
        assertFailsWith<IOException> { Socks5Probe.ipv4Octets("1.1.1") }
        assertFailsWith<IOException> { Socks5Probe.ipv4Octets("1.1.1.x") }
        assertFailsWith<IOException> { Socks5Probe.ipv4Octets("example.com") }
    }

    // ---- UdpDnsProbe -------------------------------------------------------

    @Test
    fun `the query is a single recursive a question`() {
        val query = UdpDnsProbe.query()
        assertEquals(0x1A, query[0].toInt() and 0xFF)
        assertEquals(0x2B, query[1].toInt() and 0xFF)
        assertEquals(1, query[5].toInt())            // QDCOUNT = 1
        assertEquals(7, query[12].toInt())           // "example".length
        assertEquals(1, query[query.size - 1].toInt()) // QCLASS = IN
    }

    @Test
    fun `an echoed query does not prove udp works`() {
        val echoed = UdpDnsProbe.query()
        val outcome = UdpDnsProbe.inspect(echoed, echoed.size)
        assertFalse(outcome.answered, outcome.detail)
    }

    @Test
    fun `a real response proves udp works even when it carries no records`() {
        val reply = UdpDnsProbe.query()
        reply[2] = (reply[2].toInt() or 0x80).toByte() // QR = response
        assertTrue(UdpDnsProbe.inspect(reply, reply.size).answered)
    }

    @Test
    fun `a foreign transaction id and a truncated reply are both rejected`() {
        val reply = UdpDnsProbe.query()
        reply[2] = (reply[2].toInt() or 0x80).toByte()
        reply[0] = 0x00
        assertFalse(UdpDnsProbe.inspect(reply, reply.size).answered)
        assertFalse(UdpDnsProbe.inspect(ByteArray(4), 4).answered)
    }

    // ---- MiniHttp ----------------------------------------------------------

    /**
     * REGRESSION: the reader used to be "read until EOF", so a peer that ignores
     * `Connection: close` made it block for the whole socket timeout and the
     * attempt was then reported as a FAILED provider - for a body that had
     * already arrived in full.
     */
    @Test
    fun `a keep alive response is read without waiting for eof`() {
        val body = """{"query":"9.9.9.9","countryCode":"NL"}"""
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = thread(isDaemon = true) {
            runCatching {
                server.accept().use { connection ->
                    connection.getInputStream().read(ByteArray(2048))
                    connection.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "\r\n" + body
                                ).toByteArray()
                        )
                        flush()
                    }
                    // Deliberately keeps the connection open.
                    Thread.sleep(SERVER_LINGER_MS)
                }
            }
        }
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                    CONNECT_TIMEOUT_MS,
                )
                socket.soTimeout = SERVER_LINGER_MS.toInt()
                val started = System.nanoTime()
                val response = MiniHttp.get(socket, "probe.test", "/json")
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertEquals(200, response.status)
                assertTrue(response.isSuccess)
                assertEquals(body, response.body)
                assertTrue(elapsedMs < SERVER_LINGER_MS / 2, "waited ${elapsedMs}ms for a complete body")
            }
        } finally {
            runCatching { server.close() }
            peer.interrupt()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val SERVER_LINGER_MS = 4_000L
    }
}
