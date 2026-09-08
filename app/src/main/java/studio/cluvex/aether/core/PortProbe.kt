package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.delay
import studio.cluvex.aether.core.probe.ProbeDefaults
import studio.cluvex.aether.core.probe.TcpProbe

/**
 * The ground-truth "are we connected?" check: a successful TCP connect to the
 * local SOCKS5 port means the engine is up and tunnelling.
 */
object PortProbe {

    /** Default for one-off callers that just want a yes/no. */
    private const val DEFAULT_TIMEOUT_MS = 800

    /** Keep polling tightly for this long, then back off. */
    private const val FAST_PHASE_MS = 5_000L

    /** Upper bound for the adaptive poll interval. */
    private const val MAX_INTERVAL_MS = 1_500L

    fun isOpen(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean =
        TcpProbe.isOpen(host, port, timeoutMs)

    /**
     * Polls until the port opens or [totalTimeoutMs] elapses. Aborts early if
     * [isEngineAlive] returns false, so a dead engine fails fast with a clear
     * error instead of the caller hanging for the entire (possibly 5-minute)
     * timeout window.
     *
     * ADAPTIVE POLL: the engine either opens its port within the first few
     * seconds (fast path - keep the tight interval so we notice immediately) or
     * it is doing a long endpoint scan that can run for MINUTES. In the patient
     * scan modes a flat 300 ms interval meant well over a thousand wake-ups and
     * socket syscalls per connect attempt, all of it pure overhead on the exact
     * code path where the CPU is already busy scanning. Backing off cuts that by
     * roughly an order of magnitude at the cost of at most one extra second of
     * detection latency.
     *
     * CADENCE FIX: each poll now uses a 250 ms connect timeout instead of
     * inheriting the 800 ms default, which had quietly stretched the "300 ms
     * poll" to as much as 1.1 s per iteration on a silent port.
     */
    suspend fun awaitOpen(
        host: String,
        port: Int,
        totalTimeoutMs: Long,
        intervalMs: Long = 300,
        isEngineAlive: () -> Boolean = { true },
    ): Boolean {
        // Monotonic clock: the wall clock can jump (an NTP resync while offline
        // is routine on this app's target networks) and would stretch or collapse
        // every deadline here.
        val deadline = SystemClock.elapsedRealtime() + totalTimeoutMs
        val fastPhaseEnd = SystemClock.elapsedRealtime() + FAST_PHASE_MS
        var interval = intervalMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isOpen(host, port, ProbeDefaults.LOCAL_PORT_TIMEOUT_MS)) return true
            if (!isEngineAlive()) return false
            delay(interval)
            if (SystemClock.elapsedRealtime() > fastPhaseEnd) {
                interval = (interval * 3 / 2).coerceAtMost(MAX_INTERVAL_MS)
            }
        }
        return false
    }

    /**
     * Waits until nothing is listening on [port] any more.
     *
     * PROTOCOL-SWITCH FIX: a new engine must never be started while the previous
     * one still owns the local SOCKS5 port, otherwise the connect either races a
     * dying listener or verifies against it. Polls cheaply, because a released
     * localhost port is what the user is waiting for.
     */
    suspend fun awaitClosed(
        host: String,
        port: Int,
        totalTimeoutMs: Long,
        intervalMs: Long = 100,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + totalTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isOpen(host, port, ProbeDefaults.LOCAL_PORT_TIMEOUT_MS)) return true
            delay(intervalMs)
        }
        return !isOpen(host, port, ProbeDefaults.LOCAL_PORT_TIMEOUT_MS)
    }
}
