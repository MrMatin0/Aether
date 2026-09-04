package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostPortTest {

    @Test
    fun plainHostFallsBackToTheDefaultPort() {
        assertEquals(HostPort("example.com", 443), HostPort.parse("example.com", 443))
        assertEquals(HostPort("example.com", 80), HostPort.parse("example.com", 80))
    }

    @Test
    fun explicitPortWins() {
        assertEquals(HostPort("example.com", 8443), HostPort.parse("example.com:8443", 443))
    }

    @Test
    fun bracketedIpv6IsUnwrapped() {
        assertEquals(HostPort("::1", 443), HostPort.parse("[::1]", 443))
        assertEquals(HostPort("2001:db8::1", 8443), HostPort.parse("[2001:db8::1]:8443", 443))
    }

    @Test
    fun bracketedIpv6RoundTripsThroughAuthority() {
        assertEquals("[::1]:8443", HostPort("::1", 8443).authority())
        assertEquals("example.com:80", HostPort("example.com", 80).authority())
    }

    @Test
    fun unbracketedIpv6IsRefusedRatherThanMisSplit() {
        // The old substringAfterLast(':') split turned this into host "::" and
        // port 1, i.e. it dialled something the client never asked for.
        assertNull(HostPort.parse("::1", 443))
        assertNull(HostPort.parse("2001:db8::1", 443))
    }

    @Test
    fun portsOutsideTheWireRangeAreRefused() {
        assertNull(HostPort.parse("example.com:0", 443))
        assertNull(HostPort.parse("example.com:65536", 443))
        assertNull(HostPort.parse("example.com:99999", 443))
        assertNull(HostPort.parse("example.com:-1", 443))
        assertNull(HostPort.parse("example.com:https", 443))
    }

    @Test
    fun emptyPortKeepsTheDefault() {
        assertEquals(HostPort("example.com", 443), HostPort.parse("example.com:", 443))
    }

    @Test
    fun hostsThatCouldSpliceTheRequestAreRefused() {
        assertNull(HostPort.parse("", 80))
        assertNull(HostPort.parse("   ", 80))
        assertNull(HostPort.parse("exa mple.com", 80))
        assertNull(HostPort.parse("example.com/path", 80))
        assertNull(HostPort.parse("user@example.com", 80))
        assertNull(HostPort.parse(":443", 80))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(HostPort("example.com", 8080), HostPort.parse("  example.com:8080  ", 80))
    }
}
