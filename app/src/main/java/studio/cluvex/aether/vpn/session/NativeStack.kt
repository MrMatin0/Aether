package studio.cluvex.aether.vpn.session

import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherProcess
import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.RoutingEngine
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SmartDnsRuntime
import studio.cluvex.aether.core.SocksTunBridge
import studio.cluvex.aether.core.SstpCore
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Hop

private const val TAG = "vpn"

/**
 * Owns every native moving part of a session: the engine process, the chained
 * overlay cores (Psiphon, Tor, the SOCKS front and the Smart DNS front), the
 * SSTP packet tunnel, the TUN fd, the in-process hev-socks5-tunnel core, the
 * userspace filter bridge and the LAN share listeners.
 *
 * This is the whole reason the service used to be 1,000 lines: five pieces of
 * process-wide state, each with its own teardown order, all as loose `var`s on
 * the service. Two things are guaranteed here that were not before:
 *
 *  - every field is @Volatile, because the session coroutine writes them on an
 *    IO thread while `onDestroy()` / `onRevoke()` read them on the main thread;
 *  - teardown is serialized, so a disconnect racing `onDestroy()` can no longer
 *    call `HevTunnel.stop()` or close the same fd twice.
 *
 * CONTEXT RULE - READ BEFORE ADDING A FIELD: this class is constructed from
 * AetherVpnService's own field initializer, which the platform runs inside the
 * Service CONSTRUCTOR, i.e. BEFORE attachBaseContext(). [service] is therefore
 * a ContextWrapper with a null base at construction time, and touching ANY
 * Context method here (getFilesDir, getSystemService, getString, …) crashes the
 * whole service before onCreate() is ever reached. Every use of [service] must
 * happen from a session call, never from an initializer.
 *
 * Teardown ORDER is load-bearing and lives in exactly one place
 * ([stopForwarding]): the SSTP pump and session, sharing, then the bridge, then
 * hev, then the chain (fronts first, then Tor, then Psiphon), then the engine,
 * and the TUN last of all.
 */
internal class NativeStack(private val service: VpnService) {

    @Volatile
    private var engine: AetherProcess? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var tunnelStarted = false

    @Volatile
    private var bridge: SocksTunBridge? = null

    /**
     * Runs the SSTP core's connection job and the TUN packet pump. Building a
     * scope touches no Context, so it is safe in an initializer (see the
     * CONTEXT RULE above).
     */
    private val sstpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var sstpPump: Job? = null

    @Volatile
    private var sstpStarted = false

    /**
     * Built LAZILY, and that is load-bearing (see the CONTEXT RULE above).
     *
     * This used to read `service.filesDir` eagerly, which - because the whole
     * object is created in the Service's field initializer, before
     * attachBaseContext() - dereferenced a null base context and killed every
     * single connect attempt with:
     *
     *     Unable to create service studio.cluvex.aether.vpn.AetherVpnService:
     *     java.lang.NullPointerException: Attempt to invoke virtual method
     *     'java.io.File android.content.Context.getFilesDir()' on a null
     *     object reference
     *
     * The crash landed in the CONSTRUCTOR, so no amount of guarding inside
     * onStartCommand() could have caught it. Nothing needs the chain until a
     * session actually runs, and the context is attached long before that, so
     * resolving the directory on first use is both the minimal and the correct
     * fix. `lazy` defaults to thread-safe publication, which this needs: the
     * session coroutine and the main thread both reach the chain.
     */
    private val chain by lazy { ChainStack(service, service.filesDir) }

    private val teardownLock = Any()

    val engineAlive: Boolean get() = engine?.isAlive() == true

    // ---------------------------------------------------------------- engine

    fun startEngine(profile: ConnectionProfile) {
        engine = AetherProcess(service.applicationInfo.nativeLibraryDir, service.filesDir)
            .also { it.start(profile) }
    }

    /**
     * Blocks (interruptibly, inside [AetherProcess]) until the engine exits or
     * [timeoutMs] elapses.
     *
     * 1.2.2 CPU FIX: the supervisor used to wake up every 2 s for the ENTIRE
     * lifetime of the tunnel just to ask "is the engine still alive?" - 1,800
     * wake-ups per hour of a healthy, otherwise idle connection, each one
     * preventing the CPU from settling into a deep idle state and quietly
     * draining the battery. Parking on the process itself means the OS wakes us
     * the instant the engine exits and never before, so a healthy tunnel costs
     * exactly zero polling.
     */
    suspend fun awaitEngineExit(timeoutMs: Long) {
        engine?.awaitExit(timeoutMs)
    }

    fun stopEngine() {
        runCatching { engine?.stop() }
    }

    // ----------------------------------------------------------------- chain

    /** Cores [mode] needs that this build does not ship. */
    fun missingCores(mode: ChainMode): List<Hop> = chain.missingCores(mode)

    /**
     * Brings up the non-Aether hops and returns the chain ENTRY port. The
     * engine, when the mode uses one, must already be listening.
     *
     * [deadline] (elapsedRealtime) bounds the WHOLE chain - see ChainStack;
     * null means the chain's own budget from now.
     */
    suspend fun startChain(profile: ConnectionProfile, deadline: Long? = null): Int =
        chain.start(profile, deadline)

