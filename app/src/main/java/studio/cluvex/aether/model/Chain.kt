package studio.cluvex.aether.model

/**
 * One circumvention core the app can run.
 *
 * Aether, Psiphon, Xray and Tor each expose a local SOCKS5 proxy on loopback,
 * which is the ONLY thing that makes chaining them possible at all. [SSTP] is
 * the odd one out: it is a LAYER 3 tunnel (PPP inside TLS), so what it hands the
 * device is IP packets, not a SOCKS5 listener. That is why it only ever runs
 * alone - see [ChainMode.SSTP].
 */
enum class Hop(val label: String) {
    /** The bundled Rust engine (libaether.so): MASQUE / WireGuard / Gool over WARP. */
    AETHER("Aether"),

    /** psiphon-tunnel-core's console client (libpsiphon.so). */
    PSIPHON("Psiphon"),

    /** tor, as built for Android by the Tor Project / Guardian Project (libtor.so). */
    TOR("Tor"),

    /**
     * MS-SSTP, in pure Kotlin (core/SstpCore.kt): no binary, so it is in every
     * build. Talks to a private SSTP server or a VPN Gate relay.
     */
    SSTP("SSTP"),

    /**
     * XTLS/Xray-core (libxray.so), running the USER'S OWN server config:
     * VLESS / VMess / Trojan / Shadowsocks over TCP, WS, gRPC, XHTTP...,
     * with TLS or REALITY. See core/XrayConfig.kt.
     *
     * Xray HAS an upstream option - `streamSettings.sockopt.dialerProxy` - so
     * unlike Aether it can sit on top of another hop: its own handshake to the
     * user's server is then dialled through that hop's local SOCKS5. Appended
     * last on purpose, like every enum here whose names may be persisted.
     */
    XRAY("Xray"),
}

/**
 * Which cores carry this session, and in which order.
 *
 * ### Reading [hops]
 *
 * [hops] is ordered **internet-side first**: `hops.first()` is the core that
 * talks to the open internet, `hops.last()` is the core the device's own traffic
 * enters (the CHAIN ENTRY, i.e. the local SOCKS5 port the TUN forwarder is
 * pointed at). So `TOR_OVER_PSIPHON_OVER_AETHER` is
 *
 * ```
 * device -> Tor -> Psiphon -> Aether -> internet
 * ```
 *
 * ### Why the order is fixed and not a free permutation
 *
 * Chaining works by telling core N to dial its upstream through core N-1's
 * local SOCKS5 port. That only works for cores that HAVE such an option:
 *
 *  - Psiphon has `UpstreamProxyUrl` (psiphon/config.go).
 *  - Tor has `Socks5Proxy` (torrc), which routes every OR connection through it.
 *  - Xray has `streamSettings.sockopt.dialerProxy`, pointed at a SOCKS
 *    outbound on the hop below it (see core/XrayConfig.kt).
 *  - The Aether engine has NO upstream-proxy option — it dials WARP endpoints
 *    itself.
 *
 * Aether therefore can only ever be the internet-facing hop, and Tor — the
 * slowest and the only one that also anonymises — is always the entry, because
 * putting anything after Tor would hand a single fixed proxy every stream that
 * left the Tor network and defeat the point of using it. That leaves the seven
 * Aether / Psiphon / Tor combinations below, plus the Xray ones.
 *
 * ### Xray is never alone
 *
 * Xray is offered ONLY stacked: either its handshake rides Aether and/or
 * Psiphon (the network sees WARP or Psiphon, never the Xray server's address),
 * or it carries Tor's relay connections. A bare "Xray" mode would be a plain
 * v2ray client, which is not what this app adds.
 *
 * ### Why SSTP is never stacked
 *
 * SSTP has neither a SOCKS5 listener to be dialled through nor an upstream-proxy
 * option to dial through somebody else's, so it can neither sit under Psiphon /
 * Tor nor above Aether. It is a standalone mode whose session builds the TUN
 * straight from the PPP link (see AetherVpnService).
 */
enum class ChainMode(val hops: List<Hop>) {
    /** Aether alone. The behaviour of every build before chaining existed. */
    AETHER(listOf(Hop.AETHER)),

    /** Psiphon alone. Useful when WARP itself is what the network blocks. */
    PSIPHON(listOf(Hop.PSIPHON)),

    /** Tor alone. */
    TOR(listOf(Hop.TOR)),

    /** Psiphon dials out through Aether: the operator sees only WARP traffic. */
    PSIPHON_OVER_AETHER(listOf(Hop.AETHER, Hop.PSIPHON)),

    /** Tor dials out through Aether: hides "this device uses Tor" from the ISP. */
    TOR_OVER_AETHER(listOf(Hop.AETHER, Hop.TOR)),

    /** Tor dials out through Psiphon, for networks where Tor bridges are dead. */
    TOR_OVER_PSIPHON(listOf(Hop.PSIPHON, Hop.TOR)),

