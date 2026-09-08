package studio.cluvex.aether.core.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine

/**
 * The log pipeline, one piece at a time.
 *
 * The logger is the record of an incident, so its failure modes are
 * load-bearing - and until this refactor none of them could be tested, because
 * the buffer, the queue, the writer thread and the file were one object with two
 * shared locks.
 */
class LogPipelineTest {

    // ---- LogRingBuffer -----------------------------------------------------

    @Test
    fun `the buffer keeps the newest lines and evicts the oldest`() {
        val buffer = LogRingBuffer(3)
        repeat(5) { buffer.add(line("m$it")) }
        assertEquals(listOf("m2", "m3", "m4"), buffer.snapshot().map { it.message })
    }

    @Test
    fun `a restored session is trimmed to the cap`() {
        val buffer = LogRingBuffer(2)
        buffer.replaceAll(listOf(line("a"), line("b"), line("c")))
        assertEquals(listOf("b", "c"), buffer.snapshot().map { it.message })
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
    }

    // ---- PendingWrites -----------------------------------------------------

    @Test
    fun `a full queue drops the oldest lines and records the loss once`() {
        val pending = PendingWrites(3)
        listOf("1", "2", "3", "4").forEach { pending.add(it) }

        val afterOverflow = pending.drainNow()
        // The newest line survives, the oldest are gone, and the log says so.
        assertEquals(listOf("3", PendingWrites.OVERFLOW_MARKER, "4"), afterOverflow)

        pending.addAll(afterOverflow)
        pending.add("5")
        val second = pending.drainNow()
        assertTrue(second.contains("5"))
        assertEquals(1, second.count { it == PendingWrites.OVERFLOW_MARKER })
    }

    @Test
    fun `clearing forgets queued lines so a new session starts empty`() {
        val pending = PendingWrites(8)
        pending.addAll(listOf("old", "lines"))
        pending.clear()
        assertTrue(pending.drainNow().isEmpty())
    }

    // ---- LogFile -----------------------------------------------------------

    @Test
    fun `attaching preserves the previous session and returns its lines`() {
        val target = tempLog()
        target.writeText("crashed line 1\ncrashed line 2\n")

        val store = LogFile(maxBytes = 4096L, keepLinesOnTrim = 4)
        val previous = store.attach(target)

        assertEquals(listOf("crashed line 1", "crashed line 2"), previous)
        assertTrue(File(target.parentFile, target.name + ".prev").exists())
        assertTrue(store.isReady)
    }

    @Test
    fun `an unattached file reports failure instead of swallowing lines`() {
        val store = LogFile(maxBytes = 4096L, keepLinesOnTrim = 4)
        assertFalse(store.isReady)
        assertFalse(store.append(listOf("nowhere to go")))
    }

    @Test
    fun `an oversized file is trimmed to its most recent lines`() {
        val target = tempLog()
        val store = LogFile(maxBytes = 4L, keepLinesOnTrim = 2)
        store.attach(target)

        assertTrue(store.append(listOf("a", "b", "c", "d")))
        assertEquals(listOf("c", "d"), target.readLines())
    }

    @Test
    fun `resetting rotates the file aside and empties it`() {
        val target = tempLog()
        val store = LogFile(maxBytes = 4096L, keepLinesOnTrim = 4)
        store.attach(target)
        store.append(listOf("session one"))

        store.reset()

        assertEquals("", target.readText())
        assertTrue(File(target.parentFile, target.name + ".prev").readText().contains("session one"))
    }

    // ---- fixtures ----------------------------------------------------------

    private fun line(message: String) = LogLine(0L, "test", LogLevel.DEBUG, message)

    private fun tempLog(): File =
        File.createTempFile("aether-diagnostics", ".log").apply { deleteOnExit() }
}
