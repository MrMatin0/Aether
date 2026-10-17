package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.TunnelConfig

/**
 * SSTP is a packet tunnel, not a SOCKS hop. Everything the SOCKS chain
 * machinery derives from a mode has to say "nothing to do" for it, or the
 * session would wait on ports nobody binds and point a forwarder at nothing.
 */
class SstpChainTest {

    @Test
    fun sstpRunsAlone() {
        val mode = ChainMode.SSTP
        assertEquals(listOf(Hop.SSTP), mode.hops)
        assertEquals(Hop.SSTP, mode.entryHop)
        assertTrue(mode.usesSstp)
        assertFalse(mode.isChained)
        assertFalse(mode.usesAether || mode.usesPsiphon || mode.usesTor)
        ChainMode.entries.filter { it.isChained }.forEach { assertFalse(it.usesSstp, "${it.name} stacks SSTP") }
    }

    @Test
    fun pathLabelAndStoredNames() {
        assertEquals("SSTP \u2192 internet", ChainMode.SSTP.pathLabel())
        assertEquals(ChainMode.SSTP, ChainMode.fromStored("SSTP"))
        assertEquals(ChainMode.SSTP, ChainMode.fromStored("sstp"))
        assertEquals(ChainMode.SSTP, ChainMode.fromStored("vpngate"))
        assertEquals(ChainMode.SSTP, ChainMode.fromStored("vpn-gate"))
    }

    @Test
    fun sstpBindsNoLocalPorts() {
        assertEquals(emptyList(), ChainRuntime.portsFor(ChainMode.SSTP))
        assertFalse(ChainRuntime.needsFront(ChainMode.SSTP))
        assertEquals(TunnelConfig.ENGINE_SOCKS_PORT, ChainRuntime.entryPortFor(ChainMode.SSTP))
        // And the SOCKS modes are unchanged by the new helper.
        assertTrue(ChainRuntime.needsFront(ChainMode.TOR_OVER_AETHER))
        assertFalse(ChainRuntime.needsFront(ChainMode.AETHER))
    }

    @Test
    fun sstpNeedsNoBundledBinary() {
        val bare = CoreAvailability.Snapshot(
            psiphonBinary = false,
            torBinary = false,
            psiphonConfigBundled = false,
            torGeoipBundled = false,
        )
        assertTrue(bare.has(Hop.SSTP))
        assertTrue(bare.canRun(ChainMode.SSTP))
    }

    @Test
    fun smartDnsNeverAppliesToSstp() {
        val profile = ConnectionProfile(
            chain = ChainMode.SSTP,
            smartDns = true,
            smartDnsServers = "192.0.2.53",
        )
        assertFalse(profile.usesSmartDns)
    }

    @Test
    fun aVpnGateRelayBecomesAnUnverifiedVpnGateConfig() {
        val relay = VpnGateServer(
            sstpHostname = "public-vpn-1.opengw.net",
            sstpPort = 1352,
            countryCode = "JP",
            countryName = "Japan",
            ip = "192.0.2.10",
        )
        val tuned = SstpConfig(mtu = 1300, mru = 1350, verifyCert = true, customSni = "x.example")
        val config = relay.toSstpConfig(tuned)
        assertEquals("public-vpn-1.opengw.net", config.hostname)
        assertEquals(1352, config.port)
        assertFalse(config.verifyCert)
        assertEquals("", config.customSni)
        assertEquals(SstpSource.VPNGATE, config.source)
        assertEquals("JP", config.countryCode)
        assertEquals(1300, config.mtu, "the user's MTU must survive picking a relay")
        assertEquals(1350, config.mru)
        val private = SstpConfig(hostname = "vpn.example", password = "hunter2-secret")
        assertTrue(private.toString().contains("password=***"))
        assertFalse(private.toString().contains("hunter2-secret"))
    }

    @Test
    fun sortingByCountryPutsUnknownCountriesLast() {
        val relays = listOf(
            VpnGateServer(sstpHostname = "c.opengw.net", sstpPort = 443),
            VpnGateServer(sstpHostname = "b.opengw.net", sstpPort = 443, countryCode = "US"),
            VpnGateServer(sstpHostname = "a.opengw.net", sstpPort = 443, countryCode = "JP"),
        )
        val sorted = relays.filteredAndSorted(VpnGateFilter(), VpnGateSort.COUNTRY)
        assertEquals(listOf("JP", "US", ""), sorted.map { it.countryCode })
        val japan = relays.filteredAndSorted(VpnGateFilter(countryCode = "jp"), VpnGateSort.PING)
        assertEquals(listOf("a.opengw.net"), japan.map { it.sstpHostname })
    }
}
