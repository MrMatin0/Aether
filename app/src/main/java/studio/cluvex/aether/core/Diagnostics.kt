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
 * GATE vs HEALTH CHECK (perf/fast-connect). The whole pipeline used to be the
 * gate for the Connected state, and both outbound checks were retried for up to
 * 90 s - a grace window for engines that opened their SOCKS5 port before their
 * tunnel could carry anything. On a slow or partly filtered path that was a
 * minute and a half of "Verifying" for EVERY attempt, on top of the scan. Two
 * things changed:
 *
 *   1. The service no longer starts TUN/hev on an open port alone: it waits for
 *      a real CONNECT through the engine first (vpn/session/Readiness), so the
 *      cold-start window this grace existed for is already behind us here.
 *   2. The gate ([runGate]) is now port + handshake + ONE outbound TCP check
 *      with a short grace. That proves the exact path the user's traffic takes.
 *      The DNS + HTTP / exit-IP check ([runHealthCheck]) runs in the BACKGROUND
 *      after Connected: it still fills in the panel and the IP badge, and a
 *      tunnel that really stops carrying traffic is the watchdog's job.
 *
 * Every step is TIMED on the monotonic clock and records the handful of facts a
 * reader actually wants (which endpoint, which target, where DNS was resolved,
 * which exit IP answered). [runCheck] re-runs a SINGLE step.
 */
object Diagnostics {
    const val C_PORT = "socks_port"
    const val C_HANDSHAKE = "socks_handshake"
    const val C_TCP = "tcp_via_proxy"
    const val C_DNS = "dns_http_via_tunnel"

    /** Pipeline order. The stepper renders straight from this. */
    val ORDER: List<String> = listOf(C_PORT, C_HANDSHAKE, C_TCP, C_DNS)

    private const val TAG = "diag"

    /**
     * Grace for the gate's single outbound TCP check. Short on purpose: the
     * engine's data plane was already proven before TUN/hev came up, so what is
     * left to wait for here is the chain entry / front, not a cold tunnel.
     */
    private const val GATE_GRACE_MS = 10_000L

    /**
     * Grace for the background DNS + HTTP health check. It no longer holds the
     * Connected state, so it can afford patience: a slow first resolution on a
     * Tor exit is not a reason to tear a working tunnel down.
     */
    private const val HEALTH_GRACE_MS = 60_000L

    /**
     * Grace for a MANUAL single-node retest. Deliberately short: a reader who
     * taps "retest" is waiting for an answer, not for a tunnel to warm up.
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

    /**
     * The CONNECT GATE: port, handshake and one outbound TCP check. True means
     * the entry the user's traffic goes through can open a real outbound
     * connection right now. DNS + HTTP is left to [runHealthCheck].
     */
    suspend fun runGate(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ): Boolean = withContext(Dispatchers.IO) {
        resetChecks(host, port)
        DiagnosticsLog.i(TAG, "Starting connect gate (port, handshake, outbound TCP)\u2026")

        if (!probePort(host, port)) {
            failRemaining(C_HANDSHAKE, C_TCP, C_DNS)
            return@withContext false
        }
        if (!probeHandshake(host, port)) {
            failRemaining(C_TCP, C_DNS)
            return@withContext false
        }
        val deadline = SystemClock.elapsedRealtime() + GATE_GRACE_MS
        val tcp = probeTcp(host, port, deadline)
        if (!tcp) {
            failRemaining(C_DNS)
            DiagnosticsLog.w(TAG, "Proxy cannot open outbound connections \u2192 engine has no upstream route.")
        }
        tcp
    }

    /**
     * The BACKGROUND health check: DNS + HTTP end to end, which also discovers
     * the exit IP for the badge. Never gates Connected; the caller only logs
     * the result.
     */
    suspend fun runHealthCheck(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + HEALTH_GRACE_MS
        val info = probeDns(host, port, deadline)
        if (info == null) {
            DiagnosticsLog.w(
                TAG,
                "TCP works but DNS/HTTP fails \u2192 likely broken remote DNS (SOCKS5 UDP ASSOCIATE).",
            )
        }
        info != null
    }

    /**
     * The full pipeline (steps 3+4 concurrently), for the panel's manual
     * "run all". NOT the connect gate any more - see the class doc.
     */
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
        // its own fast retry loop. Monotonic clock: a wall-clock jump must not
        // cut the window short or stretch it.
        val deadline = SystemClock.elapsedRealtime() + HEALTH_GRACE_MS
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
        // independent lookup.
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