    /**
     * True while every core this session needs is still running.
     *
     * Takes the profile rather than reading a stored copy: a chain without an
     * Aether hop never starts the engine, and asking `engineAlive` about it
     * would report a dead tunnel forever.
     */
    fun coresAlive(profile: ConnectionProfile): Boolean =
        (!profile.chain.usesAether || engineAlive) && chain.alive

    /** Parks until a core exits or [timeoutMs] elapses. */
    suspend fun awaitCoreExit(profile: ConnectionProfile, timeoutMs: Long) {
        if (profile.chain.usesAether) awaitEngineExit(timeoutMs) else chain.awaitExit(timeoutMs)
    }

    /**
     * Stops the cores but KEEPS the TUN and the forwarder, so a restart does
     * not tear the interface down and open a leak window.
     */
    fun stopCores() = synchronized(teardownLock) {
        runCatching { chain.stop() }
        runCatching { engine?.stop() }
        engine = null
    }

    // ------------------------------------------------------------------ SSTP

    /** True while the SSTP session this stack started has a live PPP link. */
    val sstpAlive: Boolean get() = sstpStarted && SstpCore.isConnected

    /**
     * Dials the profile's SSTP server and waits until IPCP is up, or fails
     * with the reason the core reported. Returns what the TUN is built from.
     *
     * The session socket is handed to VpnService.protect() before it connects
     * (TODO from the first draft: "protect the SSTP socket before
     * establish()"). The app's own package is excluded from the TUN as well,
     * so a reconnect dialled while a TUN is up can never loop into it.
     *
     * @throws IllegalStateException with a user-facing message.
     */
    suspend fun startSstp(profile: ConnectionProfile, timeoutMs: Long): SstpCore.TunnelInfo {
        val config = profile.sstpConfig
        ChainRuntime.begin(ChainMode.SSTP)
        ChainRuntime.update(Hop.SSTP, ChainRuntime.HopState.STARTING, "${config.hostname}:${config.port}")
        SstpCore.socketProtector = { socket -> service.protect(socket) }
        sstpStarted = true
        SstpCore.start(config, sstpScope)

        val end = withTimeoutOrNull(timeoutMs) {
            SstpCore.state.first { it == SstpCore.SstpState.CONNECTED || it == SstpCore.SstpState.ERROR }
        }
        val info = SstpCore.tunnelInfo.value
        if (end == SstpCore.SstpState.CONNECTED && info != null) {
            ChainRuntime.update(Hop.SSTP, ChainRuntime.HopState.READY, info.localIp)
            return info
        }

        val reason = SstpCore.statusMessage.value
        runCatching { SstpCore.stop() }
        ChainRuntime.update(Hop.SSTP, ChainRuntime.HopState.FAILED, reason.ifBlank { null })
        DiagnosticsLog.e(TAG, "SSTP did not come up: ${reason.ifBlank { "timed out after ${timeoutMs / 1000}s" }}")
        throw IllegalStateException(
            if (end == null) {
                service.getString(R.string.err_sstp_timeout)
            } else {
                service.getString(R.string.err_sstp_failed, reason.ifBlank { "unknown error" })
            },
        )
    }

    /**
     * Puts up the TUN for an SSTP session, REPLACING whatever TUN is up.
     *
     * On a reconnect the old interface stays up until the new one exists, so
     * there is no moment in which traffic could leave outside the tunnel: the
     * old pump keeps reading (and dropping) until its fd is closed here.
     */
    fun establishSstpTun(profile: ConnectionProfile, info: SstpCore.TunnelInfo) {
        val fresh = TunFactory.establishSstp(service, profile, info)
        synchronized(teardownLock) {
            val previous = tun
            tun = fresh
            if (previous != null && previous !== fresh) runCatching { previous.close() }
        }
    }

    /** Starts pumping packets between the TUN and the SSTP session. */
    fun startSstpBridge() {
        val pfd = tun ?: throw IllegalStateException("TUN descriptor is null")
        sstpPump?.cancel()
        DiagnosticsLog.i(TAG, "Starting the SSTP packet pump (fd=${pfd.fd})")
        sstpPump = sstpScope.launch { SstpCore.bridgeTun(pfd) }
    }

    /**
     * A new SSTP session for a live one that dropped: dial, swap the TUN for one
     * with the (possibly new) address, re-attach the pump.
     */
    suspend fun restartSstp(profile: ConnectionProfile, timeoutMs: Long) {
        val info = startSstp(profile, timeoutMs)
        establishSstpTun(profile, info)
        startSstpBridge()
    }

