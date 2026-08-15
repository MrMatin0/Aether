package studio.cluvex.aether.core

/**
 * Single source of truth for the tunnel plumbing constants shared between the
 * VpnService (which builds the TUN + hev config) and the UI/diagnostics layer
 * (which talks to the local SOCKS5 proxy to probe connectivity and geolocation).
 *
 * IMPORTANT: [TUN_IPV4] MUST be written into BOTH the VpnService interface
 * address AND the hev-socks5-tunnel `tunnel.ipv4` field. v2rayNG always sets
 * `tunnel.ipv4` in its hev config; omitting it leaves hev's internal lwIP netif
 * without an address, so packets are read from TUN but never routed to the
 * SOCKS5 proxy -> the classic "connected but no site loads" symptom.
 */
object TunnelConfig {
    /** Local SOCKS5 proxy the Aether engine exposes. */
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1819

    /** Point-to-point TUN addressing (matches hev tunnel.ipv4 / tunnel.ipv6). */
    const val TUN_IPV4 = "10.10.14.1"
    const val TUN_IPV4_PREFIX = 30
    const val TUN_IPV6 = "fc00::10:10:14:1"
    const val TUN_IPV6_PREFIX = 126

    /**
     * DNS resolvers advertised on the TUN interface.
     *
     * NOTE: there is deliberately no MTU constant here any more. The one source
     * of truth is [studio.cluvex.aether.model.ConnectionProfile.DEFAULT_MTU]
     * (1280 — safe for Iranian mobile networks and aggressive DPI), clamped by
     * the VpnService, which writes the SAME value into the TUN and into hev's
     * `tunnel.mtu`.
     */
    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
}
