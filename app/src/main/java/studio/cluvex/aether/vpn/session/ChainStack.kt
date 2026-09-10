package studio.cluvex.aether.vpn.session

import android.content.Context
import kotlinx.coroutines.delay
import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.DnsRoute
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.PsiphonCore
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.SocksFront
import studio.cluvex.aether.core.TorCore
import studio.cluvex.aether.core.TunnelConfig
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

    /** The DNS-capable SOCKS front could not bind its port. */
    FRONT_BIND,
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
 * startup and tor reads `Socks5Proxy` once at startup; starting either of them
 * before the hop underneath it can actually carry a connection means the core
 * burns its own retry budget failing against a dead proxy, and reports the
 * network as blocked when the real problem was the start order.
 *
 * "Proven ready" is deliberately each core's OWN signal, never the open port:
 * both bind their listener seconds to minutes before they can carry traffic
 * (see [PsiphonCore.awaitReady] and [TorCore.awaitReady]).
 */
internal class ChainStack(
    private val context: Context,
    private val filesDir: File,
) {
    @Volatile
    private var psiphon: PsiphonCore? = null

    @Volatile
    private var tor: TorCore? = null

    @Volatile
    private var front: SocksFront? = null

    private val lock = Any()

    /** True while every core this chain started is still running. */
    val alive: Boolean
        get() = (psiphon?.isAlive ?: true) && (tor?.isAlive ?: true)

    /**
     * Cores [mode] needs that this build does not ship.
     *
     * Reads the SAME snapshot the Chain settings page reads
     * ([CoreAvailability]), which is the point: a mode the picker offers and the
     * session then refuses is a worse experience than a mode that was never
     * offered, and that is exactly what two independent availability checks
     * eventually produce. It also stops this question from having a side effect -
     * the previous version constructed a PsiphonCore and a TorCore just to ask,
     * and both create their data directory in their initializer.
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
     * @throws ChainException with the specific reason, so the user is told which
     *   core failed rather than "could not connect".
     */
    suspend fun start(profile: ConnectionProfile): Int {
        val mode = profile.chain
        ChainRuntime.begin(mode)
        if (mode.usesAether) ChainRuntime.update(Hop.AETHER, ChainRuntime.HopState.READY)

        // Every hop dials through the one before it; null means "straight out".
        var upstream: Int? = if (mode.usesAether) TunnelConfig.ENGINE_SOCKS_PORT else null

        if (mode.usesPsiphon) {
            upstream = startPsiphon(profile, upstream)
        }
        if (mode.usesTor) {
            upstream = startTor(profile, upstream)
        }

        val entry = when (mode.entryHop) {
            // Aether's own SOCKS5 speaks UDP ASSOCIATE, so the forwarder can
            // talk to it directly and no front is needed.
            Hop.AETHER -> TunnelConfig.ENGINE_SOCKS_PORT
            Hop.PSIPHON, Hop.TOR -> startFront(profile, mode, requireNotNull(upstream))
        }

        ChainRuntime.publish(mode, entry)
        DiagnosticsLog.i(TAG, "Chain ready: ${mode.pathLabel()} (entry 127.0.0.1:$entry)")
        return entry
    }

    /**
     * Parks until a core dies or [timeoutMs] elapses.
     *
     * Parks on the ENTRY core, because that is the one whose death makes the
     * tunnel stop carrying traffic immediately. A deeper hop dying is caught by
     * [alive] on the next supervisor cycle instead, which costs at most one
     * watchdog interval and keeps this a single blocking wait rather than a poll
     * over three processes.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean = when {
        tor != null -> tor?.awaitExit(timeoutMs) ?: true
        psiphon != null -> psiphon?.awaitExit(timeoutMs) ?: true
        else -> true
    }

    /**
     * Stops everything, front first.
     *
     * Order is load-bearing: the front holds live sockets INTO tor/Psiphon, so
     * closing it first means the cores see clean disconnects instead of a
     * process dying under them, and nothing is left holding a port the next
     * connect has to bind.
     */
    fun stop() = synchronized(lock) {
        runCatching { front?.stop() }
        front = null
        runCatching { tor?.stop() }
        tor = null
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
     * appear; the app sits on "Connecting" for the full readiness budget and then
     * reports a timeout, with nothing in the log that explains why. A working
     * tunnel in the wrong country beats no tunnel, so a hand-picked country gets
     * ONE retry with the filter removed and a fresh datastore. Nothing is retried
     * when the user already chose Automatic - there is no filter left to relax.
     *
     * The retry uses a NEW core rather than restarting the old one: the previous
     * attempt's datastore is wiped between passes, and a child that is still on
     * its way out must not be the thing that owns it.
     */
    private suspend fun startPsiphon(profile: ConnectionProfile, upstream: Int?): Int {
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
                VpnTunables.PSIPHON_PORT_WAIT_MS,
            ) { attempt.isAlive }
            if (listening && attempt.awaitReady(VpnTunables.PSIPHON_READY_WAIT_MS)) {
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
     * Brings tor up, walking the bridge ladder until one rung bootstraps.
     *
     * ROOT CAUSE this handles: a blocked TRANSPORT is not a blocked network.
     * obfs4 is what every censor spends its effort on, because it is what every
     * client tries first - and the same DPI box is routinely blind to a snowflake
     * WebRTC flow. Before this loop existed, one filtered transport was the end
     * of the session: tor sat at 5% for four minutes and the user was told Tor
     * was blocked here, when the next rung would have worked.
     *
     * Each rung is a NEW [TorCore], because tor reads its torrc exactly once at
     * startup - there is no way to change bridges in place - and because the
     * outgoing process must be reaped before its replacement binds the same local
     * ports.
     *
     * BUDGETS: only the LAST rung gets the full bootstrap window. An earlier one
     * gets [VpnTunables.TOR_BRIDGE_ATTEMPT_WAIT_MS], which is long enough to tell
     * a working transport from a filtered one and short enough that three rungs
     * are not a ten-minute spinner.
     */
    private suspend fun startTor(profile: ConnectionProfile, upstream: Int?): Int {
        var core = TorCore(context, filesDir)
        if (!core.isAvailable) {
            ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.FAILED, "not bundled")
            throw ChainException(ChainFailure.TOR_MISSING)
        }
        synchronized(lock) { tor = core }

        val ladder = core.attempts(profile)
        for ((index, attempt) in ladder.withIndex()) {
            if (index > 0) {
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
                VpnTunables.TOR_PORT_WAIT_MS,
            ) { rung.isAlive }
            val budget = if (index == ladder.lastIndex) {
                VpnTunables.TOR_BOOTSTRAP_WAIT_MS
            } else {
                VpnTunables.TOR_BRIDGE_ATTEMPT_WAIT_MS
            }
            if (listening && rung.awaitReady(budget)) {
                ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.READY, "100%")
                return TunnelConfig.TOR_SOCKS_PORT
            }
        }

        ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.FAILED, "${core.bootstrapPercent}%")
        throw ChainException(ChainFailure.TOR_TIMEOUT)
    }

    /**
     * Brings up the DNS-capable front in front of a non-Aether entry core.
     *
     * Tor gets its own DNSPort (resolution happens inside the Tor network, so
     * names never leave the device in the clear); anything else gets DNS over
     * TCP through the tunnel, to the resolvers the user configured or the
     * app defaults.
     */
    private fun startFront(profile: ConnectionProfile, mode: ChainMode, upstream: Int): Int {
        val route = if (mode.usesTor) {
            DnsRoute.LocalUdp(TunnelConfig.SOCKS_HOST, TunnelConfig.TOR_DNS_PORT)
        } else {
            val configured = profile.sanitizedDns()
            DnsRoute.OverSocksTcp(configured.ifEmpty { TunnelConfig.DNS_SERVERS })
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
}
