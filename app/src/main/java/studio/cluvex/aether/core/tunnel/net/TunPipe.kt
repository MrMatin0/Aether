package studio.cluvex.aether.core.tunnel.net

import studio.cluvex.aether.core.LogRepository
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns everything about WRITING to the TUN: the two outbound queues, the writer
 * thread, and the byte counter.
 *
 * Splitting this out is what makes [studio.cluvex.aether.core.tunnel.SocksTunBridge]
 * readable — the queue-priority and eviction rules below are subtle, and they
 * used to be spread across a field declaration, a thread body and a method 150
 * lines apart from each other.
 *
 * The two queues exist because they have DIFFERENT delivery guarantees:
 *
 *  - [dataQueue] carries TCP data segments and applies BACK-PRESSURE. A data
 *    segment's sequence number is committed the moment it is enqueued, so
 *    silently dropping one leaves a hole no retransmission can ever fill.
 *  - [controlQueue] carries SYN/ACK, ACK, FIN, RST and synthesized DNS answers.
 *    It is always drained first, and when it saturates evicting another control
 *    packet is recoverable: every peer retransmits all of these.
 *
 * The original single queue evicted indiscriminately, which is exactly how it
 * corrupted streams under load.
 */
internal class TunPipe(
    private val isRunning: () -> Boolean
) {
    private val dataQueue = LinkedBlockingQueue<ByteArray>(DATA_CAPACITY)
    private val controlQueue = LinkedBlockingQueue<ByteArray>(CONTROL_CAPACITY)

    /** Bytes handed to the TUN, i.e. downstream traffic from the app's view. */
    val bytesWritten = AtomicLong(0)

    @Volatile
    private var writer: Thread? = null

    fun start(rawOutput: FileOutputStream) {
        writer = Thread({ pump(rawOutput) }, "Aether-TunWriter").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        writer?.interrupt()
        writer = null
        dataQueue.clear()
        controlQueue.clear()
    }

    /**
     * Queues a packet for the TUN. Returns false when it could not be queued at
     * all — callers that already committed a sequence number MUST treat that as
     * fatal for the session rather than ignoring it.
     */
    fun offer(packet: ByteArray, critical: Boolean = false): Boolean {
        if (critical) {
            if (controlQueue.offer(packet)) return true
            controlQueue.poll()
            return controlQueue.offer(packet)
        }
        if (dataQueue.offer(packet)) return true
        return runCatching {
            dataQueue.offer(packet, DATA_BACKPRESSURE_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    private fun pump(rawOutput: FileOutputStream) {
        // OWNERSHIP: the stream is closed when the pump exits. The original code
        // opened it inline in the thread body and never closed it.
        rawOutput.use { out ->
            while (isRunning()) {
                try {
                    // THROUGHPUT: park on the queue only while it is EMPTY. Once
                    // there is work, drain it in a tight loop instead of paying a
                    // timed poll (lock + park + timer arm) per packet — at
                    // download rates the queue is never empty, so that overhead
                    // was charged ~10k times a second for nothing.
                    var packet = controlQueue.poll() ?: dataQueue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS)
                    if (packet == null) continue

                    var written = 0L
                    while (packet != null) {
                        out.write(packet)
                        written += packet.size
                        packet = controlQueue.poll() ?: dataQueue.poll()
                    }
                    bytesWritten.addAndGet(written)
                } catch (_: InterruptedException) {
                    return
                } catch (e: Exception) {
                    if (isRunning()) LogRepository.w("TUN write error: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val DATA_CAPACITY = 32768
        const val CONTROL_CAPACITY = 4096
        const val IDLE_POLL_MS = 500L

        /** How long a data segment waits for room before the session is reset. */
        const val DATA_BACKPRESSURE_MS = 250L
    }
}