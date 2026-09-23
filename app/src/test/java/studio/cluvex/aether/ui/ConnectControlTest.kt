package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.ButtonMode

class ConnectControlTest {
    /** Teardown is the one state where the orb must not take another tap. */
    @Test fun orbIsDisabledOnlyWhileDisconnecting() {
        assertFalse(connectControlEnabled(ConnectionState.Disconnecting))
        listOf(
            ConnectionState.Idle,
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            ConnectionState.Reconnecting(1, 3),
            ConnectionState.Connected("127.0.0.1:1080"),
            ConnectionState.Error("failed"),
        ).forEach { assertTrue(connectControlEnabled(it), "$it should accept a tap") }
    }

    /** Every busy stage must stay cancellable from the orb. */
    @Test fun busyStagesStayCancellable() {
        listOf(
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            ConnectionState.Reconnecting(2, 3),
        ).forEach {
            assertEquals(ButtonMode.BUSY, buttonMode(it))
            assertTrue(connectControlEnabled(it))
        }
    }
}
