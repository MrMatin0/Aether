package studio.cluvex.aether.model

/**
 * What the chosen core dials out THROUGH: the hops underneath the chain entry,
 * internet side first, in the same order as [ChainMode.hops].
 *
 * ### Why this exists
 *
 * The connection tab picks a route in two steps: which core carries the
 * device's traffic (the entry, [ChainMode.entryHop]), then what that core rides
 * on. Every [ChainMode] is exactly one such pair, so this is a read-only view of
 * the modes that already exist, not a new thing to persist: the profile keeps
 * storing a [ChainMode] name, and [CoreRoutes.modeOf] / [CoreRoutes.underlayOf]
 * convert both ways.
 *
 * ### Reading [hops]
 *
 * `entry + underlay` is `underlay.hops + entry`, so [PSIPHON_AETHER] under Tor
 * is `device -> Tor -> Psiphon -> Aether -> internet`, i.e.
 * [ChainMode.TOR_OVER_PSIPHON_OVER_AETHER]. The labels read the same way, from
 * the core outwards: "via Psiphon + Aether".
 */
enum class Underlay(val hops: List<Hop>) {
    /** Nothing underneath: the core talks to the network itself. */
    DIRECT(emptyList()),

    /** Through the Aether engine (WARP). */
    AETHER(listOf(Hop.AETHER)),

    /** Through Psiphon. Only Tor can ride it. */
    PSIPHON(listOf(Hop.PSIPHON)),

    /** Through Psiphon, itself through Aether. Only Tor can ride it. */
    PSIPHON_AETHER(listOf(Hop.AETHER, Hop.PSIPHON)),

    /** Through the user's own Xray server. */
    XRAY(listOf(Hop.XRAY)),

    /** Through Aether, itself dialled through the user's Xray server. */
    AETHER_XRAY(listOf(Hop.XRAY, Hop.AETHER)),
    ;

    /** True when this underlay needs a saved Xray config to run at all. */
    val usesXray: Boolean get() = Hop.XRAY in hops
}

/**
 * The (entry core, underlay) view of [ChainMode], and the one rule the core
 * selector needs on top of it.
 *
 * Pure Kotlin on purpose: every answer here is a function of the enums alone,
 * so the whole table is pinned by a unit test (CoreRouteTest) that round-trips
 * all fourteen modes.
 */
object CoreRoutes {

    /** The five cores, in the order the connection tab shows them. */
    val cores: List<Hop> = listOf(Hop.AETHER, Hop.PSIPHON, Hop.TOR, Hop.SSTP, Hop.XRAY)

    /**
     * What [core] can ride on, in picker order (Direct first).
     *
     *  - Aether: Direct, via Xray.
     *  - Psiphon: Direct, via Aether, via Xray, via Aether + Xray.
     *  - Tor: Direct, via Aether, via Psiphon, via Psiphon + Aether, via Xray,
     *    via Aether + Xray.
     *  - SSTP: Direct only. It is a packet tunnel with no SOCKS5 to be dialled
     *    through and no upstream option to dial through anyone else.
     *  - Xray: Direct only. It is always the internet-facing hop.
     */
    fun underlaysFor(core: Hop): List<Underlay> = when (core) {
        Hop.AETHER -> listOf(Underlay.DIRECT, Underlay.XRAY)
        Hop.PSIPHON -> listOf(Underlay.DIRECT, Underlay.AETHER, Underlay.XRAY, Underlay.AETHER_XRAY)
        Hop.TOR -> listOf(
            Underlay.DIRECT,
            Underlay.AETHER,
            Underlay.PSIPHON,
            Underlay.PSIPHON_AETHER,
            Underlay.XRAY,
            Underlay.AETHER_XRAY,
        )
        Hop.SSTP, Hop.XRAY -> listOf(Underlay.DIRECT)
    }

    /**
     * The mode that runs [core] on [underlay], or null when that pairing is not
     * one of the fourteen modes (SSTP via anything, Aether via Psiphon...).
     */
    fun modeOf(core: Hop, underlay: Underlay): ChainMode? {
        if (underlay !in underlaysFor(core)) return null
        val hops = underlay.hops + core
        return ChainMode.entries.firstOrNull { it.hops == hops }
    }

    /**
     * What [mode]'s entry rides on. Total over [ChainMode] (pinned by the
     * round-trip test); [Underlay.DIRECT] is only the defensive answer for a
     * mode added later without a matching underlay.
     */
    fun underlayOf(mode: ChainMode): Underlay {
        val below = mode.hops.dropLast(1)
        return Underlay.entries.firstOrNull { it.hops == below } ?: Underlay.DIRECT
    }

    /**
     * The mode a tap on [core]'s pill lands on, starting from [current].
     *
     * Tapping the core that is already the entry changes nothing. Otherwise the
     * core keeps the underlay it was last used with ([remembered]) when that
     * pairing exists AND [allowed] accepts it (its cores are in this build, an
     * Xray config is saved...), and falls back to Direct when it does not.
     */
    fun switchTo(
        current: ChainMode,
        core: Hop,
        remembered: Underlay?,
        allowed: (ChainMode) -> Boolean,
    ): ChainMode {
        if (current.entryHop == core) return current
        val kept = remembered?.let { modeOf(core, it) }?.takeIf(allowed)
        return kept ?: modeOf(core, Underlay.DIRECT) ?: current
    }
}
