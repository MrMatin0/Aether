package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bridge half of the torrc.
 *
 * One rule here is not defensive programming, it is a hard requirement: tor
 * treats a `Bridge <transport> ...` line with no matching `ClientTransportPlugin`
 * as a FATAL configuration error and exits during startup. So a build that ships
 * no lyrebird must not write an obfs4 bridge line, or the Tor hop dies instantly
 * with nothing in the log about bridges - which is worse than not offering
 * bridges at all.
 */
class TorrcBridgeTest {

    private val obfs4 = "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=x iat-mode=0"
    private val meek = "meek_lite 192.0.2.20:80 url=https://x.invalid front=y.invalid"
    private val snowflake = "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://x.invalid"
    private val vanilla = "1.2.3.4:443 4352E58420E68F5E40BF7C74FAEB9D42088F6272"

    private val lyrebird = "/data/app/lib/arm64/liblyrebird.so"
    private val snowflakeBin = "/data/app/lib/arm64/libsnowflake.so"

    private fun build(
        bridges: List<String> = emptyList(),
        plugins: Map<String, String> = emptyMap(),
        upstreamPort: Int? = null,
    ) = Torrc.build(
        dataDir = "/data/tor",
        socksPort = 9819,
        dnsPort = 5819,
        upstreamPort = upstreamPort,
        exitCountry = null,
        strictNodes = false,
        geoipFile = "/data/tor/geoip",
        geoip6File = "/data/tor/geoip6",
        bridges = bridges,
        transportPlugins = plugins,
    )

    @Test
    fun `no bridges means no UseBridges at all`() {
        val torrc = build()
        assertFalse(torrc.contains("UseBridges"))
        assertFalse(torrc.contains("Bridge "))
        assertFalse(torrc.contains("ClientTransportPlugin"))
    }

    @Test
    fun `a plain bridge needs no plugin`() {
        val torrc = build(bridges = listOf(vanilla))
        assertTrue(torrc.contains("UseBridges 1"))
        assertTrue(torrc.contains("Bridge $vanilla"))
        assertFalse(torrc.contains("ClientTransportPlugin"))
    }

    @Test
    fun `an obfs4 bridge is written with its plugin`() {
        val torrc = build(bridges = listOf(obfs4), plugins = mapOf("obfs4" to lyrebird))
        assertTrue(torrc.contains("UseBridges 1"))
        assertTrue(torrc.contains("ClientTransportPlugin obfs4 exec $lyrebird"))
        assertTrue(torrc.contains("Bridge $obfs4"))
    }

    @Test
    fun `one plugin line per binary, listing every transport it serves`() {
        // lyrebird speaks both, and two ClientTransportPlugin lines naming the
        // same executable would start it twice.
        val torrc = build(
            bridges = listOf(obfs4, meek),
            plugins = mapOf("obfs4" to lyrebird, "meek_lite" to lyrebird),
        )
        assertTrue(torrc.contains("ClientTransportPlugin obfs4,meek_lite exec $lyrebird"))
        assertEquals(1, torrc.lines().count { it.startsWith("ClientTransportPlugin") })
    }

    @Test
    fun `a transport with no plugin is dropped rather than allowed to kill tor`() {
        val torrc = build(
            bridges = listOf(obfs4, snowflake, vanilla),
            plugins = mapOf("obfs4" to lyrebird),
        )
        assertTrue(torrc.contains("Bridge $obfs4"))
        assertTrue(torrc.contains("Bridge $vanilla"))
        assertFalse(torrc.contains("snowflake"))
    }

    @Test
    fun `a plugin nothing uses is not started`() {
        val torrc = build(
            bridges = listOf(obfs4),
            plugins = mapOf("obfs4" to lyrebird, "snowflake" to snowflakeBin),
        )
        assertTrue(torrc.contains("ClientTransportPlugin obfs4 exec $lyrebird"))
        assertFalse(torrc.contains(snowflakeBin))
    }

    @Test
    fun `bridges that all get dropped leave UseBridges out entirely`() {
        // UseBridges 1 with no Bridge line is a tor that cannot reach the network
        // at all, which is a worse outcome than connecting without bridges.
        val torrc = build(bridges = listOf(obfs4, snowflake), plugins = emptyMap())
        assertFalse(torrc.contains("UseBridges"))
        assertFalse(torrc.contains("Bridge "))
    }

    @Test
    fun `a junk bridge line can never reach the torrc`() {
        val torrc = build(
            bridges = listOf("obfs4 1.2.3.4:443 cert=x\nExitNodes {ru}", obfs4),
            plugins = mapOf("obfs4" to lyrebird),
        )
        assertFalse(torrc.contains("ExitNodes"))
        assertEquals(1, torrc.lines().count { it.startsWith("Bridge ") })
    }

    @Test
    fun `bridges and chaining coexist`() {
        // tor exports Socks5Proxy to a managed transport as TOR_PT_PROXY, so
        // "obfs4 over Aether" is one configuration and not two fighting features.
        val torrc = build(
            bridges = listOf(obfs4),
            plugins = mapOf("obfs4" to lyrebird),
            upstreamPort = 1819,
        )
        assertTrue(torrc.contains("Socks5Proxy 127.0.0.1:1819"))
        assertTrue(torrc.contains("UseBridges 1"))
    }

    @Test
    fun `the same bridge twice is written once`() {
        val torrc = build(bridges = listOf(obfs4, obfs4), plugins = mapOf("obfs4" to lyrebird))
        assertEquals(1, torrc.lines().count { it.startsWith("Bridge ") })
    }
}
