package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.auto.StrategyLadder
import studio.cluvex.aether.core.probe.ProbeDefaults
import studio.cluvex.aether.core.probe.TcpProbe
import studio.cluvex.aether.core.probe.TlsProbe
import studio.cluvex.aether.core.probe.UdpDnsProbe
import studio.cluvex.aether.model.ConnectionProfile

/**
 * How hostile the current network's filtering (DPI) looks, derived from the
 * direct probes in [SmartAuto.fingerprint]:
 *
 *  - OPEN          : UDP answers and TLS-with-SNI completes - a mostly clean path.
 *  - SNI_FILTERING : UDP is fine but a TLS handshake carrying an SNI stalls or
 *                    resets - classic SNI-based DPI. Obfuscation (noize) matters.
 *  - UDP_THROTTLED : TLS works but UDP gets no answers - the operator drops or
 *                    throttles UDP, which starves WireGuard/QUIC. TCP-shaped
 *                    transports (MASQUE over HTTP/2) are the way in.
 *  - HOSTILE       : both are broken - prefer TCP, fragmentation and ECH;
 *                    stronger noize only when the user has enabled obfuscation.
 */
enum class DpiClass { OPEN, SNI_FILTERING, UDP_THROTTLED, HOSTILE }

/** Everything Smart Auto learned about the current network before connecting. */
data class NetworkFingerprint(
    val dpiClass: DpiClass,
    val udpOk: Boolean,
    val tlsSniOk: Boolean,
    val operatorName: String,
    /** True when the active transport is cellular AND the MCC is 432 (Iran). */
    val iranCellular: Boolean,
    /** WARP range CIDR -> TCP connect latency in ms (-1 = unreachable). */
    val edgeLatencyMs: Map<String, Long>,
)

/** One concrete, ready-to-launch strategy in the Smart Auto ladder. */
data class AutoCandidate(
    val profile: ConnectionProfile,
    val timeoutMs: Long,
    val label: String,
)

/**
 * ROOT-CAUSE FIX for "Auto never connects": the old AUTO simply passed NO
 * protocol flag to the engine and hoped its default worked - there was no
 * intelligence and no fallback, so on any filtered network it just hung while
 * every manually chosen protocol worked fine.
 *
 * Smart Auto instead works like an engineer would:
 *
 *  1. FINGERPRINT ([fingerprint]) - before the engine even launches, probe the
 *     real network DIRECTLY (the app is excluded from its own TUN, so these
 *     probes always see the raw operator path):
 *       - UDP health: real DNS queries to two public resolvers.
 *       - SNI DPI: a full TLS handshake to the anycast resolver carrying the
 *         SNI "www.cloudflare.com" (with hostname verification, no data sent).
 *       - WARP edge reachability: TCP connect latency to one representative
 *         host in each built-in Cloudflare WARP range.
 *       - Operator: name + MCC (432 = Iran) + transport (cellular/Wi-Fi), read
 *         WITHOUT any extra permissions.
 *  2. CLASSIFY the DPI behaviour into a [DpiClass].
 *  3. PLAN ([buildPlan]) - hand the fingerprint to [StrategyLadder], which
 *     builds an ordered list of concrete strategies (protocol + noize +
 *     fragment/ECH + the ranges that actually answered), most-likely-to-succeed
 *     first, plus a full-range last resort.
 *  4. The VpnService then walks the ladder: each candidate gets a real connect
 *     attempt gated by the 4-step self-test; the first one that passes wins.
 *
 * Every probe result and every decision is written to the in-app log, so the
 * user can see exactly WHY Smart Auto picked what it picked. The probes
 * themselves live in core/probe now - this object is the sequencing and the
 * narration.
 */
object SmartAuto {

    private const val TAG = "auto"
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val TLS_PROBE_TIMEOUT_MS = 4_000

    /** Built-in Cloudflare WARP ranges + one representative probe host each. */
    private val EDGES = listOf(
        "162.159.192.0/24" to "162.159.192.1",
        "162.159.195.0/24" to "162.159.195.1",
        "188.114.96.0/24" to "188.114.96.1",
        "188.114.97.0/24" to "188.114.97.1",
        "8.6.112.0/24" to "8.6.112.1",
    )

    // ---- Stage 1+2: probe the network and classify its DPI ----------------

