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
    /** Loopback: every core in the chain listens here and nowhere else. */
    const val SOCKS_HOST = "127.0.0.1"

    /**
     * The Aether engine's OWN local SOCKS5 listener. Fixed: the engine binds it
     * itself and takes no port argument.
     */
    const val ENGINE_SOCKS_PORT = 1819

    /**
     * The DNS-capable SOCKS5 front ([SocksFront]). Used as the chain entry
     * whenever the entry core is NOT Aether, because neither Psiphon's nor
     * Tor's local SOCKS5 implements UDP ASSOCIATE and the TUN forwarder needs
     * *something* to answer the device's UDP DNS queries. Deliberately adjacent
     * to 1819 so the two ports read as one block in a `netstat` dump.
     */
    const val FRONT_SOCKS_PORT = 1820

    /**
     * Psiphon's local SOCKS5 (`LocalSocksProxyPort`).
     *
     * NOT 1080: that is psiphon-tunnel-core's own default and therefore what a
     * separately installed Psiphon app already owns. Two tunnels fighting over
     * one port is how "Aether breaks Psiphon" bug reports get written.
     */
    const val PSIPHON_SOCKS_PORT = 1891

    /**
     * Tor's `SocksPort` and `DNSPort`.
     *
     * NOT 9050 / 9051 / 5400: those belong to Orbot, and Orbot is exactly the
     * app a user of this feature is most likely to also have installed.
     */
    const val TOR_SOCKS_PORT = 9819
    const val TOR_DNS_PORT = 5819

    /**
     * The port that carries the USER'S traffic: the chain entry.
     *
     * ### Why this is a value and not a constant any more
     *
     * With one core there was one port and the two ideas were the same thing.
     * With a chain they are not: `Tor over Aether` has the engine on 1819 and
     * the device's traffic entering on [FRONT_SOCKS_PORT]. Everything in the
     * app that probes "is the tunnel working", measures its latency, reads the
     * exit IP or relays LAN clients into it means the ENTRY, so they all keep
     * reading `SOCKS_PORT` and follow the chain for free. Only the code that
     * starts and waits for the engine specifically wants [ENGINE_SOCKS_PORT].
     *
     * Published by [ChainRuntime] when a session's chain is up, and reset with
     * it on teardown, so a finished session's port can never leak into the
     * next one's probes.
     */
    val SOCKS_PORT: Int get() = ChainRuntime.entryPort

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
     * (1280 - safe for Iranian mobile networks and aggressive DPI), clamped by
     * the VpnService, which writes the SAME value into the TUN and into hev's
     * `tunnel.mtu`.
     */
    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
}
