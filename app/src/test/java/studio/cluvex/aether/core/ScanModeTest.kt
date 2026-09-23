package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreVersion
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

/**
 * The scan mode is the one setting that reaches the engine as an argument AND
 * arrives from four different persisted sources, so it is worth pinning down:
 * which modes exist, which flag each sends, which core can parse it, and every
 * retired name still landing somewhere sensible.
 *
 * Every toArgs() / connectTimeoutMs() call here names its core explicitly. The
 * default is the core vendored in this checkout, and these tests have to hold
 * on both sides of the 2.0.0 -> 2.1.0 sync.
 */
class ScanModeTest {

    private val core20 = "2.0.0"
    private val core21 = "2.1.0"

    @Test
    fun the_modes_exist_in_the_order_the_picker_shows_them() {
        assertEquals(
            listOf(ScanMode.TURBO, ScanMode.PRECISE, ScanMode.VERIFIED, ScanMode.ULTRA),
            ScanMode.entries.toList(),
        )
        assertEquals(ScanMode.entries.toList(), ScanMode.offeredBy(core21))
    }

    @Test
    fun verified_is_not_offered_by_a_core_that_cannot_parse_it() {
        val three = listOf(ScanMode.TURBO, ScanMode.PRECISE, ScanMode.ULTRA)
        assertEquals(three, ScanMode.offeredBy(core20))
        assertEquals(three, ScanMode.offeredBy("unknown"))
        assertEquals(three, ScanMode.offeredBy(""))
    }

    @Test
    fun every_mode_carries_upstreams_own_engine_flag() {
        assertEquals("--turbo", ScanMode.TURBO.engineFlag)
        assertEquals("--balanced", ScanMode.PRECISE.engineFlag)
        assertEquals("--verified", ScanMode.VERIFIED.engineFlag)
        assertEquals("--ironclad", ScanMode.ULTRA.engineFlag)
        assertEquals(ScanMode.entries.size, ScanMode.entries.map { it.engineFlag }.toSet().size)
    }

    @Test
    fun no_mode_depends_on_the_aliases_only_this_repos_cli_rs_patch_knows() {
        val appOnly = setOf("--precise", "--accurate", "--ultra", "--ultra-precise")
        for (mode in ScanMode.entries) {
            assertFalse(mode.engineFlag in appOnly, "${mode.name} must not need the cli.rs patch")
        }
    }

    @Test
    fun a_stored_verified_on_an_older_core_runs_as_precise() {
        val verified = ConnectionProfile(scanMode = ScanMode.VERIFIED)
        val args = verified.toArgs(core20)
        assertTrue("--balanced" in args, "2.0.0 gets the mode it can run")
        assertFalse("--verified" in args, "2.0.0 refuses --verified as an unknown option")
        assertEquals(
            ConnectionProfile(scanMode = ScanMode.PRECISE).connectTimeoutMs(core20),
            verified.connectTimeoutMs(core20),
            "and it is waited on like the scan it becomes",
        )
    }

    @Test
    fun verified_reaches_core_2_1_on_every_protocol_that_scans() {
        for (protocol in listOf(Protocol.MASQUE, Protocol.MIM, Protocol.WIREGUARD, Protocol.GOOL)) {
            assertTrue(
                ConnectionProfile(protocol = protocol, scanMode = ScanMode.VERIFIED)
                    .toArgs(core21).contains("--verified"),
                "$protocol should pass --verified to a 2.1.0 engine",
            )
        }
    }

    @Test
    fun the_five_retired_names_migrate_to_the_closest_current_mode() {
        assertEquals(ScanMode.TURBO, ScanMode.fromStored("TURBO"))
        assertEquals(ScanMode.TURBO, ScanMode.fromStored("turbo"))
        assertEquals(ScanMode.PRECISE, ScanMode.fromStored("BALANCED"))
        assertEquals(ScanMode.PRECISE, ScanMode.fromStored("THOROUGH"))
        // Chosen as "quiet and patient" before 1.4.6. Upstream 2.1.0 reusing
        // --stealth as an alias of verified does not change what was asked for.
        assertEquals(ScanMode.PRECISE, ScanMode.fromStored("STEALTH"))
        assertEquals(ScanMode.ULTRA, ScanMode.fromStored("IRONCLAD"))
        assertEquals(ScanMode.VERIFIED, ScanMode.fromStored("VERIFIED"))
        assertNull(ScanMode.fromStored("nonsense"))
        assertNull(ScanMode.fromStored(""))
        assertNull(ScanMode.fromStored(null))
    }