    suspend fun fingerprint(context: Context): NetworkFingerprint = withContext(Dispatchers.IO) {
        val (operatorName, iranCellular) = readOperator(context)
        DiagnosticsLog.i(
            TAG,
            "Fingerprinting the network - operator=\"$operatorName\"" +
                if (iranCellular) " (Iranian cellular)" else "",
        )
        // Monotonic: a wall-clock resync mid-connect used to turn this duration
        // into a meaningless (occasionally negative) number in the log.
        val started = SystemClock.elapsedRealtime()
        val fingerprint = coroutineScope {
            // All probes run in PARALLEL - the whole stage costs one timeout at worst.
            val udpPrimary = async { udpProbe(ProbeDefaults.ANYCAST_RESOLVER_IP) }
            val udpSecondary = async { udpProbe(ProbeDefaults.SECONDARY_RESOLVER_IP) }
            val tls = async { tlsSniProbe() }
            val edgeJobs = EDGES.map { (cidr, probeIp) -> async { cidr to edgeLatencyMs(cidr, probeIp) } }
            val udpOk = udpPrimary.await() || udpSecondary.await()
            val tlsOk = tls.await()
            NetworkFingerprint(
                dpiClass = classify(udpOk, tlsOk),
                udpOk = udpOk,
                tlsSniOk = tlsOk,
                operatorName = operatorName,
                iranCellular = iranCellular,
                edgeLatencyMs = edgeJobs.awaitAll().toMap(),
            )
        }
        report(fingerprint, SystemClock.elapsedRealtime() - started)
        fingerprint
    }

    // ---- Stage 3: turn the fingerprint into an ordered strategy ladder ----

    fun buildPlan(user: ConnectionProfile, fp: NetworkFingerprint): List<AutoCandidate> =
        StrategyLadder.build(user, fp).also { plan ->
            DiagnosticsLog.i(TAG, "Strategy ladder for ${fp.dpiClass} (${plan.size} steps):")
            plan.forEachIndexed { index, candidate ->
                DiagnosticsLog.i(TAG, "  ${index + 1}. ${candidate.label}")
            }
        }

    private fun classify(udpOk: Boolean, tlsOk: Boolean): DpiClass = when {
        udpOk && tlsOk -> DpiClass.OPEN
        udpOk -> DpiClass.SNI_FILTERING
        tlsOk -> DpiClass.UDP_THROTTLED
        else -> DpiClass.HOSTILE
    }

    // ---- Probes: sequencing + narration only (see core/probe) --------------

    private fun udpProbe(server: String): Boolean {
        val outcome = UdpDnsProbe.probe(server, PROBE_TIMEOUT_MS)
        DiagnosticsLog.d(TAG, "udp53 probe $server -> ${outcome.detail}")
        return outcome.answered
    }

    private fun tlsSniProbe(): Boolean {
        val outcome = TlsProbe.fingerprint(
            ip = ProbeDefaults.ANYCAST_RESOLVER_IP,
            sniHost = ProbeDefaults.TLS_SNI_PROBE_HOST,
            port = ProbeDefaults.HTTPS_PORT,
            timeoutMs = TLS_PROBE_TIMEOUT_MS,
        )
        val detail = if (outcome.verified) "handshake ok" else "failed (${outcome.failure})"
        DiagnosticsLog.d(TAG, "tls-sni probe -> $detail")
        return outcome.verified
    }

    private fun edgeLatencyMs(cidr: String, probeIp: String): Long {
        val measurement = TcpProbe.connect(probeIp, ProbeDefaults.HTTPS_PORT, PROBE_TIMEOUT_MS)
        if (!measurement.reachable) {
            DiagnosticsLog.d(TAG, "edge $cidr via $probeIp unreachable: ${measurement.failure}")
        }
        return measurement.ms
    }

    private fun report(fp: NetworkFingerprint, elapsedMs: Long) {
        val edges = fp.edgeLatencyMs.entries.joinToString(", ") { (range, ms) ->
            "$range=${if (ms < 0) "unreachable" else "${ms}ms"}"
        }
        DiagnosticsLog.i(
            TAG,
            "DPI fingerprint ready in $elapsedMs ms: " +
                "udp=${fp.udpOk} tlsSni=${fp.tlsSniOk} -> ${fp.dpiClass} | edges: $edges",
        )
    }

    /** Operator name + "is Iranian cellular" - no runtime permissions needed. */
    private fun readOperator(context: Context): Pair<String, Boolean> = runCatching {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val name = telephony?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        val mcc = telephony?.networkOperator?.take(3)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        name to (cellular && mcc == IRAN_MCC)
    }.getOrDefault("unknown" to false)

    private const val IRAN_MCC = "432"
}
