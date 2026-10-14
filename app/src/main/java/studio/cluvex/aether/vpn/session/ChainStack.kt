package studio.cluvex.aether.vpn.session

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.DnsRoute
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.PsiphonCore
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.SmartDnsFront
import studio.cluvex.aether.core.SocksFront
import studio.cluvex.aether.core.TorCore
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.core.XrayCore
import studio.cluvex.aether.core.XrayLink
import studio.cluvex.aether.data.XrayConfigStore
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Hop
import java.io.File

private const val TAG = "chain"

/** How long a Psiphon being replaced mid-retry gets to exit on its own. */
private const val PSIPHON_STOP_WAIT_MS = 5_000L

/** Grace for the local SOCKS port to leave TIME_WAIT before the retry binds it. */
private const val PSIPHON_PORT_RELEASE_MS = 1_500L

/** Same, for a tor being replaced between two rungs of the bridge ladder. */
private const val TOR_STOP_WAIT_MS = 5_000L
private const val TOR_PORT_RELEASE_MS = 1_500L

/** Lower bound for the entry warm-up, even with almost no chain budget left. */
private const val WARMUP_FLOOR_MS = 1_000L

/** Why a chain could not be brought up. Mapped to a user-facing string by the service. */
internal enum class ChainFailure {
    /** This build does not bundle libpsiphon.so. */
    PSIPHON_MISSING,

    /**
     * This build has no Psiphon bootstrap server list, so the core has nothing
     * to dial and cannot establish a first tunnel.
     *
     * Used to mean "no client config", which is no longer a thing that can
     * happen: [PsiphonCore] carries its own. The constant keeps its name so the
     * service's mapping and the string resource stay put; only its meaning and
     * its copy moved.
     */
    PSIPHON_CONFIG,

    /** Psiphon never established a tunnel inside its budget. */
    PSIPHON_TIMEOUT,

    /** This build does not bundle libtor.so. */
    TOR_MISSING,

    /** Tor never finished bootstrapping inside its budget. */
    TOR_TIMEOUT,

    /** The DNS-capable SOCKS front (or the Smart DNS front) could not bind its port. */
    FRONT_BIND,

    /** This build does not bundle libxray.so, or the bundled one cannot be started. */
    XRAY_MISSING,

    /**
     * No Xray config was saved, what was saved is not a config this app can
     * run, or Xray itself refused it at startup.
     */
    XRAY_CONFIG,

    /**
     * Xray is running but never bound its local inbound in time.
     *
     * It no longer means "no round trip through the user's server": the hop
     * above Xray is started without waiting for one (see
     * [ChainStack.startXray]), so a server that never answers now surfaces as
     * that hop's own timeout instead.
     */
    XRAY_TIMEOUT,
}

internal class ChainException(val failure: ChainFailure) : Exception(failure.name)

