package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        assertTrue(p.sanitizedSmartDnsProxies().isEmpty())
        assertFalse("--route-direct" in p.toArgs())
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun activeSmartDnsDrivesTheEngineResolverToo() {
        assertTrue(active.usesSmartDns)
        assertTrue(active.engineUsesSmartDns)
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
    fun sanitisingDropsAddressesNoResolverCanHave() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "255.255.255.255, 224.0.0.251, 239.1.2.3, 240.0.0.1, " +
                "169.254.1.1, 198.51.100.53, 10.0.0.53",
        )
        // Broadcast, multicast, reserved and link-local are gone; public and
        // LAN resolvers stay.
        assertEquals(listOf("198.51.100.53", "10.0.0.53"), p.sanitizedSmartDns())
    }

    @Test
    fun aListOfOnlyUnusableAddressesLeavesSmartDnsInactive() {
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "255.255.255.255, 169.254.0.1")
        assertFalse(p.usesSmartDns)
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun theListIsCapped() {
        val many = (1..20).joinToString(",") { "192.0.2.$it" }
        val p = ConnectionProfile(smartDns = true, smartDnsServers = many)
        assertEquals(ConnectionProfile.MAX_DNS_SERVERS, p.sanitizedSmartDns().size)
    }

    @Test
    fun theParsedListsAreComputedOncePerInstance() {
        assertSame(active.sanitizedSmartDns(), active.sanitizedSmartDns())
        assertSame(active.sanitizedSmartDnsProxies(), active.sanitizedSmartDnsProxies())
        // ...and an edit, which is a copy, parses its own.
        assertEquals(listOf("192.0.2.9"), active.copy(smartDnsServers = "192.0.2.9").sanitizedSmartDns())
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

    // ---- direct mode ----

    @Test
    fun directModePutsTheResolversInFrontOfTheUsersRules() {
        val p = active.copy(smartDnsDirect = true, routeDirect = "bank.example.ir")
        assertEquals(
            "203.0.113.53,198.51.100.53,bank.example.ir",
            p.toArgs().valueOf("--route-direct"),
        )
    }

    @Test
    fun directModeAlsoSendsTheProviderProxiesDirect() {
        val p = active.copy(smartDnsDirect = true, smartDnsProxies = "192.0.2.10, 198.51.100.0/24")
        assertEquals(
            "203.0.113.53,198.51.100.53,192.0.2.10,198.51.100.0/24",
            p.toArgs().valueOf("--route-direct"),
        )
    }

    @Test
    fun proxiesStayInTheTunnelWithoutDirectMode() {
        val p = active.copy(smartDnsProxies = "192.0.2.10")
        assertNull(p.toArgs().valueOf("--route-direct"))
    }

    @Test
    fun proxySanitisingKeepsOnlyNarrowUnicastIpv4() {
        val p = ConnectionProfile(
            smartDnsProxies = "192.0.2.10, 198.51.100.0/24, 10.0.0.0/8, proxy.example.com, " +
                "2001:db8::1, 224.0.0.1, 169.254.0.0/16, 192.0.2.10, 203.0.113.0/33, 0.0.0.0/0",
        )
        assertEquals(listOf("192.0.2.10", "198.51.100.0/24"), p.sanitizedSmartDnsProxies())
    }

    @Test
    fun theSmartDnsRulesSurviveAUserListAtTheCap() {
        val full = (1..ConnectionProfile.MAX_ROUTE_RULES).joinToString(",") { "site$it.example" }
        val p = active.copy(smartDnsDirect = true, routeDirect = full)
        val rules = p.routeDirectRules()
        assertEquals(ConnectionProfile.MAX_ROUTE_RULES, rules.size)
        assertEquals(listOf("203.0.113.53", "198.51.100.53"), rules.take(2))
        // What made room for them is counted, so it can be logged.
        assertEquals(2, p.droppedRouteDirectRules())
    }

    @Test
    fun nothingIsDroppedBelowTheCap() {
        val p = active.copy(smartDnsDirect = true, routeDirect = "bank.example.ir")
        assertEquals(0, p.droppedRouteDirectRules())
        assertEquals(0, ConnectionProfile().droppedRouteDirectRules())
    }

    @Test
    fun directModeKeepsTheEngineOnTheOrdinaryResolvers() {
        // The engine resolves through the tunnel, where a provider that only
        // answers Iranian sources is silent; a Smart DNS --dns would fail every
        // socks5h lookup and the self-test with it.
        val p = active.copy(smartDnsDirect = true)
        assertTrue(p.usesSmartDns)
        assertFalse(p.engineUsesSmartDns)
        assertEquals("9.9.9.9", p.toArgs().valueOf("--dns"))
        // The device itself still resolves through the Smart DNS servers.
        assertEquals(listOf("203.0.113.53", "198.51.100.53"), TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun directRulesAreBareAddressesTheUdpRelayCanMatch() {
        // hev relays the device's DNS queries as UDP datagrams to the engine's
        // UDP ASSOCIATE, which runs routes().decide(Host::Ip(dst), port) on
        // every one (socks.rs, handle_udp_associate); routing.rs reads a bare
        // address as a /32 and a CIDR as a net. So each emitted rule must be
        // exactly that - no port, no scheme, no name - or the queries would
        // never match and direct mode would do nothing.
        val p = active.copy(smartDnsDirect = true, smartDnsProxies = "198.51.100.0/24")
        val rules = assertNotNull(p.toArgs().valueOf("--route-direct")).split(",")
        val bare = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?$")
        rules.forEach { assertTrue(bare.matches(it), "not a bare address rule: $it") }
    }

    @Test
    fun withoutDirectModeRouteDirectIsTheUsersOwn() {
        val p = active.copy(routeDirect = "bank.example.ir")
        assertEquals("bank.example.ir", p.toArgs().valueOf("--route-direct"))
        assertNull(active.toArgs().valueOf("--route-direct"))
    }

    @Test
    fun directModeDoesNothingWhileSmartDnsIsInactive() {
        val p = active.copy(smartDns = false, smartDnsDirect = true, smartDnsProxies = "192.0.2.10")
        assertFalse("--route-direct" in p.toArgs())
    }
}
