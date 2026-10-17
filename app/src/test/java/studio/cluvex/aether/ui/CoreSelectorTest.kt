package studio.cluvex.aether.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.CoreRoutes
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.Underlay

/**
 * The core selector must never offer a route this build cannot start, never
 * offer a change mid-session, and never move the orb. These pin the pure rules
 * behind CoreSelector.kt and CoreSheets.kt.
 */
class CoreSelectorTest {

    private fun build(
        psiphon: Boolean = true,
        tor: Boolean = true,
        xray: Boolean = true,
    ) = CoreAvailability.Snapshot(
        psiphonBinary = psiphon,
        torBinary = tor,
        psiphonConfigBundled = false,
        torGeoipBundled = false,
        psiphonServerList = true,
        torBridgeListBundled = false,
        xrayBinary = xray,
    )

    // ------------------------------------------------------------- the lock --

    @Test
    fun onlyRestAndFailureAreEditable() {
        assertTrue(quickControlsEditable(ConnectionState.Idle))
        assertTrue(quickControlsEditable(ConnectionState.Error("failed")))
        listOf(
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            ConnectionState.Reconnecting(1, 3),
            ConnectionState.Connected("127.0.0.1:1080"),
            ConnectionState.Disconnecting,
        ).forEach { assertFalse(quickControlsEditable(it), "$it must lock the selector") }
    }

    // ------------------------------------------------------- availability --

    @Test
    fun directIsAlwaysOfferedForACoreThatShips() {
        CoreRoutes.cores.forEach { core ->
            assertNull(underlayBlock(core, Underlay.DIRECT, build(), xrayReady = false), "$core")
        }
    }

    @Test
    fun viaXrayWaitsForAUsableConfig() {
        val all = build()
        listOf(
            Hop.AETHER to Underlay.XRAY,
            Hop.PSIPHON to Underlay.XRAY,
            Hop.PSIPHON to Underlay.AETHER_XRAY,
            Hop.TOR to Underlay.XRAY,
            Hop.TOR to Underlay.AETHER_XRAY,
        ).forEach { (core, underlay) ->
            assertEquals(UnderlayBlock.XRAY_CONFIG_MISSING, underlayBlock(core, underlay, all, xrayReady = false))
            assertNull(underlayBlock(core, underlay, all, xrayReady = true))
        }
        // Underlays without Xray never wait for it.
        assertNull(underlayBlock(Hop.TOR, Underlay.PSIPHON_AETHER, all, xrayReady = false))
        assertNull(underlayBlock(Hop.PSIPHON, Underlay.AETHER, all, xrayReady = false))
    }

    @Test
    fun aMissingCoreBlocksEveryRouteThatNeedsIt() {
        val noPsiphon = build(psiphon = false)
        assertEquals(UnderlayBlock.CORE_MISSING, underlayBlock(Hop.TOR, Underlay.PSIPHON, noPsiphon, xrayReady = true))
        assertEquals(
            UnderlayBlock.CORE_MISSING,
            underlayBlock(Hop.TOR, Underlay.PSIPHON_AETHER, noPsiphon, xrayReady = true),
        )
        assertNull(underlayBlock(Hop.TOR, Underlay.AETHER, noPsiphon, xrayReady = true))

        // A missing binary outranks a missing config: there is nothing to configure.
        val noXray = build(xray = false)
        assertEquals(UnderlayBlock.CORE_MISSING, underlayBlock(Hop.AETHER, Underlay.XRAY, noXray, xrayReady = false))

        val bare = build(psiphon = false, tor = false, xray = false)
        CoreRoutes.underlaysFor(Hop.TOR).forEach { underlay ->
            assertEquals(UnderlayBlock.CORE_MISSING, underlayBlock(Hop.TOR, underlay, bare, xrayReady = true), "$underlay")
        }
        // Aether and SSTP are in every build.
        assertNull(underlayBlock(Hop.AETHER, Underlay.DIRECT, bare, xrayReady = false))
        assertNull(underlayBlock(Hop.SSTP, Underlay.DIRECT, bare, xrayReady = false))
    }

    @Test
    fun sstpAndXrayHaveNothingToChainOnto() {
        Underlay.entries.filter { it != Underlay.DIRECT }.forEach { underlay ->
            assertEquals(UnderlayBlock.UNSUPPORTED, underlayBlock(Hop.SSTP, underlay, build(), xrayReady = true))
            assertEquals(UnderlayBlock.UNSUPPORTED, underlayBlock(Hop.XRAY, underlay, build(), xrayReady = true))
        }
    }

    @Test
    fun aPillTapNeverLandsOnARouteThatCannotStart() {
        val all = build()
        // Remembered "via Xray", but no config: back to Direct.
        assertEquals(
            ChainMode.TOR,
            CoreRoutes.switchTo(ChainMode.AETHER, Hop.TOR, Underlay.XRAY) { coreModeAllowed(it, all, xrayReady = false) },
        )
        assertEquals(
            ChainMode.TOR_OVER_XRAY,
            CoreRoutes.switchTo(ChainMode.AETHER, Hop.TOR, Underlay.XRAY) { coreModeAllowed(it, all, xrayReady = true) },
        )
        // Remembered "via Psiphon", and Psiphon is gone from this build.
        val noPsiphon = build(psiphon = false)
        assertEquals(
            ChainMode.TOR,
            CoreRoutes.switchTo(ChainMode.AETHER, Hop.TOR, Underlay.PSIPHON) {
                coreModeAllowed(it, noPsiphon, xrayReady = true)
            },
        )
        // Xray alone is where the config gets pasted: always reachable.
        assertTrue(coreModeAllowed(ChainMode.XRAY, all, xrayReady = false))
        assertFalse(coreModeAllowed(ChainMode.AETHER_OVER_XRAY, all, xrayReady = false))
        assertFalse(coreModeAllowed(ChainMode.PSIPHON, noPsiphon, xrayReady = true))
    }

