package studio.cluvex.aether.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.EndpointMode

/**
 * The quick controls must never offer a change mid-session, and never show a
 * value as active when the route or a pinned endpoint makes it idle.
 */
class QuickControlsTest {

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
        ).forEach { assertFalse(quickControlsEditable(it), "$it must lock the controls") }
    }

    @Test
    fun everyControlLocksWhileNotEditable() {
        ChainMode.entries.forEach { chain ->
            QuickControl.entries.forEach { control ->
                assertEquals(
                    ControlAvailability.LOCKED,
                    quickControlAvailability(control, ConnectionProfile(chain = chain), editable = false),
                )
            }
        }
    }

    @Test
    fun routeIsAlwaysReadyAtRest() {
        ChainMode.entries.forEach { chain ->
            assertEquals(
                ControlAvailability.READY,
                quickControlAvailability(QuickControl.ROUTE, ConnectionProfile(chain = chain), editable = true),
            )
        }
    }

    @Test
    fun protocolAndScanAreIdleWithoutAnAetherHop() {
        ChainMode.entries.forEach { chain ->
            val expected = if (chain.usesAether) ControlAvailability.READY else ControlAvailability.NOT_USED
            val profile = ConnectionProfile(chain = chain)
            assertEquals(expected, quickControlAvailability(QuickControl.PROTOCOL, profile, editable = true), "$chain")
            assertEquals(expected, quickControlAvailability(QuickControl.SCAN, profile, editable = true), "$chain")
        }
    }

    @Test
    fun aPinnedPeerIdlesTheScanButNotTheProtocol() {
        val pinned = ConnectionProfile(endpointMode = EndpointMode.MANUAL_PEER, manualPeer = "162.159.192.1:2408")
        assertEquals(ControlAvailability.PINNED, quickControlAvailability(QuickControl.SCAN, pinned, editable = true))
        assertEquals(ControlAvailability.READY, quickControlAvailability(QuickControl.PROTOCOL, pinned, editable = true))
    }

    @Test
    fun aBlankPinnedPeerStillScans() {
        val blank = ConnectionProfile(endpointMode = EndpointMode.MANUAL_PEER, manualPeer = "  ")
        assertEquals(ControlAvailability.READY, quickControlAvailability(QuickControl.SCAN, blank, editable = true))
    }

    @Test
    fun advancedLinksPointAtThePageThatOwnsTheRest() {
        assertEquals(listOf(SettingsPage.CHAIN), advancedPagesFor(QuickControl.ROUTE, ChainMode.AETHER))
        assertEquals(listOf(SettingsPage.CHAIN), advancedPagesFor(QuickControl.ROUTE, ChainMode.PSIPHON))
        ChainMode.entries.filter { it.usesTor }.forEach { chain ->
            assertEquals(listOf(SettingsPage.CHAIN, SettingsPage.BRIDGES), advancedPagesFor(QuickControl.ROUTE, chain))
        }
        ChainMode.entries.forEach { chain ->
            assertEquals(listOf(SettingsPage.CONNECTION), advancedPagesFor(QuickControl.PROTOCOL, chain))
            assertEquals(listOf(SettingsPage.CONNECTION), advancedPagesFor(QuickControl.SCAN, chain))
        }
        // Every linked page writes the profile, so it carries its own lock.
        QuickControl.entries.forEach { control ->
            advancedPagesFor(control, ChainMode.TOR_OVER_PSIPHON_OVER_AETHER).forEach { assertTrue(it.editsProfile) }
        }
    }

    @Test
    fun tilesStackOnNarrowWindowsAndLargeText() {
        assertFalse(quickControlsStacked(320.dp, 1f))   // 360dp phone minus gutters
        assertFalse(quickControlsStacked(520.dp, 1.15f))
        assertTrue(quickControlsStacked(280.dp, 1f))    // split screen
        assertTrue(quickControlsStacked(520.dp, 1.3f))  // large text
        assertTrue(quickControlsStacked(900.dp, 2f))
    }

    @Test
    fun theReserveStillFitsTheDeckOnCommonPhones() {
        // The deck adds a row under the hero; the hero slot must still leave
        // room for it plus the tallest cards, so the orb never resizes.
        listOf(393.dp to 700.dp, 412.dp to 780.dp, 360.dp to 560.dp, 320.dp to 420.dp).forEach { (w, h) ->
            val density = homeDensity(h)
            val side = heroSlotHeight(w, h, density, twoPane = false)
            assertTrue(side + heroReserve(density, twoPane = false) <= h, "$w x $h")
        }
    }
}
