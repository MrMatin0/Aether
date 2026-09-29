package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import studio.cluvex.aether.core.TunnelConfig

/**
 * Smart DNS: the device resolves through a proxying resolver so geo-blocked
 * sites see the provider's country. What is pinned here is WHEN it applies,
 * WHICH servers survive parsing for each protocol, and WHAT reaches the TUN and
 * the engine - the network behaviour itself is the provider's.
 *
 * Since the DoH / DoT rework the TUN advertises one virtual resolver
 * ([TunnelConfig.SMART_DNS_RESOLVER]) that the in-process Smart DNS front
 * answers, the engine never gets a `--dns`, and the old manual "direct" switch
 * and proxy list are gone: the front picks tunnel or direct by itself.
 */
class SmartDnsTest {

    private val active = ConnectionProfile(
        smartDns = true,
        smartDnsServers = "203.0.113.53, 198.51.100.53:53",
        dnsServers = "9.9.9.9",
    )

    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    private fun ConnectionProfile.hosts(): List<String> = sanitizedSmartDns().map { it.host }

    private fun ConnectionProfile.labels(): List<String> = sanitizedSmartDns().map { it.label }

    // ---- when it applies ----

    @Test
    fun aDefaultProfileIsUntouched() {
        val p = ConnectionProfile()
        assertFalse(p.usesSmartDns)
        assertEquals(SmartDnsProtocol.PLAIN, p.smartDnsProtocol)
        assertTrue(p.sanitizedSmartDns().isEmpty())
        assertFalse("--route-direct" in p.toArgs())
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun activeSmartDnsPointsTheTunAtTheVirtualResolver() {
        assertTrue(active.usesSmartDns)
        assertEquals(listOf(TunnelConfig.SMART_DNS_RESOLVER), TunnelConfig.dnsServersFor(active))
    }

    @Test
    fun theEngineNeverGetsADnsFlag() {
        // Smart DNS resolves in the app's own front, before a request reaches
        // the engine, and the retired custom-DNS field is not applied either.
        assertFalse("--dns" in active.toArgs())
        assertFalse("--dns" in active.copy(smartDns = false).toArgs())
    }

    @Test
    fun switchedOffItFallsBackToTheDefaultResolvers() {
        val p = active.copy(smartDns = false)
        assertFalse(p.usesSmartDns)
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
        assertFalse(p.usesSmartDns, "a host name is not a plain-DNS server")
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun aListOfOnlyUnusableAddressesLeavesSmartDnsInactive() {
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "255.255.255.255, 169.254.0.1")
        assertFalse(p.usesSmartDns)
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun theProtocolDecidesWhatTheSameTextMeans() {
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "dns.example.com")
        assertFalse(p.usesSmartDns)
        assertTrue(p.copy(smartDnsProtocol = SmartDnsProtocol.DOT).usesSmartDns)
        assertTrue(p.copy(smartDnsProtocol = SmartDnsProtocol.DOH).usesSmartDns)
    }

    // ---- plain DNS ----

    @Test
    fun plainSanitisingKeepsUsableIpv4AndItsPort() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "203.0.113.53:5353, 2001:db8::53, dns.example.com, 127.0.0.1, " +
                "0.0.0.0, 198.51.100.53\n198.51.100.53;256.1.1.1\t192.0.2.1:53, udp://192.0.2.7",
        )
        // A resolver on any port works now: the front talks to it, not the TUN.
        assertEquals(
            listOf("203.0.113.53:5353", "198.51.100.53", "192.0.2.1", "192.0.2.7"),
            p.labels(),
        )
        assertTrue(p.sanitizedSmartDns().all { it.protocol == SmartDnsProtocol.PLAIN })
        assertTrue(p.sanitizedSmartDns().all { it.isIpLiteral })
    }

    @Test
    fun plainSanitisingDropsAddressesNoResolverCanHave() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "255.255.255.255, 224.0.0.251, 239.1.2.3, 240.0.0.1, " +
                "169.254.1.1, 198.51.100.53, 10.0.0.53",
        )
        // Broadcast, multicast, reserved and link-local are gone; public and
        // LAN resolvers stay.
        assertEquals(listOf("198.51.100.53", "10.0.0.53"), p.hosts())
    }

    @Test
    fun plainRejectsOtherSchemes() {
        val p = ConnectionProfile(smartDnsServers = "https://1.1.1.1/dns-query, tls://1.1.1.1, 1.1.1.1/24")
        assertTrue(p.sanitizedSmartDns().isEmpty())
    }

    // ---- DNS over TLS ----

    @Test
    fun dotAcceptsNamesAddressesAndPorts() {
        val p = ConnectionProfile(
            smartDnsProtocol = SmartDnsProtocol.DOT,
            smartDnsServers = "dns.example.com, tls://dot.example.net:8853, 1.1.1.1, " +
                "127.0.0.1, localhost, https://dns.example.org, dns.example.123",
        )
        assertEquals(
            listOf("tls://dns.example.com", "tls://dot.example.net:8853", "tls://1.1.1.1"),
            p.labels(),
        )
        assertEquals(listOf(853, 8853, 853), p.sanitizedSmartDns().map { it.port })
    }

    // ---- DNS over HTTPS ----

    @Test
    fun dohFillsInTheDefaultPathAndStripsTheTemplate() {
        val p = ConnectionProfile(
            smartDnsProtocol = SmartDnsProtocol.DOH,
            smartDnsServers = "https://dns.example.com/custom, dns.example.net, " +
                "https://doh.example.org:8443/dns-query{?dns}",
        )
        val servers = p.sanitizedSmartDns()
        assertEquals(listOf("/custom", SmartDnsServers.DEFAULT_DOH_PATH, "/dns-query"), servers.map { it.path })
        assertEquals(listOf(443, 443, 8443), servers.map { it.port })
        assertEquals("https://doh.example.org:8443/dns-query", servers[2].label)
    }

    @Test
    fun dohDropsCredentialsQueriesAndOtherSchemes() {
        val p = ConnectionProfile(
            smartDnsProtocol = SmartDnsProtocol.DOH,
            smartDnsServers = "https://user@dns.example.com/dns-query, http://dns.example.com, " +
                "https://dns.example.com/dns-query?dns=AAAB, tls://dns.example.com",
        )
        assertTrue(p.sanitizedSmartDns().isEmpty())
    }

    // ---- stored names ----

    @Test
    fun protocolNamesAreReadForgivingly() {
        assertEquals(SmartDnsProtocol.PLAIN, SmartDnsProtocol.fromStored("PLAIN"))
        assertEquals(SmartDnsProtocol.PLAIN, SmartDnsProtocol.fromStored("udp"))
        assertEquals(SmartDnsProtocol.DOH, SmartDnsProtocol.fromStored("doh"))
        assertEquals(SmartDnsProtocol.DOH, SmartDnsProtocol.fromStored("dns-over-https"))
        assertEquals(SmartDnsProtocol.DOT, SmartDnsProtocol.fromStored(" TLS "))
        assertNull(SmartDnsProtocol.fromStored("nonsense"))
        assertNull(SmartDnsProtocol.fromStored(""))
        assertNull(SmartDnsProtocol.fromStored(null))
    }

    // ---- bookkeeping ----

    @Test
    fun theListIsCapped() {
        val many = (1..20).joinToString(",") { "192.0.2.$it" }
        val p = ConnectionProfile(smartDns = true, smartDnsServers = many)
        assertEquals(SmartDnsServers.MAX_SERVERS, p.sanitizedSmartDns().size)
    }

    @Test
    fun theParsedListIsComputedOncePerInstance() {
        assertSame(active.sanitizedSmartDns(), active.sanitizedSmartDns())
        // ...and an edit, which is a copy, parses its own.
        assertEquals(listOf("192.0.2.9"), active.copy(smartDnsServers = "192.0.2.9").hosts())
    }

    @Test
    fun routeDirectIsOnlyEverTheUsersOwn() {
        // The front reaches the provider directly by itself when it has to, so
        // Smart DNS adds nothing to the engine's direct rules.
        assertNull(active.toArgs().valueOf("--route-direct"))
        val p = active.copy(routeDirect = "bank.example.ir")
        assertEquals("bank.example.ir", p.toArgs().valueOf("--route-direct"))
    }

    @Test
    fun domainRoutingRulesAreToldApartFromAddressRules() {
        assertFalse(ConnectionProfile().hasDomainRoutingRules)
        assertFalse(ConnectionProfile(routeDirect = "10.0.0.0/8, 192.0.2.1, private").hasDomainRoutingRules)
        assertTrue(ConnectionProfile(routeDirect = "bank.example.ir").hasDomainRoutingRules)
        assertTrue(ConnectionProfile(routeBlock = "keyword:ads").hasDomainRoutingRules)
    }
}
