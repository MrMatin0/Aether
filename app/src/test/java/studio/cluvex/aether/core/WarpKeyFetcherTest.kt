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
 * The manual key request runs the user's saved settings (IP family,
 * obfuscation), not a bare ConnectionProfile(), and injects the ECH-first
 * camouflaged API environment exclusively for the key-fetch subprocess while
 * leaving normal session profiles free of ECH flags.
 */
class WarpKeyFetcherTest {

    private val saved = ConnectionProfile(
        protocol = Protocol.WIREGUARD,
        scanMode = ScanMode.ULTRA,
        quickReconnect = true,
        ipVersion = IpVersion.BOTH,
        noize = Noize.GFW,
        team = "acme",
        teamAuth = TeamAuth.TOKEN,
        accessToken = "jwt",
    )

    @Test
    fun a_key_request_keeps_the_users_network_settings() {
        for (protocol in WarpKeyFetcher.PROTOCOLS) {
            val profile = WarpKeyFetcher.keyProfile(saved, protocol)
            assertEquals(protocol, profile.protocol)
            assertEquals(IpVersion.BOTH, profile.ipVersion)
            assertEquals(Noize.GFW, profile.noize)
        }
    }

    @Test
    fun a_key_request_never_asks_for_ech() {
        for (protocol in WarpKeyFetcher.PROTOCOLS) {
            val profile = WarpKeyFetcher.keyProfile(saved, protocol)
            assertFalse("--ech" in profile.toArgs(), "$protocol")
            assertFalse(profile.toEnv().keys.any { it.startsWith("AETHER_ECH") }, "$protocol")
        }
    }

    @Test
    fun key_env_enables_ech_first_camouflaged_api_route_for_subprocess() {
        for (protocol in WarpKeyFetcher.PROTOCOLS) {
            val profile = WarpKeyFetcher.keyProfile(saved, protocol)
            val env = WarpKeyFetcher.keyEnv(profile)
            assertEquals("auto", env["AETHER_ECH"], "$protocol")
            assertTrue(env["AETHER_ECH_DOMAIN"]?.contains("cloudflare-ech.com") == true, "$protocol")
            assertTrue(env["AETHER_ECH_DNS"]?.contains("https://162.159.36.1/dns-query") == true, "$protocol")
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
