package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.formatSessionUptime

/**
 * The session timer's rules. Pure on purpose: SystemClock is not available in
 * a local unit test, so the clock is always handed in.
 */
class SessionClockTest {

    private val connected = ConnectionState.Connected("127.0.0.1:1080")

    private val noClock: () -> Long = { fail("the clock must only be read when a stamp is minted") }

    @Test
    fun firstConnectedMintsTheStampFromTheGivenClock() {
        assertEquals(5_000L, SessionClock.next(null, connected) { 5_000L })
    }

    @Test
    fun laterTransitionsNeverReadTheClock() {
        assertEquals(5_000L, SessionClock.next(5_000L, connected, noClock))
        assertEquals(5_000L, SessionClock.next(5_000L, ConnectionState.Reconnecting(1, 3), noClock))
        assertEquals(5_000L, SessionClock.next(5_000L, ConnectionState.Verifying, noClock))
        assertNull(SessionClock.next(5_000L, ConnectionState.Idle, noClock))
    }

    /** The supervisor's reconnect is Reconnecting -> Verifying -> Connected. */
    @Test
    fun aTransientReconnectKeepsTheRunningTimer() {
        var since: Long? = null
        var clock = 1_000L
        val tick = { clock }
        listOf(
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            connected,
        ).forEach { since = SessionClock.next(since, it, tick) }
        assertEquals(1_000L, since)

        clock = 90_000L
        listOf(
            ConnectionState.Reconnecting(1, 3),
            ConnectionState.Verifying,
            connected,
        ).forEach { since = SessionClock.next(since, it, tick) }
        assertEquals(1_000L, since, "a reconnect restarted the session clock")
    }

    @Test
    fun aFreshAttemptNeverInheritsAStamp() {
        // Verifying on the way UP has nothing to keep.
        assertNull(SessionClock.next(null, ConnectionState.Verifying, noClock))
        listOf(
            ConnectionState.Idle,
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Disconnecting,
            ConnectionState.Error("failed"),
        ).forEach { assertNull(SessionClock.next(5_000L, it, noClock), "$it kept the stamp") }
    }

    @Test
    fun wallClockConversionKeepsTheElapsedDuration() {
        val wall = 1_790_000_000_000L
        assertEquals(
            wall - 60_000L,
            SessionClock.toWallClock(stamp = 40_000L, nowElapsed = 100_000L, nowWall = wall),
        )
        // A stamp from the future reads as "just now", never as a negative age.
        assertEquals(
            wall,
            SessionClock.toWallClock(stamp = 200_000L, nowElapsed = 100_000L, nowWall = wall),
        )
    }

    /**
     * The regression itself: a stamp on the SAME timeline as the screen's clock
     * ticks, while an epoch stamp read against elapsedRealtime is stuck on zero.
     */
    @Test
    fun theScreenClockTicksOnlyWhenBothSidesShareATimeline() {
        val bootMillis = 86_400_000L // a phone that has been up for a day
        val since = SessionClock.next(null, connected) { bootMillis }
        assertEquals("00:01:05", formatSessionUptime(since, bootMillis + 65_000L))

        // What the old controller handed the screen: epoch millis.
        val epochStamp = 1_790_000_000_000L
        assertEquals("00:00:00", formatSessionUptime(epochStamp, bootMillis + 65_000L))
    }
}