/**
 * Owns every core in a chain EXCEPT the Aether engine, and the order they come
 * up in.
 *
 * The engine stays with [NativeStack] because it was already there and its
 * lifecycle is entangled with the endpoint-scan ladder; everything else lives
 * here. What this class really encodes is one rule:
 *
 * > core N is started only after core N-1 is PROVEN ready, and is configured to
 * > dial through it.
 *
 * That ordering is not cosmetic. Psiphon reads `UpstreamProxyUrl` once at
 * startup, tor reads `Socks5Proxy` once at startup and the engine reads
 * `AETHER_UPSTREAM` once at launch; starting any of them before the hop
 * underneath it can actually carry a connection means the core burns its own
 * retry budget failing against a dead proxy, and reports the network as
 * blocked when the real problem was the start order.
 *
 * ### The one exception: Xray
 *
 * Xray is "ready enough" for the next hop as soon as its config was accepted
 * and its local inbound is bound. The hop above it is started RIGHT AWAY and
 * does its own handshake through Xray while Xray's handshake with the user's
 * server is still settling. Waiting for a round trip first (the old rule)
 * killed configs that never answer the readiness probe but do carry the
 * next hop's traffic; started in parallel, some of them come alive. The
 * round-trip check still runs, in the background and non-fatal, so the logs
 * still say whether the user's server answered.
 *
 * ### Two stages, because the engine is in the middle
 *
 * The hops are started in [ChainMode.hops] order (internet side first). When
 * something sits UNDER the engine (Xray, see [ChainMode.hopsBeforeEngine]) the
 * session runs in two stages:
 *
 *  1. [startBeforeEngine] - the hops under Aether, here; returns the port the
 *     engine must be launched with as its `AETHER_UPSTREAM`;
 *  2. the engine, launched by the service through [NativeStack];
 *  3. [start] - every hop above the engine, and the entry.
 *
 * A mode with nothing under the engine skips stage 1, exactly as before.
 *
 * "Proven ready" is deliberately each core's OWN signal, never the open port:
 * every core binds its listener before it can carry traffic (see
 * [PsiphonCore.awaitReady] and [TorCore.awaitReady]). Xray is the exception
 * described above: its open inbound is all the next hop waits for.
 *
 * ONE DEADLINE (perf/fast-connect). [start] runs under a single deadline, and
 * every wait inside it - port, Psiphon readiness, every Tor rung - is
 * min(its own budget, what is left). [startBeforeEngine] gets its own, from
 * the same session deadline.
 *
 * SSTP is NOT a hop of this class: it is a packet tunnel with no SOCKS5 entry,
 * and its session is run by the service through [NativeStack.startSstp].
 */
