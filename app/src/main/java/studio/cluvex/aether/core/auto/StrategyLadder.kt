package studio.cluvex.aether.core.auto

import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.core.DpiClass
import studio.cluvex.aether.core.NetworkFingerprint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

/**
 * Turns a [NetworkFingerprint] into the ordered list of strategies the
 * VpnService walks, most-likely-to-succeed first.
 *
 * This was a 90-line function inside SmartAuto with a nested candidate builder,
 * four inline ladders and the label formatting all in one scope. The ladders are
 * now a declarative table of [Step]s, and merging a step with the user's own
 * profile is one small function - which is what makes the merge rules (below)
 * reviewable at all.
 */
internal object StrategyLadder {

    /** One rung, before it is merged with the user's profile. */
    private data class Step(
        val protocol: Protocol,
        val noize: Noize,
        val http2: Boolean = false,
        val fragment: Boolean = false,
        val ech: Boolean = false,
    )

    /** How many probe-verified ranges a candidate is narrowed to. */
    private const val MAX_NARROWED_RANGES = 2

    /**
     * Obfuscation levels ordered by STRENGTH.
     *
     * RANKING FIX: the merge below used to compare `Noize.ordinal`, but the enum
     * is declared in the order the UI lists it (OFF, LIGHT, FIREWALL, BALANCED,
     * GFW, AGGRESSIVE), which is NOT strength order. So "keep whichever is
     * stronger" silently DOWNGRADED a user who had chosen FIREWALL to BALANCED
     * on an SNI-filtered network, and never upgraded a BALANCED user to
     * FIREWALL. Ranking belongs here, next to the ladder that needs it, rather
     * than being implied by a declaration order the UI owns.
     */
    private val STRENGTH = mapOf(
        Noize.OFF to 0,
        Noize.LIGHT to 1,
        Noize.BALANCED to 2,
        Noize.FIREWALL to 3,
        Noize.GFW to 4,
        Noize.AGGRESSIVE to 5,
    )

    fun build(user: ConnectionProfile, fp: NetworkFingerprint): List<AutoCandidate> {
        // Prefer the ranges that actually answered, fastest first. Narrowing the
        // scan to live ranges is what makes each attempt FAST; the last resort
        // still covers the engine's full built-in ranges.
        val narrowedRanges = fp.narrowedRanges(MAX_NARROWED_RANGES)
        // NEVER override an endpoint the user pinned manually in Settings.
        val keepUserEndpoint = user.endpointMode != EndpointMode.AUTO
        val ladder = stepsFor(fp.dpiClass).map { candidate(user, it, narrowedRanges, keepUserEndpoint) }
        val lastResort = lastResort(user, ladder.first(), keepUserEndpoint)
        return (ladder + lastResort).distinctBy { it.profile }
    }

    /**
     * A `when` rather than a map, so adding a [DpiClass] is a compile error here
     * instead of a missing-key crash on someone's phone.
     */
    private fun stepsFor(dpiClass: DpiClass): List<Step> = when (dpiClass) {
        DpiClass.OPEN -> listOf(
            Step(Protocol.WIREGUARD, Noize.OFF),
            Step(Protocol.MASQUE, Noize.OFF),
            Step(Protocol.GOOL, Noize.LIGHT),
        )
        DpiClass.SNI_FILTERING -> listOf(
            Step(Protocol.WIREGUARD, Noize.BALANCED),
            Step(Protocol.GOOL, Noize.BALANCED),
            Step(Protocol.MASQUE, Noize.FIREWALL, fragment = true, ech = true),
        )
        DpiClass.UDP_THROTTLED -> listOf(
            Step(Protocol.MASQUE, Noize.LIGHT, http2 = true, fragment = true, ech = true),
            Step(Protocol.GOOL, Noize.AGGRESSIVE),
            Step(Protocol.WIREGUARD, Noize.GFW),
        )
        DpiClass.HOSTILE -> listOf(
            Step(Protocol.MASQUE, Noize.GFW, http2 = true, fragment = true, ech = true),
            Step(Protocol.GOOL, Noize.AGGRESSIVE),
            Step(Protocol.WIREGUARD, Noize.AGGRESSIVE),
        )
    }

    private fun candidate(
        user: ConnectionProfile,
        step: Step,
        narrowedRanges: String,
        keepUserEndpoint: Boolean,
    ): AutoCandidate {
        var profile = user.copy(
            protocol = step.protocol,
            noize = mergeNoize(user.noize, step.noize),
            masqueHttp2 = user.masqueHttp2 || (step.http2 && step.protocol == Protocol.MASQUE),
            fragment = user.fragment || step.fragment,
            ech = user.ech || step.ech,
            // TURBO per attempt: the ladder's speed comes from trying the NEXT
            // strategy quickly, not from one long exhaustive scan.
            scanMode = ScanMode.TURBO,
        )
        if (!keepUserEndpoint && narrowedRanges.isNotEmpty()) {
            profile = profile.copy(
                endpointMode = EndpointMode.MANUAL_RANGE,
                manualRange = narrowedRanges,
            )
        }
        return AutoCandidate(
            profile = profile,
            timeoutMs = profile.connectTimeoutMs(),
            label = label(profile, if (keepUserEndpoint) "" else narrowedRanges),
        )
    }

    /**
     * The top strategy again, but scanning the engine's FULL built-in ranges
     * with the user's own scan mode - it covers the rare case where the
     * probe-narrowed ranges themselves were the problem.
     */
    private fun lastResort(
        user: ConnectionProfile,
        best: AutoCandidate,
        keepUserEndpoint: Boolean,
    ): AutoCandidate {
        var profile = best.profile.copy(scanMode = user.scanMode)
        if (!keepUserEndpoint) {
            profile = profile.copy(endpointMode = EndpointMode.AUTO, manualRange = user.manualRange)
        }
        val label = buildString {
            append(label(profile, ""))
            // Only true when we are the ones choosing the endpoint; a pinned
            // peer or range is left exactly as the user set it.
            if (!keepUserEndpoint) append(" · full built-in ranges")
            append(" (last resort)")
        }
        return AutoCandidate(profile, profile.connectTimeoutMs(), label)
    }

    /** OFF is a veto, not the weakest level to upgrade automatically. */
    private fun mergeNoize(user: Noize, planned: Noize): Noize = when {
        user == Noize.OFF -> Noize.OFF
        strength(user) >= strength(planned) -> user
        else -> planned
    }

    private fun strength(noize: Noize): Int = STRENGTH[noize] ?: noize.ordinal

    private fun label(profile: ConnectionProfile, ranges: String): String = buildString {
        append(profile.protocol.name)
        append(" · noize=").append(profile.noize.name.lowercase())
        if (profile.masqueHttp2) append(" · h2")
        if (profile.fragment) append(" · fragment")
        if (profile.ech) append(" · ech")
        if (ranges.isNotEmpty()) append(" · ranges[").append(ranges).append("]")
        append(" · scan=").append(profile.scanMode.name.lowercase())
    }

    /** Reachable ranges, fastest first, as the engine's comma-separated form. */
    private fun NetworkFingerprint.narrowedRanges(limit: Int): String =
        edgeLatencyMs.filterValues { it >= 0 }
            .entries
            .sortedBy { it.value }
            .take(limit)
            .joinToString(", ") { it.key }
}
