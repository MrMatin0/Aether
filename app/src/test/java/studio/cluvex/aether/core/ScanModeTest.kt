package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.ScanMode

/**
 * The scan mode is the one setting that reaches the engine as an argument AND
 * arrives from four different persisted sources, so it is worth pinning down:
 * three modes, three flags, and every retired name still landing somewhere
 * sensible.
 */
class ScanModeTest {

    @Test
    fun the_ui_offers_exactly_three_modes_in_order() {
        assertEquals(
            listOf(ScanMode.TURBO, ScanMode.PRECISE, ScanMode.ULTRA),
            ScanMode.entries.toList(),
        )
    }

    @Test
    fun every_mode_carries_its_own_engine_flag() {
        assertEquals("--turbo", ScanMode.TURBO.engineFlag)
        assertEquals("--precise", ScanMode.PRECISE.engineFlag)
        assertEquals("--ultra", ScanMode.ULTRA.engineFlag)
        assertEquals(3, ScanMode.entries.map { it.engineFlag }.toSet().size)
    }

    @Test
    fun the_five_retired_names_migrate_to_the_closest_of_the_three() {
        assertEquals(ScanMode.TURBO, ScanMode.fromStored("TURBO"))
        assertEquals(ScanMode.TURBO, ScanMode.fromStored("turbo"))
        assertEquals(ScanMode.PRECISE, ScanMode.fromStored("BALANCED"))
        assertEquals(ScanMode.PRECISE, ScanMode.fromStored("THOROUGH"))
        assertEquals(ScanMode.PRECISE, ScanMode.fromStored("STEALTH"))
        assertEquals(ScanMode.ULTRA, ScanMode.fromStored("IRONCLAD"))
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
                ConnectionProfile(scanMode = mode).toArgs().contains(mode.engineFlag),
                "${mode.name} should pass ${mode.engineFlag} to the engine",
            )
        }

        val pinned = ConnectionProfile(
            endpointMode = EndpointMode.MANUAL_PEER,
            manualPeer = "162.159.192.1:443",
        )
        val flags = ScanMode.entries.map { it.engineFlag }
        assertTrue(
            pinned.toArgs().none { it in flags },
            "a pinned peer skips scanning, so no scan flag should be sent",
        )
    }

    @Test
    fun the_app_waits_longer_the_more_precise_the_scan_is() {
        val turbo = ConnectionProfile(scanMode = ScanMode.TURBO).connectTimeoutMs()
        val precise = ConnectionProfile(scanMode = ScanMode.PRECISE).connectTimeoutMs()
        val ultra = ConnectionProfile(scanMode = ScanMode.ULTRA).connectTimeoutMs()
        assertTrue(turbo < precise, "turbo is the quick one")
        assertTrue(precise < ultra, "very precise is the patient one")

        // Each budget has to outlast the engine's own budget for that mode
        // (prober/scan.rs: 45 s / 150 s / 300 s), or the app gives up while the
        // engine is still legitimately scanning.
        assertTrue(turbo > 45_000L)
        assertTrue(precise > 150_000L)
        assertTrue(ultra > 300_000L)
    }
}
