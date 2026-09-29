package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.cluvex.aether.core.TunnelConfig

/**
 * Smart DNS: the device resolves through a proxying resolver so geo-blocked
 * sites see the provider's country. What is pinned here is WHEN it applies and
 * WHAT it emits - the network behaviour itself is the provider's.
 */
class SmartDnsTest {

    private val active = ConnectionProfile(
        smartDns = true,
        smartDnsServers = "203.0.113.53, 198.51.100.53:53",
        dnsServers = "9.9.9.9",
    )

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    @Test
    fun aDefaultProfileIsUntouched() {
        val p = ConnectionProfile()
        assertFalse(p.usesSmartDns)
        assertTrue(p.sanitizedSmartDns().isEmpty())
        assertFalse("--route-direct" in p.toArgs())
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun activeSmartDnsDrivesTheEngineResolverToo() {
        assertTrue(active.usesSmartDns)
        // Overrides dnsServers, so the engine's own lookups agree with the device's.
        assertEquals("203.0.113.53,198.51.100.53", active.toArgs().valueOf("--dns"))
    }

    @Test
    fun theTunAdvertisesTheSmartServers() {
        assertEquals(listOf("203.0.113.53", "198.51.100.53"), TunnelConfig.dnsServersFor(active))
    }

    @Test
    fun sanitisingKeepsOnlyPlainIpv4OnPort53() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "203.0.113.53:5353, 2001:db8::53, dns.example.com, 127.0.0.1, " +
                "0.0.0.0, 198.51.100.53\n198.51.100.53;256.1.1.1\t192.0.2.1:53",
        )
        assertEquals(listOf("198.51.100.53", "192.0.2.1"), p.sanitizedSmartDns())
    }

    @Test
    fun theListIsCapped() {
        val many = (1..20).joinToString(",") { "192.0.2.$it" }
        val p = ConnectionProfile(smartDns = true, smartDnsServers = many)
        assertEquals(ConnectionProfile.MAX_DNS_SERVERS, p.sanitizedSmartDns().size)
    }

    @Test
    fun switchedOffItFallsBackToTheOrdinaryDnsSetting() {
        val p = active.copy(smartDns = false)
        assertFalse(p.usesSmartDns)
        assertEquals("9.9.9.9", p.toArgs().valueOf("--dns"))
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun itOnlyAppliesToTheAetherOnlyChain() {
        val p = active.copy(chain = ChainMode.TOR_OVER_PSIPHON_OVER_AETHER)
        assertFalse(p.usesSmartDns)
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun noValidServerNeverLeavesTheDeviceWithoutAResolver() {
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "dns.example.com")
        assertFalse(p.usesSmartDns)
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun directModeAppendsTheResolversToRouteDirect() {
        val p = active.copy(smartDnsDirect = true, routeDirect = "bank.example.ir")
        assertEquals(
            "bank.example.ir,203.0.113.53,198.51.100.53",
            p.toArgs().valueOf("--route-direct"),
        )
    }

    @Test
    fun withoutDirectModeRouteDirectIsTheUsersOwn() {
        val p = active.copy(routeDirect = "bank.example.ir")
        assertEquals("bank.example.ir", p.toArgs().valueOf("--route-direct"))
        assertNull(active.toArgs().valueOf("--route-direct"))
    }

    @Test
    fun directModeDoesNothingWhileSmartDnsIsInactive() {
        val p = active.copy(smartDns = false, smartDnsDirect = true)
        assertFalse("--route-direct" in p.toArgs())
    }
}
