package studio.cluvex.aether.core.log

import studio.cluvex.aether.core.LogLine

/**
 * Bounded in-memory log buffer, mutated IN PLACE.
 *
 * PERFORMANCE (this is why it exists): the 1.2.1 logger did
 * `_lines.value = _lines.value + line` on EVERY line, then `takeLast(800)` on
 * top - two fresh ~800-element lists allocated per log write, several times a
 * second during a scan. That is what made the app feel heavy and kept the GC
 * busy while connecting.
 *
 * Appending and eviction are both O(1) here, and a snapshot is taken only when
 * the publisher actually hands one to the UI.
 *
 * NEW: THE SNAPSHOT IS CACHED BEHIND A REVISION COUNTER. The publisher ticks on
 * a fixed interval, so a quiet buffer used to re-copy the same 800 elements
 * several times a second for a list that had not changed. Now an unchanged
 * buffer hands back the SAME instance, which StateFlow compares by equality and
 * drops - so "nothing new" costs one reference compare and zero recompositions,
 * and a burst of engine lines still costs exactly one copy per publish tick.
 */
internal class LogRingBuffer(private val maxLines: Int) {

    private val lines = ArrayDeque<LogLine>(maxLines)
    private val lock = Any()

    private var revision = 0L
    private var cached: List<LogLine> = emptyList()
    private var cachedRevision = 0L

    fun add(line: LogLine) {
        synchronized(lock) {
            lines.addLast(line)
            evictLocked()
            revision++
        }
    }

    /** One lock acquisition for a whole batch, not one per line. */
    fun addAll(batch: List<LogLine>) {
        if (batch.isEmpty()) return
        synchronized(lock) {
            lines.addAll(batch)
            evictLocked()
            revision++
        }
    }

    /** Used when a previous session is restored from disk. */
    fun replaceAll(restored: List<LogLine>) {
        synchronized(lock) {
            lines.clear()
            lines.addAll(restored)
            evictLocked()
            revision++
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            revision++
            cached = emptyList()
            cachedRevision = revision
        }
    }

    /**
     * An immutable view of the buffer. Identical to the previous return value -
     * the same object, not just an equal one - when nothing has been logged
     * since, so the caller can hand it straight to a StateFlow.
     */
    fun snapshot(): List<LogLine> = synchronized(lock) {
        if (cachedRevision != revision || cached.size != lines.size) {
            cached = lines.toList()
            cachedRevision = revision
        }
        cached
    }

    /** Monotonic change counter. Lets a consumer skip work without diffing. */
    val revisionNow: Long get() = synchronized(lock) { revision }

    /**
     * Batch eviction: one pass for the whole overflow instead of a loop that
     * re-tests the size after every single removal. Matters when a restored
     * session or a packet-loop burst arrives all at once.
     */
    private fun evictLocked() {
        val excess = lines.size - maxLines
        if (excess <= 0) return
        repeat(excess) { lines.removeFirst() }
    }
}
