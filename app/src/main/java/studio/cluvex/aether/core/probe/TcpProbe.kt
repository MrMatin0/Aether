package studio.cluvex.aether.core.probe

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * "Can I open a TCP connection, and how long did it take?"
 *
 * This single helper replaces three near-identical copies: PortProbe's
 * `isOpen`, PingMonitor's latency measurement and SmartAuto's edge-latency
 * probe. They differed only in what they threw away - one wanted a Boolean,
 * one wanted milliseconds, one wanted -1 on failure - and all three quietly
 * discarded the REASON a connect failed, which is the one thing a user reading
 * the log wants.
 *
 * Timing uses [System.nanoTime]: monotonic (the wall clock resyncs mid-connect
 * on this app's target networks) and finer-grained than a millisecond clock,
 * which matters when a localhost handshake is being measured.
 */
internal object TcpProbe {

    /** Latency value that means "never got there". */
    const val UNREACHABLE = -1L

    /** One connect attempt: how long it took, or why it did not happen. */
    class Measurement(val ms: Long, val failure: String?) {
        val reachable: Boolean get() = ms != UNREACHABLE
    }

    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        connect(host, port, timeoutMs).reachable

    fun latencyMs(host: String, port: Int, timeoutMs: Int): Long =
        connect(host, port, timeoutMs).ms

    /**
     * Connects and immediately closes.
     *
     * [proxy] routes the handshake through a proxy (the local SOCKS5 listener),
     * which is how the same call measures tunnel latency and operator latency.
     */
    fun connect(host: String, port: Int, timeoutMs: Int, proxy: Proxy? = null): Measurement {
        val socket = if (proxy == null) Socket() else Socket(proxy)
        return try {
            socket.use {
                val started = System.nanoTime()
                it.connect(InetSocketAddress(host, port), timeoutMs)
                // Measured BEFORE the close: teardown is not latency.
                Measurement(((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L), null)
            }
        } catch (e: Exception) {
            Measurement(UNREACHABLE, e.message ?: e.javaClass.simpleName)
        }
    }
}