internal class ChainStack(
    private val context: Context,
    private val filesDir: File,
) {
    @Volatile
    private var psiphon: PsiphonCore? = null

    @Volatile
    private var xray: XrayCore? = null

    @Volatile
    private var tor: TorCore? = null

    @Volatile
    private var front: SocksFront? = null

    /** The Smart DNS front, when this session runs one (Aether-only chain). */
    @Volatile
    private var smartFront: SmartDnsFront? = null

    /**
     * The mode whose hops under the engine are already up, and the port the
     * engine dials out through. Set by [startBeforeEngine], consumed by
     * [start], cleared by [stop].
     */
    @Volatile
    private var beforeEngine: Pair<ChainMode, Int>? = null

    /** The mode [start] brought up, so [awaitExit] knows which core is the entry. */
    @Volatile
    private var running: ChainMode? = null

    /**
     * Background, NON-FATAL check of Xray's handshake with the user's server
     * (see [startXray]). Diagnostics only: nothing waits on it, and [stop]
     * cancels it.
     */
    @Volatile
    private var xrayWatch: Job? = null

    /**
     * The server summary of the running Xray when its background probe got NO
     * answer from the user's server; null when it did, when the probe has not
     * finished yet, or when no Xray is running.
     *
     * Read when a hop that dials THROUGH Xray (Psiphon, Tor) fails to come up:
     * Xray is the internet-facing hop of every Xray mode, so a server that
     * never answers makes every hop above it fail with its own timeout, and
     * the log used to name only that hop. See [explainSilentXray].
     */
    @Volatile
    private var xraySilent: String? = null

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()

    /** True while every core this chain started is still running. */
    val alive: Boolean
        get() = (psiphon?.isAlive ?: true) && (xray?.isAlive ?: true) && (tor?.isAlive ?: true)

    /**
     * Cores [mode] needs that this build does not ship.
     *
     * Reads the SAME snapshot the Chain settings page reads
     * ([CoreAvailability]): a mode the picker offers and the session then
     * refuses is a worse experience than a mode that was never offered.
     */
    fun missingCores(mode: ChainMode): List<Hop> = CoreAvailability.of(context).missing(mode)

    /**
     * STAGE 1: brings up every hop UNDER the Aether engine and returns the
     * local SOCKS5 port the engine must be launched with (`AETHER_UPSTREAM`),
     * or null when [profile]'s chain has nothing under the engine.
     *
     * Must run BEFORE the engine is launched: the engine reads its upstream
     * once, and an engine launched without it dials WARP straight from the
     * device - which is exactly the traffic this mode exists to hide.
     *
     * [deadline] is on the [SystemClock.elapsedRealtime] clock; null means
     * "now + [VpnTunables.preEngineBudgetMs]" (the supervisor's restarts).
     *
     * @throws ChainException with the specific reason.
     */
    suspend fun startBeforeEngine(profile: ConnectionProfile, deadline: Long? = null): Int? {
        val mode = profile.chain
        val under = mode.hopsBeforeEngine
        if (under.isEmpty()) return null
        check(!mode.usesSstp) { "SSTP is a packet tunnel and has no SOCKS chain to start" }
        val until = deadline ?: (SystemClock.elapsedRealtime() + VpnTunables.preEngineBudgetMs(mode))
        ChainRuntime.begin(mode)

        var upstream: Int? = null
        for (hop in under) upstream = startHop(hop, profile, upstream, until)
        val port = requireNotNull(upstream) { "a hop under the engine returned no port" }
        synchronized(lock) { beforeEngine = mode to port }
        DiagnosticsLog.i(
            TAG,
            "Ready before the engine: ${under.joinToString(" \u2192 ") { it.label }} - " +
                "Aether dials out through ${TunnelConfig.SOCKS_HOST}:$port.",
        )
        return port
    }

    /**
     * STAGE 3: brings up every hop ABOVE the engine (or every hop, when the
     * mode has no engine) and returns the CHAIN ENTRY port: the local SOCKS5
     * the TUN forwarder (or a proxy-mode client) must be pointed at.
     *
     * The Aether hop, when the mode uses one, must already be up and listening
     * on [TunnelConfig.ENGINE_SOCKS_PORT] before this is called - and so must
     * every hop under it ([startBeforeEngine]).
     *
     * [deadline] is on the [SystemClock.elapsedRealtime] clock; null means
     * "now + [VpnTunables.chainBudgetMs]" (the supervisor's restarts).
     *
     * @throws ChainException with the specific reason, so the user is told which
     *   core failed rather than "could not connect".
     */
    suspend fun start(profile: ConnectionProfile, deadline: Long? = null): Int {
        val mode = profile.chain
        check(!mode.usesSstp) { "SSTP is a packet tunnel and has no SOCKS chain to start" }
        val under = mode.hopsBeforeEngine
        val early = beforeEngine?.takeIf { it.first == mode }
        check(under.isEmpty() || early != null) {
            "${mode.name} needs ${under.joinToString { it.label }} up before the engine - call startBeforeEngine first"
        }
        val until = deadline ?: (SystemClock.elapsedRealtime() + VpnTunables.chainBudgetMs(mode))
        // Stage 1 already published this mode's rows; beginning again would
        // wipe the READY it reported for the hops under the engine.
        if (early == null) ChainRuntime.begin(mode)
        running = mode

        // Every hop dials through the one before it; null means "straight out".
        var upstream: Int? = null
        for (hop in mode.hops) {
            upstream = if (hop in under) {
                // Already up (stage 1); it only matters as the engine's upstream.
                early?.second
            } else {
                startHop(hop, profile, upstream, until)
            }
        }

        val entry = when (mode.entryHop) {
            // Aether's own SOCKS5 speaks UDP ASSOCIATE, so the forwarder can
            // talk to it directly and no front is needed - unless Smart DNS is
            // on, in which case its front answers the virtual resolver and
            // relays everything else to the engine untouched.
            Hop.AETHER -> if (profile.usesSmartDns) {
                startSmartDnsFront(profile)
            } else {
                TunnelConfig.ENGINE_SOCKS_PORT
            }
            // Xray's inbound speaks UDP ASSOCIATE and answers port-53 UDP with
            // its own resolver over TCP through the user's server (see
            // XrayConfig), so it is an entry by itself, like the engine.
            Hop.XRAY -> requireNotNull(upstream)
            Hop.PSIPHON, Hop.TOR -> startFront(mode, requireNotNull(upstream))
            // Unreachable (checked above); spelled out so the `when` stays
            // exhaustive and a new hop has to decide where its traffic enters.
            Hop.SSTP -> error("SSTP has no SOCKS entry")
        }

        // READINESS BEFORE PUBLISHING (perf/fast-connect): one bounded DNS
        // query through the entry moves the first-lookup warm-up from the
        // user's first request to here. The engine's own port was already
        // proven by the service's data-plane check.
        if (entry != TunnelConfig.ENGINE_SOCKS_PORT) warmEntry(entry, until)

        ChainRuntime.publish(mode, entry)
        DiagnosticsLog.i(TAG, "Chain ready: ${mode.pathLabel()} (entry 127.0.0.1:$entry)")
        return entry
    }

    /**
     * Parks until a core dies or [timeoutMs] elapses.
     *
     * Parks on the ENTRY core, because that is the one whose death makes the
     * tunnel stop carrying traffic immediately. A deeper hop dying is caught
     * by [alive] on the next supervisor cycle instead.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean = when (running?.entryHop) {
        Hop.TOR -> tor?.awaitExit(timeoutMs)
        Hop.PSIPHON -> psiphon?.awaitExit(timeoutMs)
        Hop.XRAY -> xray?.awaitExit(timeoutMs)
        // The engine is the entry (the service parks on it itself), or nothing
        // is running: fall back to whichever overlay core is up.
        else -> when {
            tor != null -> tor?.awaitExit(timeoutMs)
            psiphon != null -> psiphon?.awaitExit(timeoutMs)
            xray != null -> xray?.awaitExit(timeoutMs)
            else -> true
        }
    } ?: true

    /**
     * Stops everything, fronts first, then entry side to internet side.
     *
     * Order is load-bearing: the fronts hold live sockets INTO the cores, so
     * closing them first means the cores see clean disconnects instead of a
     * process dying under them, and nothing is left holding a port the next
     * connect has to bind. Xray goes last: with Xray under the engine, every
     * other core's connections run through it.
     */
    fun stop() = synchronized(lock) {
        runCatching { xrayWatch?.cancel() }
        xrayWatch = null
        xraySilent = null
        runCatching { smartFront?.stop() }
        smartFront = null
        runCatching { front?.stop() }
        front = null
        runCatching { tor?.stop() }
        tor = null
        runCatching { psiphon?.stop() }
        psiphon = null
        runCatching { xray?.stop() }
        xray = null
        beforeEngine = null
        running = null
    }

    // ------------------------------------------------------------------ hops

    /** Starts one hop on top of [upstream] and returns the port the next hop dials. */
    private suspend fun startHop(hop: Hop, profile: ConnectionProfile, upstream: Int?, deadline: Long): Int =
        when (hop) {
            // Already up (started by the service); it only becomes the
            // upstream of whatever comes next.
            Hop.AETHER -> {
                ChainRuntime.update(Hop.AETHER, ChainRuntime.HopState.READY)
                TunnelConfig.ENGINE_SOCKS_PORT
            }
            Hop.PSIPHON -> startPsiphon(profile, upstream, deadline)
            Hop.XRAY -> startXray(upstream, deadline)
            Hop.TOR -> startTor(profile, upstream, deadline)
            Hop.SSTP -> error("SSTP has no SOCKS entry")
        }

    /**
     * Brings Psiphon up, and does not let one unlucky country be the end of it.
     *
     * ROOT CAUSE this handles: `EgressRegion` is a HARD filter inside
     * psiphon-tunnel-core. If no server is currently reachable in the country the
     * user picked, the controller keeps hunting for an egress that will never
     * appear. A working tunnel in the wrong country beats no tunnel, so a
     * hand-picked country gets ONE retry with the filter removed and a fresh
     * datastore. Nothing is retried when the user already chose Automatic.
     *
     * BUDGET: both passes share [deadline]. The pinned-region pass gets
     * [VpnTunables.PSIPHON_REGION_ATTEMPT_WAIT_MS] so the retry still fits, and
     * the retry is skipped when less than [VpnTunables.PSIPHON_RETRY_MIN_MS] is
     * left.
     *
     * The retry uses a NEW core rather than restarting the old one: the previous
     * attempt's datastore is wiped between passes, and a child that is still on
     * its way out must not be the thing that owns it.
     */
    private suspend fun startPsiphon(profile: ConnectionProfile, upstream: Int?, deadline: Long): Int {
        var core = PsiphonCore(context, filesDir)
        if (!core.isAvailable) {
            ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.FAILED, "not bundled")
            throw ChainException(ChainFailure.PSIPHON_MISSING)
        }
        if (!core.hasServerList()) {
            ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.FAILED, "no server list")
            throw ChainException(ChainFailure.PSIPHON_CONFIG)
        }
        synchronized(lock) { psiphon = core }

        val wanted = PsiphonRegions.sanitize(profile.psiphonRegion)
        val attempts = if (wanted.isEmpty()) listOf("") else listOf(wanted, "")

        for ((index, egress) in attempts.withIndex()) {
            if (index > 0) {
                val left = remainingMs(deadline)
                if (left < VpnTunables.PSIPHON_RETRY_MIN_MS) {
                    DiagnosticsLog.w(
                        TAG,
                        "Psiphon found no usable server in ${PsiphonRegions.name(wanted)}, and only " +
                            "${left / 1000}s of the chain budget is left - not retrying with an automatic exit.",
                    )
                    break
                }
                DiagnosticsLog.w(
                    TAG,
                    "Psiphon found no usable server in ${PsiphonRegions.name(wanted)} - " +
                        "retrying with an automatic exit and a fresh datastore.",
                )
                val previous = core
                runCatching { previous.stop() }
                previous.awaitExit(PSIPHON_STOP_WAIT_MS)
                previous.resetDataStore()
                delay(PSIPHON_PORT_RELEASE_MS)
                core = PsiphonCore(context, filesDir)
                synchronized(lock) { psiphon = core }
            }

            val attempt = core
            ChainRuntime.update(
                Hop.PSIPHON,
                ChainRuntime.HopState.STARTING,
                PsiphonRegions.name(egress),
            )
            attempt.start(profile, upstream, egress)

            val listening = PortProbe.awaitOpen(
                TunnelConfig.SOCKS_HOST,
                TunnelConfig.PSIPHON_SOCKS_PORT,
                minOf(VpnTunables.PSIPHON_PORT_WAIT_MS, remainingMs(deadline)),
            ) { attempt.isAlive }
            val ownBudget = if (index < attempts.lastIndex) {
                VpnTunables.PSIPHON_REGION_ATTEMPT_WAIT_MS
            } else {
                VpnTunables.PSIPHON_READY_WAIT_MS
            }
            val readyBudget = minOf(ownBudget, remainingMs(deadline))
            if (listening && readyBudget > 0 && attempt.awaitReady(readyBudget)) {
                ChainRuntime.update(
                    Hop.PSIPHON,
                    ChainRuntime.HopState.READY,
                    attempt.egressRegion ?: PsiphonRegions.name(egress),
                )
                return TunnelConfig.PSIPHON_SOCKS_PORT
            }
        }

        ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.FAILED)
        explainSilentXray(Hop.PSIPHON)
        throw ChainException(ChainFailure.PSIPHON_TIMEOUT)
    }

    /**
     * Brings Xray up and hands its port to the next hop RIGHT AWAY.
     *
     * The user's config is read from the Keystore-sealed [XrayConfigStore]
     * (never from the profile, which is exported), parsed by [XrayLink], and
     * run by [XrayCore]. Xray is the internet-facing hop of every Xray mode, so
     * [upstream] is null in practice and Xray dials the server itself; the
     * parameter stays so a `dialerProxy` chain is still one argument away.
     *
     * XRAY-FIRST, NOT XRAY-GATED. Xray counts as up once the config was
     * accepted and its local inbound is bound. This used to wait for an ANSWER
     * through the inbound ([Readiness.awaitRoundTrip]) and fail the session
     * with [ChainFailure.XRAY_TIMEOUT] when none came back inside
     * [VpnTunables.XRAY_READY_WAIT_MS]. In practice some configs never answer
     * that probe (a DNS-over-TCP query to a public resolver) yet do carry the
     * next hop's own handshake - WARP's MASQUE / WireGuard, Psiphon, Tor - so
     * they were killed before they were given a chance. Now the next hop is
     * started immediately and handshakes THROUGH Xray while Xray's own
     * handshake with the user's server is still settling.
     *
     * The round-trip check still runs, in the background and non-fatal
     * ([watchXrayHandshake]), so the diagnostics log still says whether the
     * user's server answered. A config that really is dead now surfaces as the
     * next hop's own timeout instead of [ChainFailure.XRAY_TIMEOUT], and
     * [explainSilentXray] adds the line that names Xray as the likely cause.
     */
    private suspend fun startXray(upstream: Int?, deadline: Long): Int {
        val core = XrayCore(context, filesDir)
        if (!core.isAvailable) {
            ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.FAILED, "not bundled")
            throw ChainException(ChainFailure.XRAY_MISSING)
        }
        val text = withContext(Dispatchers.IO) {
            runCatching { XrayConfigStore(context).read() }.getOrDefault("")
        }
        val outbound = XrayLink.parse(text)
        if (outbound == null) {
            val why = if (text.isBlank()) "no config" else "config not understood"
            DiagnosticsLog.w(TAG, "Xray: $why - paste a share link or Xray JSON on the Chain page.")
            ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.FAILED, why)
            throw ChainException(ChainFailure.XRAY_CONFIG)
        }
        synchronized(lock) {
            xray = core
            xraySilent = null
        }

        val label = outbound.remark.ifBlank { outbound.summary }
        ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.STARTING, label)
        // A binary that is present but cannot be spawned (wrong ABI, no exec
        // bit, a config file that could not be written) is reported like every
        // other hop's failure, not as an exception the service cannot map.
        val spawned = withContext(Dispatchers.IO) { runCatching { core.start(outbound, upstream) } }
        spawned.exceptionOrNull()?.let { error ->
            DiagnosticsLog.w(TAG, "Xray could not be started: ${error.message}")
            ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.FAILED, "did not start")
            throw ChainException(ChainFailure.XRAY_MISSING)
        }

        val listening = PortProbe.awaitOpen(
            TunnelConfig.SOCKS_HOST,
            TunnelConfig.XRAY_SOCKS_PORT,
            minOf(VpnTunables.XRAY_PORT_WAIT_MS, remainingMs(deadline)),
        ) { core.isAlive }
        if (!listening) {
            val reason = core.failure
            ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.FAILED, if (reason != null) "config rejected" else "did not start")
            if (reason != null) DiagnosticsLog.w(TAG, "Xray refused the config: $reason")
            // A process that died on its own config is a config problem; one
            // that is alive but never bound is the core misbehaving.
            throw ChainException(if (reason != null || !core.isAlive) ChainFailure.XRAY_CONFIG else ChainFailure.XRAY_TIMEOUT)
        }

        // Config accepted, inbound bound: Xray is up. Hand it to the next hop
        // now and let both handshakes run at the same time.
        ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.READY, label)
        DiagnosticsLog.i(
            TAG,
            "Xray is up on ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.XRAY_SOCKS_PORT} (${outbound.summary}" +
                (if (upstream != null) " via 127.0.0.1:$upstream" else "") +
                ") - starting the next hop now, without waiting for a round trip.",
        )
        watchXrayHandshake(core, outbound.summary, label, upstream)
        return TunnelConfig.XRAY_SOCKS_PORT
    }

    /**
     * Checks, in the background, whether an answer comes back through Xray's
     * inbound - i.e. whether the handshake with the user's server succeeded.
     *
     * NON-FATAL and diagnostics only: nothing waits on it, it never stops a
     * core and never fails the session. It only logs the outcome and, when no
     * answer came back, marks the Xray row as unverified so a dead config is
     * still easy to spot, and records it in [xraySilent] so a hop that later
     * fails through Xray can say so. Cancelled by [stop]; a result for a core
     * that has since been replaced is dropped.
     */
    private fun watchXrayHandshake(core: XrayCore, summary: String, label: String, upstream: Int?) {
        val job = watchScope.launch {
            val answered = Readiness.awaitRoundTrip(
                TunnelConfig.SOCKS_HOST,
                TunnelConfig.XRAY_SOCKS_PORT,
                TunnelConfig.DNS_SERVERS.first(),
                VpnTunables.XRAY_READY_WAIT_MS,
            ) { core.isAlive }
            if (xray !== core) return@launch
            xraySilent = if (!answered && core.isAlive) summary else null
            if (answered) {
                DiagnosticsLog.i(TAG, "Xray handshake with $summary confirmed (background check).")
            } else if (core.isAlive) {
                DiagnosticsLog.w(
                    TAG,
                    "Xray is running but its readiness probe got no answer through $summary" +
                        (if (upstream != null) " via 127.0.0.1:$upstream" else "") +
                        " - the chain was NOT stopped; the hop above keeps trying through it. " +
                        "If nothing connects, check the server, its UUID / password and its TLS / REALITY settings.",
                )
                ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.READY, "$label (unverified)")
            }
        }
        synchronized(lock) {
            xrayWatch?.cancel()
            xrayWatch = job
        }
    }

    /**
     * When [hop] failed to come up while the Xray underneath it never got an
     * answer from the user's server, says so: that hop's timeout is only the
     * symptom. Without this, an Xray -> Psiphon session against a dead or
     * blocked server ended with "Psiphon did not establish a tunnel" and a
     * run of `meek round trip failed: EOF` (Xray dropping each connection it
     * could not forward), and nothing that pointed back at the Xray server.
     *
     * Log-only on purpose: the failure code stays the hop's own, so the
     * service's mapping and its strings are untouched.
     */
    private fun explainSilentXray(hop: Hop) {
        val silent = xraySilent ?: return
        DiagnosticsLog.w(
            TAG,
            "${hop.label} never came up through Xray, and Xray got no answer from $silent either - " +
                "the Xray server is the likely cause, not ${hop.label}. Check that the server is up and " +
                "reachable from this network, its UUID / password and its TLS / REALITY settings " +
                "(a plain, unencrypted-transport config such as VMess over raw TCP is easy for DPI to block).",
        )
        ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.FAILED, "no answer from server")
    }

    /**
     * Brings tor up, walking the bridge ladder until one rung bootstraps.
     *
     * ROOT CAUSE this handles: a blocked TRANSPORT is not a blocked network.
     * obfs4 is what every censor spends its effort on, and the same DPI box is
     * routinely blind to a snowflake WebRTC flow, so one filtered transport must
     * not be the end of the session.
     *
     * Each rung is a NEW [TorCore], because tor reads its torrc exactly once at
     * startup, and because the outgoing process must be reaped before its
     * replacement binds the same local ports.
     *
     * BUDGETS: every rung shares [deadline] (at most
     * [VpnTunables.TOR_CHAIN_BUDGET_MS] for the whole ladder). An earlier rung
     * gets at most [VpnTunables.TOR_BRIDGE_ATTEMPT_WAIT_MS], the last one at
     * most [VpnTunables.TOR_BOOTSTRAP_WAIT_MS], and a rung that cannot get
     * [VpnTunables.TOR_RUNG_MIN_MS] is not started at all.
     */
    private suspend fun startTor(profile: ConnectionProfile, upstream: Int?, deadline: Long): Int {
        var core = TorCore(context, filesDir)
        if (!core.isAvailable) {
            ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.FAILED, "not bundled")
            throw ChainException(ChainFailure.TOR_MISSING)
        }
        synchronized(lock) { tor = core }

        val ladder = core.attempts(profile)
        for ((index, attempt) in ladder.withIndex()) {
            if (index > 0) {
                val left = remainingMs(deadline)
                if (left < VpnTunables.TOR_RUNG_MIN_MS) {
                    DiagnosticsLog.w(
                        TAG,
                        "Tor did not bootstrap with ${ladder[index - 1].label}, and only ${left / 1000}s " +
                            "of the chain budget is left - not trying ${attempt.label}.",
                    )
                    break
                }
                DiagnosticsLog.w(
                    TAG,
                    "Tor did not bootstrap with ${ladder[index - 1].label} - " +
                        "falling back to ${attempt.label}.",
                )
                val previous = core
                runCatching { previous.stop() }
                previous.awaitExit(TOR_STOP_WAIT_MS)
                delay(TOR_PORT_RELEASE_MS)
                core = TorCore(context, filesDir)
                synchronized(lock) { tor = core }
            }

            val rung = core
            ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.STARTING, attempt.label)
            rung.start(profile, upstream, attempt)

            val listening = PortProbe.awaitOpen(
                TunnelConfig.SOCKS_HOST,
                TunnelConfig.TOR_SOCKS_PORT,
                minOf(VpnTunables.TOR_PORT_WAIT_MS, remainingMs(deadline)),
            ) { rung.isAlive }
            val ownBudget = if (index == ladder.lastIndex) {
                VpnTunables.TOR_BOOTSTRAP_WAIT_MS
            } else {
                VpnTunables.TOR_BRIDGE_ATTEMPT_WAIT_MS
            }
            val budget = minOf(ownBudget, remainingMs(deadline))
            if (listening && budget > 0 && rung.awaitReady(budget)) {
                ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.READY, "100%")
                return TunnelConfig.TOR_SOCKS_PORT
            }
        }

        ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.FAILED, "${core.bootstrapPercent}%")
        explainSilentXray(Hop.TOR)
        throw ChainException(ChainFailure.TOR_TIMEOUT)
    }

    /**
     * Brings up the DNS-capable front in front of a Psiphon or Tor entry.
     *
     * Tor gets its own DNSPort (resolution happens inside the Tor network, so
     * names never leave the device in the clear); anything else gets DNS over
     * TCP through the tunnel, to the app's default resolvers.
     */
    private fun startFront(mode: ChainMode, upstream: Int): Int {
        val route = if (mode.usesTor) {
            DnsRoute.LocalUdp(TunnelConfig.SOCKS_HOST, TunnelConfig.TOR_DNS_PORT)
        } else {
            DnsRoute.OverSocksTcp(TunnelConfig.DNS_SERVERS)
        }
        val socksFront = SocksFront(
            listenPort = TunnelConfig.FRONT_SOCKS_PORT,
            upstreamHost = TunnelConfig.SOCKS_HOST,
            upstreamPort = upstream,
            dns = route,
        )
        if (!socksFront.start()) throw ChainException(ChainFailure.FRONT_BIND)
        synchronized(lock) { front = socksFront }
        return TunnelConfig.FRONT_SOCKS_PORT
    }

    /**
     * Brings up the Smart DNS front in front of the engine and returns its
     * port, which becomes the chain entry.
     *
     * ONE server: the Smart DNS page lets the user pick exactly one, and a
     * profile saved by an older build with several is cut down to its first
     * here as well, so the front never fans out over a list the user can no
     * longer see.
     */
    private fun startSmartDnsFront(profile: ConnectionProfile): Int {
        val smart = SmartDnsFront(
            listenPort = TunnelConfig.SMART_DNS_FRONT_PORT,
            upstreamHost = TunnelConfig.SOCKS_HOST,
            upstreamPort = TunnelConfig.ENGINE_SOCKS_PORT,
            protocol = profile.smartDnsProtocol,
            servers = profile.sanitizedSmartDns().take(1),
            rewriteDomains = !profile.hasDomainRoutingRules,
        )
        if (!smart.start()) throw ChainException(ChainFailure.FRONT_BIND)
        synchronized(lock) { smartFront = smart }
        return TunnelConfig.SMART_DNS_FRONT_PORT
    }

    /**
     * One bounded DNS query through the entry on [entry], to the resolver the
     * TUN will advertise for it. Non-fatal by design; see [Readiness.warmDns].
     */
    private suspend fun warmEntry(entry: Int, deadline: Long) {
        val resolver = if (entry == TunnelConfig.SMART_DNS_FRONT_PORT) {
            TunnelConfig.SMART_DNS_RESOLVER
        } else {
            TunnelConfig.DNS_SERVERS.first()
        }
        val budget = remainingMs(deadline).coerceIn(WARMUP_FLOOR_MS, VpnTunables.FRONT_WARMUP_MS)
        Readiness.warmDns(TunnelConfig.SOCKS_HOST, entry, resolver, budget)
    }

    private fun remainingMs(deadline: Long): Long =
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
}
