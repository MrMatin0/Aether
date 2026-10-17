package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VpnGateRepositoryTest {

    @Test
    fun parsesHostsWithAndWithoutAPort() {
        val servers = VpnGateRepository.parseHostList(
            sequenceOf(
                "public-vpn-100.opengw.net",
                "vpn113368656.opengw.net:1956",
                "laud.opengw.net:443",
            ),
        )
        assertEquals(3, servers.size)
        assertEquals("public-vpn-100.opengw.net", servers[0].sstpHostname)
        assertEquals(443, servers[0].sstpPort)
        assertEquals(1956, servers[1].sstpPort)
        assertEquals("", servers[0].countryCode, "the raw list carries no country")
    }

    @Test
    fun aBareHostNameGetsTheSoftEtherDomain() {
        val server = assertNotNull(VpnGateRepository.parseHostLine("public-vpn-1"))
        assertEquals("public-vpn-1.opengw.net", server.sstpHostname)
        assertEquals(443, server.sstpPort)
    }

    @Test
    fun dropsDuplicatesCommentsAndMalformedLines() {
        val servers = VpnGateRepository.parseHostList(
            sequenceOf(
                "# comment",
                "",
                "vpn1.opengw.net:443",
                "VPN1.opengw.net:443",
                "vpn1.opengw.net",
                "vpn1.opengw.net:1194",
                "_unregistered_vpn1.opengw.net:443",
                "bad host.opengw.net",
                "vpn2.opengw.net:notaport",
                "vpn3.opengw.net:70000",
                "<html>captive portal</html>",
            ),
        )
        // vpn1:443 once (case and the implicit 443 fold into it), vpn1:1194 is its own relay.
        assertEquals(listOf("vpn1.opengw.net:443", "vpn1.opengw.net:1194"), servers.map { it.id })
    }

    @Test
    fun readsCountriesFromTheGeoBatch() {
        val body = """
            [{"status":"success","country":"Japan","countryCode":"JP","query":"219.100.37.1"},
             {"status":"fail","message":"private range","query":"10.0.0.1"},
             {"status":"success","country":"Korea","countryCode":"kr","query":"1.2.3.4"},
             {"status":"success","country":"Nowhere","countryCode":"XYZ","query":"5.6.7.8"}]
        """.trimIndent()
        val countries = VpnGateRepository.parseGeoBatch(body)
        assertEquals(VpnGateRepository.GeoCountry("JP", "Japan"), countries["219.100.37.1"])
        assertEquals("KR", countries["1.2.3.4"]?.code)
        assertNull(countries["10.0.0.1"])
        assertNull(countries["5.6.7.8"])
    }
}
