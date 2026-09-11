package studio.cluvex.aether.core.log

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The queue's two new properties: a suspending hand-off with no lost wake-ups,
 * and slab eviction.
 */
class PendingWritesHandoffTest {

    @Test
    fun `a queued batch is handed over without suspending`() = runBlocking {
        val pending = PendingWrites(8)
        pending.addAll(listOf("a", "b", "c"))

        val batch = ArrayList<String>()
        withTimeout(TIMEOUT_MS) { pending.awaitBatch(2, batch) }

        assertEquals(listOf("a", "b"), batch)
        assertEquals(1, pending.size)
    }

    /**
     * THE LOST-WAKE-UP CASE. The writer drains, finds nothing, and only then
     * parks. A line published in that window must still wake it, or the log stops
     * reaching disk until the next line happens to arrive.
     */
    @Test
    fun `a line published while nothing is waiting still wakes the drain`() = runBlocking {
        val pending = PendingWrites(8)
        val batch = ArrayList<String>()

        launch { pending.add("late arrival") }
        withTimeout(TIMEOUT_MS) { pending.awaitBatch(4, batch) }

        assertEquals(listOf("late arrival"), batch)
    }

    /**
     * Dropping ONE line per overflowing line means the packet loop pays a
     * poll+offer round trip per line once the queue is full - precisely when the
     * device is already struggling. A slab amortises it.
     */
    @Test
    fun `a large queue evicts a slab, not a line`() {
        val pending = PendingWrites(16)
        (1..16).forEach { pending.add(it.toString()) }
        pending.add("17")

        val drained = pending.drainNow()
        assertTrue(drained.contains("17"), "the newest line must survive")
        assertEquals(1, drained.count { it == PendingWrites.OVERFLOW_MARKER })
        // Two slabs of two went: one to make room, one for the overflow marker.
        assertFalse(drained.contains("1"))
        assertFalse(drained.contains("4"))
        assertTrue(drained.contains("5"))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
