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
 * sites see the provider's country. What is pinned here is WHEN it applies,
 * WHICH servers survive parsing and which transport each one is read as, and
 * WHAT reaches the TUN and the engine - the network behaviour itself is the
 * provider's.
 *
 * Since the home-screen rework the transport is detected PER ENTRY (UDP, DoH,
 * DoT mixed in one list); the stored protocol only decides what a bare entry
 * meant in a profile saved while it was a setting. Built-in presets are stored
 * as `preset:<id>` tokens and are never spelled out in a label.
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

    private fun ConnectionProfile.protocols(): List<SmartDnsProtocol> = sanitizedSmartDns().map { it.protocol }

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
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "dns.example.123, 127.0.0.1, localhost")
        assertFalse(p.usesSmartDns, "nothing in the list is a server of any transport")
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    @Test
    fun aListOfOnlyUnusableAddressesLeavesSmartDnsInactive() {
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "255.255.255.255, 169.254.0.1")
        assertFalse(p.usesSmartDns)
        assertEquals(TunnelConfig.DNS_SERVERS, TunnelConfig.dnsServersFor(p))
    }

    // ---- automatic transport ----

    @Test
    fun theTransportIsDetectedPerEntry() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "203.0.113.53, https://dns.example.com/dns-query, tls://dot.example.net, " +
                "dns.example.org, 198.51.100.9:853, doh.example.io:443, udp://192.0.2.7",
        )
        assertTrue(p.usesSmartDns)
        assertEquals(
            listOf(
                SmartDnsProtocol.PLAIN,
                SmartDnsProtocol.DOH,
                SmartDnsProtocol.DOT,
                SmartDnsProtocol.DOH,
                SmartDnsProtocol.DOT,
                SmartDnsProtocol.DOH,
                SmartDnsProtocol.PLAIN,
            ),
            p.protocols(),
        )
        assertEquals("https://dns.example.org/dns-query", p.sanitizedSmartDns()[3].label)
    }

    @Test
    fun aStoredProtocolStillDecidesWhatABareEntryMeant() {
        // Profiles saved while the protocol was a setting keep their meaning.
        val p = ConnectionProfile(smartDns = true, smartDnsServers = "dns.example.com, 1.1.1.1")
        assertEquals(listOf(SmartDnsProtocol.DOH, SmartDnsProtocol.PLAIN), p.protocols())
        assertEquals(
            listOf(SmartDnsProtocol.DOT, SmartDnsProtocol.DOT),
            p.copy(smartDnsProtocol = SmartDnsProtocol.DOT).protocols(),
        )
        assertEquals(
            listOf(SmartDnsProtocol.DOH, SmartDnsProtocol.DOH),
            p.copy(smartDnsProtocol = SmartDnsProtocol.DOH).protocols(),
        )
    }

    @Test
    fun schemesPickTheTransportAndUnknownOnesAreDropped() {
        val p = ConnectionProfile(
            smartDnsServers = "https://1.1.1.1/dns-query, tls://1.1.1.1, 1.1.1.0/24, " +
                "http://dns.example.com, ftp://dns.example.com",
        )
        assertEquals(listOf("https://1.1.1.1/dns-query", "tls://1.1.1.1"), p.labels())
    }

    // ---- plain DNS ----

    @Test
    fun plainSanitisingKeepsUsableIpv4AndItsPort() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "203.0.113.53:5353, 2001:db8::53, 127.0.0.1, " +
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

    // ---- DNS over TLS ----

    @Test
    fun dotAcceptsNamesAddressesAndPorts() {
        val p = ConnectionProfile(
            smartDnsProtocol = SmartDnsProtocol.DOT,
            smartDnsServers = "dns.example.com, tls://dot.example.net:8853, 1.1.1.1, " +
                "127.0.0.1, localhost, https://dns.example.org, dns.example.123",
        )
        // The https:// entry is DoH whatever the stored hint says.
        assertEquals(
            listOf(
                "tls://dns.example.com",
                "tls://dot.example.net:8853",
                "tls://1.1.1.1",
                "https://dns.example.org/dns-query",
            ),
            p.labels(),
        )
        assertEquals(listOf(853, 8853, 853, 443), p.sanitizedSmartDns().map { it.port })
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
                "https://dns.example.com/dns-query?dns=AAAB",
        )
        assertTrue(p.sanitizedSmartDns().isEmpty())
    }

    // ---- built-in presets ----

    @Test
    fun everyBuiltInPresetUnsealsToAServer() {
        assertEquals(19, SmartDnsPresets.ALL.size)
        assertEquals(SmartDnsPresets.ALL.size, SmartDnsPresets.ALL.map { it.id }.toSet().size)
        SmartDnsPresets.ALL.forEach { preset ->
            val server = assertNotNull(preset.server, preset.id)
            assertEquals(preset.protocol, server.protocol, preset.id)
            assertEquals(preset.token, server.label, "a preset is shown by its token, never its address")
        }
        assertEquals(5, SmartDnsPresets.ALL.count { it.group == SmartDnsPresetGroup.ENCRYPTED })
        assertEquals(14, SmartDnsPresets.ALL.count { it.group == SmartDnsPresetGroup.CLASSIC })
    }

    @Test
    fun theRecommendedPresetComesFirst() {
        val first = SmartDnsPresets.ALL.first()
        assertSame(first, SmartDnsPresets.DEFAULT)
        assertEquals("geohide-eu", first.id)
        assertTrue(first.recommended)
        assertEquals(SmartDnsProtocol.DOH, first.protocol)
        assertEquals(1, SmartDnsPresets.ALL.count { it.recommended })
    }

    @Test
    fun presetTokensResolveAndMixWithTypedServers() {
        val p = ConnectionProfile(
            smartDns = true,
            smartDnsServers = "preset:geohide-eu, preset:udp-01, 203.0.113.53, preset:nope",
        )
        assertTrue(p.usesSmartDns)
        assertEquals(listOf("preset:geohide-eu", "preset:udp-01", "203.0.113.53"), p.labels())
        assertEquals(
            listOf(SmartDnsProtocol.DOH, SmartDnsProtocol.PLAIN, SmartDnsProtocol.PLAIN),
            p.protocols(),
        )
    }

    @Test
    fun theSelectionRoundTripsThroughTheStoredText() {
        val stored = SmartDnsPresets.compose(listOf("geohide-eu", "xbox-dns"), "203.0.113.53, tls://dns.example.net")
        assertEquals(listOf("geohide-eu", "xbox-dns"), SmartDnsPresets.selectedIds(stored))
        assertEquals("203.0.113.53, tls://dns.example.net", SmartDnsPresets.customPart(stored, SmartDnsProtocol.PLAIN))
        assertEquals(listOf("geohide-eu"), SmartDnsPresets.toggle(listOf("geohide-eu", "xbox-dns"), "xbox-dns"))
        assertEquals(listOf("geohide-eu", "udp-02"), SmartDnsPresets.toggle(listOf("geohide-eu"), "udp-02"))
        assertEquals("", SmartDnsPresets.compose(emptyList(), "  "))
        assertNull(SmartDnsPresets.fromToken("geohide-eu"))
    }

    @Test
    fun aLegacyProtocolIsWrittenOutWhenTheListIsEdited() {
        assertEquals(
            "tls://dns.example.com, tls://1.1.1.1",
            SmartDnsPresets.customPart("dns.example.com, 1.1.1.1", SmartDnsProtocol.DOT),
        )
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