    /** Parks until the SSTP link leaves CONNECTED or [timeoutMs] elapses. */
    suspend fun awaitSstpExit(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            SstpCore.state.first { it != SstpCore.SstpState.CONNECTED }
        }
    }

    // ------------------------------------------------------------------- TUN

    fun establishTun(profile: ConnectionProfile) {
        tun = TunFactory.establishSession(service, profile)
    }

    /**
     * Replaces whatever TUN is up with the kill-switch blackhole. Returns false
     * when the platform refused, i.e. when we are NOT holding the traffic and
     * the caller must not claim protection.
     */
    fun establishLockdownTun(profile: ConnectionProfile): Boolean =
        synchronized(teardownLock) {
            closeTunLocked()
            tun = TunFactory.establishLockdown(service, profile)
            tun != null
        }

    // -------------------------------------------------------------- forwarder

    /**
     * Starts whatever moves packets between the TUN and [socksPort], the chain
     * entry.
     *
     * Two mutually exclusive paths: the battle-tested in-process hev core for
     * everyone, and the userspace filter bridge when per-app blocking is on.
     */
    fun startForwarder(profile: ConnectionProfile, socksPort: Int) {
        val pfd = tun ?: throw IllegalStateException("TUN descriptor is null")
        if (profile.blockedApps.isNotEmpty()) {
            // PER-APP BLOCKING (1.2.4): hev-socks5-tunnel cannot filter per
            // UID, so a userspace filter bridge (merged into Aether's
            // SocksTunBridge) reads the TUN itself, resolves each flow's
            // owning app and drops blocked apps' packets. It is activated
            // ONLY when blocking is configured; the battle-tested hev path
            // below stays the default for everyone else.
            //
            // Snapshot the blocked set ONCE: the bridge asks its provider on
            // every packet, and `blockedApps.toSet()` allocated a fresh set per
            // call - a per-packet allocation on the hottest path in the app.
            val blocked = profile.blockedApps.toSet()
            DiagnosticsLog.i(TAG, "Starting userspace filter bridge (blocked apps=${blocked.size})")
            bridge = SocksTunBridge(
                vpnService = service,
                tunDescriptor = pfd,
                socksHost = VpnTunables.SOCKS_HOST,
                socksPort = socksPort,
                mtu = profile.safeMtu(),
                blockedPackagesProvider = { blocked },
                routingEngine = RoutingEngine(emptyList()),
            ).also { it.start() }
            return
        }
        val config = HevConfig.write(service.filesDir, profile.safeMtu(), socksPort)
        // Use the LIVE fd of the ParcelFileDescriptor (do NOT detach): hev uses it
        // while running and we close the pfd ourselves on teardown. The fd is only
        // valid inside THIS process, which is exactly why hev must run in-process.
        DiagnosticsLog.i(TAG, "Starting hev-socks5-tunnel in-process (fd=${pfd.fd}, socks=$socksPort)")
        HevTunnel.start(config.absolutePath, pfd.fd)
        tunnelStarted = true
    }

    // ------------------------------------------------------------ local proxy

    /**
     * Proxy mode: bring up the fixed local SOCKS5 + HTTP listeners.
     *
     * startSync is ground truth: in proxy mode these listeners ARE the product,
     * so a bind failure has to be reported instead of swallowed (the old
     * fire-and-forget start hid EADDRINUSE and still claimed the ports were
     * ready, and external apps then simply could not connect). Returns false
     * when a port could not be opened.
     */
    fun startLocalProxy(localOnly: Boolean): Boolean = ShareBridge.startSync(localOnly = localOnly)

    /** LAN sharing on top of a full-tunnel session: other devices on the Wi-Fi/hotspot. */
    fun startLanShare() {
        ShareBridge.start(localOnly = false)
    }

    // ---------------------------------------------------------------- teardown

    /**
     * Stops sharing, the forwarder and every core but deliberately KEEPS the
     * TUN, so the kill-switch blackhole can take it over without opening a leak
     * window.
     *
     * The traffic meter polls hev's and the bridge's counters, so the caller has
     * to stop it BEFORE calling this.
     */
    fun stopForwarding() = synchronized(teardownLock) { stopForwardingLocked() }

    /** Full teardown: everything above, plus the TUN itself. */
    fun teardown() = synchronized(teardownLock) {
        stopForwardingLocked()
        closeTunLocked()
    }

    private fun stopForwardingLocked() {
        // SSTP first: its pump writes into the TUN, and its session socket is
        // the only thing that still talks to the network for it. The pump
        // itself ends when the TUN is closed (closeTunLocked) or replaced.
        stopSstpLocked()
        runCatching { ShareBridge.stop() }
        bridge?.let { runCatching { it.stop() } }
        bridge = null
        if (tunnelStarted) {
            // Blocks (bounded) until hev's loop has exited and been joined, so
            // the next session never starts on top of it.
            runCatching { HevTunnel.stop() }
            tunnelStarted = false
        }
        // Chain before engine: the overlays' sockets run INTO the engine, so
        // killing the engine first would make them fail and retry against a
        // proxy that is already gone.
        runCatching { chain.stop() }
        runCatching { engine?.stop() }
        engine = null
    }

    private fun stopSstpLocked() {
        sstpPump?.cancel()
        sstpPump = null
        if (sstpStarted) {
            runCatching { SstpCore.stop() }
            SstpCore.socketProtector = null
            sstpStarted = false
        }
    }

    private fun closeTunLocked() {
        runCatching { tun?.close() }
        tun = null
        // The session TUN is gone, and with it anything it had to report.
        SmartDnsRuntime.reset()
    }
}
