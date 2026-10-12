package studio.cluvex.aether.model

import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.TunnelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Xray is a hop of the chain, never a mode of its own: these pin where it sits,
 * what it dials through, and where the device's traffic enters.
 */
class XrayChainTest {

    private val xrayModes = ChainMode.entries.filter { it.usesXray }

    @Test
    fun thereIsNoXrayOnItsOwn() {
        assertEquals(5, xrayModes.size)
        xrayModes.forEach { assertTrue(it.isChained, "${it.name} runs Xray alone") }
    }

    @Test
    fun xraysHandshakeIsDialledThroughTheHopUnderneath() {
        assertEquals(Hop.AETHER, ChainMode.XRAY_OVER_AETHER.upstreamOf(Hop.XRAY))
        assertEquals(Hop.XRAY, ChainMode.XRAY_OVER_AETHER.entryHop)
        assertEquals(Hop.PSIPHON, ChainMode.XRAY_OVER_PSIPHON.upstreamOf(Hop.XRAY))
        assertEquals(Hop.PSIPHON, ChainMode.XRAY_OVER_PSIPHON_OVER_AETHER.upstreamOf(Hop.XRAY))
        assertEquals(Hop.AETHER, ChainMode.XRAY_OVER_PSIPHON_OVER_AETHER.upstreamOf(Hop.PSIPHON))
        assertEquals(Hop.XRAY, ChainMode.TOR_OVER_XRAY_OVER_AETHER.upstreamOf(Hop.TOR))
        assertEquals(Hop.AETHER, ChainMode.TOR_OVER_XRAY_OVER_AETHER.upstreamOf(Hop.XRAY))
        // Tor over Xray: Xray is the internet-facing hop and dials its server itself.
        assertNull(ChainMode.TOR_OVER_XRAY.upstreamOf(Hop.XRAY))
        assertEquals(
            "Tor \u2192 Xray \u2192 Aether \u2192 internet",
            ChainMode.TOR_OVER_XRAY_OVER_AETHER.pathLabel(),
        )
    }

    @Test
    fun anXrayEntryNeedsNoDnsFrontButTorOverXrayDoes() {
        assertEquals(TunnelConfig.XRAY_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.XRAY_OVER_AETHER))
        assertEquals(TunnelConfig.XRAY_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.XRAY_OVER_PSIPHON))
        assertFalse(ChainRuntime.needsFront(ChainMode.XRAY_OVER_PSIPHON_OVER_AETHER))
        assertTrue(ChainRuntime.needsFront(ChainMode.TOR_OVER_XRAY))
        assertEquals(TunnelConfig.FRONT_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.TOR_OVER_XRAY))
    }

    @Test
    fun teardownWaitsForXraysPortOnlyWhenXrayRuns() {
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.TOR_OVER_XRAY_OVER_AETHER))
        assertTrue(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.XRAY_OVER_AETHER))
        assertFalse(TunnelConfig.XRAY_SOCKS_PORT in ChainRuntime.portsFor(ChainMode.TOR_OVER_PSIPHON))
    }

    @Test
    fun xraysPortCollidesWithNoOtherListener() {
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
    }

    @Test
    fun handWrittenXrayNamesAreUnderstood() {
        assertEquals(ChainMode.XRAY_OVER_AETHER, ChainMode.fromStored("aether+xray"))
        assertEquals(ChainMode.XRAY_OVER_PSIPHON, ChainMode.fromStored("psiphon-xray"))
        assertEquals(ChainMode.TOR_OVER_XRAY, ChainMode.fromStored("xray-tor"))
        assertEquals(ChainMode.TOR_OVER_XRAY_OVER_AETHER, ChainMode.fromStored("aether+xray+tor"))
        assertEquals(ChainMode.XRAY_OVER_PSIPHON_OVER_AETHER, ChainMode.fromStored("XRAY_OVER_PSIPHON_OVER_AETHER"))
    }
}
