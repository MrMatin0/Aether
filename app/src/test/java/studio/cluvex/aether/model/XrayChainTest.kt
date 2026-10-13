package studio.cluvex.aether.model

import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.TunnelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Xray is a hop of the chain, never a mode of its own, and it always connects
 * FIRST: these pin where it sits, what dials through it, which hops have to be
 * up before the engine, and where the device's traffic enters.
 */
class XrayChainTest {

    private val xrayModes = ChainMode.entries.filter { it.usesXray }

    @Test
    fun thereIsNoXrayOnItsOwn() {
        assertEquals(5, xrayModes.size)
        xrayModes.forEach { assertTrue(it.isChained, "${it.name} runs Xray alone") }
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
    fun teardownWaitsForXraysPortsOnlyWhenXrayRuns() {
        val ports = ChainRuntime.portsFor(ChainMode.TOR_OVER_AETHER_OVER_XRAY)
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT in ports)
        assertTrue(TunnelConfig.XRAY_RELAY_PORT in ports)
        assertTrue(TunnelConfig.ENGINE_SOCKS_PORT in ports)
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.AETHER_OVER_XRAY))
        assertFalse(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.TOR_OVER_PSIPHON))
        assertFalse(TunnelConfig.XRAY_RELAY_PORT in ChainRuntime.portsFor(ChainMode.AETHER))
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
    fun everyCurrentNameRoundTrips() {
        ChainMode.entries.forEach { mode -> assertEquals(mode, ChainMode.fromStored(mode.name)) }
    }
}
