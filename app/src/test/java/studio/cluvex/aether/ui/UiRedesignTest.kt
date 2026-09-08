package studio.cluvex.aether.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.R
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.LogFilter
import studio.cluvex.aether.ui.components.matchesLogFilter
import studio.cluvex.aether.ui.theme.*

class UiRedesignTest {
    private fun contrast(a: Color, b: Color): Float =
        (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)

    @Test fun lightTextAndStateTokensMeetNormalTextContrast() {
        listOf(Paper00, Paper10, Paper20).forEach { surface ->
            listOf(PaperHigh, PaperMid, IrisStrong).forEach { text ->
                assertTrue(contrast(text, surface) >= 4.5f, "Text contrast ${contrast(text, surface)}")
            }
        }
        val a = LightAccents
        listOf(a.brand to a.brandWash, a.protected to a.protectedWash,
            a.working to a.workingWash, a.failed to a.failedWash,
            a.onBrand to a.brand, a.onProtected to a.protected,
            a.onWorking to a.working, a.onFailed to a.failed).forEach { (text, fill) ->
            assertTrue(contrast(text, fill) >= 4.5f, "State contrast ${contrast(text, fill)}")
        }
    }

    @Test fun disconnectingNeverOffersConnectOrCancel() {
        assertEquals(R.string.state_disconnecting, connectionActionLabel(ConnectionState.Disconnecting))
        assertEquals(R.string.state_disconnecting, connectionStatusLabel(ConnectionState.Disconnecting))
    }

    @Test fun everyBusyStageOffersCancellationNotAnotherConnection() {
        listOf(ConnectionState.Launching, ConnectionState.Connecting, ConnectionState.Verifying,
            ConnectionState.Reconnecting(1, 3)).forEach {
            assertEquals(R.string.action_cancel, connectionActionLabel(it))
        }
        assertEquals(R.string.action_connect, connectionActionLabel(ConnectionState.Idle))
        assertEquals(R.string.action_retry, connectionActionLabel(ConnectionState.Error("failure")))
        assertEquals(R.string.action_disconnect, connectionActionLabel(ConnectionState.Connected("127.0.0.1:1080")))
    }

    @Test fun onlyVerifiedStateHasVerifiedStatus() {
        listOf(ConnectionState.Idle, ConnectionState.Launching, ConnectionState.Connecting,
            ConnectionState.Verifying, ConnectionState.Reconnecting(1, 3),
            ConnectionState.Disconnecting, ConnectionState.Error("failure")).forEach {
            assertFalse(connectionStatusLabel(it) == R.string.passage_verified)
        }
        assertEquals(R.string.passage_verified, connectionStatusLabel(ConnectionState.Connected("127.0.0.1:1080")))
    }

    @Test fun warningFilterKeepsErrorsAndRejectsNoise() {
        LogLevel.entries.forEach { assertTrue(matchesLogFilter(it, LogFilter.ALL)) }
        assertTrue(matchesLogFilter(LogLevel.WARN, LogFilter.WARN))
        assertTrue(matchesLogFilter(LogLevel.ERROR, LogFilter.WARN))
        assertFalse(matchesLogFilter(LogLevel.DEBUG, LogFilter.WARN))
        assertFalse(matchesLogFilter(LogLevel.INFO, LogFilter.WARN))
        LogLevel.entries.forEach { assertEquals(it == LogLevel.ERROR, matchesLogFilter(it, LogFilter.ERROR)) }
    }

    /**
     * A destination in no group is unreachable; a destination in two appears
     * twice in the index. Both are navigation bugs, not styling ones.
     */
    @Test fun everySettingsDestinationIsIndexedExactlyOnce() {
        val indexed = SettingsGroup.entries.flatMap { it.pages }
        assertEquals(SettingsPage.entries.size, indexed.size)
        assertEquals(SettingsPage.entries.toSet(), indexed.toSet())
    }

    @Test fun everySettingsDestinationSurvivesEnumRestoration() {
        SettingsPage.entries.forEach { assertEquals(it, SettingsPage.valueOf(it.name)) }
    }

    /** Only the pages that write the profile may be held back mid-session. */
    @Test fun connectionOnlyLocksTheDestinationsThatWriteTheProfile() {
        listOf(SettingsPage.CONNECTION, SettingsPage.CHAIN, SettingsPage.TRANSPORT,
            SettingsPage.ROUTING, SettingsPage.SECURITY, SettingsPage.ORGANIZATION,
            SettingsPage.TUNING, SettingsPage.RESET, SettingsPage.SETUPS)
            .forEach { assertTrue(it.editsProfile, "${it.name} should lock while connected") }
        listOf(SettingsPage.SHARING, SettingsPage.APPEARANCE, SettingsPage.AUTOMATION,
            SettingsPage.HISTORY, SettingsPage.ABOUT)
            .forEach { assertFalse(it.editsProfile, "${it.name} must stay editable while connected") }
        // Saved setups words the lock itself, so the page must not repeat it.
        assertFalse(SettingsPage.SETUPS.showsLockNotice)
        assertTrue(SettingsPage.CONNECTION.showsLockNotice)
    }

    @Test fun blankQueryMatchesEveryDestination() {
        assertTrue(matchesSettingsQuery("", listOf("Routing")))
        assertTrue(matchesSettingsQuery("   ", listOf("Routing")))
    }

    @Test fun everyTermHasToLandSomewhereInTheEntry() {
        val entry = listOf("Security & stability", "Kill switch \u00B7 IPv6 guard")
        assertTrue(matchesSettingsQuery("kill switch", entry))
        assertTrue(matchesSettingsQuery("  IPV6  ", entry))
        assertTrue(matchesSettingsQuery("guard stability", entry))
        assertFalse(matchesSettingsQuery("kill mtu", entry))
    }

    /**
     * Persian typed on a phone keyboard is not byte-identical to Persian in a
     * resource file: Arabic yeh and kaf are everywhere, and a compound word
     * carries a zero-width non-joiner nobody can type in a search box.
     */
    @Test fun persianSearchToleratesArabicLettersAndZeroWidthJoiners() {
        assertTrue(matchesSettingsQuery("\u0645\u0633\u06cc\u0631\u06cc\u0627\u0628\u06cc", listOf("\u0645\u0633\u064a\u0631\u064a\u0627\u0628\u064a")))
        assertTrue(matchesSettingsQuery("\u06a9\u0644\u06cc\u062f", listOf("\u0643\u0644\u064a\u062f \u0642\u0637\u0639")))
        assertTrue(matchesSettingsQuery("\u0632\u0645\u0627\u0646 \u0628\u0646\u062f\u06cc", listOf("\u0632\u0645\u0627\u0646\u200c\u0628\u0646\u062f\u06cc")))
        assertFalse(matchesSettingsQuery("\u067e\u0631\u0627\u06a9\u0633\u06cc", listOf("\u0645\u0633\u06cc\u0631\u06cc\u0627\u0628\u06cc")))
    }

    @Test fun normalizationCollapsesWhitespaceAndCase() {
        assertEquals("kill switch", normalizeSettingsText("  Kill\tSwitch "))
    }
}
