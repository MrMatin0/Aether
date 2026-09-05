package studio.cluvex.aether.vpn.session

import android.net.VpnService
import android.os.ParcelFileDescriptor
import studio.cluvex.aether.core.AetherProcess
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.RoutingEngine
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SocksTunBridge
import studio.cluvex.aether.model.ConnectionProfile

private const val TAG = "vpn"

/**
 * Owns every native moving part of a session: the engine process, the TUN fd,
 * the in-process hev-socks5-tunnel core, the userspace filter bridge and the
 * LAN share listeners.
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
 * Teardown ORDER is load-bearing and lives in exactly one place ([stopForwarding]):
 * sharing, then the bridge, then hev, then the engine, and the TUN last of all.
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
     * lifetime of the tunnel just to ask "is the engine still alive?" — 1,800
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
     * Starts whatever moves packets between the TUN and the engine's SOCKS5.
     *
     * Two mutually exclusive paths: the battle-tested in-process hev core for
     * everyone, and the userspace filter bridge when per-app blocking is on.
     */
    fun startForwarder(profile: ConnectionProfile) {
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
            // call — a per-packet allocation on the hottest path in the app.
            val blocked = profile.blockedApps.toSet()
            DiagnosticsLog.i(TAG, "Starting userspace filter bridge (blocked apps=${blocked.size})")
            bridge = SocksTunBridge(
                vpnService = service,
                tunDescriptor = pfd,
                socksHost = VpnTunables.SOCKS_HOST,
                socksPort = VpnTunables.SOCKS_PORT,
                mtu = profile.safeMtu(),
                blockedPackagesProvider = { blocked },
                routingEngine = RoutingEngine(emptyList()),
            ).also { it.start() }
            return
        }
        val config = HevConfig.write(service.filesDir, profile.safeMtu())
        // Use the LIVE fd of the ParcelFileDescriptor (do NOT detach): hev uses it
        // while running and we close the pfd ourselves on teardown. The fd is only
        // valid inside THIS process, which is exactly why hev must run in-process.
        DiagnosticsLog.i(TAG, "Starting hev-socks5-tunnel in-process (fd=${pfd.fd})")
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
     * Stops sharing, the forwarder and the engine but deliberately KEEPS the
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
        runCatching { ShareBridge.stop() }
        bridge?.let { runCatching { it.stop() } }
        bridge = null
        if (tunnelStarted) {
            runCatching { HevTunnel.stop() }
            tunnelStarted = false
        }
        runCatching { engine?.stop() }
        engine = null
    }

    private fun closeTunLocked() {
        runCatching { tun?.close() }
        tun = null
    }
}
