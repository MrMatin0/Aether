package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.probe.ProbeDefaults

/**
 * Runs the ordered connectivity self-test against the local SOCKS5 proxy and
 * records every step in [DiagnosticsLog]. The order is deliberate so a reader
 * can pinpoint WHERE the pipeline breaks:
 *
 *   port      -> is the engine even listening?
 *   handshake -> does it speak SOCKS5?
 *   tcp       -> can it open an outbound TCP connection (to an IP, no DNS)?
 *   dns_http  -> can it resolve a domain AND fetch over HTTP end-to-end?
 *
 * Example: port+handshake+tcp PASS but dns_http FAIL => the tunnel works but
 * DNS (SOCKS5 UDP ASSOCIATE / remote resolution) is broken - the usual reason a
 * WARP-style tunnel "connects but no site loads".
 *
 * SPEED: this self-test is the GATE for the Connected state, so every second it
 * wastes is a second the user stares at "connecting". Three structural choices
 * keep it short:
 *
 *   1. The TCP and DNS+HTTP checks run CONCURRENTLY. They are independent probes
 *      of the same proxy; running them back-to-back doubled the cold-start wait
 *      for no benefit.
 *   2. Retries fire every 750 ms instead of every 3 s. The engine's inner tunnel
 *      becomes ready at an unpredictable instant inside the warm-up window; a 3 s
 *      poll added up to ~3 s of pure detection latency PER CHECK after the
 *      tunnel was already usable.
 *   3. The DNS+HTTP probe races all geolocation providers in parallel instead of
 *      trying them one by one. Individual providers are often filtered or slow
 *      in ways that differ per operator and region, so a serial chain could burn
 *      20-30 s of timeouts before reaching the one that answers.
 *
 * WHAT IS NEW HERE: every step is TIMED on the monotonic clock and records the
 * handful of facts a reader actually wants (which endpoint, which target, where
 * DNS was resolved, which exit IP answered). The panel renders those as latency
 * badges and drill-downs, so "it failed" becomes "it failed after 4003 ms
 * against 1.1.1.1:80" without anyone reading the console. [runCheck] re-runs a
 * SINGLE step, because once a reader knows which node is broken, re-running the
 * whole pipeline to retest it is 90 seconds of waiting for information they
 * already have.
 */
object Diagnostics {
    const val C_PORT = "socks_port"
    const val C_HANDSHAKE = "socks_handshake"
    const val C_TCP = "tcp_via_proxy"
    const val C_DNS = "dns_http_via_tunnel"

    /** Pipeline order. The stepper renders straight from this. */
    val ORDER: List<String> = listOf(C_PORT, C_HANDSHAKE, C_TCP, C_DNS)

    private const val TAG = "diag"

    // How long we keep retrying the outbound checks after connect. Warp-in-warp
    // (GOOL) keeps building its INNER tunnel for a while after the SOCKS5 port
    // is already open; during that window every CONNECT is rejected with rep=1.
    // That is a COLD START, not a failure, so give the engine a grace window
    // instead of failing on the very first attempt.
    private const val OUTBOUND_GRACE_MS = 90_000L

    /**
     * Grace window for a MANUAL single-node retest. Deliberately short: the
     * cold-start window exists for the automatic run right after connect, and a
     * reader who taps "retest" is waiting for an answer, not for a tunnel to
     * warm up.
     */
    private const val SINGLE_GRACE_MS = 12_000L

    private const val OUTBOUND_RETRY_DELAY_MS = 750L
    private const val TCP_PROBE_TIMEOUT_MS = 4_000
    private const val GEO_PROBE_TIMEOUT_MS = 6_000
    private const val PORT_PROBE_TIMEOUT_MS = 1_500

    /** Target of the "can the proxy reach anything at all" check. */
    private val TCP_TARGET_IP = ProbeDefaults.ANYCAST_RESOLVER_IP
    private const val TCP_TARGET_PORT = ProbeDefaults.HTTP_PORT

    fun resetChecks(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ) {
        DiagnosticsLog.setChecks(
            listOf(
                ComponentCheck(C_PORT, "SOCKS5 port $host:$port"),
                ComponentCheck(C_HANDSHAKE, "SOCKS5 handshake"),
                ComponentCheck(C_TCP, "TCP via proxy ($TCP_TARGET_IP:$TCP_TARGET_PORT)"),
                ComponentCheck(C_DNS, "DNS + HTTP via tunnel"),
            )
        )
    }

