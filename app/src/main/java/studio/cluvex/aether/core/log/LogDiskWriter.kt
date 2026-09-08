package studio.cluvex.aether.core.log

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The single thread that moves queued lines onto disk, so no scanning, UI or
 * connect-path thread ever blocks on flash storage.
 *
 * UNKILLABLE BY DESIGN, with higher stakes than the publisher: if this thread
 * dies, NOTHING is ever persisted again - including the FATAL line written by
 * the crash handler, which is the whole point of having an on-disk log.
 */
internal class LogDiskWriter(
    private val store: LogFile,
    private val pending: PendingWrites,
    private val batchSize: Int,
) {
    private val started = AtomicBoolean(false)

    fun ensureRunning() {
        if (!started.compareAndSet(false, true)) return
        thread(name = "log-writer", isDaemon = true, priority = Thread.MIN_PRIORITY) { loop() }
    }

    private fun loop() {
        val batch = ArrayList<String>(batchSize)
        while (true) {
            try {
                batch.clear()
                pending.takeBatch(batchSize, batch)
                awaitStore()
                if (!store.append(batch)) {
                    requeue(batch)
                    Thread.sleep(FAILURE_BACKOFF_MS)
                }
            } catch (_: InterruptedException) {
                // LOG-LOSS FIX: this used to drop the batch it was holding.
                // Waiting for init() to provide the file is interruptible, so an
                // interrupt at the wrong moment silently deleted the tail of the
                // log - during a crash, the very tail that explains it.
                Thread.interrupted()
                requeue(batch)
            } catch (_: Throwable) {
                // Never let one bad batch end the writer, and back off so a
                // persistent failure cannot spin a core.
                requeue(batch)
                runCatching { Thread.sleep(FAILURE_BACKOFF_MS) }
            }
        }
    }

    /**
     * Blocks until init() has handed over a log file. Waiting beats the old
     * behaviour, which threw the drained batch away when the file was not there
     * yet - i.e. it lost precisely the startup lines.
     */
    private fun awaitStore() {
        while (!store.isReady) Thread.sleep(STORE_WAIT_MS)
    }

    private fun requeue(batch: List<String>) {
        if (batch.isEmpty()) return
        pending.addAll(batch)
    }

    private companion object {
        const val STORE_WAIT_MS = 200L
        const val FAILURE_BACKOFF_MS = 200L
    }
}
