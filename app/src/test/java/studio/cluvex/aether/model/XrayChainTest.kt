package studio.cluvex.aether.model

import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.TunnelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Xray always connects FIRST: stacked, it sits under every other core, and in
 * [ChainMode.XRAY] it runs alone. These pin where it sits, what dials through
 * it, which hops have to be up before the engine, and where the device's
 * traffic enters.
 */
class XrayChainTest {

    private val xrayModes = ChainMode.entries.filter { it.usesXray }

    @Test
    fun xrayRunsAloneInExactlyOneMode() {
        assertEquals(6, xrayModes.size)
        assertEquals(listOf(ChainMode.XRAY), xrayModes.filterNot { it.isChained })
        assertEquals(listOf(Hop.XRAY), ChainMode.XRAY.hops)
        assertEquals(Hop.XRAY, ChainMode.XRAY.entryHop)
        assertFalse(ChainMode.XRAY.usesAether)
        assertFalse(ChainMode.XRAY.usesPsiphon || ChainMode.XRAY.usesTor || ChainMode.XRAY.usesSstp)
        assertEquals("Xray \u2192 internet", ChainMode.XRAY.pathLabel())
    }

    @Test
    fun xrayIsAlwaysTheInternetFacingHop() {
        xrayModes.forEach { mode ->
            assertEquals(Hop.XRAY, mode.hops.first(), "${mode.name} must connect Xray first")
            assertNull(mode.upstreamOf(Hop.XRAY), "${mode.name}: Xray dials the user's server itself")
        }
    }

    @Test
    fun everythingAboveXrayDialsThroughIt() {
        assertEquals(Hop.XRAY, ChainMode.AETHER_OVER_XRAY.upstreamOf(Hop.AETHER))
        assertEquals(Hop.AETHER, ChainMode.AETHER_OVER_XRAY.entryHop)
        assertEquals(Hop.XRAY, ChainMode.PSIPHON_OVER_XRAY.upstreamOf(Hop.PSIPHON))
        assertEquals(Hop.XRAY, ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY.upstreamOf(Hop.AETHER))
        assertEquals(Hop.AETHER, ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY.upstreamOf(Hop.PSIPHON))
        assertEquals(Hop.XRAY, ChainMode.TOR_OVER_XRAY.upstreamOf(Hop.TOR))
        assertEquals(Hop.XRAY, ChainMode.TOR_OVER_AETHER_OVER_XRAY.upstreamOf(Hop.AETHER))
        assertEquals(Hop.AETHER, ChainMode.TOR_OVER_AETHER_OVER_XRAY.upstreamOf(Hop.TOR))
        assertEquals(
            "Tor \u2192 Aether \u2192 Xray \u2192 internet",
            ChainMode.TOR_OVER_AETHER_OVER_XRAY.pathLabel(),
        )
    }

    @Test
    fun onlyTheHopsUnderTheEngineStartBeforeIt() {
        assertEquals(listOf(Hop.XRAY), ChainMode.AETHER_OVER_XRAY.hopsBeforeEngine)
        assertEquals(listOf(Hop.XRAY), ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY.hopsBeforeEngine)
        assertEquals(listOf(Hop.XRAY), ChainMode.TOR_OVER_AETHER_OVER_XRAY.hopsBeforeEngine)
        // No engine at all: the chain starts in hop order, nothing goes first.
        assertTrue(ChainMode.PSIPHON_OVER_XRAY.hopsBeforeEngine.isEmpty())
        assertTrue(ChainMode.TOR_OVER_XRAY.hopsBeforeEngine.isEmpty())
        assertTrue(ChainMode.XRAY.hopsBeforeEngine.isEmpty())
        ChainMode.entries.filterNot { it.usesXray }.forEach { mode ->
            assertTrue(mode.hopsBeforeEngine.isEmpty(), "${mode.name} has nothing under the engine")
        }
    }

