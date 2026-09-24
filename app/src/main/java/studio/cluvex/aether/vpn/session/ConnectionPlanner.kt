package studio.cluvex.aether.vpn.session

import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.model.ConnectionProfile

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
     * Plan for a protocol the user picked by hand (MASQUE, MASQUE-in-MASQUE,
     * WireGuard or Gool).
     *
     * Both MASQUE transports may retry over HTTP/2 with a fragmented TLS
     * handshake when their first attempt fails. The selected obfuscation
     * profile is preserved on EVERY attempt: OFF must never become FIREWALL
     * just because an endpoint did not answer. A protocol with no distinct
     * fallback gets one attempt.
     *
     * EVERY ATTEMPT GETS THE FULL BUDGET (fix/masque-scan). The first MASQUE
     * attempt used to be capped at 75 s so the HTTP/2 pass would come sooner.
     * But the engine's own MASQUE sweep runs for up to 120 s on balanced and
     * 180 s on ironclad, and from core 2.1.0 it first re-verifies up to eight
     * remembered gateways at 5 s each. So on exactly the networks where the
     * scan was slow but working, the app killed it mid-sweep and moved on to
     * an HTTP/2 pass that cannot work where only QUIC gets through - which is
     * what "MASQUE does not scan" looked like from the outside. The engine
     * never gives up by itself (an empty sweep is followed by a fresh one), so
     * the budget the app waits is the only thing that ends an attempt, and it
     * has to outlast the scan it is waiting for.
     *
     * The anti-DPI pass no longer asks for ECH: the MASQUE endpoint does not
     * accept it (see [ConnectionProfile.sendsEch]).
     */
    fun manualProtocol(profile: ConnectionProfile): List<AutoCandidate> {
        val fullBudget = profile.connectTimeoutMs()
        val masque = profile.protocol.isMasque
        val hardened = profile.copy(
            masqueHttp2 = profile.masqueHttp2 || masque,
            fragment = profile.fragment || masque,
        )

        // Nothing left to harden: a second, identical pass would only double
        // the time the user waits for the very same failure.
        if (hardened == profile) {
            return listOf(
                AutoCandidate(profile, fullBudget, "${profile.protocol.name} · as configured"),
            )
        }

        return listOf(
            AutoCandidate(profile, fullBudget, "${profile.protocol.name} · as configured"),
            AutoCandidate(
                hardened,
                fullBudget,
                "${profile.protocol.name} · noize=${hardened.noize.name.lowercase()}" +
                    (if (masque) " · h2 · fragment" else "") + " (anti-DPI pass)",
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