    /** All three. Slowest and most expensive; the last thing left to try. */
    TOR_OVER_PSIPHON_OVER_AETHER(listOf(Hop.AETHER, Hop.PSIPHON, Hop.TOR)),

    /**
     * SSTP alone: a packet tunnel to an SSTP server or a VPN Gate relay.
     * Appended LAST on purpose: names are persisted, and the picker groups
     * modes by [isChained], not by declaration order.
     */
    SSTP(listOf(Hop.SSTP)),

    // ------------------------------------------------------------- Xray
    // Appended after SSTP for the same reason SSTP was appended: names are
    // persisted. Every one of them is stacked (see "Xray is never alone").

    /**
     * The user's Xray server, with the Xray handshake dialled THROUGH Aether:
     * the operator sees WARP traffic, and the Xray server sees Cloudflare.
     */
    XRAY_OVER_AETHER(listOf(Hop.AETHER, Hop.XRAY)),

    /** The user's Xray server, reached through Psiphon. For when WARP is blocked too. */
    XRAY_OVER_PSIPHON(listOf(Hop.PSIPHON, Hop.XRAY)),

    /** Xray through Psiphon through Aether. The heaviest Xray path. */
    XRAY_OVER_PSIPHON_OVER_AETHER(listOf(Hop.AETHER, Hop.PSIPHON, Hop.XRAY)),

    /** Tor's relay connections ride the user's Xray server: the ISP sees only Xray. */
    TOR_OVER_XRAY(listOf(Hop.XRAY, Hop.TOR)),

    /** Tor through the user's Xray server, itself reached through Aether. */
    TOR_OVER_XRAY_OVER_AETHER(listOf(Hop.AETHER, Hop.XRAY, Hop.TOR)),
    ;

    val usesAether: Boolean get() = Hop.AETHER in hops
    val usesPsiphon: Boolean get() = Hop.PSIPHON in hops
    val usesTor: Boolean get() = Hop.TOR in hops
    val usesXray: Boolean get() = Hop.XRAY in hops

    /** True for the SSTP packet tunnel, which has no SOCKS5 entry at all. */
    val usesSstp: Boolean get() = Hop.SSTP in hops

    /** The hop the device's traffic ENTERS. Its local port is the chain entry. */
    val entryHop: Hop get() = hops.last()

    /** True when more than one core is stacked. */
    val isChained: Boolean get() = hops.size > 1

    /**
     * The hop [hop] must dial its upstream through, or null when [hop] talks
     * straight to the network. This is the single place the wiring order turns
     * into a concrete answer, so a new mode cannot be half-wired.
     */
    fun upstreamOf(hop: Hop): Hop? {
        val index = hops.indexOf(hop)
        return if (index <= 0) null else hops[index - 1]
    }

    /** "Tor -> Psiphon -> Aether -> internet", for logs and the info row. */
    fun pathLabel(): String =
        (hops.asReversed().map { it.label } + "internet").joinToString(" \u2192 ")

    companion object {
        /**
         * Reads a mode from a persisted / transported name, or null when the
         * value means nothing (the caller keeps its own default).
         *
         * The aliases exist because "aether+psiphon" is the obvious thing for a
         * human to type into an exported config by hand, and silently resetting
         * such a config to Aether-only would be indistinguishable from the
         * feature not working.
         */
        fun fromStored(raw: String?): ChainMode? {
            val name = raw?.trim()?.uppercase()?.replace('-', '_')?.replace('+', '_')
                ?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.name == name }?.let { return it }
            return when (name) {
                "OFF", "NONE", "AETHER_ONLY" -> AETHER
                "AETHER_PSIPHON", "PSIPHON_AETHER" -> PSIPHON_OVER_AETHER
                "AETHER_TOR", "TOR_AETHER" -> TOR_OVER_AETHER
                "PSIPHON_TOR", "TOR_PSIPHON" -> TOR_OVER_PSIPHON
                "AETHER_PSIPHON_TOR", "ALL" -> TOR_OVER_PSIPHON_OVER_AETHER
                "SSTP_ONLY", "VPNGATE", "VPN_GATE", "MS_SSTP" -> SSTP
                "AETHER_XRAY", "XRAY_AETHER" -> XRAY_OVER_AETHER
                "PSIPHON_XRAY", "XRAY_PSIPHON" -> XRAY_OVER_PSIPHON
                "AETHER_PSIPHON_XRAY", "XRAY_PSIPHON_AETHER" -> XRAY_OVER_PSIPHON_OVER_AETHER
                "XRAY_TOR", "TOR_XRAY" -> TOR_OVER_XRAY
                "AETHER_XRAY_TOR", "TOR_XRAY_AETHER" -> TOR_OVER_XRAY_OVER_AETHER
                else -> null
            }
        }
    }
}
