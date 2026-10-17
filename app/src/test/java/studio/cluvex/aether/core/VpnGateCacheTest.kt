package studio.cluvex.aether.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import studio.cluvex.aether.model.VpnGateServer

/** The on-disk relay cache: what the picker opens on instead of a download. */
class VpnGateCacheTest {

    private val relay = VpnGateServer(
        sstpHostname = "vpn1.opengw.net",
        sstpPort = 1956,
        countryCode = "KR",
        countryName = "Korea, Republic of",
        ip = "1.2.3.4",
    )

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
        val file = File(dir, "relays.txt")
        VpnGateRepository.writeCache(file, VpnGateRepository.formatCache(listOf(relay)))

        val snapshot = assertNotNull(VpnGateRepository.readCache(file))
        assertEquals(relay, snapshot.servers.single(), "a comma in the country name must survive")
        assertEquals(false, File(dir, "relays.txt.partial").exists())
    }

    @Test
    fun aRelayWithoutACountryRoundTripsBlank() = withTempDir { dir ->
        val file = File(dir, "relays.txt")
        val unknown = VpnGateServer(sstpHostname = "vpn2.opengw.net", sstpPort = 443)
        VpnGateRepository.writeCache(file, VpnGateRepository.formatCache(listOf(unknown)))

        val server = assertNotNull(VpnGateRepository.readCache(file)).servers.single()
        assertEquals(unknown, server)
        assertEquals("", server.countryCode)
    }

    @Test
    fun aNewListReplacesTheOldOne() = withTempDir { dir ->
        val file = File(dir, "relays.txt")
        VpnGateRepository.writeCache(file, VpnGateRepository.formatCache(listOf(relay)))
        VpnGateRepository.writeCache(file, "vpn2.opengw.net:443\n")

        val snapshot = assertNotNull(VpnGateRepository.readCache(file))
        assertEquals("vpn2.opengw.net", snapshot.servers.single().sstpHostname)
    }

    @Test
    fun missingOrUnusableCacheReadsAsNothing() = withTempDir { dir ->
        assertNull(VpnGateRepository.readCache(File(dir, "absent.txt")))
        val portal = File(dir, "portal.txt").apply { writeText("<html>captive portal</html>") }
        assertNull(VpnGateRepository.readCache(portal))
    }
}
