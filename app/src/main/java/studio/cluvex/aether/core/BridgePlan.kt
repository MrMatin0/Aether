package studio.cluvex.aether.core

import android.content.Context
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ConnectionProfile

/**
 * ONE try at getting tor into the network: a set of bridge lines, or nothing.
 *
 * [lines] are already canonical and already filtered to what this build can
 * launch, because the only thing that ever builds one of these is [BridgePlan].
 * An attempt with no lines is the honest way to say "connect to the public
 * relays" - it is what a profile with bridges off produces, and it is never
 * appended to a ladder as a consolation prize: on a network that blocks the Tor
 * network itself it cannot work, and it would cost four minutes to prove it.
 */
data class BridgeAttempt(
    /** Transports these lines need, in first-seen order. Empty for plain bridges. */
    val transports: List<BridgeTransport>,
    /** Canonical bridge lines, ready for the torrc. */
    val lines: List<String>,
) {
    val usesBridges: Boolean get() = lines.isNotEmpty()

    /** What the UI hop row and the log call this attempt. */
    val label: String
        get() = when {
            lines.isEmpty() -> "no bridges"
            transports.isEmpty() -> "plain bridges"
            else -> transports.joinToString("+") { it.torName.ifEmpty { "vanilla" } }
        }
}

/**
 * The order tor tries bridges in, and the whole of the fallback story.
 *
 * ### Why a ladder and not one attempt
 *
 * A blocked transport is not a blocked network. obfs4 is the transport every
 * censor spends its effort on precisely because it is the one every client
 * reaches for first, and the DPI boxes that recognise it are usually blind to a
 * snowflake WebRTC flow or a webtunnel session that looks like HTTPS to an
 * ordinary website. Before this existed, the app configured whatever the profile
 * carried, tor sat at 5% for four minutes, and the user was told Tor was blocked
 * here - when the next transport down the list would have worked.
 *
 * So the question "which bridges" has an ordered answer instead of a single one:
 *
 *  1. what the profile actually carries (pasted, personal, or a built-in
 *     selection the user made). The user's own choice is always tried FIRST -
 *     a personal bridge from moat is scarce and was requested for a reason;
 *  2. the built-in list for the profile's transport, then for every other
 *     transport in [PRIORITY] order, skipping any this build cannot launch.
 *
 * ### Why it stops at [MAX_ATTEMPTS]
 *
 * Every rung costs a fresh tor process and its own bootstrap budget. Three is
 * where the arithmetic stops being a fallback and starts being a user staring at
 * a spinner: two obfuscated transports plus the profile's own bridges is already
 * an honest answer to "is Tor reachable from this network at all".
 *
 * PURE on purpose - [of] takes the catalogue and the payload as arguments rather
 * than reading a Context - because "which transport does a censored network get
 * next" is exactly the kind of thing that must not need a device to verify.
 */
object BridgePlan {

    /** How many rungs a ladder may have, including the profile's own bridges. */
    const val MAX_ATTEMPTS = 3

    /**
     * Fallback order, cheapest and most likely to work first.
     *
     * obfs4 leads because it is what the Tor Project hands out first and what
     * most bridges actually run. snowflake is second because it needs no bridge
     * address at all (the broker finds a volunteer proxy), which makes it the
     * transport most likely to survive a list-based block. webtunnel is third: it
     * looks like ordinary HTTPS, but a bridge for it has to be pasted, so this
     * rung only exists for a build whose asset carries some. meek_lite is fourth
     * and slow. [BridgeTransport.VANILLA] is last and, having no public list, in
     * practice only appears when the user pasted one.
     */
    val PRIORITY: List<BridgeTransport> = listOf(
        BridgeTransport.OBFS4,
        BridgeTransport.SNOWFLAKE,
        BridgeTransport.WEBTUNNEL,
        BridgeTransport.MEEK,
        BridgeTransport.VANILLA,
    )

    /**
     * The ladder for [profile], longest-odds last.
     *
     * Never empty: a profile with bridges off - or one whose every line was
     * dropped as unrunnable - yields a single attempt with no bridges, which is
     * the behaviour of every build before bridges existed.
     *
     * @param catalog built-in bridges per transport, i.e. what
     *   [BridgeCatalog.bundled] or [BridgeCatalog.current] returned.
     * @param supportedTorNames transport tokens this build has a PT binary for
     *   ([PluggableTransports.Snapshot.supportedTorNames]). A line whose
     *   transport is absent from this set is DROPPED rather than tried: tor
     *   treats a `Bridge obfs4 ...` line with no matching `ClientTransportPlugin`
     *   as a fatal configuration error and exits during startup, so one
     *   unrunnable rung would cost the whole session rather than itself.
     */
    fun of(
        profile: ConnectionProfile,
        catalog: Map<BridgeTransport, List<String>>,
        supportedTorNames: Set<String>,
    ): List<BridgeAttempt> {
        if (!profile.torBridgeMode.isOn) return listOf(DIRECT)

        val attempts = mutableListOf<BridgeAttempt>()
        val seen = mutableSetOf<String>()

        fun add(lines: List<String>) {
            if (attempts.size >= MAX_ATTEMPTS) return
            val usable = BridgeLine.usable(BridgeLine.parseAll(lines), supportedTorNames)
            if (usable.isEmpty()) return
            val canonical = usable.map { it.line }
            // Two rungs with the same bridges are one rung and one wasted
            // bootstrap budget. The built-in obfs4 list IS what the profile
            // carries for most users, so this is the common case, not the edge.
            if (!seen.add(canonical.sorted().joinToString("\n"))) return
            attempts += BridgeAttempt(
                transports = usable.mapNotNull { it.transport }.distinct(),
                lines = canonical,
            )
        }

        // The user's own bridges, whatever their provenance.
        add(profile.activeBridgeLines())

        // Then the built-ins: the transport the page is set to first (that is the
        // one the user expressed a preference for), then the rest of the ladder.
        for (transport in (listOf(profile.torBridgeTransport) + PRIORITY).distinct()) {
            if (attempts.size >= MAX_ATTEMPTS) break
            if (transport.needsPlugin && transport.torName !in supportedTorNames) continue
            add(catalog[transport].orEmpty())
        }

        return attempts.ifEmpty { listOf(DIRECT) }
    }

    /**
     * What a profile that has never chosen bridges should START with: the best
     * transport this build can launch, and the built-in lines for it.
     *
     * Used exactly once per install, by [studio.cluvex.aether.data.ProfileStore],
     * so the Bridges page opens on a real selection instead of an empty list the
     * user has to populate before the feature does anything. Runs [of] rather
     * than repeating its ordering, so "which transport first" has one answer in
     * the whole app.
     *
     * @return the transport to select, and its lines as the profile field wants
     *   them (newline separated). Blank lines when this build ships no catalogue
     *   and no transports, which is a valid build.
     */
    fun initialSelection(
        context: Context,
        preferred: BridgeTransport,
    ): Pair<BridgeTransport, String> {
        val plan = of(
            profile = ConnectionProfile(torBridgeTransport = preferred),
            catalog = BridgeCatalog.bundled(context),
            supportedTorNames = PluggableTransports.of(context).supportedTorNames,
        )
        val first = plan.firstOrNull { it.usesBridges } ?: return preferred to ""
        return (first.transports.firstOrNull() ?: preferred) to first.lines.joinToString("\n")
    }

    /** tor, straight at the public relays. */
    private val DIRECT = BridgeAttempt(transports = emptyList(), lines = emptyList())
}
