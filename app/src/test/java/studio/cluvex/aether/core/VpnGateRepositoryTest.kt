package studio.cluvex.aether.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VpnGateRepositoryTest {

    private fun ovpn(proto: String, port: Int): String = Base64.getEncoder().encodeToString(
        "client\r\ndev tun\r\nproto $proto\r\nremote 219.100.37.1 $port\r\n".toByteArray(Charsets.UTF_8),
    )

    private fun row(
        host: String,
        ip: String,
        message: String = "",
        config: String = ovpn("tcp", 1352),
    ): String = "$host,$ip,1234567,12,98765432,Japan,JP,42,86400000,1000,123456789,2weeks,Operator,$message,$config"

    @Test
    fun skipsBannerHeaderAndFooter() {
        val servers = VpnGateRepository.parseCsv(
            sequenceOf(
                "*vpn_servers",
                "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers," +
                    "TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64",
                row("public-vpn-1", "1.2.3.4"),
                "*",
            ),
        )
        assertEquals(1, servers.size)
        assertEquals("public-vpn-1.opengw.net", servers[0].sstpHostname)
        assertEquals("JP", servers[0].countryCode)
    }

    @Test
    fun derivesSstpPortFromTheTcpListener() {
        val server = assertNotNull(VpnGateRepository.parseCsvRow(row("vpn1", "1.2.3.4")))
        assertEquals(1352, server.sstpPort)
        assertEquals(1352, server.openVpnTcpPort)
        assertNull(server.openVpnUdpPort)
    }

    @Test
    fun fallsBackTo443ForUdpOnlyRelays() {
        val server = assertNotNull(VpnGateRepository.parseCsvRow(row("vpn1", "1.2.3.4", config = ovpn("udp", 1195))))
        assertEquals(443, server.sstpPort)
        assertEquals(1195, server.openVpnUdpPort)
        assertNull(server.openVpnTcpPort)
    }

    @Test
    fun toleratesCommasInTheOperatorMessage() {
        val server = assertNotNull(VpnGateRepository.parseCsvRow(row("vpn1", "1.2.3.4", message = "hello, world")))
        assertEquals("hello, world", server.operatorMessage)
        assertEquals("Operator", server.operatorName)
        assertEquals(1352, server.sstpPort)
        assertEquals(1234567L, server.score)
    }

    @Test
    fun dropsDuplicatesAndMalformedRows() {
        val servers = VpnGateRepository.parseCsv(
            sequenceOf(
                row("vpn1", "1.2.3.4"),
                row("vpn1", "1.2.3.4"),
                "garbage,row",
                row("bad host", "1.2.3.4"),
                row("vpn2", "not-an-ip"),
            ),
        )
        assertEquals(1, servers.size)
    }
}
