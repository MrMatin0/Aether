package studio.cluvex.aether.model

/**
 * One circumvention core the app can run. Each of the three exposes a local
 * SOCKS5 proxy on loopback, which is the ONLY thing that makes chaining them
 * possible at all.
 */
enum class Hop(val label: String) {
    /** The bundled Rust engine (libaether.so): MASQUE / WireGuard / Gool over WARP. */
    AETHER("Aether"),

    /** psiphon-tunnel-core's console client (libpsiphon.so). */
    PSIPHON("Psiphon"),

    /** tor, as built for Android by the Tor Project / Guardian Project (libtor.so). */
    TOR("Tor"),
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
 *  - The Aether engine has NO upstream-proxy option — it dials WARP endpoints
 *    itself.
 *
 * Aether therefore can only ever be the internet-facing hop, and Tor — the
 * slowest and the only one that also anonymises — is always the entry, because
 * putting anything after Tor would hand a single fixed proxy every stream that
 * left the Tor network and defeat the point of using it. That leaves exactly
 * the seven combinations below; anything else is either impossible or
 * pointless, which is why this is an enum and not three checkboxes.
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
    ;

    val usesAether: Boolean get() = Hop.AETHER in hops
    val usesPsiphon: Boolean get() = Hop.PSIPHON in hops
    val usesTor: Boolean get() = Hop.TOR in hops

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
                else -> null
            }
        }
    }
}
