package studio.cluvex.aether.vpn.session

import studio.cluvex.aether.core.BridgePlan
import studio.cluvex.aether.core.ChainRuntime
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile

/**
 * Every timeout, retry budget and bound the VPN session depends on, in one
 * place, each one saying WHY it has the value it has.
 *
 * These used to sit in AetherVpnService's companion object next to a handful of
 * raw literals scattered through the file, which made it impossible to tell at
 * a glance which numbers were load-bearing. Same convention as
 * [studio.cluvex.aether.core.tunnel.Tunables], which does this for the bridge.
 */
internal object VpnTunables {

    /** Loopback. Single source of truth: [TunnelConfig]. */
    const val SOCKS_HOST = TunnelConfig.SOCKS_HOST

    /** The Aether engine's own listener. Only the engine path cares about this. */
    const val ENGINE_SOCKS_PORT = TunnelConfig.ENGINE_SOCKS_PORT

    /**
     * The port that carries the user's traffic for the CURRENT session, i.e.
     * the chain entry. Read live: with a chain it is not the engine's port, and
     * with no chain it still is.
     */
    val entryPort: Int get() = ChainRuntime.entryPort

    /** `host:port` form of the entry, used for the Connected state label. */
    val entryEndpoint: String get() = ChainRuntime.endpoint

    /** Backoff ladder between automatic core restarts. */
    val BACKOFF = longArrayOf(2_000L, 5_000L, 10_000L)

    /**
     * Automatic core restarts allowed per session. Smart Reconnect lets the
     * user lower it; without it the supervisor keeps trying.
     */
    const val MAX_ENGINE_RESTARTS = 50

    /** Bounds a user-supplied MTU to what `VpnService.Builder` accepts. */
    const val MIN_MTU = 576
    const val MAX_MTU = 9000

    /**
     * Watchdog probe cadence while the tunnel is up (1.2.4). It doubles as
     * the upper bound for ONE blocking wait on a core process: the supervisor
     * never polls, it parks on the process itself and only wakes up this often
     * to re-check its own cancellation state and to probe the tunnel
     * end-to-end.
     */
    const val WATCHDOG_INTERVAL_MS = 30_000L

    /**
     * Consecutive failed checks before the cores are restarted (1.2.4
     * hardening): three failed checks = 90 s+ of proven dead tunnel, so
     * only a genuinely dead session is restarted.
     */
    const val WATCHDOG_FAIL_CYCLES = 3

    /**
     * Attempts per watchdog check, rotating over anycast resolvers so one
     * blocked or slow target can never fake a dead tunnel (1.2.4 fix).
     */
    const val PROBE_ATTEMPTS = 3
    val PROBE_TARGETS = arrayOf("1.1.1.1:53", "1.0.0.1:53", "9.9.9.9:53")
    const val PROBE_TIMEOUT_MS = 8_000
    const val PROBE_RETRY_GAP_MS = 1_500L

    /**
     * How long to wait for the previous session to release a local port before
     * starting a new core on it (1.2.2 protocol-switch fix).
     */
    const val PORT_RELEASE_WAIT_MS = 3_000L

    /**
     * Cap for the FIRST attempt of a hand-picked protocol, so a throttled
     * network cannot hold the user on "Connecting" for the whole scan
     * budget before the hardened second pass is even tried.
     */
    const val FIRST_PASS_MAX_MS = 75_000L

    // ------------------------------------------------------------ chain hops

    /**
     * How long Psiphon's local SOCKS5 listener gets to appear. Short on
     * purpose: the listener is opened almost immediately after launch, so a
     * miss here means the process is broken, not slow.
     */
    const val PSIPHON_PORT_WAIT_MS = 20_000L

    /**
     * How long Psiphon gets to establish an actual tunnel. Generous, because
     * this is server discovery over a filtered network - the same class of
     * work the engine's own endpoint scan does, and the engine is allowed up
     * to 300 s for it.
     */
    const val PSIPHON_READY_WAIT_MS = 180_000L

    /** Same reasoning as [PSIPHON_PORT_WAIT_MS]: tor binds its ports at once. */
    const val TOR_PORT_WAIT_MS = 20_000L

    /**
     * How long tor gets to reach `Bootstrapped 100%`.
     *
     * A first bootstrap on a hostile mobile network regularly takes over two
     * minutes: tor has to fetch a consensus and build a circuit, and when it is
     * chained it does all of that THROUGH another tunnel's added latency. Four
     * minutes is the difference between "Tor is slow here" and a false "Tor is
     * blocked here".
     *
     * This is the budget for the LAST rung of the bridge ladder, i.e. the one
     * that has no fallback left behind it.
     */
    const val TOR_BOOTSTRAP_WAIT_MS = 240_000L

    /**
     * How long a rung of the bridge ladder that still HAS a fallback gets.
     *
     * Deliberately much shorter than [TOR_BOOTSTRAP_WAIT_MS], because the two
     * answer different questions. "Is Tor reachable from this network at all"
     * deserves four patient minutes. "Is THIS transport getting through" does
     * not: a transport that is going to work is normally past 50% inside a
     * minute, and one that is being filtered sits at 5% forever. Spending the
     * full window on each rung would turn a three-rung ladder into twelve
     * minutes of spinner, which is a worse outcome than not falling back.
     */
    const val TOR_BRIDGE_ATTEMPT_WAIT_MS = 90_000L

    /**
     * Total budget for a chain-only attempt (no Aether hop, so no endpoint
     * scan): the sum of what each core is allowed, plus a little slack for the
     * front to bind and the self-test to run.
     *
     * The Tor term is the WORST CASE of a full bridge ladder
     * ([BridgePlan.MAX_ATTEMPTS] rungs), not of one attempt. It is a ceiling
     * that only a session in which every transport failed ever reaches; each
     * individual wait is bounded by the constants above.
     */
    fun chainBudgetMs(mode: ChainMode): Long {
        var budget = 15_000L
        if (mode.usesPsiphon) budget += PSIPHON_PORT_WAIT_MS + PSIPHON_READY_WAIT_MS
        if (mode.usesTor) {
            budget += TOR_PORT_WAIT_MS + TOR_BOOTSTRAP_WAIT_MS
            budget += (BridgePlan.MAX_ATTEMPTS - 1) *
                (TOR_PORT_WAIT_MS + TOR_BRIDGE_ATTEMPT_WAIT_MS)
        }
        return budget
    }
}

/**
 * The profile MTU, clamped to what the TUN (and hev's lwIP netif) accept.
 *
 * Both must agree, so this is the single place the value is bounded - the TUN
 * builder, the hev config and the userspace bridge all read it from here.
 */
internal fun ConnectionProfile.safeMtu(): Int =
    mtu.coerceIn(VpnTunables.MIN_MTU, VpnTunables.MAX_MTU)
