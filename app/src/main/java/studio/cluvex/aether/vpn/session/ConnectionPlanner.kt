package studio.cluvex.aether.vpn.session

import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol

/**
 * Turns a profile into the ordered ladder of concrete attempts a session walks.
 *
 * Pure: no service, no natives, no coroutines — which is exactly why it can be
 * unit-tested, unlike the version that lived inside the service. AUTO's ladder
 * is built by [studio.cluvex.aether.core.SmartAuto] (it needs a live DPI
 * fingerprint of the network); every hand-picked protocol is planned here.
 */
internal object ConnectionPlanner {

    /**
     * Two-pass plan for a protocol the user picked by hand (MASQUE, WireGuard
     * or Gool).
     *
     * 1.2.2 "MASQUE hangs forever" FIX: a hand-picked protocol used to get ONE
     * attempt with the full scan budget of the selected scan mode — with no
     * second chance. On a network where QUIC/UDP is throttled that means the
     * user stares at "Connecting" for minutes and then just fails, while Smart
     * mode (which walks a ladder of shorter, hardened attempts) connects in
     * seconds. So the chosen protocol now gets:
     *   1. a first pass exactly as configured, on a capped budget, and
     *   2. if that fails, the SAME protocol again with anti-DPI hardening
     *      (obfuscation on, plus HTTP/2 + TLS fragmentation + ECH for MASQUE)
     *      on the full budget.
     * The protocol the user chose is never swapped for another one.
     */
    fun manualProtocol(profile: ConnectionProfile): List<AutoCandidate> {
        val fullBudget = profile.connectTimeoutMs()
        val hardenedNoize = if (profile.noize == Noize.OFF) Noize.FIREWALL else profile.noize
        val masque = profile.protocol == Protocol.MASQUE
        val hardened = profile.copy(
            noize = hardenedNoize,
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
                "${profile.protocol.name} · noize=${hardenedNoize.name.lowercase()}" +
                    (if (masque) " · h2 · fragment · ech" else "") + " (anti-DPI pass)",
            ),
        )
    }
}
