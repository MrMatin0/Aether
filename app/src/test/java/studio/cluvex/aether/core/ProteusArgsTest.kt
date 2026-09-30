package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Proteus command line and its input validation. A wrong argument here is
 * a clap usage error in a child process, which the user would only ever see as
 * "Proteus did not come up", so it is pinned on the JVM instead.
 */
class ProteusArgsTest {

    @Test
    fun `ipv4, hostname and bracketed ipv6 servers parse`() {
        assertEquals(ProteusArgs.Server("1.2.3.4", 443), ProteusArgs.parseServer("1.2.3.4:443"))
        assertEquals(
            ProteusArgs.Server("proxy.example.com", 8443),
            ProteusArgs.parseServer("  proxy.example.com:8443 "),
        )
        val v6 = ProteusArgs.parseServer("[2001:db8::1]:9000")
        assertEquals(ProteusArgs.Server("2001:db8::1", 9000), v6)
        assertTrue(v6!!.isIpv6Literal)
        assertTrue(v6.isLiteral)
        assertFalse(ProteusArgs.parseServer("proxy.example.com:1")!!.isLiteral)
    }

    @Test
    fun `malformed servers are rejected, not guessed at`() {
        listOf(
            null,
            "",
            "   ",
            "1.2.3.4",
            ":443",
            "1.2.3.4:0",
            "1.2.3.4:65536",
            "1.2.3.4:-1",
            "1.2.3.4:44a",
            "999.1.1.1:80",
            "1.2.3:80",
            "bad host:80",
            "under_score.com:80",
            "2001:db8::1",
            "2001:db8::1:443",
            "[2001:db8::1]",
            "[2001:db8::1]443",
            "[not-v6]:443",
        ).forEach { raw ->
            assertNull(ProteusArgs.parseServer(raw), "'$raw' must not parse")
        }
    }

    @Test
    fun `socket addresses bracket ipv6 only`() {
        assertEquals("1.2.3.4:443", ProteusArgs.socketAddress("1.2.3.4", 443))
        assertEquals("[2001:db8::1]:443", ProteusArgs.socketAddress("2001:db8::1", 443))
    }

    @Test
    fun `client command line matches the proteus cli`() {
        assertEquals(
            listOf(
                "--log-level", "info",
                "client", "/data/proteus/client.psf",
                "--connect", "1.2.3.4:443",
                "--listen", "127.0.0.1:1829",
                "--mode", "tunnel",
                "--persist", "false",
            ),
            ProteusArgs.clientArgs(
                psfPath = "/data/proteus/client.psf",
                connect = "1.2.3.4:443",
                listenPort = ProteusCore.SOCKS_PORT,
                mode = ProteusMode.TUNNEL,
                persist = false,
            ),
        )
        val stream = ProteusArgs.clientArgs("p", "[::1]:1", 1829, ProteusMode.STREAM, true)
        assertEquals(listOf("--mode", "stream", "--persist", "true"), stream.takeLast(4))
    }

    @Test
    fun `psf text is normalised to lf with one trailing newline`() {
        assertEquals("a\nb\n", ProteusArgs.normalizePsf("a\r\nb\r\n\r\n"))
        assertEquals("a\nb\n", ProteusArgs.normalizePsf("a\rb"))
    }

    @Test
    fun `mode round trips and falls back to tunnel`() {
        ProteusMode.entries.forEach { mode ->
            assertEquals(mode, ProteusMode.fromStored(mode.wire))
            assertEquals(mode, ProteusMode.fromStored(mode.name))
        }
        assertEquals(ProteusMode.TUNNEL, ProteusMode.fromStored(null))
        assertEquals(ProteusMode.TUNNEL, ProteusMode.fromStored("nonsense"))
    }

    @Test
    fun `settings are complete only with a valid server and a psf`() {
        assertTrue(ProteusSettings(server = "1.2.3.4:443", psf = "@SEGMENT.FORMATS").isComplete)
        assertFalse(ProteusSettings(server = "1.2.3.4:443", psf = "  ").isComplete)
        assertFalse(ProteusSettings(server = "1.2.3.4", psf = "x").isComplete)
        assertFalse(ProteusSettings().isComplete)
    }
}
