package studio.cluvex.aether.core.log

import kotlin.random.Random

/**
 * Retry pacing for the disk writer.
 *
 * WHY THIS IS ITS OWN FILE: the writer used to sleep a hard-coded 200 ms both
 * while it waited for init() to hand over a file AND after a failed append. On
 * a device with full storage or a revoked files directory that is five wake-ups
 * a second, forever, for a write that will never succeed - and it was
 * untestable, because the only way to observe the schedule was to start the
 * thread and watch the clock.
 *
 * EXPONENTIAL WITH FULL-WIDTH JITTER: more than one component can be backing
 * off against the same failing storage at the same moment (the VpnService, the
 * crash handler, the UI process), and an undithered schedule makes them all
 * retry in lockstep - which is the worst possible pattern for a device that is
 * already out of space.
 */
internal object LogBackoff {

    /** Where the first retry lands, roughly. Short: most failures are transient. */
    const val BASE_MS = 50L

    /** Never wait longer than this, or a recovered disk stays idle for minutes. */
    const val MAX_MS = 5_000L

    /** Jitter spread, as a fraction of the ceiling. */
    const val JITTER = 0.25

    /** Doubling stops here so the shift can never overflow a Long. */
    private const val EXPONENT_CAP = 20

    /**
     * Undithered ceiling for a 1-based [attempt]: doubles, then flattens at
     * [MAX_MS]. Pure, so the schedule can be asserted without a clock.
     */
    fun ceiling(attempt: Int): Long {
        val step = (attempt.coerceAtLeast(1) - 1).coerceAtMost(EXPONENT_CAP)
        return (BASE_MS shl step).coerceAtMost(MAX_MS)
    }

    /** [ceilingMs] +/- [JITTER], clamped to 1 ms .. [MAX_MS]. */
    fun jitter(ceilingMs: Long, random: Random): Long {
        val bounded = ceilingMs.coerceIn(1L, MAX_MS)
        val spread = (bounded * JITTER).toLong().coerceAtLeast(1L)
        val low = (bounded - spread).coerceAtLeast(1L)
        val high = (bounded + spread).coerceAtMost(MAX_MS)
        if (high <= low) return low
        return random.nextLong(low, high + 1L)
    }

    fun delayFor(attempt: Int, random: Random = Random.Default): Long =
        jitter(ceiling(attempt), random)
}
