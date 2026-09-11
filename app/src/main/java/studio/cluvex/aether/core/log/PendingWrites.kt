package studio.cluvex.aether.core.log

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * The bounded hand-off between "something logged a line" and "the disk writer
 * got to it".
 *
 * OOM FIX (kept): this queue used to be UNBOUNDED. Two ordinary situations grow
 * it without limit - init() has not run yet (so the writer parks and nothing
 * drains), and the append keeps failing (full storage, revoked directory), in
 * which case every line that could not be written is handed straight BACK.
 * That turns the logger into the thing that kills the process, during the exact
 * failure it was trying to record.
 *
 * TWO CHANGES IN THIS REVISION:
 *
 *  1. BATCH EVICTION. Dropping ONE line per overflowing line means the VPN
 *     packet loop and the probe heartbeats each pay a poll+offer round trip for
 *     every single line once the queue is full, which is precisely when the
 *     device is already struggling. Evicting a slab at a time amortises that.
 *     The slab is capacity/8 with a floor of 1, so a small queue behaves
 *     exactly as it did before.
 *
 *  2. A SUSPENDING TAKE. The writer is a coroutine now, so blocking on
 *     take() would pin a dispatcher thread. Arrivals are announced on a
 *     CONFLATED channel: a signal published between a drain and a receive is
 *     kept, so there is no lost-wake-up window, and a burst of a thousand lines
 *     still costs one wake-up.
 *
 * Still a plain class with no threads of its own: the drop policy is the part
 * worth testing, and it stays testable without starting anything.
 */
internal class PendingWrites(private val capacity: Int) {

    private val queue = LinkedBlockingQueue<String>(capacity)
    private val overflowed = AtomicBoolean(false)

    /** How many lines one overflow evicts. Small queues still evict exactly one. */
    private val evictSlab = (capacity / EVICT_DIVISOR).coerceAtLeast(1)

    /** "Something arrived", conflated: the writer only needs to know THAT. */
    private val arrivals = Channel<Unit>(Channel.CONFLATED)

    val size: Int get() = queue.size

    /**
     * Queues one line, making room by dropping the OLDEST when full.
     *
     * Dropping the oldest rather than the newest is deliberate: the reason this
     * queue ever backs up is that something is going wrong, and the lines that
     * explain what are the ones being written right now.
     */
    fun add(line: String) {
        if (!queue.offer(line)) {
            makeRoom()
            if (overflowed.compareAndSet(false, true)) {
                // Record the loss exactly once, so the on-disk log cannot
                // silently pretend to be complete.
                makeRoom()
                queue.offer(OVERFLOW_MARKER)
            }
            queue.offer(line)
        }
        arrivals.trySend(Unit)
    }

    fun addAll(lines: List<String>) = lines.forEach { add(it) }

    /**
     * Suspends until at least one line is queued, then sweeps up to [max] of
     * them into [into]. Cancellation-safe: nothing has left the queue until
     * this returns.
     */
    suspend fun awaitBatch(max: Int, into: MutableList<String>) {
        while (true) {
            queue.drainTo(into, max)
            if (into.isNotEmpty()) return
            arrivals.receive()
        }
    }

    /** Everything queued right now, oldest first. Does not block. */
    fun drainNow(): MutableList<String> {
        val drained = ArrayList<String>()
        queue.drainTo(drained)
        return drained
    }

    /** Forgets everything queued: a new session must not inherit old lines. */
    fun clear() {
        queue.clear()
        overflowed.set(false)
    }

    private fun makeRoom() {
        repeat(evictSlab) { if (queue.poll() == null) return }
    }

    companion object {
        const val OVERFLOW_MARKER = "\u2014\u2014 log write queue overflowed; older lines were dropped \u2014\u2014"

        /** capacity/8 per overflow. Empirically one flash write's worth of lines. */
        private const val EVICT_DIVISOR = 8
    }
}
