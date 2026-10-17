package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The core selector stores nothing new: it reads every [ChainMode] as an
 * (entry, underlay) pair and writes the mode back. These pin that the two views
 * can never disagree.
 */
class CoreRouteTest {

    @Test
    fun everyModeRoundTripsThroughEntryAndUnderlay() {
        assertEquals(14, ChainMode.entries.size, "a new mode needs a row in CoreRoutes")
        ChainMode.entries.forEach { mode ->
            val entry = mode.entryHop
            val underlay = CoreRoutes.underlayOf(mode)
            assertTrue(underlay in CoreRoutes.underlaysFor(entry), "$mode: $underlay not offered for $entry")
            assertEquals(mode, CoreRoutes.modeOf(entry, underlay), "$mode did not round-trip")
        }
    }

    @Test
    fun everyOfferedPairIsARealModeAndNoModeIsReachedTwice() {
        val reached = CoreRoutes.cores.flatMap { core ->
            CoreRoutes.underlaysFor(core).map { underlay ->
                requireNotNull(CoreRoutes.modeOf(core, underlay)) { "$core via $underlay is not a mode" }
            }
        }
        assertEquals(ChainMode.entries.toSet(), reached.toSet())
        assertEquals(reached.size, reached.toSet().size)
    }

    @Test
    fun theFivePillsAreEveryEntryCoreInOrder() {
        assertEquals(listOf(Hop.AETHER, Hop.PSIPHON, Hop.TOR, Hop.SSTP, Hop.XRAY), CoreRoutes.cores)
        assertEquals(ChainMode.entries.map { it.entryHop }.toSet(), CoreRoutes.cores.toSet())
    }

    @Test
    fun underlayListsMatchTheSpec() {
        assertEquals(listOf(Underlay.DIRECT, Underlay.XRAY), CoreRoutes.underlaysFor(Hop.AETHER))
        assertEquals(
            listOf(Underlay.DIRECT, Underlay.AETHER, Underlay.XRAY, Underlay.AETHER_XRAY),
            CoreRoutes.underlaysFor(Hop.PSIPHON),
        )
        assertEquals(
            listOf(
                Underlay.DIRECT,
                Underlay.AETHER,
                Underlay.PSIPHON,
                Underlay.PSIPHON_AETHER,
                Underlay.XRAY,
                Underlay.AETHER_XRAY,
            ),
            CoreRoutes.underlaysFor(Hop.TOR),
        )
        assertEquals(listOf(Underlay.DIRECT), CoreRoutes.underlaysFor(Hop.SSTP))
        assertEquals(listOf(Underlay.DIRECT), CoreRoutes.underlaysFor(Hop.XRAY))
    }

    @Test
    fun theMappingTable() {
        val table = mapOf(
            (Hop.AETHER to Underlay.DIRECT) to ChainMode.AETHER,
            (Hop.AETHER to Underlay.XRAY) to ChainMode.AETHER_OVER_XRAY,
            (Hop.PSIPHON to Underlay.DIRECT) to ChainMode.PSIPHON,
            (Hop.PSIPHON to Underlay.AETHER) to ChainMode.PSIPHON_OVER_AETHER,
            (Hop.PSIPHON to Underlay.XRAY) to ChainMode.PSIPHON_OVER_XRAY,
            (Hop.PSIPHON to Underlay.AETHER_XRAY) to ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY,
            (Hop.TOR to Underlay.DIRECT) to ChainMode.TOR,
            (Hop.TOR to Underlay.AETHER) to ChainMode.TOR_OVER_AETHER,
            (Hop.TOR to Underlay.PSIPHON) to ChainMode.TOR_OVER_PSIPHON,
            (Hop.TOR to Underlay.PSIPHON_AETHER) to ChainMode.TOR_OVER_PSIPHON_OVER_AETHER,
            (Hop.TOR to Underlay.XRAY) to ChainMode.TOR_OVER_XRAY,
            (Hop.TOR to Underlay.AETHER_XRAY) to ChainMode.TOR_OVER_AETHER_OVER_XRAY,
            (Hop.SSTP to Underlay.DIRECT) to ChainMode.SSTP,
            (Hop.XRAY to Underlay.DIRECT) to ChainMode.XRAY,
        )
        assertEquals(14, table.size)
        table.forEach { (pair, mode) ->
            assertEquals(mode, CoreRoutes.modeOf(pair.first, pair.second), "$pair")
            assertEquals(pair.second, CoreRoutes.underlayOf(mode), "$mode")
        }
    }

    @Test
    fun sstpAndXrayNeverChain() {
        Underlay.entries.filter { it != Underlay.DIRECT }.forEach { underlay ->
            assertNull(CoreRoutes.modeOf(Hop.SSTP, underlay))
            assertNull(CoreRoutes.modeOf(Hop.XRAY, underlay))
        }
        assertNull(CoreRoutes.modeOf(Hop.AETHER, Underlay.PSIPHON))
        assertNull(CoreRoutes.modeOf(Hop.AETHER, Underlay.AETHER))
    }

    @Test
    fun onlyXrayUnderlaysNeedAnXrayConfig() {
        assertEquals(
            setOf(Underlay.XRAY, Underlay.AETHER_XRAY),
            Underlay.entries.filter { it.usesXray }.toSet(),
        )
    }

    @Test
    fun tappingTheCurrentCoreChangesNothing() {
        ChainMode.entries.forEach { mode ->
            assertEquals(mode, CoreRoutes.switchTo(mode, mode.entryHop, Underlay.DIRECT) { true })
        }
    }

    @Test
    fun switchingKeepsTheRememberedUnderlayWhileItIsValid() {
        assertEquals(
            ChainMode.TOR_OVER_PSIPHON,
            CoreRoutes.switchTo(ChainMode.AETHER, Hop.TOR, Underlay.PSIPHON) { true },
        )
        assertEquals(
            ChainMode.PSIPHON_OVER_AETHER_OVER_XRAY,
            CoreRoutes.switchTo(ChainMode.TOR, Hop.PSIPHON, Underlay.AETHER_XRAY) { true },
        )
    }

    @Test
    fun switchingFallsBackToDirect() {
        // Nothing remembered.
        assertEquals(ChainMode.PSIPHON, CoreRoutes.switchTo(ChainMode.AETHER, Hop.PSIPHON, null) { true })
        // Remembered, but not a pairing this core has.
        assertEquals(ChainMode.AETHER, CoreRoutes.switchTo(ChainMode.TOR, Hop.AETHER, Underlay.PSIPHON) { true })
        // Remembered and real, but no longer allowed (core gone, no Xray config).
        assertEquals(
            ChainMode.TOR,
            CoreRoutes.switchTo(ChainMode.AETHER, Hop.TOR, Underlay.XRAY) { !it.usesXray },
        )
        // SSTP and Xray only ever land on themselves.
        assertEquals(ChainMode.SSTP, CoreRoutes.switchTo(ChainMode.TOR_OVER_AETHER, Hop.SSTP, Underlay.AETHER) { true })
        assertEquals(ChainMode.XRAY, CoreRoutes.switchTo(ChainMode.AETHER, Hop.XRAY, null) { false })
    }
}
