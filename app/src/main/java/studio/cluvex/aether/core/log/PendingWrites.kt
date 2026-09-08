package studio.cluvex.aether.core.log

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bounded hand-off between "something logged a line" and "the disk writer
 * got to it".
 *
 * OOM FIX: this queue used to be UNBOUNDED. Two ordinary situations grow it
 * without limit - init() has not run yet (so the writer parks and nothing
 * drains), and the append keeps failing (full storage, revoked directory), in
 * which case every line that could not be written is handed straight BACK. That
 * turns the logger into the thing that kills the process, during the exact
 * failure it was trying to record.
 *
 * It is a plain class with no threads on purpose: the drop policy is the part
 * worth testing, and it is now testable without starting anything.
 */
internal class PendingWrites(capacity: Int) {

    private val queue = LinkedBlockingQueue<String>(capacity)
    private val overflowed = AtomicBoolean(false)

    /**
     * Queues one line, making room by dropping the OLDEST when full.
     *
     * Dropping the oldest rather than the newest is deliberate: the reason this
     * queue ever backs up is that something is going wrong, and the lines that
     * explain what are the ones being written right now.
     */
    fun add(line: String) {
        if (queue.offer(line)) return
        makeRoom()
        if (overflowed.compareAndSet(false, true)) {
            // Record the loss exactly once, so the on-disk log cannot silently
            // pretend to be complete.
            makeRoom()
            queue.offer(OVERFLOW_MARKER)
        }
        queue.offer(line)
    }

    fun addAll(lines: List<String>) = lines.forEach { add(it) }

    /** Blocks for the first line, then sweeps up whatever else has queued. */
    fun takeBatch(max: Int, into: MutableList<String>) {
        into.add(queue.take())
        queue.drainTo(into, max)
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
        queue.poll()
    }

    companion object {
        const val OVERFLOW_MARKER = "—— log write queue overflowed; older lines were dropped ——"
    }
}
