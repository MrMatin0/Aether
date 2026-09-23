package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
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
    fun everyProtocolAndEndpointModeExplicitlyPassesEveryNoizeProfile() {
        for (protocol in Protocol.entries) {
            for (endpoint in EndpointMode.entries) {
                for (noize in Noize.entries) {
                    for (quick in listOf(false, true)) {
                        val profile = ConnectionProfile(
                            protocol = protocol,
                            endpointMode = endpoint,
                            manualPeer = "162.159.197.1:443",
                            manualRange = "162.159.197.0/24",
                            noize = noize,
                            quickReconnect = quick,
                        )
                        val args = profile.toArgs()
                        assertEquals(1, args.count { it == "--noize" }, "$protocol/$endpoint/$noize")
                        assertEquals(noize.name.lowercase(), args[args.indexOf("--noize") + 1])
                    }
                }
            }
        }
    }

    @Test
    fun offSurvivesTheIntentCodecAndStillReachesTheEngine() {
        for (protocol in Protocol.entries) {
            val profile = ConnectionProfile(protocol = protocol, noize = Noize.OFF)
            val decoded = ProfileCodec.decode(ProfileCodec.encode(profile))
            assertEquals(Noize.OFF, decoded.noize)
            assertTrue(decoded.toArgs().windowed(2).contains(listOf("--noize", "off")))
        }
    }

    /**
     * The DEFAULT profile must still ask for obfuscation.
     *
     * Before the engine honoured an explicit OFF, a default profile emitted no
     * `--noize` at all and the engine applied its own defaults (firewall for
     * MASQUE, balanced for WireGuard) - so out of the box this app has always
     * connected WITH junk packets, which is what carries a MASQUE scan through
     * a filtered network. Now that OFF is passed through verbatim, a default of
     * OFF would quietly remove it. FIREWALL maps to those same two native
     * defaults, so the shipped behaviour is unchanged.
     */
    @Test
    fun theDefaultProfileStillAsksTheEngineForObfuscation() {
        val default = ConnectionProfile()
        assertEquals(Noize.FIREWALL, default.noize)
        val args = default.toArgs()
        assertTrue(args.windowed(2).contains(listOf("--noize", "firewall")))
        assertFalse(args.windowed(2).contains(listOf("--noize", "off")))
    }

    /**
     * MASQUE-in-MASQUE is a protocol: it REPLACES --masque rather than joining
     * it, its endpoints only go out while it is selected, and a pinned peer is
     * its outer hop rather than a single-hop --peer.
     */
    @Test
    fun mimIsItsOwnTransportAndOwnsItsEndpoints() {
        val mim = ConnectionProfile(
            protocol = Protocol.MIM,
            mimOuterPeer = "162.159.192.1:2408",
            mimInnerPeer = "188.114.97.2:934",
        )
        val args = mim.toArgs()
        assertTrue(args.contains("--mim"))
        assertFalse(args.contains("--masque"), "--mim replaces --masque")
        assertTrue(args.windowed(2).contains(listOf("--mim-outer", "162.159.192.1:2408")))
        assertTrue(args.windowed(2).contains(listOf("--mim-inner", "188.114.97.2:934")))

        val plainMasque = mim.copy(protocol = Protocol.MASQUE).toArgs()
        assertTrue(plainMasque.contains("--masque"))
        assertFalse(plainMasque.any { it.startsWith("--mim") }, "hop fields are idle outside MIM")

        val pinned = mim.copy(endpointMode = EndpointMode.MANUAL_PEER, manualPeer = "188.114.96.1:443")
            .toArgs()
        assertTrue(pinned.windowed(2).contains(listOf("--mim-outer", "188.114.96.1:443")))
        assertFalse(pinned.contains("--peer"))
        assertEquals(1, pinned.count { it == "--mim-outer" }, "the pinned peer IS the outer hop")
    }

    /**
     * The app runs ONE Tor - the bundled core behind the chain modes. No
     * profile, whatever its chain or protocol, may ask the engine to start the
     * second one it can embed.
     */
    @Test
    fun noProfileAsksTheEngineForItsOwnTor() {
        for (protocol in Protocol.entries) {
            for (chain in ChainMode.entries) {
                val profile = ConnectionProfile(protocol = protocol, chain = chain)
                assertFalse(profile.toArgs().any { it.startsWith("--tor") || it == "--no-tor-bridges" }, "$protocol/$chain")
                assertFalse(profile.toEnv().keys.any { it.startsWith("AETHER_TOR") }, "$protocol/$chain")
            }
        }
    }

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
