package studio.cluvex.aether.vpn.session

import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Protocol

/**
 * Turns a profile into the ordered ladder of concrete attempts a session walks.
 *
 * Pure: no service, no natives, no coroutines - which is exactly why it can be
 * unit-tested, unlike the version that lived inside the service. AUTO's ladder
 * is built by [studio.cluvex.aether.core.SmartAuto] (it needs a live DPI
 * fingerprint of the network); every hand-picked protocol is planned here.
 */
internal object ConnectionPlanner {

    /**
     * Plan for a protocol the user picked by hand (MASQUE, WireGuard or Gool).
     *
     * MASQUE may retry over HTTP/2 with fragmentation and ECH on the full
     * budget when its first, capped attempt fails. The selected obfuscation
     * profile is preserved on EVERY attempt: OFF must never become FIREWALL
     * just because an endpoint did not answer. A protocol with no distinct
     * fallback gets one full-budget attempt.
     */
    fun manualProtocol(profile: ConnectionProfile): List<AutoCandidate> {
        val fullBudget = profile.connectTimeoutMs()
        val masque = profile.protocol == Protocol.MASQUE
        val hardened = profile.copy(
            masqueHttp2 = profile.masqueHttp2 || masque,
            fragment = profile.fragment || masque,
            ech = profile.ech || masque,
        )

        // Nothing left to harden: a second, identical pass would only double
        // the time the user waits for the very same failure.
        if (hardened == profile) {
            return listOf(
                AutoCandidate(profile, fullBudget, "${profile.protocol.name} · as configured"),
            )
        }

        return listOf(
            AutoCandidate(
                profile,
                fullBudget.coerceAtMost(VpnTunables.FIRST_PASS_MAX_MS),
                "${profile.protocol.name} · as configured",
            ),
            AutoCandidate(
                hardened,
                fullBudget,
                "${profile.protocol.name} · noize=${hardened.noize.name.lowercase()}" +
                    (if (masque) " · h2 · fragment · ech" else "") + " (anti-DPI pass)",
            ),
        )
    }

    /**
     * ONE attempt, for a chain that has no Aether hop.
     *
     * There is deliberately no ladder here. Every rung of the protocol ladder
     * exists to vary something about the Aether engine's transport or endpoint
     * choice, and a Psiphon-or-Tor-only session has neither: those cores do
     * their own transport selection and their own retrying internally, over
     * budgets measured in minutes. Retrying them from the outside would just
     * restart that work from scratch and double the wait.
     */
    fun chainOnly(profile: ConnectionProfile): List<AutoCandidate> = listOf(
        AutoCandidate(
            profile,
            VpnTunables.chainBudgetMs(profile.chain),
            profile.chain.pathLabel(),
        ),
    )
}
