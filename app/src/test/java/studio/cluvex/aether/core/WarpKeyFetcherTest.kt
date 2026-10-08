package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TeamAuth

/**
 * fix/ech-from-core: the manual key request ran a bare ConnectionProfile(),
 * so the user's ECH switch never reached the registration it was meant for.
 */
class WarpKeyFetcherTest {

    private val saved = ConnectionProfile(
        protocol = Protocol.WIREGUARD,
        scanMode = ScanMode.ULTRA,
        quickReconnect = true,
        ech = true,
        ipVersion = IpVersion.BOTH,
        noize = Noize.GFW,
        team = "acme",
        teamAuth = TeamAuth.TOKEN,
        accessToken = "jwt",
    )

    @Test
    fun a_key_request_keeps_the_users_ech_and_network_settings() {
        for (protocol in WarpKeyFetcher.PROTOCOLS) {
            val profile = WarpKeyFetcher.keyProfile(saved, protocol)
            assertEquals(protocol, profile.protocol)
            assertTrue(profile.sendsEch, "$protocol")
            assertTrue(profile.toArgs().windowed(2).contains(listOf("--ech", "auto")), "$protocol")
            assertEquals("ip.gs", profile.toEnv()["AETHER_ECH_DOMAIN"], "$protocol")
            assertEquals(IpVersion.BOTH, profile.ipVersion)
            assertEquals(Noize.GFW, profile.noize)
        }
    }

    @Test
    fun a_key_request_scans_fast_and_never_walks_the_ring() {
        val profile = WarpKeyFetcher.keyProfile(saved, Protocol.MASQUE)
        assertEquals(ScanMode.TURBO, profile.scanMode)
        assertFalse(profile.quickReconnect)
    }

    /** A team identity lands in aether-team-<team>.toml, which the fetcher does not watch. */
    @Test
    fun a_key_request_is_always_consumer_warp() {
        val profile = WarpKeyFetcher.keyProfile(saved, Protocol.GOOL)
        assertFalse(profile.hasTeam)
        assertFalse("--team" in profile.toArgs())
        assertFalse(profile.toEnv().containsKey("AETHER_ACCESS_TOKEN"))
    }
}
