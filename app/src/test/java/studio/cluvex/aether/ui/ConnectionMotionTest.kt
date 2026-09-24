package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.tickLit

/**
 * Motion and readouts on the connection tab: the running phase's light travels
 * the way progress does in both scripts, the orb's ladder never lights on an
 * empty ring, and the orb carries the right clock for the state.
 */
class ConnectionMotionTest {

    private val left = 100f
    private val width = 80f
    private val band = 44f
    private val eps = 0.001f

    @Test
    fun englishBandEntersLeftAndLeavesRight() {
        assertEquals(left - band, shimmerBandStart(left, width, band, 0f, rtl = false), eps)
        assertEquals(left + width, shimmerBandStart(left, width, band, 1f, rtl = false), eps)
    }

    @Test
    fun persianBandEntersRightAndLeavesLeft() {
        assertEquals(left + width, shimmerBandStart(left, width, band, 0f, rtl = true), eps)
        assertEquals(left - band, shimmerBandStart(left, width, band, 1f, rtl = true), eps)
    }

    /** The reported bug: in Persian the light has to move leftwards, every frame. */
    @Test
    fun persianBandMovesLeftwardsThroughTheWholeSweep() {
        val starts = (0..20).map { shimmerBandStart(left, width, band, it / 20f, rtl = true) }
        starts.zipWithNext().forEach { (a, b) -> assertTrue(b < a, "band moved right: $a -> $b") }
    }

    @Test
    fun englishBandMovesRightwardsThroughTheWholeSweep() {
        val starts = (0..20).map { shimmerBandStart(left, width, band, it / 20f, rtl = false) }
        starts.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "band moved left: $a -> $b") }
    }

    @Test
    fun theTwoDirectionsAreMirrorImages() {
        (0..10).map { it / 10f }.forEach { phase ->
            val ltr = shimmerBandStart(left, width, band, phase, rtl = false)
            val rtl = shimmerBandStart(left, width, band, phase, rtl = true)
            // The two band centres sit symmetrically about the track's centre.
            assertEquals(2f * left + width, (ltr + band / 2f) + (rtl + band / 2f), eps)
        }
    }

    @Test
    fun outOfRangePhasesAreClamped() {
        assertEquals(
            shimmerBandStart(left, width, band, 0f, rtl = true),
            shimmerBandStart(left, width, band, -0.5f, rtl = true),
            eps,
        )
        assertEquals(
            shimmerBandStart(left, width, band, 1f, rtl = false),
            shimmerBandStart(left, width, band, 1.5f, rtl = false),
            eps,
        )
    }

    @Test
    fun anEmptyRingLightsNoTickNotEvenAtTwelve() {
        assertFalse(tickLit(fraction = 0f, sweep = 0f, mode = ButtonMode.IDLE))
        (0 until 36).forEach { assertFalse(tickLit(it / 36f, 0f, ButtonMode.IDLE)) }
    }

    @Test
    fun theLadderLightsExactlyTheArc() {
        assertTrue(tickLit(0f, 0.25f, ButtonMode.BUSY))
        assertTrue(tickLit(0.25f, 0.25f, ButtonMode.BUSY))
        assertFalse(tickLit(0.26f, 0.25f, ButtonMode.BUSY))
        (0 until 36).forEach { assertTrue(tickLit(it / 36f, 1f, ButtonMode.CONNECTED)) }
    }

    @Test
    fun aFailedRingIsBrokenNotFilled() {
        (0 until 36).forEach { assertFalse(tickLit(it / 36f, 1f, ButtonMode.ERROR)) }
    }

    @Test
    fun theOrbCarriesTheAttemptClockWhileWorking() {
        listOf(
            ConnectionState.Launching,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            ConnectionState.Reconnecting(1, 3),
        ).forEach { assertEquals("attempt", orbDetail(it, 5_000L, "attempt", "session"), "$it") }
    }

    @Test
    fun theOrbCarriesTheSessionClockOnceVerified() {
        val connected = ConnectionState.Connected("127.0.0.1:1080")
        assertEquals("session", orbDetail(connected, 5_000L, "attempt", "session"))
        // No stamp yet: no readout, rather than a placeholder in the hero.
        assertNull(orbDetail(connected, null, "attempt", "session"))
    }

    @Test
    fun theOrbCarriesNoClockAtRestOrOnTheWayDown() {
        listOf(
            ConnectionState.Idle,
            ConnectionState.Disconnecting,
            ConnectionState.Error("failed"),
        ).forEach { assertNull(orbDetail(it, 5_000L, "attempt", "session"), "$it") }
    }
}
