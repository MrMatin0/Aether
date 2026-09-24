package studio.cluvex.aether.vpn.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol

/**
 * These tests are the point of the refactor.
 *
 * The two-pass ladder for a hand-picked protocol was a private method on a
 * `VpnService`, so the only way to check that MASQUE really gets a full first
 * attempt followed by a hardened one was to install the app on a phone, join a
 * throttled network and watch the diagnostics panel. It is a pure function over
 * a profile; now it is tested like one.
 */
class ConnectionPlannerTest {

    /**
     * REGRESSION (fix/masque-scan): the first pass was capped at 75 s, under
     * the engine's own 120 s balanced MASQUE sweep and the remembered-gateway
     * ring it walks first, so a scan that was slow but working was killed
     * before it could finish. The transport fallback must not override the
     * obfuscation setting either.
     */
    @Test
    fun `masque gets a full first pass and an http2 second one`() {
        val profile = ConnectionProfile(protocol = Protocol.MASQUE, noize = Noize.OFF)
        val plan = ConnectionPlanner.manualProtocol(profile)

        assertEquals(2, plan.size)
        assertEquals(profile, plan[0].profile, "the first pass runs exactly as configured")
        assertEquals(profile.connectTimeoutMs(), plan[0].timeoutMs, "and is never cut short")
        // prober.rs balanced sweep (120 s) behind lib.rs's quick-reconnect ring
        // (up to 8 remembered gateways, 5 s each, one at a time).
        assertTrue(plan[0].timeoutMs > 120_000L + 8 * 5_000L, "the first pass outlasts the sweep")
        assertEquals(profile.connectTimeoutMs(), plan[1].timeoutMs)

        val hardened = plan[1].profile
        assertEquals(Noize.OFF, hardened.noize)
        assertTrue(plan[1].label.contains("noize=off"))
        assertTrue(hardened.toArgs().windowed(2).contains(listOf("--noize", "off")))
        assertTrue(hardened.masqueHttp2, "the anti-DPI pass turns HTTP/2 on for MASQUE")
        assertTrue(hardened.fragment, "the anti-DPI pass fragments the TLS handshake")
        assertFalse("--ech" in hardened.toArgs(), "the WARP MASQUE endpoint does not accept ECH")
    }

    /** Not even when the user switched ECH on: see ConnectionProfile.sendsEch. */
    @Test
    fun `no masque attempt ever sends ech`() {
        for (protocol in listOf(Protocol.MASQUE, Protocol.MIM)) {
            for (http2 in listOf(false, true)) {
                val profile = ConnectionProfile(protocol = protocol, masqueHttp2 = http2, ech = true)
                ConnectionPlanner.manualProtocol(profile).forEach {
                    assertFalse("--ech" in it.profile.toArgs(), it.label)
                }
            }
        }
    }

    @Test
    fun `every manual attempt preserves the selected obfuscation profile`() {
        for (protocol in Protocol.entries.filter { it != Protocol.AUTO }) {
            for (noize in Noize.entries) {
                val profile = ConnectionProfile(protocol = protocol, noize = noize)
                ConnectionPlanner.manualProtocol(profile).forEach {
                    assertEquals(noize, it.profile.noize, "$protocol/$noize")
                    assertTrue(it.profile.toArgs().windowed(2).contains(listOf("--noize", noize.name.lowercase())))
                }
            }
        }
    }

    @Test
    fun `wireguard and gool with off get one full budget pass rather than forced noise`() {
        for (protocol in listOf(Protocol.WIREGUARD, Protocol.GOOL)) {
            val profile = ConnectionProfile(protocol = protocol, noize = Noize.OFF)
            val plan = ConnectionPlanner.manualProtocol(profile)
            assertEquals(1, plan.size)
            assertEquals(profile, plan.single().profile)
            assertEquals(profile.connectTimeoutMs(), plan.single().timeoutMs)
        }
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
     * on the FULL budget.
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
