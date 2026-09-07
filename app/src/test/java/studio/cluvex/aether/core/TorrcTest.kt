package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The torrc is the entire configuration surface of the Tor hop, and two lines in
 * it are load-bearing:
 *
 *  - `Socks5Proxy` IS the chaining. Lose it and "Tor over Aether" is just Tor,
 *    connecting directly on a network that blocks Tor - i.e. the feature looks
 *    implemented and does nothing.
 *  - `DNSPort` is the device's only DNS answer path in a Tor session (Tor
 *    carries no UDP), so without it every site fails to resolve while every
 *    connectivity check still passes.
 *
 * Both are checked here rather than on a device, which is why Torrc is a pure
 * function in the first place.
 */
class TorrcTest {

    private fun build(
        upstreamPort: Int? = null,
        exitCountry: String? = null,
        strictNodes: Boolean = false,
        geoip: String? = "/data/tor/geoip",
    ) = Torrc.build(
        dataDir = "/data/tor",
        socksPort = 9819,
        dnsPort = 5819,
        upstreamPort = upstreamPort,
        exitCountry = exitCountry,
        strictNodes = strictNodes,
        geoipFile = geoip,
        geoip6File = geoip?.plus("6"),
    )

    @Test
    fun `always opens a socks port and a dns port on loopback`() {
        val torrc = build()
        assertTrue(torrc.contains("SocksPort 127.0.0.1:9819"))
        assertTrue(torrc.contains("DNSPort 127.0.0.1:5819"))
        assertTrue(torrc.contains("AutomapHostsOnResolve 1"))
    }

    @Test
    fun `is a client and never a relay`() {
        val torrc = build()
        assertTrue(torrc.contains("ClientOnly 1"))
        assertTrue(torrc.contains("ControlPort 0"))
    }

    @Test
    fun `chains through the previous hop only when there is one`() {
        assertTrue(build(upstreamPort = 1819).contains("Socks5Proxy 127.0.0.1:1819"))
        assertFalse(build(upstreamPort = null).contains("Socks5Proxy"))
    }

    @Test
    fun `exit country needs a geoip database to be requested at all`() {
        val withGeoip = build(exitCountry = "DE")
        assertTrue(withGeoip.contains("ExitNodes {de}"))

        // Without the database tor cannot map relays to countries and ignores
        // the request with a warning, so asking for it would be a lie.
        val withoutGeoip = build(exitCountry = "DE", geoip = null)
        assertFalse(withoutGeoip.contains("ExitNodes"))
    }

    @Test
    fun `strict nodes only applies with a country and is off by default`() {
        assertFalse(build(exitCountry = "de").contains("StrictNodes"))
        assertTrue(build(exitCountry = "de", strictNodes = true).contains("StrictNodes 1"))
        // No country: strict would pin nothing, so it must not be emitted.
        assertFalse(build(strictNodes = true).contains("StrictNodes"))
    }

    @Test
    fun `a junk country is dropped rather than passed through`() {
        listOf("germany", "d", "de de", "{de}", "../etc").forEach { junk ->
            assertFalse(
                build(exitCountry = junk).contains("ExitNodes"),
                "\"$junk\" must not reach the torrc",
            )
        }
    }
}
