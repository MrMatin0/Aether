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
 */
internal class LogRingBuffer(private val maxLines: Int) {

    private val lines = ArrayDeque<LogLine>(maxLines)
    private val lock = Any()

    fun add(line: LogLine) = synchronized(lock) {
        lines.addLast(line)
        evictLocked()
    }

    /** Used when a previous session is restored from disk. */
    fun replaceAll(restored: List<LogLine>) = synchronized(lock) {
        lines.clear()
        lines.addAll(restored)
        evictLocked()
    }

    fun clear() = synchronized(lock) { lines.clear() }

    fun snapshot(): List<LogLine> = synchronized(lock) { lines.toList() }

    private fun evictLocked() {
        while (lines.size > maxLines) lines.removeFirst()
    }
}
