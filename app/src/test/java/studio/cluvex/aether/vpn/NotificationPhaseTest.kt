package studio.cluvex.aether.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionState

class NotificationPhaseTest {

    @Test
    fun everyInFlightStateIsBusy() {
        listOf(
            ConnectionState.Idle,
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            ConnectionState.Reconnecting(attempt = 1, maxAttempts = 3),
        ).forEach { assertEquals(NotifPhase.BUSY, phaseOf(it), "state=$it") }
    }

    @Test
    fun terminalStatesHaveTheirOwnPhase() {
        assertEquals(NotifPhase.CONNECTED, phaseOf(ConnectionState.Connected("127.0.0.1:1819")))
        assertEquals(NotifPhase.ERROR, phaseOf(ConnectionState.Error("boom")))
        assertEquals(NotifPhase.CLOSING, phaseOf(ConnectionState.Disconnecting))
    }

    @Test
    fun routeStartsWhereTrafficEnters() {
        assertEquals(
            "Tor \u2192 Psiphon \u2192 Aether",
            routeLabel(ChainMode.TOR_OVER_PSIPHON_OVER_AETHER),
        )
        assertEquals("Psiphon \u2192 Aether", routeLabel(ChainMode.PSIPHON_OVER_AETHER))
        assertEquals("Aether", routeLabel(ChainMode.AETHER))
        assertEquals("", routeLabel(null))
    }
}
