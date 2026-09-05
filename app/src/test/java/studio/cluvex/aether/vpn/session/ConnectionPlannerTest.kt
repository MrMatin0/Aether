package studio.cluvex.aether.vpn.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol

/**
 * These tests are the point of the refactor.
 *
 * The two-pass ladder for a hand-picked protocol was a private method on a
 * `VpnService`, so the only way to check that MASQUE really gets a capped first
 * attempt followed by a hardened one was to install the app on a phone, join a
 * throttled network and watch the diagnostics panel. It is a pure function over
 * a profile; now it is tested like one.
 */
class ConnectionPlannerTest {

    /** The 1.2.2 "MASQUE hangs forever" fix, pinned. */
    @Test
    fun `masque gets a capped first pass and a hardened second one`() {
        val profile = ConnectionProfile(protocol = Protocol.MASQUE, noize = Noize.OFF)
        val plan = ConnectionPlanner.manualProtocol(profile)

        assertEquals(2, plan.size)
        assertTrue(
            plan[0].timeoutMs <= VpnTunables.FIRST_PASS_MAX_MS,
            "the first pass must not eat the whole scan budget",
        )
        assertEquals(profile, plan[0].profile, "the first pass runs exactly as configured")
        assertEquals(profile.connectTimeoutMs(), plan[1].timeoutMs)

        val hardened = plan[1].profile
        assertEquals(Noize.FIREWALL, hardened.noize)
        assertTrue(hardened.masqueHttp2, "the anti-DPI pass turns HTTP/2 on for MASQUE")
        assertTrue(hardened.fragment, "the anti-DPI pass fragments the TLS handshake")
        assertTrue(hardened.ech, "the anti-DPI pass asks for ECH")
    }

    /** The protocol the user chose is never swapped for another one. */
    @Test
    fun `no rung of the ladder ever changes the protocol`() {
        for (protocol in Protocol.entries.filter { it != Protocol.AUTO }) {
            val plan = ConnectionPlanner.manualProtocol(ConnectionProfile(protocol = protocol))
            assertTrue(plan.isNotEmpty(), "${protocol.name} must get at least one attempt")
            plan.forEach { assertEquals(protocol, it.profile.protocol) }
        }
    }

    /**
     * A second, identical pass would only double the time the user waits for the
     * very same failure, so a profile with nothing left to harden gets one rung
     * on the FULL budget rather than a capped one it cannot finish in.
     */
    @Test
    fun `a profile with nothing left to harden gets a single full budget pass`() {
        val protocol = Protocol.entries.first { it != Protocol.AUTO && it != Protocol.MASQUE }
        val profile = ConnectionProfile(
            protocol = protocol,
            noize = Noize.FIREWALL,
            masqueHttp2 = true,
            fragment = true,
            ech = true,
        )
        val plan = ConnectionPlanner.manualProtocol(profile)

        assertEquals(1, plan.size)
        assertEquals(profile, plan[0].profile)
        assertEquals(profile.connectTimeoutMs(), plan[0].timeoutMs)
    }

    /** Every rung is announced in the diagnostics panel, so none may be nameless. */
    @Test
    fun `every candidate is labelled with the protocol it runs`() {
        val plan = ConnectionPlanner.manualProtocol(
            ConnectionProfile(protocol = Protocol.MASQUE, noize = Noize.OFF),
        )
        plan.forEach { candidate ->
            assertTrue(candidate.label.contains(Protocol.MASQUE.name), candidate.label)
            assertTrue(candidate.timeoutMs > 0, candidate.label)
        }
        assertEquals(plan.size, plan.map { it.label }.toSet().size, "labels must be distinguishable")
    }
}
