package studio.cluvex.aether.vpn.session

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

    // There is deliberately no cap on the FIRST attempt of a hand-picked
    // protocol any more (fix/masque-scan). FIRST_PASS_MAX_MS = 75 s sat under
    // the engine's own 120 s balanced / 180 s ironclad MASQUE sweep, so the
    // attempt was killed mid-scan on exactly the networks where the scan was
    // slow but working. See ConnectionPlanner.manualProtocol.

    // --------------------------------------------------------- session budgets
    //
    // perf/fast-connect. Every ladder used to be a plain loop over candidates,
    // each with its own full budget, so the time a FAILING session took was the
    // SUM of every rung's scan budget plus a 90 s self-test per rung - up to
    // about twelve minutes on Smart Auto. Each ladder now runs under ONE
    // session deadline (AetherVpnService.runLadder): a rung gets
    // min(its own budget, what is left), and a rung that cannot fit is skipped.

    /**
     * What a session needs on top of the engine/chain budgets: the data-plane
     * check, TUN + hev, and the connect gate.
     */
    const val SESSION_SLACK_MS = 30_000L

    /**
     * Session budget of a hand-picked protocol beyond its FULL first pass: room
     * for the anti-DPI (HTTP/2 + fragment) pass, and for the slack above.
     */
    const val MANUAL_SESSION_EXTRA_MS = 90_000L

    /** Smallest remaining session budget worth launching another rung with. */
    const val MIN_RUNG_BUDGET_MS = 15_000L

    // ------------------------------------------------------------- readiness

    /**
     * After the engine's SOCKS5 port opens: how long a real CONNECT through it
     * gets to succeed before TUN/hev are started. The engine binds its listener
     * only once a tunnel validated, so this is normally a single round trip;
     * the window covers a gool / masque-in-masque inner hop that is still
     * settling, without the old 90 s self-test grace.
     */
    const val ENGINE_DATAPLANE_WAIT_MS = 25_000L

    /** Never less than this, even when the session budget is nearly spent. */
    const val DATAPLANE_MIN_WAIT_MS = 5_000L

    /** One CONNECT probe of the data-plane check. */
    const val DATAPLANE_PROBE_TIMEOUT_MS = 3_000

    /** Gap between two data-plane probes. */
    const val DATAPLANE_RETRY_MS = 500L

    /**
     * Warm-up of a DNS front / Smart DNS front before its port is published as
     * the chain entry. Bounded and non-fatal: it only moves the first lookup's
     * cost from the user's first request to the connect.
     */
    const val FRONT_WARMUP_MS = 6_000L

    // ------------------------------------------------------------ chain hops

    /** Fixed allowance in every chain budget for the fronts to bind and warm. */
    const val CHAIN_SLACK_MS = 15_000L

    /**
     * How long Psiphon's local SOCKS5 listener gets to appear. Short on
     * purpose: the listener is opened almost immediately after launch, so a
     * miss here means the process is broken, not slow.
     */
    const val PSIPHON_PORT_WAIT_MS = 20_000L

    /**
     * How long Psiphon gets to establish an actual tunnel. Generous, because
     * this is server discovery over a filtered network - the same class of
     * work the engine's own endpoint scan does.
     */
    const val PSIPHON_READY_WAIT_MS = 180_000L

    /**
     * The FIRST pass of a hand-picked Psiphon region, when an automatic-exit
     * retry is still planned behind it. Shorter than [PSIPHON_READY_WAIT_MS] so
     * that retry fits inside the same chain deadline instead of being a second
     * full budget.
     */
    const val PSIPHON_REGION_ATTEMPT_WAIT_MS = 90_000L

    /** Psiphon's automatic-exit retry is only started with at least this left. */
    const val PSIPHON_RETRY_MIN_MS = 45_000L

    /** Same reasoning as [PSIPHON_PORT_WAIT_MS]: tor binds its ports at once. */
    const val TOR_PORT_WAIT_MS = 20_000L

    /**
     * How long tor gets to reach `Bootstrapped 100%` on the LAST rung of the
     * bridge ladder, the one with no fallback behind it - bounded by whatever
     * is left of [TOR_CHAIN_BUDGET_MS].
     *
     * A first bootstrap on a hostile mobile network regularly takes over two
     * minutes: tor has to fetch a consensus and build a circuit, and when it is
     * chained it does all of that THROUGH another tunnel's added latency.
     */
    const val TOR_BOOTSTRAP_WAIT_MS = 240_000L

    /**
     * How long a rung of the bridge ladder that still HAS a fallback gets.
     *
     * "Is THIS transport getting through" does not deserve four minutes: a
     * transport that is going to work is normally past 50% inside a minute,
     * and one that is being filtered sits at 5% forever.
     */
    const val TOR_BRIDGE_ATTEMPT_WAIT_MS = 90_000L

    /**
     * The WHOLE Tor hop, every rung of the bridge ladder included. Before
     * perf/fast-connect each rung brought its own full budget and nothing
     * bounded their sum.
     */
    const val TOR_CHAIN_BUDGET_MS = 300_000L

    /** A later Tor rung is only launched with at least this much left. */
    const val TOR_RUNG_MIN_MS = 30_000L

    /**
     * Total budget for the overlay hops of [mode]: what ChainStack's deadline
     * is set to, and therefore a REAL ceiling now, not the sum of worst cases
     * it used to be (that number was passed on as an engine timeout and never
     * bounded the chain at all).
     */
    fun chainBudgetMs(mode: ChainMode): Long {
        var budget = CHAIN_SLACK_MS
        if (mode.usesPsiphon) budget += PSIPHON_PORT_WAIT_MS + PSIPHON_READY_WAIT_MS
        if (mode.usesTor) budget += TOR_CHAIN_BUDGET_MS
        return budget
    }

    /**
     * What a session with an Aether hop adds to its own ladder budget for the
     * Psiphon / Tor hops on top of it; zero for an Aether-only chain.
     */
    fun overlayBudgetMs(mode: ChainMode): Long =
        if (mode.usesPsiphon || mode.usesTor) chainBudgetMs(mode) else 0L
}

/**
 * The profile MTU, clamped to what the TUN (and hev's lwIP netif) accept.
 *
 * Both must agree, so this is the single place the value is bounded - the TUN
 * builder, the hev config and the userspace bridge all read it from here.
 */
internal fun ConnectionProfile.safeMtu(): Int =
    mtu.coerceIn(VpnTunables.MIN_MTU, VpnTunables.MAX_MTU)