    @Test
    fun theEntryIsWhateverSitsOnTop() {
        assertEquals(TunnelConfig.ENGINE_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.AETHER_OVER_XRAY))
        assertFalse(ChainRuntime.needsFront(ChainMode.AETHER_OVER_XRAY))
        assertEquals(TunnelConfig.FRONT_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.PSIPHON_OVER_XRAY))
        assertTrue(ChainRuntime.needsFront(ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY))
        assertTrue(ChainRuntime.needsFront(ChainMode.TOR_OVER_XRAY))
        assertEquals(TunnelConfig.FRONT_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.TOR_OVER_AETHER_OVER_XRAY))
    }

    @Test
    fun xrayAloneIsItsOwnEntryWithNoFront() {
        // Xray's inbound speaks UDP ASSOCIATE and answers port-53 UDP itself.
        assertEquals(TunnelConfig.XRAY_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.XRAY))
        assertFalse(ChainRuntime.needsFront(ChainMode.XRAY))
    }

    @Test
    fun teardownWaitsForXraysPortsOnlyWhenXrayRuns() {
        val ports = ChainRuntime.portsFor(ChainMode.TOR_OVER_AETHER_OVER_XRAY)
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT in ports)
        assertTrue(TunnelConfig.XRAY_RELAY_PORT in ports)
        assertTrue(TunnelConfig.ENGINE_SOCKS_PORT in ports)
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.AETHER_OVER_XRAY))
        assertFalse(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.TOR_OVER_PSIPHON))
        assertFalse(TunnelConfig.XRAY_RELAY_PORT in ChainRuntime.portsFor(ChainMode.AETHER))

        val alone = ChainRuntime.portsFor(ChainMode.XRAY)
        assertEquals(listOf(TunnelConfig.XRAY_SOCKS_PORT, TunnelConfig.XRAY_RELAY_PORT), alone)
        assertFalse(TunnelConfig.ENGINE_SOCKS_PORT in alone)
        assertFalse(TunnelConfig.FRONT_SOCKS_PORT in alone)
    }

    @Test
    fun xraysPortsCollideWithNoOtherListener() {
        val others = listOf(
            TunnelConfig.ENGINE_SOCKS_PORT,
            TunnelConfig.FRONT_SOCKS_PORT,
            TunnelConfig.SMART_DNS_FRONT_PORT,
            TunnelConfig.PSIPHON_SOCKS_PORT,
            TunnelConfig.TOR_SOCKS_PORT,
            TunnelConfig.TOR_DNS_PORT,
            10_808, 10_809, 10_810, 10_811,
        )
        assertFalse(TunnelConfig.XRAY_SOCKS_PORT in others)
        assertFalse(TunnelConfig.XRAY_RELAY_PORT in others)
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT != TunnelConfig.XRAY_RELAY_PORT)
    }

    @Test
    fun savedXrayOnTopProfilesMoveToTheXrayFirstModes() {
        assertEquals(ChainMode.AETHER_OVER_XRAY, ChainMode.fromStored("XRAY_OVER_AETHER"))
        assertEquals(ChainMode.PSIPHON_OVER_XRAY, ChainMode.fromStored("XRAY_OVER_PSIPHON"))
        assertEquals(ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY, ChainMode.fromStored("XRAY_OVER_PSIPHON_OVER_AETHER"))
        assertEquals(ChainMode.TOR_OVER_AETHER_OVER_XRAY, ChainMode.fromStored("TOR_OVER_XRAY_OVER_AETHER"))
        assertEquals(ChainMode.TOR_OVER_XRAY, ChainMode.fromStored("TOR_OVER_XRAY"))
    }

    @Test
    fun handWrittenXrayNamesAreUnderstood() {
        assertEquals(ChainMode.AETHER_OVER_XRAY, ChainMode.fromStored("aether+xray"))
        assertEquals(ChainMode.PSIPHON_OVER_XRAY, ChainMode.fromStored("psiphon-xray"))
        assertEquals(ChainMode.TOR_OVER_XRAY, ChainMode.fromStored("xray-tor"))
        assertEquals(ChainMode.TOR_OVER_AETHER_OVER_XRAY, ChainMode.fromStored("aether+xray+tor"))
        assertEquals(ChainMode.TOR_OVER_AETHER_OVER_XRAY, ChainMode.fromStored("xray+aether+tor"))
        assertEquals(ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY, ChainMode.fromStored("xray-aether-psiphon"))
    }

    @Test
    fun handWrittenXrayAloneNamesAreUnderstood() {
        assertEquals(ChainMode.XRAY, ChainMode.fromStored("xray"))
        assertEquals(ChainMode.XRAY, ChainMode.fromStored("xray-only"))
        assertEquals(ChainMode.XRAY, ChainMode.fromStored("XRAY_ONLY"))
        assertEquals(ChainMode.XRAY, ChainMode.fromStored("pure-xray"))
        assertEquals(ChainMode.XRAY, ChainMode.fromStored("v2ray"))
    }

    @Test
    fun everyCurrentNameRoundTrips() {
        ChainMode.entries.forEach { mode -> assertEquals(mode, ChainMode.fromStored(mode.name)) }
    }
}
