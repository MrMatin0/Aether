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

    @Test fun engineSectionsHaveUniqueLabelsAndSurviveEnumRestoration() {
        assertEquals(6, EnginePage.entries.size)
        assertEquals(EnginePage.entries.size, EnginePage.entries.map { it.title }.toSet().size)
        EnginePage.entries.forEach { assertEquals(it, EnginePage.valueOf(it.name)) }
    }
}
