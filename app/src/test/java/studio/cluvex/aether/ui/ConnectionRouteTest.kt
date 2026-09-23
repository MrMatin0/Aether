package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionState

/**
 * The route card and the orb must never disagree about how far an attempt got,
 * and the traffic meter must read on both an idle tunnel and a download.
 */
class ConnectionRouteTest {

    @Test
    fun routeLightsFollowThePhaseArithmetic() {
        // You -> Aether -> Internet
        assertEquals(1, litRouteNodes(ConnectionState.Idle, null, 3))
        assertEquals(1, litRouteNodes(ConnectionState.Launching, 0, 3))
        assertEquals(2, litRouteNodes(ConnectionState.Connecting, 1, 3))
        assertEquals(3, litRouteNodes(ConnectionState.Verifying, 2, 3))
        assertEquals(3, litRouteNodes(ConnectionState.Connected("127.0.0.1:1080"), null, 3))
    }

    @Test
    fun failureAndTeardownKeepOnlyTheDeviceLit() {
        assertEquals(1, litRouteNodes(ConnectionState.Error("failed"), null, 5))
        assertEquals(1, litRouteNodes(ConnectionState.Disconnecting, null, 5))
    }

    @Test
    fun longChainsNeverOverflowOrGoDark() {
        listOf(0, 1, 2).forEach { step ->
            val lit = litRouteNodes(ConnectionState.Connecting, step, 5)
            assertTrue(lit in 1..5, "step $step lit $lit")
        }
        assertEquals(0, litRouteNodes(ConnectionState.Idle, null, 0))
    }

    @Test
    fun rateLevelIsLogScaledAndClamped() {
        assertEquals(0f, rateLevel(0L))
        assertEquals(0f, rateLevel(1024L))
        assertTrue(rateLevel(100L * 1024L) in 0.3f..0.6f)
        assertTrue(rateLevel(50L * 1024L * 1024L) >= 0.999f)
        assertEquals(1f, rateLevel(Long.MAX_VALUE))
        assertTrue(rateLevel(10L * 1024L) < rateLevel(1024L * 1024L))
    }
}