    /** Runs all checks (steps 3+4 concurrently). Safe to call from any coroutine. */
    suspend fun run(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ): Boolean = withContext(Dispatchers.IO) {
        resetChecks(host, port)
        DiagnosticsLog.i(TAG, "Starting connectivity self-test\u2026")

        // 1. Port open
        if (!probePort(host, port)) {
            failRemaining(C_HANDSHAKE, C_TCP, C_DNS)
            return@withContext false
        }

        // 2. SOCKS5 handshake
        if (!probeHandshake(host, port)) {
            failRemaining(C_TCP, C_DNS)
            return@withContext false
        }

        // 3 + 4. TCP-via-proxy and DNS+HTTP end-to-end - CONCURRENT, each with
        // its own fast retry loop over the shared cold-start grace window.
        // Monotonic clock: a wall-clock jump must not cut the grace window short
        // or stretch it on a device that resyncs NTP mid-connect.
        val deadline = SystemClock.elapsedRealtime() + OUTBOUND_GRACE_MS
        val (tcp, info) = coroutineScope {
            val tcpJob = async { probeTcp(host, port, deadline) }
            val dnsJob = async { probeDns(host, port, deadline) }
            Pair(tcpJob.await(), dnsJob.await())
        }

        val dnsOk = info != null
        if (!dnsOk) {
            DiagnosticsLog.w(
                TAG,
                if (tcp) "TCP works but DNS/HTTP fails \u2192 likely broken remote DNS (SOCKS5 UDP ASSOCIATE)."
                else "Proxy cannot open outbound connections \u2192 engine has no upstream route.",
            )
        }
        dnsOk
    }

    /**
     * Re-runs ONE pipeline node and returns whether it passed.
     *
     * Unknown ids are ignored rather than throwing: the caller is a tap handler
     * on a list the engine owns, and a stale id must not crash the panel.
     */
    suspend fun runCheck(
        id: String,
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + SINGLE_GRACE_MS
        when (id) {
            C_PORT -> probePort(host, port)
            C_HANDSHAKE -> probeHandshake(host, port)
            C_TCP -> probeTcp(host, port, deadline)
            C_DNS -> probeDns(host, port, deadline) != null
            else -> false
        }
    }

    // ---- the four nodes ----------------------------------------------------

    private fun probePort(host: String, port: Int): Boolean {
        DiagnosticsLog.updateCheck(C_PORT, CheckState.RUNNING, facts = emptyList())
        val started = SystemClock.elapsedRealtime()
        val open = PortProbe.isOpen(host, port, PORT_PROBE_TIMEOUT_MS)
        val took = SystemClock.elapsedRealtime() - started
        DiagnosticsLog.updateCheck(
            C_PORT,
            if (open) CheckState.PASS else CheckState.FAIL,
            if (open) "listening" else "no listener",
            latencyMs = took,
            facts = listOf(
                CheckFact("endpoint", "$host:$port"),
                CheckFact("connect timeout", "$PORT_PROBE_TIMEOUT_MS ms"),
            ),
        )
        DiagnosticsLog.log(
            TAG,
            if (open) LogLevel.INFO else LogLevel.ERROR,
            "port open = $open ($took ms)",
        )
        return open
    }

    private fun probeHandshake(host: String, port: Int): Boolean {
        DiagnosticsLog.updateCheck(C_HANDSHAKE, CheckState.RUNNING, facts = emptyList())
        val started = SystemClock.elapsedRealtime()
        val ok = NetProbe.checkSocksHandshake(host, port)
        val took = SystemClock.elapsedRealtime() - started
        DiagnosticsLog.updateCheck(
            C_HANDSHAKE,
            if (ok) CheckState.PASS else CheckState.FAIL,
            if (ok) "speaks SOCKS5" else "no SOCKS5 greeting",
            latencyMs = took,
            facts = listOf(
                CheckFact("endpoint", "$host:$port"),
                CheckFact("protocol", "SOCKS5 greeting only"),
            ),
        )
        DiagnosticsLog.log(
            TAG,
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            "socks5 handshake = $ok ($took ms)",
        )
        return ok
    }

