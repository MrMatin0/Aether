package studio.cluvex.aether.core

import studio.cluvex.aether.model.BridgeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bridge line comes from a text field, a Telegram message or an HTTPS response
 * and is written into the file that is tor's ENTIRE configuration. Everything
 * below is either "a real bridge must survive" or "this must never reach a
 * torrc", and neither can be checked on a device.
 */
class BridgeLineTest {

    private val obfs4 =
        "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C " +
            "cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0"

    private val snowflake =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478 utls-imitate=hellorandomizedalpn"

    private val meek =
        "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org " +
            "front=www.phpmyadmin.net utls=HelloRandomizedALPN"

    private val vanilla = "1.2.3.4:443 4352E58420E68F5E40BF7C74FAEB9D42088F6272"

    private val ipv6 =
        "obfs4 [2a0c:4d80:42:702::1]:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C " +
            "cert=TD7PbUO0 iat-mode=0"

    private val webtunnel =
        "webtunnel [2001:db8:ff:ff::1]:443 54BF1146B161573185FBA0299B0DC3A8F7D08080 " +
            "url=https://example.invalid/5m9YmTFQ ver=0.0.1"

    @Test
    fun `every shape the tor project actually hands out survives byte for byte`() {
        listOf(obfs4, snowflake, meek, vanilla, ipv6, webtunnel).forEach { line ->
            assertEquals(line, BridgeLine.parse(line)?.line, "round trip changed: $line")
        }
    }

    @Test
    fun `a snowflake line keeps the commas inside its parameters`() {
        val parsed = assertNotNull(BridgeLine.parse(snowflake))
        assertTrue(parsed.params.any { it.startsWith("ice=") && it.contains(',') })
        assertTrue(parsed.params.any { it == "fronts=app.datapacket.com,www.datapacket.com" })
    }

    @Test
    fun `a bridge with no fingerprint is still a bridge`() {
        // The built-in meek line has none, so requiring one would drop the only
        // meek bridge the Tor Project publishes.
        val parsed = assertNotNull(BridgeLine.parse(meek))
        assertNull(parsed.fingerprint)
        assertEquals(BridgeTransport.MEEK, parsed.transport)
    }

    @Test
    fun `a first token that is an address means a bridge with no transport`() {
        val parsed = assertNotNull(BridgeLine.parse(vanilla))
        assertEquals("", parsed.transportName)
        assertEquals(BridgeTransport.VANILLA, parsed.transport)
        assertEquals(443, parsed.port)
        assertEquals("1.2.3.4", parsed.address)
    }

    @Test
    fun `a transport this app does not know is kept, not dropped`() {
        // Upstream adds transports faster than an app updates. The line is still
        // usable as long as the build has a plugin serving that name.
        val parsed = assertNotNull(
            BridgeLine.parse("conjure 1.2.3.4:443 AAAABBBBCCCCDDDDEEEEFFFF00001111 url=https://x.invalid"),
        )
        assertEquals("conjure", parsed.transportName)
        assertNull(parsed.transport)
    }

    @Test
    fun `the keyword from a torrc or a browser text box is not part of the bridge`() {
        assertEquals(obfs4, BridgeLine.parse("Bridge $obfs4")?.line)
    }

    @Test
    fun `nothing that could become a torrc option is ever accepted`() {
        listOf(
            // The whole reason this parser exists.
            "obfs4 1.2.3.4:443\nExitNodes {ru}",
            "obfs4 1.2.3.4:443 cert=x\\",
            "obfs4 1.2.3.4:443 cert=x # Socks5Proxy 127.0.0.1:1",
            "obfs4 1.2.3.4:443 cert=\"x\"",
            // The codec folds bridge lines on '|', so a line containing one
            // could not survive the Intent round trip.
            "obfs4 1.2.3.4:443 url=https://x.invalid/a|b",
            "ExitNodes {ru}",
            "",
            "   ",
        ).forEach { junk ->
            assertNull(BridgeLine.parse(junk), "must not parse: $junk")
        }
    }

    @Test
    fun `an out of range port is dropped rather than handed to tor`() {
        // tor rejects the whole bridge line, so one typo would cost every other
        // bridge in the list too.
        assertNull(BridgeLine.parse("obfs4 1.2.3.4:99999 AAAA"))
        assertNull(BridgeLine.parse("obfs4 1.2.3.4:0 cert=x"))
        assertNotNull(BridgeLine.parse("obfs4 1.2.3.4:65535 cert=x"))
    }

    @Test
    fun `a line longer than the cap is refused`() {
        val long = "obfs4 1.2.3.4:443 " + "cert=" + "a".repeat(BridgeLine.MAX_LENGTH)
        assertNull(BridgeLine.parse(long))
    }

    @Test
    fun `parsing a list drops junk, de-duplicates and caps`() {
        val text = listOf(obfs4, "nonsense", obfs4, meek, "").joinToString("\n")
        val parsed = BridgeLine.parseAll(text)
        assertEquals(2, parsed.size)
        assertEquals(listOf(obfs4, meek), parsed.map { it.line })

        val many = (1..BridgeLine.MAX_LINES + 10).joinToString("\n") { "obfs4 1.2.3.$it:443 cert=x" }
        assertEquals(BridgeLine.MAX_LINES, BridgeLine.parseAll(many).size)
    }

    @Test
    fun `usable keeps vanilla and drops a transport with no plugin`() {
        val parsed = BridgeLine.parseAll(listOf(obfs4, snowflake, vanilla))
        val usable = BridgeLine.usable(parsed, setOf("obfs4"))
        assertEquals(listOf(obfs4, vanilla), usable.map { it.line })
        assertFalse(usable.any { it.transportName == "snowflake" })

        // No plugins at all: only the plain bridge is left, which is exactly the
        // state of a build with no pluggable transports.
        assertEquals(listOf(vanilla), BridgeLine.usable(parsed, emptySet()).map { it.line })
    }

    @Test
    fun `transports are reported once each, in the order they appear`() {
        val parsed = BridgeLine.parseAll(listOf(obfs4, obfs4, snowflake, vanilla))
        assertEquals(listOf("obfs4", "snowflake"), BridgeLine.transportsOf(parsed))
    }

    @Test
    fun `canonical text is what gets stored`() {
        // Extra spaces, a lower-cased fingerprint and a stray blank line all
        // normalise, so the same bridge cannot be stored twice in two spellings.
        val messy = "  obfs4   1.2.3.4:443   c5b7cd6946ff10c5b3e89691a7d3f2c122d2117c cert=x  \n\n"
        assertEquals(
            "obfs4 1.2.3.4:443 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=x",
            BridgeLine.canonicalText(messy.lines()),
        )
    }
}
