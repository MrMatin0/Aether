package studio.cluvex.aether.core.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hands the UI at most ONE immutable snapshot per [intervalMs], so a burst of a
 * hundred engine lines costs one recomposition instead of a hundred.
 *
 * WHAT THIS REPLACES: a raw daemon thread doing `Thread.sleep(interval)` around
 * a dirty AtomicBoolean. Two problems with that, beyond the thread itself:
 *
 *  1. It woke up on the interval whether or not anything had been logged, so an
 *     idle app paid five wake-ups a second forever.
 *  2. It was written to be unkillable by catching everything, because a single
 *     InterruptedException retired the publisher for the rest of the process -
 *     and a dead publisher means the diagnostics panel freezes on whatever
 *     snapshot it last saw, which is worst exactly when the user is watching it
 *     to understand a failure.
 *
 * Now it is a coroutine collecting `sample(intervalMs)` over a shared flow of
 * dirty marks. sample() is the operator this hand-rolled loop was imitating: it
 * emits the LATEST mark once per window and nothing at all in a window with no
 * marks. The unkillable property is kept - the collect is restarted on any
 * non-cancellation throw - but it is now a property of a supervised coroutine
 * rather than of a bare catch-all.
 */
internal class LogPublisher(
    private val intervalMs: Long,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("log-publisher"),
    ),
    private val publish: () -> Unit,
) {
    /**
     * replay = 1 so a mark that arrives before the collector is running is not
     * lost; DROP_OLDEST because only the most recent mark in a window matters.
     */
    private val marks = MutableSharedFlow<Long>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val started = AtomicBoolean(false)
    private val revision = AtomicLong(0L)

    /** Marks the buffer changed and makes sure the publisher is running. */
    fun markDirty() {
        ensureRunning()
        marks.tryEmit(revision.incrementAndGet())
    }

    fun ensureRunning() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { pump() }
    }

    @OptIn(FlowPreview::class)
    private suspend fun CoroutineScope.pump() {
        while (isActive) {
            try {
                marks.sample(intervalMs).collect {
                    // A snapshot that could not be published is not worth the
                    // panel going dark for the rest of the session.
                    runCatching { publish() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Restart the window rather than retiring the publisher.
            }
            delay(intervalMs)
        }
    }
}
