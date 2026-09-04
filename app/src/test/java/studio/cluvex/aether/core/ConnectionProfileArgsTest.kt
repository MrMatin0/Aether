package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TeamAuth

/**
 * Everything [ConnectionProfile.toArgs] emits becomes argv of a child process,
 * and everything [ConnectionProfile.toEnv] emits becomes its environment. The
 * split between the two is a SECURITY boundary (argv is readable via
 * /proc/<pid>/cmdline by any local app that can see the process; the
 * environment block is not), and the sanitisers are what stop a pasted settings
 * blob from becoming extra arguments. Both are asserted here rather than
 * assumed.
 */
class ConnectionProfileArgsTest {

    @Test
    fun accessSecretsGoToTheEnvironmentAndNeverToArgv() {
        val profile = ConnectionProfile(
            team = "acme",
            teamAuth = TeamAuth.SERVICE_TOKEN,
            accessClientId = "client-id",
            accessClientSecret = "the-secret",
        )
        val args = profile.toArgs()
        assertFalse(args.any { it.contains("the-secret") }, "argv is world-readable via /proc")
        assertEquals("the-secret", profile.toEnv()["AETHER_ACCESS_CLIENT_SECRET"])
        assertTrue(args.contains("--team"))
        assertTrue(args.contains("acme"))
    }

    @Test
    fun enrolmentTokenAlsoStaysOutOfArgv() {
        val profile = ConnectionProfile(
            team = "acme",
            teamAuth = TeamAuth.TOKEN,
            accessToken = "jwt-value",
        )
        assertFalse(profile.toArgs().any { it.contains("jwt-value") })
        assertEquals("jwt-value", profile.toEnv()["AETHER_ACCESS_TOKEN"])
    }

    @Test
    fun dnsEntriesAreValidatedNotJustPassedThrough() {
        val profile = ConnectionProfile(
            dnsServers = "1.1.1.1, 9.9.9.9:53, 999.1.1.1, ; rm -rf /, [2606:4700:4700::1111]:53",
        )
        assertEquals(
            listOf("1.1.1.1", "9.9.9.9:53", "[2606:4700:4700::1111]:53"),
            profile.sanitizedDns(),
        )
    }

    @Test
    fun dnsListIsCapped() {
        val many = (1..20).joinToString(",") { "10.0.0.$it" }
        assertEquals(ConnectionProfile.MAX_DNS_SERVERS, ConnectionProfile(dnsServers = many).sanitizedDns().size)
    }

    @Test
    fun routingRulesRejectTokensThatWouldSplitIntoExtraArguments() {
        val profile = ConnectionProfile()
        val rules = profile.sanitizedRules(
            "example.com, full:a.example.com, keyword:ads, 10.0.0.0/8, has space, --injected-flag",
        )
        assertTrue(rules.contains("example.com"))
        assertTrue(rules.contains("full:a.example.com"))
        assertTrue(rules.contains("keyword:ads"))
        assertTrue(rules.contains("10.0.0.0/8"))
        assertFalse(rules.any { it.contains(" ") }, "whitespace would split into two argv entries")
    }

    @Test
    fun aPinnedPeerReplacesTheScanFlagRatherThanJoiningIt() {
        val pinned = ConnectionProfile(
            endpointMode = EndpointMode.MANUAL_PEER,
            manualPeer = "188.114.96.1:2408",
            scanMode = ScanMode.ULTRA,
        )
        val args = pinned.toArgs()
        assertFalse(args.contains(ScanMode.ULTRA.engineFlag), "scanning is irrelevant with a pinned peer")
        assertTrue(args.contains("--peer"))
        assertTrue(args.contains("188.114.96.1:2408"))
        // A pinned peer connects almost immediately, so it must not inherit the
        // scan mode's multi-minute budget.
        assertEquals(45_000L, pinned.connectTimeoutMs())
    }

    @Test
    fun scanBudgetsComfortablyExceedTheEnginesOwnScanTime() {
        // The app aborting an attempt while the engine is still legitimately
        // scanning looks exactly like a failure and is not one.
        assertTrue(ConnectionProfile(scanMode = ScanMode.TURBO).connectTimeoutMs() > 45_000L)
        assertTrue(ConnectionProfile(scanMode = ScanMode.PRECISE).connectTimeoutMs() > 150_000L)
        assertTrue(ConnectionProfile(scanMode = ScanMode.ULTRA).connectTimeoutMs() > 300_000L)
    }

    @Test
    fun fragmentTuningOnlyShipsWhenFragmentationIsOn() {
        val off = ConnectionProfile(fragment = false, fragmentSize = "16-32", fragmentDelay = "2-10")
        assertFalse(off.toArgs().contains("--fragment-size"))
        val on = off.copy(fragment = true)
        assertTrue(on.toArgs().contains("--fragment-size"))
        assertTrue(on.toArgs().contains("16-32"))
    }

    @Test
    fun reversedOrOutOfRangeFragmentRangesAreDropped() {
        val reversed = ConnectionProfile(fragment = true, fragmentSize = "32-16")
        assertFalse(reversed.toArgs().contains("--fragment-size"))
        val zero = ConnectionProfile(fragment = true, fragmentSize = "0-10")
        assertFalse(zero.toArgs().contains("--fragment-size"))
    }

    @Test
    fun tlsGroupsAreRejectedWhenTheyCarryShellMetacharacters() {
        assertFalse(ConnectionProfile(tlsGroups = "X25519; id").toArgs().contains("--tls-groups"))
        assertTrue(ConnectionProfile(tlsGroups = "X25519:P-256").toArgs().contains("--tls-groups"))
    }
}
