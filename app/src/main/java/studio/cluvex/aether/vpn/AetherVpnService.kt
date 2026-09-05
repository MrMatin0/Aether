package studio.cluvex.aether.vpn

import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SmartAuto
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.data.SecretStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.vpn.session.ConnectionPlanner
import studio.cluvex.aether.vpn.session.NativeStack
import studio.cluvex.aether.vpn.session.TunnelHealth
import studio.cluvex.aether.vpn.session.TunnelWatchdog
import studio.cluvex.aether.vpn.session.VpnTunables

/**
 * The heart of the app. On connect it:
 *   1. launches the bundled `aether` engine (opens SOCKS5 on 127.0.0.1:1819),
 *   2. waits until that port is actually reachable (ground-truth check),
 *   3. builds the VPN TUN interface,
 *   4. starts the embedded hev-socks5-tunnel core (libhev-socks5-tunnel.so) to forward all
 *      traffic through the proxy — replacing the need for v2rayNG entirely,
 *   5. supervises both processes and auto-reconnects on failure.
 *
 * This class is now ONLY the session state machine: intent contract, connect
 * flow, retry/kill-switch policy and teardown ORDER. The parts it used to carry
 * itself live next door and can be read (and tested) on their own:
 *
 *  - [NativeStack]        — engine process, TUN fd, hev core, filter bridge, sharing
 *  - TunFactory           — the TUN builders: routing, DNS, split tunneling
 *  - [ConnectionPlanner]  — the attempt ladder for a hand-picked protocol
 *  - [TunnelWatchdog]     — end-to-end health probing of a live tunnel
 *  - [VpnNotifications]   — the shade, the speed card, the tile and the widgets
 *  - [VpnTunables]        — every timeout and budget, with its reason
 */
class AetherVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val natives = NativeStack(this)
    private val notifications = VpnNotifications(this, scope)
    private val watchdog = TunnelWatchdog()

    @Volatile
    private var runJob: Job? = null

    /**
     * The teardown coroutine of the PREVIOUS session, if one is still
     * finishing. A new connect waits for it instead of racing it (1.2.2
     * protocol-switch fix), and teardowns are chained so a second disconnect
     * can never orphan the first one's remaining work.
     */
    @Volatile
    private var stopJob: Job? = null

    /**
     * Last profile the service ran with (kill-switch decisions). Written by the
     * session on an IO thread, read by `onStartCommand` on the main one — as are
     * the two jobs above, which is what the @Volatile is for.
     */
    @Volatile
    private var lastProfile: ConnectionProfile? = null

    /** True while the kill-switch blackhole TUN is up. */
    @Volatile
    private var lockdownTunActive = false

    // =============================================================== lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FOREGROUND-SERVICE CONTRACT — DO NOT MOVE THIS BELOW THE `when`.
        //
        // Every path into this service arrives via
        // ContextCompat.startForegroundService() (see AetherController), and the
        // platform then REQUIRES a startForeground() call within ~5 s. Miss it
        // and the service is killed with ForegroundServiceDidNotStartInTime
        // Exception — an ANR on API 26-30 and an outright crash from API 31.
        //
        // The ACTION_DISCONNECT branch used to return without ever promoting
        // the service. That is fine while a session is live (we are already
        // foreground), and a CRASH in every case where we are not: the
        // notification's Disconnect action after a failed connect, a Quick
        // Settings tap, a widget toggle, or the kill-switch notification's
        // Disconnect after the service had been demoted. So promote
        // unconditionally, then decide what the intent meant.
        //
        // notifications.current() (not the plain builder) is used so promoting
        // never blanks the live speed card that is already on screen.
        startForeground(
            VpnNotifications.NOTIF_ID,
            notifications.current(getString(R.string.state_launching)),
        )

        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            // STRICT KILL SWITCH (1.2.4): a manual disconnect must not
            // open a leak window. With strict mode on, the first
            // disconnect engages lockdown instead; disconnecting FROM
            // lockdown lifts it. The lockdown branch requires a LIVE
            // session: the error notification keeps its Disconnect action
            // even after a failed connect, and engaging a device-wide
            // blackhole from that dead end would strand the user offline.
            val last = lastProfile
            when {
                lockdownTunActive -> stopEverything()
                runJob?.isActive == true && last != null && last.strictKillSwitch ->
                    enterLockdown(last)
                else -> stopEverything()
            }
            return START_NOT_STICKY
        }

        // A null intent means the SYSTEM restarted us after the process was
        // killed (START_STICKY). There is no payload then, and decoding null
        // used to yield a DEFAULT profile — so the tunnel silently came back up
        // as AUTO/PRECISE with the kill switch, split tunneling and every other
        // user setting reset. Flag that case so the session is rebuilt from the
        // persisted profile.
        if (intent == null || action == ACTION_CONNECT) {
            val restored = intent == null
            val profile = ProfileCodec.decode(intent?.getStringExtra(EXTRA_PROFILE))
            startTunnel(profile, restored)
            return START_STICKY
        }

        // ACTION HYGIENE: anything else used to fall into the connect branch and
        // bring a tunnel UP with a default profile — a stray or malformed intent
        // could start the VPN with settings the user never chose. An action we do
        // not recognise is a bug on the sender's side; say so and stand down.
        DiagnosticsLog.w(TAG, "Ignoring service intent with unknown action: $action")
        if (runJob?.isActive != true && !lockdownTunActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        runJob?.cancel()
        cleanupNatives()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ================================================================= connect

    private fun startTunnel(profile: ConnectionProfile, restored: Boolean = false) {
        lastProfile = profile
        // 1.2.2 PROTOCOL-SWITCH FIX: this used to bail out silently whenever a
        // previous run coroutine was still winding down ("if active, return"),
        // so a connect tapped right after a disconnect — or right after
        // switching protocol — was simply DROPPED. The user then waited,
        // tapped again, and the app looked like it took forever to start.
        // Now the new session takes ownership: it waits for the old one to
        // finish, tears its natives down, and only then launches the engine.
        val previousRun = runJob
        val previousStop = stopJob
        runJob = scope.launch {
            if (previousRun != null) {
                // Same ordering rule as the disconnect path: cancel, kill the
                // natives (which unblocks the old session immediately), and
                // only then wait for it to finish. Joining first would stall
                // the new connect for as long as the old session's engine wait
                // still had to run.
                previousRun.cancel()
                cleanupNatives()
                previousRun.join()
            }
            previousStop?.join()
            try {
                connectFlow(profile, restored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AetherController.setState(
                    ConnectionState.Error(e.message ?: getString(R.string.state_error)),
                )
                notifications.update(getString(R.string.state_error))
                cleanupNatives()
            }
        }
    }

    private suspend fun connectFlow(requested: ConnectionProfile, restored: Boolean) {
        DiagnosticsLog.clear()
        // Hydrated AFTER the log is cleared, so its notes survive in the panel.
        val profile = hydrate(requested, restored).also { lastProfile = it }
        // STALE-CIRCLES ROOT-CAUSE FIX: the four self-test circles were only
        // reset inside Diagnostics.run(), which starts AFTER the engine has
        // launched AND finished its endpoint scan — so on a reconnect the
        // previous session's green circles sat on screen for the entire scan
        // and appeared to "reset late". Reset them the INSTANT a new connect
        // starts, so the panel always reflects the current attempt on time.
        Diagnostics.resetChecks()
        EngineMeta.reset()
        DiagnosticsLog.i(
            TAG,
            "Connect requested — protocol=${profile.protocol} scan=${profile.scanMode} ip=${profile.ipVersion}",
        )

        val resolved: ConnectionProfile =
            if (profile.protocol == Protocol.AUTO) {
                connectSmartAuto(profile)
            } else {
                // An explicitly chosen protocol keeps that protocol; the
                // engine still selects its own endpoint.
                AetherController.setState(ConnectionState.Launching)
                runLadder(
                    ConnectionPlanner.manualProtocol(profile),
                    getString(R.string.err_protocol_failed),
                )
            }

        // Desktop-parity info row (1.2.4): publish the protocol that actually
        // won (Smart Auto resolves AUTO to a concrete protocol). The endpoint
        // arrives through EngineMeta's engine-log parser; for a pinned peer we
        // already know it here (no selection line is logged).
        EngineMeta.setProtocol(resolved.protocol.name)
        if (resolved.manualPeer.isNotBlank()) EngineMeta.setEndpoint(resolved.manualPeer)

        // LIVE SPEED IN THE SHADE: the meter starts BEFORE the Connected
        // transition, so the first notification the user pulls down already
        // carries a reading instead of a bare "Connected".
        notifications.startTrafficMeter()
        AetherController.setState(ConnectionState.Connected(VpnTunables.SOCKS_ENDPOINT))
        notifications.update(getString(R.string.state_connected))
        DiagnosticsLog.i(TAG, "All checks passed — tunnel is ready.")

        superviseEngine(resolved)
    }

    /**
     * Fills in the parts of the profile that cannot travel inside an Intent.
     *
     * - [restored] (system-initiated sticky restart, no payload): the whole
     *   profile is re-read from the DataStore, which is the only place the
     *   user's real settings still exist after a process kill.
     * - Zero Trust secrets are always re-read from the Keystore-sealed
     *   [SecretStore]: [ProfileCodec] deliberately keeps them out of the
     *   Intent, because extras show up in system service dumps.
     */
    private suspend fun hydrate(profile: ConnectionProfile, restored: Boolean): ConnectionProfile {
        if (restored) {
            DiagnosticsLog.w(TAG, "Restarted by the system — reconnecting with the saved profile.")
            // ProfileStore already unseals the Zero Trust secrets itself.
            val saved = runCatching { ProfileStore(applicationContext).profile.first() }
            (saved.exceptionOrNull() as? CancellationException)?.let { throw it }
            return saved.getOrNull() ?: profile
        }
        if (profile.teamAuth == TeamAuth.OFF) return profile
        val secrets = SecretStore(applicationContext)
        return profile.copy(
            accessClientSecret = secrets.read(SecretStore.ACCESS_SECRET),
            accessToken = secrets.read(SecretStore.ACCESS_TOKEN),
        )
    }

    /**
     * SMART AUTO (root-cause rework of the broken Auto protocol): fingerprint
     * the network's DPI first (see [SmartAuto]), then walk an ordered ladder
     * of concrete strategies — protocol + obfuscation + the IP ranges that
     * actually answered on THIS network — until one passes the full 4-step
     * self-test. Returns the strategy that won so the supervisor restarts the
     * engine with the SAME working configuration.
     */
    private suspend fun connectSmartAuto(userProfile: ConnectionProfile): ConnectionProfile {
        AetherController.setState(ConnectionState.Launching)
        notifications.update(getString(R.string.state_analyzing))
        val fingerprint = SmartAuto.fingerprint(this)
        val plan = SmartAuto.buildPlan(userProfile, fingerprint)
        return runLadder(plan, getString(R.string.err_auto_failed))
    }

    /**
     * Walks a ladder of strategies until one comes up and passes the full
     * self-test. Each failed rung is torn down before the next is tried.
     */
    private suspend fun runLadder(
        plan: List<AutoCandidate>,
        failureMessage: String,
    ): ConnectionProfile {
        var lastError: Exception? = null

        plan.forEachIndexed { index, candidate ->
            DiagnosticsLog.i(TAG, "Attempt ${index + 1}/${plan.size} → ${candidate.label}")
            try {
                connectAttempt(candidate.profile, candidate.timeoutMs)
                DiagnosticsLog.i(TAG, "Connected using ${candidate.label}")
                return candidate.profile
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                DiagnosticsLog.w(
                    TAG,
                    "${candidate.label} failed (${e.message}) — moving to the next strategy.",
                )
                cleanupNatives()
                Diagnostics.resetChecks()
            }
        }

        throw IllegalStateException(failureMessage, lastError)
    }

    /**
     * One full connect attempt with a CONCRETE protocol: launch engine, wait
     * for SOCKS5, bring up TUN/proxy, and gate on the 4-step self-test.
     * Throws on any failure; the caller decides whether to retry differently.
     */
    private suspend fun connectAttempt(profile: ConnectionProfile, timeoutMs: Long) {
        AetherController.setState(ConnectionState.Launching)
        notifications.update(getString(R.string.state_launching))
        // 1.2.2 PROTOCOL-SWITCH FIX: never start an engine on top of a dying
        // one. Tear the previous natives down and wait for the local SOCKS5
        // port to be released first, otherwise the probe below can "see" the
        // old listener and the whole attempt is verified against a socket that
        // is about to disappear. This is also what lifts a lockdown: the
        // blackhole TUN is torn down here.
        cleanupNatives()
        val portReleased = PortProbe.awaitClosed(
            VpnTunables.SOCKS_HOST,
            VpnTunables.SOCKS_PORT,
            VpnTunables.PORT_RELEASE_WAIT_MS,
        )
        if (!portReleased) {
            DiagnosticsLog.w(
                TAG,
                "Local port ${VpnTunables.SOCKS_PORT} is still busy after " +
                    "${VpnTunables.PORT_RELEASE_WAIT_MS / 1000}s — starting anyway.",
            )
        }
        DiagnosticsLog.i(TAG, "Launching engine (libaether.so)…")
        natives.startEngine(profile)

        AetherController.setState(ConnectionState.Connecting)
        notifications.update(getString(R.string.state_connecting))
        // Timeout comes from the caller: the profile's scan-mode budget for a
        // direct connect, or the per-candidate budget in the Smart Auto ladder.
        DiagnosticsLog.i(
            TAG,
            "Waiting for SOCKS5 on ${VpnTunables.SOCKS_ENDPOINT}… " +
                "(scan=${profile.scanMode}, timeout=${timeoutMs / 1000}s)",
        )
        val opened = PortProbe.awaitOpen(
            VpnTunables.SOCKS_HOST,
            VpnTunables.SOCKS_PORT,
            timeoutMs,
        ) { natives.engineAlive }
        if (!opened) {
            if (!natives.engineAlive) {
                DiagnosticsLog.e(TAG, "Engine exited before it opened the SOCKS5 port.")
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            DiagnosticsLog.e(
                TAG,
                "Engine still scanning after ${timeoutMs / 1000}s — SOCKS5 port never opened.",
            )
            throw IllegalStateException(getString(R.string.err_engine_timeout))
        }
        DiagnosticsLog.i(TAG, "SOCKS5 port is up.")

        if (profile.proxyMode) startLocalProxy(profile) else startFullTunnel(profile)

        // GATING FIX: the app used to report Connected the moment the TUN /
        // proxy was up while the 4-step self-test still ran in the background —
        // users saw "Connected" long before the tunnel could actually carry
        // traffic (and before the IP + flag appeared). The state is now held at
        // Verifying, and Connected is reported ONLY after all four checks pass,
        // so Connected == genuinely ready to browse.
        AetherController.setState(ConnectionState.Verifying)
        notifications.update(getString(R.string.state_verifying))
        DiagnosticsLog.i(
            TAG,
            if (profile.proxyMode) "Proxy started. Verifying end-to-end connectivity…"
            else "TUN + hev tunnel started. Verifying end-to-end connectivity…",
        )

        if (!selfTest(profile)) {
            DiagnosticsLog.e(TAG, "Self-test failed — refusing to report Connected.")
            throw IllegalStateException(getString(R.string.err_selftest))
        }

        // Informational only: report where the tunnel actually came out.
        // WARP edges are anycast, so the exit location is decided by the
        // engine's endpoint selection and the operator's routing, not by the
        // app. Nothing here can reject or override that choice.
        val exit = AetherController.ipInfo.value?.takeIf { it.viaTunnel }
        if (exit != null) {
            DiagnosticsLog.i(
                TAG,
                "Exit verified through the tunnel: ${exit.ip} (${exit.countryCode ?: "??"})",
            )
        }
    }

    /**
     * Proxy mode: DON'T capture the whole device through a system TUN. Instead
     * expose the engine's SOCKS5 + an HTTP proxy so individual apps (or the
     * Wi-Fi proxy setting) can opt in. This is ideal when only one app (e.g.
     * Telegram) needs the tunnel. LAN exposure only happens when the user
     * explicitly turned sharing on.
     */
    private fun startLocalProxy(profile: ConnectionProfile) {
        if (!natives.startLocalProxy(localOnly = !profile.lanShare)) {
            DiagnosticsLog.e(
                TAG,
                "Proxy mode: the fixed local proxy ports could not be opened (see errors above).",
            )
            throw IllegalStateException(getString(R.string.err_proxy_ports))
        }
        // Ports are FIXED (v2rayNG-style standard) — the same values are
        // shown as copyable rows under the Proxy-mode toggle in the UI.
        DiagnosticsLog.i(
            TAG,
            "Proxy mode: system TUN skipped. Local proxy ready — " +
                "SOCKS5 127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}, HTTP 127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
        )
    }

    private fun startFullTunnel(profile: ConnectionProfile) {
        natives.establishTun(profile)
        natives.startForwarder(profile)
        // LAN sharing: if the user enabled it, expose the tunnel to other
        // devices on the same Wi-Fi/hotspot (HTTP + SOCKS5 bridge).
        if (profile.lanShare) natives.startLanShare()
    }

    /**
     * The 4-step self-test, run against the endpoint that actually carries the
     * user's traffic.
     *
     * In proxy mode that is the SHARED SOCKS5 listener — the exact port external
     * apps connect to — so a dead bridge can no longer hide behind a passing
     * engine-port (1819) test. The initial connect got this right while the
     * post-restart check in [superviseEngine] did not, which is why the port
     * decision now lives in one function that both call.
     */
    private suspend fun selfTest(profile: ConnectionProfile): Boolean {
        val port =
            if (profile.proxyMode) ShareBridge.socksPort.value ?: VpnTunables.SOCKS_PORT
            else VpnTunables.SOCKS_PORT
        return runCatching { Diagnostics.run(port = port) }.getOrDefault(false)
    }

    // =============================================================== supervise

    /** Keeps the engine alive; retries with backoff if it dies. */
    private suspend fun superviseEngine(profile: ConnectionProfile) {
        var attempt = 0
        watchdog.reset()
        // Tracks THIS coroutine's own cancellation. The old check read the
        // `runJob` field, which a new session overwrites with its own job — so a
        // superseded supervisor could look "still active" and keep restarting an
        // engine that belonged to a session nobody was waiting for any more.
        while (currentCoroutineContext().isActive) {
            if (natives.engineAlive) {
                natives.awaitEngineExit(VpnTunables.WATCHDOG_INTERVAL_MS)
                // STABILITY WATCHDOG (1.2.4, hardened): the engine process can
                // stay alive while its session silently dies -- the classic
                // "connected, but after a minute or two no site opens"
                // symptom. Probe end-to-end THROUGH the local SOCKS5 port and
                // restart the engine only on SUSTAINED failure; see
                // TunnelWatchdog for why the bar is deliberately high.
                if (natives.engineAlive) {
                    when (watchdog.check()) {
                        TunnelHealth.HEALTHY -> attempt = 0
                        TunnelHealth.DEGRADED -> Unit
                        TunnelHealth.DEAD -> natives.stopEngine()
                    }
                }
                continue
            }

            if (attempt >= maxRetries(profile)) {
                // KILL SWITCH (1.2.4): instead of tearing the VPN down and
                // leaking direct, engage the blackhole lockdown.
                if (profile.killSwitch || profile.strictKillSwitch) {
                    enterLockdown(profile)
                    return
                }
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            val backoff = VpnTunables.BACKOFF[attempt.coerceAtMost(VpnTunables.BACKOFF.size - 1)]
            attempt++
            AetherController.setState(ConnectionState.Reconnecting(attempt, maxRetries(profile)))
            notifications.update(getString(R.string.state_reconnecting))
            delay(backoff)

            natives.startEngine(profile)
            val opened = PortProbe.awaitOpen(
                VpnTunables.SOCKS_HOST,
                VpnTunables.SOCKS_PORT,
                profile.connectTimeoutMs(),
            ) { natives.engineAlive }
            if (!opened) continue

            // Same gate as the initial connect: never claim Connected after
            // a silent engine restart until traffic really flows again.
            AetherController.setState(ConnectionState.Verifying)
            notifications.update(getString(R.string.state_verifying))
            if (selfTest(profile)) {
                attempt = 0
                watchdog.reset()
                // The engine was replaced, so the counters the meter polls
                // were rebased: restart it with the new session.
                notifications.startTrafficMeter()
                AetherController.setState(ConnectionState.Connected(VpnTunables.SOCKS_ENDPOINT))
                notifications.update(getString(R.string.state_connected))
            } else {
                DiagnosticsLog.w(TAG, "Self-test failed after engine restart — retrying.")
                natives.stopEngine()
            }
        }
    }

    /** Max automatic engine restarts (Smart Reconnect, 1.2.4). */
    private fun maxRetries(profile: ConnectionProfile): Int =
        if (profile.smartReconnect) {
            profile.reconnectRetryLimit.coerceIn(1, VpnTunables.MAX_ENGINE_RESTARTS)
        } else {
            VpnTunables.MAX_ENGINE_RESTARTS
        }

    // ================================================================ teardown

    private fun stopEverything() {
        AetherController.setState(ConnectionState.Disconnecting)
        notifications.update(getString(R.string.state_disconnecting))
        val job = runJob
        runJob = null
        // The session is over: its kill-switch policy must not leak into a
        // later ACTION_DISCONNECT that arrives with nothing running.
        lastProfile = null
        // DISCONNECT MUST BE INSTANT. Order matters:
        //   1. cancel the session coroutine (does not wait for it),
        //   2. kill the natives right away — this is what actually makes the
        //      tunnel stop, and it also unblocks any wait the session
        //      coroutine is parked in,
        //   3. flip the UI to Idle and drop the foreground notification,
        //   4. only THEN join the finished coroutine, off the critical path.
        // The previous order (join → cleanup) made the button sit on
        // "Disconnecting…" for as long as the supervisor's engine wait had
        // left to run — up to a full minute.
        job?.cancel()
        launchTeardown {
            cleanupNatives()
            // STALE-CIRCLES FIX (part 2): clear the finished session's results
            // right at disconnect, so the panel never carries green circles
            // from a dead session into the next connect.
            Diagnostics.resetChecks()
            EngineMeta.reset()
            AetherController.setState(ConnectionState.Idle)
            // The final Idle transition never goes through notifications.update(),
            // which is what repaints the tile and the widget — so without this
            // they stay on "Disconnecting…" forever.
            notifications.syncTileAndWidget()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            job?.join()
        }
    }

    /**
     * KILL SWITCH lockdown (1.2.4): stop the engine and the forwarder but
     * KEEP a blocking full-tunnel TUN up, so every packet is blackholed
     * instead of leaking direct. The service stays foreground; connecting
     * again or disconnecting lifts the lockdown.
     */
    private fun enterLockdown(profile: ConnectionProfile) {
        val job = runJob
        runJob = null
        job?.cancel()
        launchTeardown {
            // The speed meter polls hev's and the bridge's counters, so it has
            // to stop BEFORE the natives it reads are torn down.
            notifications.stopTrafficMeter()
            natives.stopForwarding()
            // If the blackhole interface cannot be established (consent
            // revoked, another VPN took over) there is nothing to hold the
            // traffic with — say so instead of pretending to be protected.
            val lockedDown = natives.establishLockdownTun(profile)
            lockdownTunActive = lockedDown
            Diagnostics.resetChecks()
            EngineMeta.reset()
            if (lockedDown) {
                AetherController.setState(ConnectionState.Error(getString(R.string.state_killswitch)))
                notifications.update(getString(R.string.state_killswitch))
            } else {
                AetherController.setState(
                    ConnectionState.Error(getString(R.string.err_lockdown_failed)),
                )
                notifications.update(getString(R.string.err_lockdown_failed))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            job?.join()
        }
    }

    /**
     * Runs a teardown on the IO dispatcher, AFTER any teardown that is still
     * finishing.
     *
     * Chaining matters: [stopJob] used to be overwritten outright, so a second
     * disconnect (or a disconnect immediately followed by a lockdown) dropped
     * the reference to the teardown still in flight, and the next connect only
     * waited for the last one — which could easily finish first.
     */
    private fun launchTeardown(block: suspend () -> Unit) {
        val previous = stopJob
        stopJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            block()
        }
    }

    /** Stops every native piece of the session, including the TUN. */
    private fun cleanupNatives() {
        // Meter first: it polls counters that belong to the natives below.
        notifications.stopTrafficMeter()
        natives.teardown()
        lockdownTunActive = false
    }

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aether.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aether.DISCONNECT"
        const val EXTRA_PROFILE = "profile"

        private const val TAG = "vpn"
    }
}
