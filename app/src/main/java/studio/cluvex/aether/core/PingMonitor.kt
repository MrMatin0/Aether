package studio.cluvex.aether.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.probe.ProbeDefaults
import studio.cluvex.aether.core.probe.TcpProbe
import java.net.InetSocketAddress
import java.net.Proxy

/** Result of a single latency measurement. */
data class PingResult(
    val ms: Long = -1L,
    val running: Boolean = false,
    val error: Boolean = false,
)

/**
 * On-demand TCP latency check.
 *
 * BATTERY DESIGN: there is deliberately NO periodic polling loop anywhere. A
 * measurement runs only when the user taps the test button next to the latency
 * value. Each run is a single TCP handshake to Cloudflare's anycast resolver
 * with a hard timeout, so one measurement costs one packet round trip and never
 * keeps the CPU awake.
 *
 * The measurement itself is [TcpProbe] - the same helper the port check and the
 * WARP edge scan use, instead of a third private copy of "connect and time it".
 */
object PingMonitor {

    private const val TAG = "ping"

    private val _state = MutableStateFlow(PingResult())
    val state: StateFlow<PingResult> = _state.asStateFlow()

    /** Serialises concurrent taps so two probes can never overlap. */
    private val mutex = Mutex()

    /**
     * Measures TCP handshake latency to the anycast resolver.
     *
     * @param viaTunnel when true the probe socket is opened THROUGH the local
     * SOCKS5 listener of the running engine, so the number reflects the tunnel's
     * real end-to-end latency; when false it connects directly and shows the
     * operator's latency instead.
     */
    suspend fun pingOnce(viaTunnel: Boolean) {
        if (!mutex.tryLock()) return
        try {
            _state.value = PingResult(running = true)
            val ms = try {
                withContext(Dispatchers.IO) { measure(viaTunnel) }
            } catch (cancelled: CancellationException) {
                // A cancelled probe must still clear the "running" flag, or the
                // test button stays stuck for the rest of the session.
                _state.value = PingResult(error = true)
                throw cancelled
            }
            _state.value = if (ms == TcpProbe.UNREACHABLE) PingResult(error = true) else PingResult(ms = ms)
        } finally {
            mutex.unlock()
        }
    }

    /** Clears the last reading so a stale number never outlives its session. */
    fun reset() {
        _state.value = PingResult()
    }

    private fun measure(viaTunnel: Boolean): Long {
        val proxy = if (viaTunnel) {
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT),
            )
        } else {
            null
        }
        val target = "${ProbeDefaults.ANYCAST_RESOLVER_IP}:${ProbeDefaults.DNS_PORT}"
        val measurement = TcpProbe.connect(
            host = ProbeDefaults.ANYCAST_RESOLVER_IP,
            port = ProbeDefaults.DNS_PORT,
            timeoutMs = ProbeDefaults.LATENCY_TIMEOUT_MS,
            proxy = proxy,
        )
        if (measurement.reachable) {
            DiagnosticsLog.d(TAG, "Latency to $target = ${measurement.ms} ms (viaTunnel=$viaTunnel)")
        } else {
            // The REASON was previously dropped on the floor, leaving the panel
            // with a red dash and no explanation.
            DiagnosticsLog.w(
                TAG,
                "Latency probe to $target failed (viaTunnel=$viaTunnel): ${measurement.failure}",
            )
        }
        return measurement.ms
    }
}
