package studio.cluvex.aether.core

import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.Hop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings page disables a chain mode based on this, and the session refuses
 * one based on this, so the two must never disagree: a mode offered in the picker
 * and rejected at connect time is worse than a mode that was never offered.
 */
class CoreAvailabilityTest {

    private fun snapshot(psiphon: Boolean, tor: Boolean) = CoreAvailability.Snapshot(
        psiphonBinary = psiphon,
        torBinary = tor,
        psiphonConfigBundled = false,
        torGeoipBundled = false,
    )

    @Test
    fun `the engine is always available and Aether-only always runs`() {
        val bare = snapshot(psiphon = false, tor = false)
        assertTrue(bare.has(Hop.AETHER))
        assertTrue(bare.canRun(ChainMode.AETHER))
        assertEquals(emptyList(), bare.missing(ChainMode.AETHER))
    }

    @Test
    fun `a mode is unavailable when any single hop is missing`() {
        val psiphonOnly = snapshot(psiphon = true, tor = false)
        assertTrue(psiphonOnly.canRun(ChainMode.PSIPHON_OVER_AETHER))
        assertFalse(psiphonOnly.canRun(ChainMode.TOR_OVER_PSIPHON))
        assertEquals(listOf(Hop.TOR), psiphonOnly.missing(ChainMode.TOR_OVER_PSIPHON))
    }

    @Test
    fun `every missing core is named, not just the first one`() {
        val bare = snapshot(psiphon = false, tor = false)
        assertEquals(
            listOf(Hop.PSIPHON, Hop.TOR),
            bare.missing(ChainMode.TOR_OVER_PSIPHON_OVER_AETHER),
        )
    }

    @Test
    fun `a full build can run all seven modes`() {
        val full = snapshot(psiphon = true, tor = true)
        ChainMode.entries.forEach { mode ->
            assertTrue(full.canRun(mode), "$mode must be runnable on a complete build")
        }
    }
}