    // --------------------------------------------------------------- links --

    @Test
    fun everySheetLinksToThePageThatOwnsTheRest() {
        assertEquals(SettingsPage.CONNECTION, coreSettingsPage(Hop.AETHER))
        assertEquals(SettingsPage.CHAIN, coreSettingsPage(Hop.PSIPHON))
        assertEquals(SettingsPage.BRIDGES, coreSettingsPage(Hop.TOR))
        assertEquals(SettingsPage.CHAIN, coreSettingsPage(Hop.SSTP))
        assertEquals(SettingsPage.CHAIN, coreSettingsPage(Hop.XRAY))
        // Every linked page writes the profile, so it carries its own lock.
        CoreRoutes.cores.forEach { assertTrue(coreSettingsPage(it).editsProfile, "$it") }
    }

    // -------------------------------------------------------------- layout --

    @Test
    fun pillsScrollOnlyForLargeTextOrANarrowBlock() {
        assertFalse(corePillsScroll(320.dp, 1f)) // 360dp phone minus gutters
        assertFalse(corePillsScroll(520.dp, 1.15f))
        assertTrue(corePillsScroll(319.dp, 1f)) // split screen
        assertTrue(corePillsScroll(280.dp, 1f))
        assertTrue(corePillsScroll(520.dp, 1.3f)) // large text
        assertTrue(corePillsScroll(900.dp, 2f))
    }

    @Test
    fun shortWindowsGetIconOnlyPills() {
        assertFalse(corePillsIconOnly(HomeDensity.ROOMY))
        assertFalse(corePillsIconOnly(HomeDensity.COMPACT))
        assertTrue(corePillsIconOnly(HomeDensity.TIGHT))
        assertTrue(corePillsIconOnly(HomeDensity.MINIMAL))
    }

    @Test
    fun proportionsFollowTheEightDpGrid() {
        assertEquals(64.dp, corePillHeight(HomeDensity.ROOMY))
        assertEquals(56.dp, corePillHeight(HomeDensity.COMPACT))
        assertEquals(44.dp, corePillHeight(HomeDensity.TIGHT))
        assertEquals(44.dp, corePillHeight(HomeDensity.MINIMAL))
        assertEquals(52.dp, coreSummaryHeight(HomeDensity.ROOMY))
        assertEquals(52.dp, coreSummaryHeight(HomeDensity.COMPACT))
        assertEquals(44.dp, coreSummaryHeight(HomeDensity.TIGHT))
        assertEquals(8.dp, CORE_SELECTOR_GAP)
        assertEquals(124.dp, coreSelectorHeight(HomeDensity.ROOMY))
        assertEquals(116.dp, coreSelectorHeight(HomeDensity.COMPACT))
        assertEquals(96.dp, coreSelectorHeight(HomeDensity.TIGHT))
        assertEquals(96.dp, coreSelectorHeight(HomeDensity.MINIMAL))
        // Labelled pills are tall enough to be their own 48dp touch target.
        listOf(HomeDensity.ROOMY, HomeDensity.COMPACT).forEach { assertTrue(corePillHeight(it) >= 48.dp) }
    }

    // ------------------------------------------------------------- reserve --

    @Test
    fun theReserveBudgetsTheSelectorPerDensity() {
        assertEquals(500.dp, heroReserve(HomeDensity.ROOMY, twoPane = false))
        assertEquals(364.dp, heroReserve(HomeDensity.COMPACT, twoPane = false))
        assertEquals(326.dp, heroReserve(HomeDensity.TIGHT, twoPane = false))
        assertEquals(272.dp, heroReserve(HomeDensity.MINIMAL, twoPane = false))
        // Two-pane: the selector lives in the scrolling details pane.
        HomeDensity.entries.forEach { assertEquals(64.dp, heroReserve(it, twoPane = true)) }
    }

    @Test
    fun theOrbStillFitsCommonPhones() {
        // These must fit outright: the orb never resizes on a phone in hand.
        listOf(393.dp to 700.dp, 412.dp to 780.dp, 360.dp to 560.dp).forEach { (w, h) ->
            val density = homeDensity(h)
            val side = heroSlotHeight(w, h, density, twoPane = false)
            assertTrue(side + heroReserve(density, twoPane = false) <= h, "$w x $h")
        }
        // Smaller than any budget: the orb stays at its minimum and the
        // details' scroll is the safety net.
        val tiny = homeDensity(420.dp)
        assertEquals(ORB_HOME_MIN, heroSlotHeight(320.dp, 420.dp, tiny, twoPane = false))
    }

    // ------------------------------------------------------------- summary --

    @Test
    fun theSummaryOnlySaysWhatIsDoingSomething() {
        val germany = "\uD83C\uDDE9\uD83C\uDDEA Germany"
        assertEquals(
            "Psiphon \u00B7 $germany \u00B7 CDN fronting \u00B7 via Aether",
            coreSummaryLine(listOf("Psiphon", " $germany ", "CDN fronting", "via Aether")),
        )
        assertEquals("Tor \u00B7 No bridges", coreSummaryLine(listOf("Tor", "No bridges", "")))
        assertEquals("SSTP", coreSummaryLine(listOf("SSTP", "  ")))
    }
}