    @Test
    fun a_profile_saved_by_an_older_build_still_decodes() {
        val ironclad = ProfileCodec.decode("protocol=MASQUE\nscan=IRONCLAD\nip=V4")
        assertEquals(ScanMode.ULTRA, ironclad.scanMode)

        val thorough = ProfileCodec.decode("protocol=WIREGUARD\nscan=THOROUGH")
        assertEquals(ScanMode.PRECISE, thorough.scanMode)

        // The 1.0/1.1 pipe format put the scan mode in field two.
        val legacy = ProfileCodec.decode("MASQUE|STEALTH|V4|true|false")
        assertEquals(ScanMode.PRECISE, legacy.scanMode)

        // A value from the future is not a reason to lose the rest.
        val unknown = ProfileCodec.decode("protocol=MASQUE\nscan=WARP_SPEED")
        assertEquals(ConnectionProfile().scanMode, unknown.scanMode)
    }

    @Test
    fun a_round_trip_through_the_codec_keeps_the_mode() {
        for (mode in ScanMode.entries) {
            val encoded = ProfileCodec.encode(ConnectionProfile(scanMode = mode))
            assertEquals(mode, ProfileCodec.decode(encoded).scanMode)
        }
    }

    @Test
    fun the_mode_reaches_the_engine_unless_a_peer_is_pinned() {
        for (mode in ScanMode.entries) {
            assertTrue(
                ConnectionProfile(scanMode = mode).toArgs(core21).contains(mode.engineFlag),
                "${mode.name} should pass ${mode.engineFlag} to the engine",
            )
        }

        val pinned = ConnectionProfile(
            endpointMode = EndpointMode.MANUAL_PEER,
            manualPeer = "162.159.192.1:443",
        )
        val flags = ScanMode.entries.map { it.engineFlag }
        for (core in listOf(core20, core21)) {
            assertTrue(
                pinned.toArgs(core).none { it in flags },
                "a pinned peer skips scanning, so no scan flag should be sent",
            )
        }
    }

    @Test
    fun the_app_waits_longer_than_the_engine_scans() {
        fun budget(mode: ScanMode) = ConnectionProfile(scanMode = mode).connectTimeoutMs(core21)
        val turbo = budget(ScanMode.TURBO)
        val precise = budget(ScanMode.PRECISE)
        val verified = budget(ScanMode.VERIFIED)
        val ultra = budget(ScanMode.ULTRA)
        assertTrue(turbo < precise, "turbo is the quick one")
        assertTrue(precise < ultra, "very precise is the patient one")

        // Each budget has to outlast the engine's own budget for that mode
        // (prober.rs strategy(): 45 s turbo, up to 150 s balanced, 60 s
        // verified, up to 300 s ironclad), or the app gives up while the engine
        // is still legitimately scanning.
        assertTrue(turbo > 45_000L)
        assertTrue(precise > 150_000L)
        assertTrue(verified > 60_000L)
        assertTrue(ultra > 300_000L)
    }

    @Test
    fun core_versions_compare_numerically() {
        assertTrue(CoreVersion.atLeast("2.1.0", "2.1.0"))
        assertTrue(CoreVersion.atLeast("2.1.1", "2.1.0"))
        assertTrue(CoreVersion.atLeast("2.10.0", "2.1.0"))
        assertTrue(CoreVersion.atLeast("10.0.0", "2.1.0"))
        assertTrue(CoreVersion.atLeast("v2.1.0", "2.1.0"))
        assertTrue(CoreVersion.atLeast("2.1", "2.1.0"))
        assertTrue(CoreVersion.atLeast(" 2.1.0\n", "2.1.0"))
        assertFalse(CoreVersion.atLeast("2.0.9", "2.1.0"))
        assertFalse(CoreVersion.atLeast("2.0.0", "2.1.0"))
        assertFalse(CoreVersion.atLeast("1.9.99", "2.1.0"))
        assertFalse(CoreVersion.atLeast("unknown", "2.1.0"))
        assertFalse(CoreVersion.atLeast("", "2.1.0"))
        assertFalse(CoreVersion.atLeast(null, "2.1.0"))
    }
}