    private suspend fun probeTcp(host: String, port: Int, deadline: Long): Boolean {
        DiagnosticsLog.updateCheck(C_TCP, CheckState.RUNNING, facts = emptyList())
        val started = SystemClock.elapsedRealtime()
        var attempts = 0
        val ok = retryUntil<Boolean>(deadline, { it }) {
            attempts++
            NetProbe.checkTcpViaProxy(host, port, TCP_TARGET_IP, TCP_TARGET_PORT, TCP_PROBE_TIMEOUT_MS)
        }
        val took = SystemClock.elapsedRealtime() - started
        DiagnosticsLog.updateCheck(
            C_TCP,
            if (ok) CheckState.PASS else CheckState.FAIL,
            if (ok) "outbound TCP open" else "CONNECT refused",
            latencyMs = took,
            facts = listOf(
                CheckFact("target", "$TCP_TARGET_IP:$TCP_TARGET_PORT"),
                CheckFact("resolution", "none (IP literal)"),
                CheckFact("attempts", attempts.toString()),
                CheckFact("probe timeout", "$TCP_PROBE_TIMEOUT_MS ms"),
            ),
        )
        DiagnosticsLog.log(
            TAG,
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            "tcp via proxy = $ok ($took ms, $attempts attempt(s))",
        )
        return ok
    }

    private suspend fun probeDns(host: String, port: Int, deadline: Long): IpInfo? {
        DiagnosticsLog.updateCheck(C_DNS, CheckState.RUNNING, facts = emptyList())
        val started = SystemClock.elapsedRealtime()
        var attempts = 0
        val info = retryUntil<IpInfo?>(deadline, { it != null }) {
            attempts++
            NetProbe.fetchIpInfoViaSocksRaced(host, port, GEO_PROBE_TIMEOUT_MS)
        }
        val took = SystemClock.elapsedRealtime() - started
        val ok = info != null
        DiagnosticsLog.updateCheck(
            C_DNS,
            if (ok) CheckState.PASS else CheckState.FAIL,
            if (ok) "exit ${info!!.ip} ${info.countryCode ?: "?"}" else "no response",
            latencyMs = took,
            facts = buildList {
                add(CheckFact("resolution", "remote (SOCKS5 domain)"))
                add(CheckFact("providers", "raced in parallel"))
                add(CheckFact("attempts", attempts.toString()))
                add(CheckFact("probe timeout", "$GEO_PROBE_TIMEOUT_MS ms"))
                if (info != null) {
                    add(CheckFact("exit ip", info.ip))
                    add(CheckFact("country", info.countryCode ?: "unknown"))
                }
            },
        )
        DiagnosticsLog.log(
            TAG,
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            if (ok) "dns+http OK, exit ip=${info!!.ip} cc=${info.countryCode} ($took ms)"
            else "dns+http FAILED ($took ms, $attempts attempt(s))",
        )

        // The self-test already discovered the real exit IP through the tunnel.
        // Feed it straight into the badge so the UI never has to race a second,
        // independent lookup right after connect - the IP + flag is visible the
        // INSTANT the app reports Connected.
        if (info != null) {
            AetherController.offerTunnelIpInfo(IpEndpoint(info.ip, info.countryCode, true))
            AetherController.setIpLoading(false)
        }
        return info
    }

    /**
     * Repeats [probe] until [isGood] accepts a result or [deadline] passes.
     *
     * Both outbound checks need exactly this loop and each used to carry its own
     * copy, which is how the two drifted into slightly different shapes.
     */
    private suspend fun <T> retryUntil(
        deadline: Long,
        isGood: (T) -> Boolean,
        probe: suspend () -> T,
    ): T {
        var result = probe()
        while (!isGood(result) && SystemClock.elapsedRealtime() < deadline) {
            delay(OUTBOUND_RETRY_DELAY_MS)
            result = probe()
        }
        return result
    }

    private fun failRemaining(vararg ids: String) {
        ids.forEach { DiagnosticsLog.updateCheck(it, CheckState.FAIL, "skipped", facts = emptyList()) }
    }
}
