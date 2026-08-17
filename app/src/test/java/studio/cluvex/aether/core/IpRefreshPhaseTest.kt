package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import studio.cluvex.aether.model.ConnectionState

class IpRefreshPhaseTest {
    @Test
    fun connectedStateUsesTunnelIp() {
        assertEquals(
            IpRefreshPhase.CONNECTED,
            IpRefreshPhase.from(ConnectionState.Connected("127.0.0.1:1819")),
        )
    }

    @Test
    fun transitionalStatesDoNotPublishAnIp() {
        assertEquals(IpRefreshPhase.BUSY, IpRefreshPhase.from(ConnectionState.Launching))
        assertEquals(IpRefreshPhase.BUSY, IpRefreshPhase.from(ConnectionState.Verifying))
        assertEquals(IpRefreshPhase.BUSY, IpRefreshPhase.from(ConnectionState.Disconnecting))
    }

    @Test
    fun idleAndErrorStatesUseDirectIp() {
        assertEquals(IpRefreshPhase.IDLE, IpRefreshPhase.from(ConnectionState.Idle))
        assertEquals(IpRefreshPhase.IDLE, IpRefreshPhase.from(ConnectionState.Error("failed")))
    }
}
