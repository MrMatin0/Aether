package studio.cluvex.aether.vpn.session

import studio.cluvex.aether.core.TunnelConfig
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

    /** The engine's local SOCKS5 listener. Single source of truth: [TunnelConfig]. */
    const val SOCKS_HOST = TunnelConfig.SOCKS_HOST
    const val SOCKS_PORT = TunnelConfig.SOCKS_PORT

    /** `host:port` form, used for the Connected state label. */
    const val SOCKS_ENDPOINT = "$SOCKS_HOST:$SOCKS_PORT"

    /** Backoff ladder between automatic engine restarts. */
    val BACKOFF = longArrayOf(2_000L, 5_000L, 10_000L)

    /**
     * Automatic engine restarts allowed per session. Smart Reconnect lets the
     * user lower it; without it the supervisor keeps trying.
     */
    const val MAX_ENGINE_RESTARTS = 50

    /** Bounds a user-supplied MTU to what `VpnService.Builder` accepts. */
    const val MIN_MTU = 576
    const val MAX_MTU = 9000

    /**
     * Watchdog probe cadence while the tunnel is up (1.2.4). It doubles as
     * the upper bound for ONE blocking wait on the engine process: the
     * supervisor never polls, it parks on the process itself and only wakes
     * up this often to re-check its own cancellation state and to probe the
     * tunnel end-to-end.
     */
    const val WATCHDOG_INTERVAL_MS = 30_000L

    /**
     * Consecutive failed checks before the engine is restarted (1.2.4
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
     * How long to wait for the previous engine to release the local SOCKS5
     * port before starting a new one (1.2.2 protocol-switch fix).
     */
    const val PORT_RELEASE_WAIT_MS = 3_000L

    /**
     * Cap for the FIRST attempt of a hand-picked protocol, so a throttled
     * network cannot hold the user on "Connecting" for the whole scan
     * budget before the hardened second pass is even tried.
     */
    const val FIRST_PASS_MAX_MS = 75_000L
}

/**
 * The profile MTU, clamped to what the TUN (and hev's lwIP netif) accept.
 *
 * Both must agree, so this is the single place the value is bounded — the TUN
 * builder, the hev config and the userspace bridge all read it from here.
 */
internal fun ConnectionProfile.safeMtu(): Int =
    mtu.coerceIn(VpnTunables.MIN_MTU, VpnTunables.MAX_MTU)
