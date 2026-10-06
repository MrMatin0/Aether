package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import studio.cluvex.aether.model.PsiphonProtocol

class PsiphonCdnFrontingTest {

    @Test
    fun autoRestrictsNothingAndKeepsTactics() {
        val plan = PsiphonCdnFronting.plan(PsiphonProtocol.AUTO, "", "", viaUpstream = false)
        assertNull(plan.limitTunnelProtocols)
        assertFalse(plan.disableTactics)
        assertNull(plan.scanSpec, "no custom edges, no scan spec")
        assertTrue(plan.useBuiltInScanSpec)
        assertEquals(1.0, plan.dialOverridesProbability)
        assertTrue(plan.dialOverrides.isNotEmpty(), "the edge overrides are offered in every mode")
    }

    @Test
    fun cdnFrontingUsesOnlyTheCdnProtocols() {
        val plan = PsiphonCdnFronting.plan(PsiphonProtocol.CDN_FRONTING, "", "", viaUpstream = false)
        assertEquals(PsiphonCdnFronting.CDN_PROTOCOLS, plan.limitTunnelProtocols)
        assertTrue(plan.disableTactics)
        assertTrue(plan.limitTunnelProtocols!!.all { it.startsWith("FRONTED-MEEK-CDN-") })
    }

    @Test
    fun directUsesTheOrdinarySetIncludingCdnProtocols() {
        val plan = PsiphonCdnFronting.plan(PsiphonProtocol.DIRECT, "", "", viaUpstream = false)
        assertEquals(PsiphonCdnFronting.DIRECT_PROTOCOLS, plan.limitTunnelProtocols)
        assertTrue(plan.disableTactics)
        assertTrue("FRONTED-MEEK-CDN-OSSH" in plan.limitTunnelProtocols!!)
    }

    @Test
    fun quicIsDroppedThroughAnUpstreamSocksHop() {
        val cdn = PsiphonCdnFronting.plan(PsiphonProtocol.CDN_FRONTING, "", "", viaUpstream = true)
        assertEquals(listOf("FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH"), cdn.limitTunnelProtocols)
        val direct = PsiphonCdnFronting.plan(PsiphonProtocol.DIRECT, "", "", viaUpstream = true)
        assertFalse(direct.limitTunnelProtocols!!.any { it.contains("QUIC") })
        assertEquals(PsiphonCdnFronting.DIRECT_PROTOCOLS.size - 3, direct.limitTunnelProtocols!!.size)
        assertNull(PsiphonCdnFronting.plan(PsiphonProtocol.AUTO, "", "", viaUpstream = true).limitTunnelProtocols)
    }

    @Test
    fun fastlyComesFirstThenEveryAkamaiEdge() {
        val overrides = PsiphonCdnFronting.dialOverrides(null)
        assertEquals(listOf("fastly-provider", "fastly-address"), overrides.take(2).map { it.id })
        assertEquals(PsiphonCdnFronting.AKAMAI_EDGES.map { it.first }, overrides.drop(2).map { it.id })
        assertTrue(overrides.all { it.tlsProfile == "Chrome-83" })
        assertEquals(listOf("h2", "http/1.1"), overrides[0].alpnProtocols)
        assertEquals(listOf("http/1.1"), overrides[2].alpnProtocols)
    }

    @Test
    fun edgeSniDefaultsToTheIpAndFollowsTheFirstCustomSni() {
        val bare = PsiphonCdnFronting.dialOverrides(null)[2]
        assertEquals("23.215.0.206", bare.sniServerName)
        assertEquals(listOf("23.215.0.206"), bare.dialAddresses)
        assertEquals("23.215.0.206", bare.verifyServerNames.first())
        assertEquals(bare.verifyServerNames.distinct(), bare.verifyServerNames, "no duplicates")

        val plan = PsiphonCdnFronting.plan(PsiphonProtocol.CDN_FRONTING, "", "www.example.com, cdn.example.net", false)
        val edge = plan.dialOverrides[2]
        assertEquals("www.example.com", edge.sniServerName)
        assertEquals(listOf("www.example.com", "23.215.0.206"), edge.verifyServerNames.take(2))
        assertTrue("www.akamai.com" in edge.verifyServerNames)
    }

    @Test
    fun defaultOverridesAreBuiltOnceAndBlankSniMeansNone() {
        assertSame(PsiphonCdnFronting.dialOverrides(null), PsiphonCdnFronting.dialOverrides(null))
        assertSame(PsiphonCdnFronting.dialOverrides(null), PsiphonCdnFronting.dialOverrides("  "))
        // A custom SNI that is also an Akamai verify name must not repeat.
        val edge = PsiphonCdnFronting.dialOverrides("www.akamai.com")[2]
        assertEquals(edge.verifyServerNames.distinct(), edge.verifyServerNames)
    }

    @Test
    fun customEdgesBecomeAScanSpec() {
        val plan = PsiphonCdnFronting.plan(
            PsiphonProtocol.CDN_FRONTING,
            "23.215.0.206\n104.16.0.0/24; garbage 999.1.1.1 23.215.0.206",
            "www.example.com",
            viaUpstream = false,
        )
        val spec = assertNotNull(plan.scanSpec)
        assertEquals(listOf("23.215.0.206", "104.16.0.0/24"), spec.ipCandidates)
        assertEquals(listOf("www.example.com"), spec.sniServerNames)
    }

    @Test
    fun ipParserRejectsWhatTheCoreWould() {
        val ok = listOf("1.2.3.4", "10.0.0.0/8", "23.12.147.13/32", "0.0.0.0")
        val bad = listOf("1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.4/7", "1.2.3.4/33",
            "1.2.3.4/", "1.2.3.4/a", "::1", "example.com", "1.2.3.4/24/1", "1234.1.1.1",
            "1.2.3.4/08", "۱.۲.۳.۴")
        ok.forEach { assertTrue(PsiphonCdnFronting.isIpv4OrCidr(it), it) }
        bad.forEach { assertFalse(PsiphonCdnFronting.isIpv4OrCidr(it), it) }
    }

    @Test
    fun ipListIsCapped() {
        val many = (1..200).joinToString(",") { "10.0.${it / 250}.${it % 250}" }
        assertEquals(PsiphonCdnFronting.MAX_IP_CANDIDATES, PsiphonCdnFronting.parseIpCandidates(many).size)
    }

    @Test
    fun persianKeyboardAndPastedTextAreUnderstood() {
        // Persian digits, Persian comma / semicolon, RLM / LRM from a chat paste, NBSP.
        assertEquals(
            listOf("23.215.0.206", "104.16.0.0/24", "92.123.102.43", "1.2.3.4", "5.6.7.8"),
            PsiphonCdnFronting.parseIpCandidates(
                "۲۳.۲۱۵.۰.۲۰۶، 104.16.0.0/24؛\u200F92.123.102.43\u200E 1.2.3.4\u00A05.6.7.8",
            ),
        )
        assertEquals(
            listOf("cdn.example.com", "www.example.org"),
            PsiphonCdnFronting.parseSniList("\u200FCDN.Example.com\u200E،www.example.org"),
        )
    }

    @Test
    fun sniParserNormalisesAndRejectsIps() {
        assertEquals(
            listOf("www.example.com", "a-b.example.org"),
            PsiphonCdnFronting.parseSniList("WWW.Example.COM. , a-b.example.org 1.2.3.4 localhost -bad.example.com bad_.example.com www.example.com"),
        )
        assertTrue(PsiphonCdnFronting.parseSniList("").isEmpty())
    }
}
