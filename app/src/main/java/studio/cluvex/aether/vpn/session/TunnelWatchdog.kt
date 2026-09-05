package studio.cluvex.aether.vpn.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import studio.cluvex.aether.core.DiagnosticsLog
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

private const val TAG = "vpn"

/** What one watchdog check concluded about the live tunnel. */
internal enum class TunnelHealth {
    /** Traffic flows. */
    HEALTHY,

    /** This check failed, but not often enough in a row to act on it. */
    DEGRADED,

    /** Failed for [VpnTunables.WATCHDOG_FAIL_CYCLES] checks running: restart. */
    DEAD,
}

/**
 * WATCHDOG PROBE, hardened (1.2.4 periodic-outage root-cause fix).
 *
 * The old probe was a single TCP connect to 1.1.1.1:53 with a 5 s timeout. On
 * high-RTT, lossy links (the tunnel's own baseline RTT is 350-550 ms and DPI
 * throttling causes multi-second UDP stalls that heal by themselves) that lone
 * probe fails SPURIOUSLY -- two unlucky probes 30 s apart were enough to kill a
 * perfectly healthy engine and force a full endpoint rescan, which is itself a
 * 30-90 s total outage. The cure had become the disease: the periodic "no site
 * opens, then it works again" the user saw every few minutes was the watchdog
 * restarting a tunnel that was only briefly stalled.
 *
 * A check now only counts as failed when THREE attempts in a row -- spread over
 * three different anycast resolvers, 8 s timeout each, 1.5 s apart -- all fail,
 * and [TunnelHealth.DEAD] is only reported after THREE consecutive failed
 * checks (90 s+ of continuously proven dead tunnel). Brief self-healing stalls
 * no longer trigger restarts, a genuinely dead session still recovers
 * automatically, and MASQUE's in-engine reconnect loop gets room to finish
 * before the app steps in.
 *
 * One instance per session; the failure streak is its only state, which is why
 * it no longer lives on the service as a field that outlived the session that
 * set it.
 */
internal class TunnelWatchdog {

    private var failedChecks = 0

    fun reset() {
        failedChecks = 0
    }

    suspend fun check(): TunnelHealth {
        if (probeCycle()) {
            failedChecks = 0
            return TunnelHealth.HEALTHY
        }
        if (++failedChecks < VpnTunables.WATCHDOG_FAIL_CYCLES) return TunnelHealth.DEGRADED
        DiagnosticsLog.w(
            TAG,
            "Watchdog: tunnel dead across $failedChecks consecutive checks -- restarting the engine.",
        )
        failedChecks = 0
        return TunnelHealth.DEAD
    }

    private suspend fun probeCycle(): Boolean {
        repeat(VpnTunables.PROBE_ATTEMPTS) { attempt ->
            val target = VpnTunables.PROBE_TARGETS[attempt % VpnTunables.PROBE_TARGETS.size]
            if (probeOnce(target)) return true
            if (attempt < VpnTunables.PROBE_ATTEMPTS - 1) delay(VpnTunables.PROBE_RETRY_GAP_MS)
        }
        return false
    }

    /**
     * Single TCP connect to [target] ("host:port") THROUGH the engine's local
     * SOCKS5 listener.
     *
     * DISCONNECT-LATENCY FIX: this is a BLOCKING `Socket.connect` with an 8 s
     * timeout, and coroutine cancellation cannot interrupt a blocking call. A
     * disconnect tapped while the watchdog happened to be mid-probe therefore
     * sat on "Disconnecting…" until the probe timed out on its own — the exact
     * same defect [studio.cluvex.aether.core.AetherProcess.awaitExit] already
     * fixes, so it gets the exact same cure: `runInterruptible` maps
     * cancellation onto a real thread interrupt, so the connect aborts
     * immediately.
     *
     * CancellationException is deliberately NOT swallowed: a cancelled
     * supervisor must unwind, not report "tunnel dead" and trigger a restart of
     * an engine that is already being torn down.
     */
    private suspend fun probeOnce(target: String): Boolean =
        runInterruptible(Dispatchers.IO) {
            runCatching {
                val host = target.substringBefore(':')
                val port = target.substringAfter(':').toInt()
                val proxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(VpnTunables.SOCKS_HOST, VpnTunables.SOCKS_PORT),
                )
                Socket(proxy).use { it.connect(InetSocketAddress(host, port), VpnTunables.PROBE_TIMEOUT_MS) }
                true
            }.getOrDefault(false)
        }
}
