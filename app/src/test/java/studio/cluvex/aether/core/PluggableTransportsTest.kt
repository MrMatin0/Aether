package studio.cluvex.aether.core

import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.PtPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a build's PT payload is allowed to claim.
 *
 * [PluggableTransports.of] needs a Context and an installed APK, so the file
 * scan itself is not unit-testable - but the ANSWER it produces is, and the
 * answer is what the settings page disables types by and what the torrc wires
 * plugins from. A wrong one does not degrade a session: tor exits during startup
 * on a bridge line with no matching `ClientTransportPlugin`.
 */
class PluggableTransportsTest {

    private val lyrebird = "/data/app/lib/arm64/liblyrebird.so"
    private val snowflake = "/data/app/lib/arm64/libsnowflake.so"
    private val webtunnel = "/data/app/lib/arm64/libwebtunnel.so"

    private val complete = PluggableTransports.Snapshot(
        mapOf(
            PtPlugin.LYREBIRD to lyrebird,
            PtPlugin.SNOWFLAKE to snowflake,
            PtPlugin.WEBTUNNEL to webtunnel,
        ),
    )

    @Test
    fun `a build with all three binaries offers every transport and misses none`() {
        // The acceptance criterion for the CI change that builds them: with
        // liblyrebird, libsnowflake and libwebtunnel packaged, nothing is missing.
        assertEquals(BridgeTransport.entries.toList(), complete.available)
        assertTrue(complete.missing.isEmpty())
        assertEquals(
            setOf("obfs4", "snowflake", "webtunnel", "meek_lite"),
            complete.supportedTorNames,
        )
    }

    @Test
    fun `one binary serves two transports`() {
        // lyrebird is obfs4 AND meek_lite, which is why the torrc gets one
        // ClientTransportPlugin line naming both.
        val plugins = complete.pluginsFor(listOf("obfs4", "meek_lite"))
        assertEquals(mapOf("obfs4" to lyrebird, "meek_lite" to lyrebird), plugins)
        assertEquals(1, plugins.values.distinct().size)
    }

    @Test
    fun `a plugin nothing asked for is not wired`() {
        // A ClientTransportPlugin line makes tor SPAWN the binary, so wiring
        // snowflake for an obfs4-only session is a WebRTC stack and a broker
        // request nobody asked for.
        assertEquals(mapOf("obfs4" to lyrebird), complete.pluginsFor(listOf("obfs4")))
    }

    @Test
    fun `a lyrebird-only build reports exactly what it cannot run`() {
        val partial = PluggableTransports.Snapshot(mapOf(PtPlugin.LYREBIRD to lyrebird))
        assertEquals(
            listOf(BridgeTransport.OBFS4, BridgeTransport.MEEK, BridgeTransport.VANILLA),
            partial.available,
        )
        assertEquals(
            listOf(BridgeTransport.SNOWFLAKE, BridgeTransport.WEBTUNNEL),
            partial.missing,
        )
    }

    @Test
    fun `a build with no transports can still run plain bridges`() {
        // tor speaks a vanilla bridge itself, which is why it is a first-class
        // option rather than an afterthought.
        val none = PluggableTransports.Snapshot(emptyMap())
        assertEquals(listOf(BridgeTransport.VANILLA), none.available)
        assertTrue(none.supportedTorNames.isEmpty())
        assertTrue(none.has(BridgeTransport.VANILLA))
    }
}
