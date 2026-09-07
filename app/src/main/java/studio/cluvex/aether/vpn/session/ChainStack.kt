package studio.cluvex.aether.vpn.session

import android.content.Context
import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.DnsRoute
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.PsiphonCore
import studio.cluvex.aether.core.SocksFront
import studio.cluvex.aether.core.TorCore
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Hop
import java.io.File

private const val TAG = "chain"

/** Why a chain could not be brought up. Mapped to a user-facing string by the service. */
internal enum class ChainFailure {
    /** This build does not bundle libpsiphon.so. */
    PSIPHON_MISSING,

    /** No Psiphon client config (none pasted in Settings, none bundled). */
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

    private suspend fun startPsiphon(profile: ConnectionProfile, upstream: Int?): Int {
        val core = PsiphonCore(context, filesDir)
        if (!core.isAvailable) {
            ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.FAILED, "not bundled")
            throw ChainException(ChainFailure.PSIPHON_MISSING)
        }
        if (!core.hasConfig(profile)) {
            ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.FAILED, "no config")
            throw ChainException(ChainFailure.PSIPHON_CONFIG)
        }
        synchronized(lock) { psiphon = core }
        ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.STARTING)
        core.start(profile, upstream)

        val listening = PortProbe.awaitOpen(
            TunnelConfig.SOCKS_HOST,
            TunnelConfig.PSIPHON_SOCKS_PORT,
            VpnTunables.PSIPHON_PORT_WAIT_MS,
        ) { core.isAlive }
        if (!listening || !core.awaitReady(VpnTunables.PSIPHON_READY_WAIT_MS)) {
            ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.FAILED)
            throw ChainException(ChainFailure.PSIPHON_TIMEOUT)
        }
        ChainRuntime.update(Hop.PSIPHON, ChainRuntime.HopState.READY, core.egressRegion)
        return TunnelConfig.PSIPHON_SOCKS_PORT
    }

    private suspend fun startTor(profile: ConnectionProfile, upstream: Int?): Int {
        val core = TorCore(context, filesDir)
        if (!core.isAvailable) {
            ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.FAILED, "not bundled")
            throw ChainException(ChainFailure.TOR_MISSING)
        }
        synchronized(lock) { tor = core }
        ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.STARTING, "0%")
        core.start(profile, upstream)

        val listening = PortProbe.awaitOpen(
            TunnelConfig.SOCKS_HOST,
            TunnelConfig.TOR_SOCKS_PORT,
            VpnTunables.TOR_PORT_WAIT_MS,
        ) { core.isAlive }
        if (!listening || !core.awaitReady(VpnTunables.TOR_BOOTSTRAP_WAIT_MS)) {
            ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.FAILED, "${core.bootstrapPercent}%")
            throw ChainException(ChainFailure.TOR_TIMEOUT)
        }
        ChainRuntime.update(Hop.TOR, ChainRuntime.HopState.READY, "100%")
        return TunnelConfig.TOR_SOCKS_PORT
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
