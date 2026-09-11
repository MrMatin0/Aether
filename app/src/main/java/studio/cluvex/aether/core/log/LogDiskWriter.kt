package studio.cluvex.aether.core.log

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Moves queued lines onto disk, so no scanning, UI or connect-path thread ever
 * blocks on flash storage.
 *
 * UNKILLABLE BY DESIGN, with higher stakes than the publisher: if this drain
 * stops, NOTHING is ever persisted again - including the FATAL line written by
 * the crash handler, which is the whole point of having an on-disk log.
 *
 * WHAT THIS REPLACES, and why each piece had to go:
 *
 *  * A raw daemon thread with `while (true)` around a BLOCKING take(). It held
 *    a thread for the life of the process to do a few kilobytes of I/O a
 *    minute, and the only way to stop it was an interrupt - which is why the
 *    old code had to catch InterruptedException and hand the batch back by
 *    hand. A structured coroutine cancels cleanly and the re-queue is a
 *    `finally`, not a special case.
 *
 *  * `while (!store.isReady) Thread.sleep(200)`. init() runs from
 *    Application.onCreate, so this spin is the NORMAL startup path, and 200 ms
 *    is both too slow to catch a file that appears immediately and too fast for
 *    one that never will. It is now the same jittered exponential schedule as
 *    an I/O failure ([LogBackoff]).
 *
 *  * A hard-coded 200 ms failure poll. Full storage is not transient; five
 *    retries a second against it burns a core and fills the log with its own
 *    failure. Backoff walks out to 5 s and stays there.
 *
 *  * The teardown drop. A cancellation between the drain and the append used to
 *    lose the batch in flight - during a crash, the very tail that explains it.
 *    [flushBlocking] closes the other half of that hole: it queues first and
 *    writes second, so a line can never be off the queue and not yet on disk.
 */
internal class LogDiskWriter(
    private val store: LogFile,
    private val pending: PendingWrites,
    private val batchSize: Int,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("log-writer"),
    ),
) {
    private val started = AtomicBoolean(false)

    fun ensureRunning() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { drain() }
    }

    private suspend fun CoroutineScope.drain() {
        val batch = ArrayList<String>(batchSize)
        var failures = 0
        try {
            while (isActive) {
                try {
                    batch.clear()
                    // Nothing leaves the queue until there is something to take.
                    pending.awaitBatch(batchSize, batch)
                    awaitStore()
                    if (store.append(batch)) {
                        failures = 0
                        batch.clear()
                    } else {
                        failures++
                        requeue(batch)
                        batch.clear()
                        delay(LogBackoff.delayFor(failures))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Never let one bad batch end the writer, and back off so a
                    // persistent failure cannot spin a core.
                    failures++
                    requeue(batch)
                    batch.clear()
                    delay(LogBackoff.delayFor(failures))
                }
            }
        } finally {
            // LOG-LOSS FIX: whatever this drain was holding goes back, on every
            // exit path including cancellation. The old thread dropped it.
            requeue(batch)
            started.set(false)
        }
    }

    /**
     * Suspends until init() has handed over a log file. Waiting beats the
     * original behaviour, which threw the drained batch away when the file was
     * not there yet - i.e. it lost precisely the startup lines.
     */
    private suspend fun awaitStore() {
        var waits = 0
        while (!store.isReady) {
            waits++
            delay(LogBackoff.delayFor(waits))
        }
    }

    /**
     * Drains the queue onto disk ON THE CALLING THREAD, for the crash handler.
     *
     * The normal path hands lines to a coroutine, but no dispatcher is
     * guaranteed to be scheduled again once the process is dying - which is
     * exactly the case the crash handler cares about, and why the FATAL line
     * could be missing from the very log that was meant to explain the crash.
     *
     * Returns true when the queue is empty afterwards. False means the lines
     * are STILL QUEUED (no file yet, or the write failed), never that they were
     * dropped. Bounded rounds, so a producer logging in a tight loop on another
     * thread cannot keep this call here forever.
     */
    fun flushBlocking(): Boolean {
        if (!store.isReady) return false
        repeat(MAX_FLUSH_ROUNDS) {
            val batch = pending.drainNow()
            if (batch.isEmpty()) return true
            if (!store.append(batch)) {
                pending.addAll(batch)
                return false
            }
        }
        return pending.size == 0
    }

    private fun requeue(batch: List<String>) {
        if (batch.isEmpty()) return
        pending.addAll(batch)
    }

    private companion object {
        const val MAX_FLUSH_ROUNDS = 8
    }
}
