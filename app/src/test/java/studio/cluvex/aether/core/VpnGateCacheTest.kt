package studio.cluvex.aether.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The on-disk relay cache: what the picker opens on instead of a download. */
class VpnGateCacheTest {

    private val row = "vpn1,1.2.3.4,100,12,1000000,Japan,JP,3,60000,10,1000,2weeks,Operator,Hello,"

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("vpngate-cache").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cachedListSurvivesARoundTrip() = withTempDir { dir ->
        val file = File(dir, "relays.csv")
        VpnGateRepository.writeCache(file, "*vpn_servers\n#HostName,IP\n$row\n*\n")

        val snapshot = assertNotNull(VpnGateRepository.readCache(file))
        val server = snapshot.servers.single()
        assertEquals("vpn1.opengw.net", server.sstpHostname)
        assertEquals("JP", server.countryCode)
        assertEquals(false, File(dir, "relays.csv.partial").exists())
    }

    @Test
    fun aNewListReplacesTheOldOne() = withTempDir { dir ->
        val file = File(dir, "relays.csv")
        VpnGateRepository.writeCache(file, "$row\n")
        VpnGateRepository.writeCache(file, row.replace("vpn1,1.2.3.4", "vpn2,5.6.7.8") + "\n")

        val snapshot = assertNotNull(VpnGateRepository.readCache(file))
        assertEquals("5.6.7.8", snapshot.servers.single().ip)
    }

    @Test
    fun missingOrUnusableCacheReadsAsNothing() = withTempDir { dir ->
        assertNull(VpnGateRepository.readCache(File(dir, "absent.csv")))
        val portal = File(dir, "portal.csv").apply { writeText("<html>captive portal</html>") }
        assertNull(VpnGateRepository.readCache(portal))
    }
}
