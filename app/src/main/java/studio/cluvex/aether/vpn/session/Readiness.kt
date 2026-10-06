package studio.cluvex.aether.vpn.session

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.DnsMessage
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.Socks5Wire
import studio.cluvex.aether.core.probe.ProbeDefaults

private const val TAG = "ready"

/**
 * "Is this hop actually READY?" - as opposed to "is its port open?".
 *
 * ROOT CAUSE this exists for (perf/fast-connect): the session treated an open
 * local port as a ready tunnel. The engine's SOCKS5 listener, the DNS front and
 * the Smart DNS front can all be accepting connections while the thing behind
 * them cannot carry a request yet - the engine's own startup bind probe, a
 * gool inner hop still coming up, a resolver that has not picked its path. The
 * app then brought up TUN + hev on top of it and let the 90 s self-test absorb
 * the difference, which is exactly the "connecting takes forever" users saw.
 *
 * Both checks here are bounded and use REAL traffic through the port:
 *  - [awaitDataplane]: a full SOCKS5 CONNECT to an IP literal (no DNS).
 *  - [warmDns]: one DNS-over-TCP query to the resolver the TUN advertises,
 *    through the entry - which also pays the first-query warm-up (resolver
 *    path choice, DoH/DoT handshake, first Tor circuit) before the user does.
 */
internal object Readiness {

    private const val WARM_NAME = "cloudflare.com"
    private const val DNS_PORT = 53
    private const val WARM_QUERY_TIMEOUT_MS = 4_000
    private const val WARM_MIN_QUERY_TIMEOUT_MS = 500L
    private const val WARM_RETRY_MS = 400L

    /**
     * Polls a real SOCKS5 CONNECT through [host]:[port] until one succeeds,
     * [budgetMs] passes, or [isAlive] reports the process behind it is gone.
     */
    suspend fun awaitDataplane(
        host: String,
        port: Int,
        budgetMs: Long,
        isAlive: () -> Boolean = { true },
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + budgetMs.coerceAtLeast(0L)
        var attempts = 0
        do {
            attempts++
            val carried = withContext(Dispatchers.IO) {
                NetProbe.checkTcpViaProxy(
                    host,
                    port,
                    ProbeDefaults.ANYCAST_RESOLVER_IP,
                    ProbeDefaults.HTTP_PORT,
                    VpnTunables.DATAPLANE_PROBE_TIMEOUT_MS,
                )
            }
            if (carried) {
                DiagnosticsLog.i(
                    TAG,
                    "Data plane on $host:$port carries traffic " +
                        "(${SystemClock.elapsedRealtime() - started} ms, $attempts attempt(s)).",
                )
                return true
            }
            if (!isAlive()) return false
            val more = SystemClock.elapsedRealtime() + VpnTunables.DATAPLANE_RETRY_MS < deadline
            if (more) delay(VpnTunables.DATAPLANE_RETRY_MS)
        } while (more)
        DiagnosticsLog.w(
            TAG,
            "No CONNECT through $host:$port got out in " +
                "${SystemClock.elapsedRealtime() - started} ms ($attempts attempt(s)).",
        )
        return false
    }

    /**
     * One DNS-over-TCP query to [resolver] THROUGH the SOCKS5 entry at
     * [host]:[port], retried inside [budgetMs]. True when an answer with
     * RCODE=NOERROR came back. Never fatal for the caller: a front that fails
     * this still works, it only means the user's first lookup may be slow.
     */
    suspend fun warmDns(host: String, port: Int, resolver: String, budgetMs: Long): Boolean {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + budgetMs.coerceAtLeast(0L)
        var attempts = 0
        do {
            attempts++
            val window = (deadline - SystemClock.elapsedRealtime())
                .coerceIn(WARM_MIN_QUERY_TIMEOUT_MS, WARM_QUERY_TIMEOUT_MS.toLong())
                .toInt()
            val answered = withContext(Dispatchers.IO) { queryOverTcp(host, port, resolver, window) }
            if (answered) {
                DiagnosticsLog.i(
                    TAG,
                    "Entry $host:$port warmed: DNS via $resolver answered in " +
                        "${SystemClock.elapsedRealtime() - started} ms ($attempts attempt(s)).",
                )
                return true
            }
            val more = SystemClock.elapsedRealtime() + WARM_RETRY_MS < deadline
            if (more) delay(WARM_RETRY_MS)
        } while (more)
        DiagnosticsLog.w(
            TAG,
            "Entry $host:$port did not answer a warm-up DNS query via $resolver in " +
                "${SystemClock.elapsedRealtime() - started} ms - continuing; the first lookup may be slow.",
        )
        return false
    }

    private fun queryOverTcp(host: String, port: Int, resolver: String, timeoutMs: Int): Boolean {
        val id = (SystemClock.elapsedRealtimeNanos() and 0xFFFF).toInt()
        val query = DnsMessage.buildQuery(WARM_NAME, DnsMessage.TYPE_A, id) ?: return false
        val socket = Socks5Wire.dial(host, port, resolver, DNS_PORT, timeoutMs, timeoutMs) ?: return false
        return try {
            val output = socket.getOutputStream()
            output.write(Socks5Wire.portBytes(query.size) + query)
            output.flush()
            val input = socket.getInputStream()
            val length = Socks5Wire.readExact(input, 2)
            val size = if (length == null) 0 else Socks5Wire.u16(length, 0)
            val answer = if (size >= 12) Socks5Wire.readExact(input, size) else null
            answer != null && (answer[3].toInt() and 0x0F) == 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
