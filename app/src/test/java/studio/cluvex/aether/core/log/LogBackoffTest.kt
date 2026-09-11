package studio.cluvex.aether.core.log

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The disk writer's retry schedule.
 *
 * This is the whole reason the backoff was pulled out of the writer: the old
 * behaviour was a hard-coded 200 ms Thread.sleep, and the only way to observe it
 * was to start a daemon thread and watch a clock. A schedule that is a pure
 * function can simply be asserted.
 */
class LogBackoffTest {

    @Test
    fun `the first retry is short and every one after it doubles`() {
        assertEquals(LogBackoff.BASE_MS, LogBackoff.ceiling(1))
        assertEquals(LogBackoff.BASE_MS * 2, LogBackoff.ceiling(2))
        assertEquals(LogBackoff.BASE_MS * 4, LogBackoff.ceiling(3))
        assertEquals(LogBackoff.BASE_MS * 8, LogBackoff.ceiling(4))
    }

    /** A caller counting from zero must not be handed a zero wait. */
    @Test
    fun `a zero or negative attempt is treated as the first`() {
        assertEquals(LogBackoff.ceiling(1), LogBackoff.ceiling(0))
        assertEquals(LogBackoff.ceiling(1), LogBackoff.ceiling(-7))
    }

    /**
     * Full storage is not transient, so the schedule has to walk out - but a
     * recovered disk must not wait minutes, and the shift must not overflow no
     * matter how long the failure lasts.
     */
    @Test
    fun `the schedule rises monotonically, caps, and never overflows`() {
        assertEquals(LogBackoff.MAX_MS, LogBackoff.ceiling(100))
        assertEquals(LogBackoff.MAX_MS, LogBackoff.ceiling(Int.MAX_VALUE))
        var previous = 0L
        (1..64).forEach { attempt ->
            val next = LogBackoff.ceiling(attempt)
            assertTrue(next >= previous, "attempt $attempt went backwards")
            assertTrue(next in 1L..LogBackoff.MAX_MS, "attempt $attempt out of range: $next")
            previous = next
        }
    }

    @Test
    fun `jitter stays inside its window and is not a fixed schedule`() {
        val random = Random(20260911)
        val ceiling = LogBackoff.ceiling(4)
        val spread = (ceiling * LogBackoff.JITTER).toLong()
        val samples = (1..200).map { LogBackoff.delayFor(4, random) }
        samples.forEach {
            assertTrue(it in 1L..LogBackoff.MAX_MS, "out of range: $it")
            assertTrue(it >= ceiling - spread && it <= ceiling + spread, "outside window: $it")
        }
        assertTrue(samples.distinct().size > 1, "jitter produced one value, i.e. lockstep retries")
    }

    /**
     * The point of jitter is that two processes backing off against the same
     * failing storage do not retry together - which has to keep holding once the
     * ceiling is pinned at the cap.
     */
    @Test
    fun `a capped wait still varies below the cap`() {
        val random = Random(7)
        val samples = (1..100).map { LogBackoff.delayFor(40, random) }
        assertTrue(samples.all { it in 1L..LogBackoff.MAX_MS })
        assertTrue(samples.any { it < LogBackoff.MAX_MS }, "a capped wait must still vary")
    }
}
