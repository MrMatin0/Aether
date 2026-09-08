package studio.cluvex.aether.core.log

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Hands the UI at most ONE immutable snapshot per [intervalMs], so a burst of a
 * hundred engine lines costs one recomposition instead of a hundred.
 *
 * UNKILLABLE BY DESIGN: the loop used to be a bare `while (true)` around
 * Thread.sleep with no catch at all, so a single InterruptedException (or any
 * unexpected throw) retired the publisher for the rest of the process - and a
 * dead publisher means the diagnostics panel freezes on whatever snapshot it
 * last saw, which is worst exactly when the user is watching it to understand a
 * failure.
 *
 * The start flag is a CAS of its own rather than a shared lock: starting a
 * thread has nothing to do with appending a line, and the two used to contend.
 */
internal class LogPublisher(
    private val intervalMs: Long,
    private val publish: () -> Unit,
) {
    private val dirty = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** Marks the buffer changed and makes sure the publisher is running. */
    fun markDirty() {
        dirty.set(true)
        ensureRunning()
    }

    private fun ensureRunning() {
        if (!started.compareAndSet(false, true)) return
        thread(name = "log-publisher", isDaemon = true, priority = Thread.MIN_PRIORITY) { loop() }
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(intervalMs)
                if (dirty.compareAndSet(true, false)) publish()
            } catch (_: InterruptedException) {
                // Nothing interrupts this thread on purpose; clear the flag and
                // keep publishing rather than going silent.
                Thread.interrupted()
            } catch (_: Throwable) {
                // A snapshot that could not be published is not worth the panel
                // going dark for the rest of the session.
            }
        }
    }
}
