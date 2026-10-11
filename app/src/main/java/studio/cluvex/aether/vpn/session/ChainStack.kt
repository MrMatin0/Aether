package studio.cluvex.aether.vpn.session

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** This build does not bundle libxray.so. */
    XRAY_MISSING,

    /**
     * No Xray config was saved, what was saved is not a config this app can
     * run, or Xray itself refused it at startup.
     */
    XRAY_CONFIG,

    /** Xray started, but no connection got through the user's server in time. */
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
 * startup, tor reads `Socks5Proxy` once at startup and Xray reads its
 * `dialerProxy` once at startup; starting any of them before the hop
 * underneath it can actually carry a connection means the core burns its own
 * retry budget failing against a dead proxy, and reports the network as
 * blocked when the real problem was the start order.
 *
 * The hops are started in [ChainMode.hops] order (internet side first), so a
 * new mode only has to list its hops for this class to bring it up correctly.
 *
 * "Proven ready" is deliberately each core's OWN signal, never the open port:
 * every core binds its listener before it can carry traffic (see
 * [PsiphonCore.awaitReady], [TorCore.awaitReady] and, for Xray, a real CONNECT
 * through its inbound - which only succeeds once the user's server completed
 * its handshake through the hop underneath).
 *
 * ONE DEADLINE (perf/fast-connect). [start] runs under a single deadline, and
 * every wait inside it - port, Psiphon readiness, Xray readiness, every Tor
 * rung - is min(its own budget, what is left).
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
     * Brings up every non-Aether hop and returns the CHAIN ENTRY port: the
     * local SOCKS5 the TUN forwarder (or a proxy-mode client) must be pointed
     * at.
     *
     * The Aether hop, when the mode uses one, must already be up and listening
     * on [TunnelConfig.ENGINE_SOCKS_PORT] before this is called.
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
        val until = deadline ?: (SystemClock.elapsedRealtime() + VpnTunables.chainBudgetMs(mode))
        ChainRuntime.begin(mode)

        // Every hop dials through the one before it; null means "straight out".
        var upstream: Int? = null
        for (hop in mode.hops) {
            upstream = when (hop) {
                // Already up (started by the service); it only becomes the
                // upstream of whatever comes next.
                Hop.AETHER -> {
                    ChainRuntime.update(Hop.AETHER, ChainRuntime.HopState.READY)
                    TunnelConfig.ENGINE_SOCKS_PORT
                }
                Hop.PSIPHON -> startPsiphon(profile, upstream, until)
                Hop.XRAY -> startXray(upstream, until)
                Hop.TOR -> startTor(profile, upstream, until)
                Hop.SSTP -> error("SSTP has no SOCKS entry")
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
     * tunnel stop carrying traffic immediately. Tor is always the top hop when
     * present, and Xray is always above Psiphon. A deeper hop dying is caught
     * by [alive] on the next supervisor cycle instead.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean = when {
        tor != null -> tor?.awaitExit(timeoutMs) ?: true
        xray != null -> xray?.awaitExit(timeoutMs) ?: true
        psiphon != null -> psiphon?.awaitExit(timeoutMs) ?: true
        else -> true
    }

    /**
     * Stops everything, fronts first, then entry side to internet side.
     *
     * Order is load-bearing: the fronts hold live sockets INTO the cores, so
     * closing them first means the cores see clean disconnects instead of a
     * process dying under them, and nothing is left holding a port the next
     * connect has to bind.
     */
    fun stop() = synchronized(lock) {
        runCatching { smartFront?.stop() }
        smartFront = null
        runCatching { front?.stop() }
        front = null
        runCatching { tor?.stop() }
        tor = null
        runCatching { xray?.stop() }
        xray = null
        runCatching { psiphon?.stop() }
        psiphon = null
    }

    // ------------------------------------------------------------------ hops

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
        throw ChainException(ChainFailure.PSIPHON_TIMEOUT)
    }

    /**
     * Brings Xray up on top of [upstream] and proves the user's server answers.
     *
     * THE CHAINING: the user's config is read from the Keystore-sealed
     * [XrayConfigStore] (never from the profile, which is exported), parsed by
     * [XrayLink], and run with a `dialerProxy` pointing at [upstream]. Xray's
     * whole connection to the server - TCP, TLS / REALITY handshake, the
     * VLESS / VMess / Trojan / Shadowsocks session - is therefore carried by
     * the hop underneath (WARP, Psiphon, or WARP + Psiphon), and the network
     * only ever sees that hop. With no hop underneath (Tor over Xray) Xray
     * dials the server itself, and tor rides on top of it.
     *
     * READY means a real CONNECT through Xray's inbound got out, which can only
     * happen once the handshake with the user's server succeeded THROUGH the
     * chain. An open port alone only proves the config loaded.
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
        synchronized(lock) { xray = core }

        val label = outbound.remark.ifBlank { outbound.summary }
        ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.STARTING, label)
        withContext(Dispatchers.IO) { core.start(outbound, upstream) }

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

        val budget = minOf(VpnTunables.XRAY_READY_WAIT_MS, remainingMs(deadline))
        if (budget > 0 &&
            Readiness.awaitDataplane(TunnelConfig.SOCKS_HOST, TunnelConfig.XRAY_SOCKS_PORT, budget) { core.isAlive }
        ) {
            ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.READY, label)
            return TunnelConfig.XRAY_SOCKS_PORT
        }

        DiagnosticsLog.w(
            TAG,
            "Xray is running but nothing got through ${outbound.summary}" +
                (if (upstream != null) " via 127.0.0.1:$upstream" else "") +
                " - check the server, its UUID / password and its TLS / REALITY settings.",
        )
        ChainRuntime.update(Hop.XRAY, ChainRuntime.HopState.FAILED, "no handshake")
        throw ChainException(ChainFailure.XRAY_TIMEOUT)
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
