package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

/**
 * fix/masque-scan: what the app sends a MASQUE engine, and how long it waits
 * for it. Both were wrong in ways that looked like "MASQUE does not scan" from
 * the outside. See docs/MASQUE_SCAN.md.
 *
 * fix/ech-from-core: the ECH part of that contract. ECH is the engine's own,
 * reaches every protocol when switched on, and every session tells the engine
 * where to look its key up. See docs/ECH.md.
 */
class MasqueScanBudgetTest {

    private val core21 = "2.1.0"

    /** The engine's own MASQUE sweep per mode: prober.rs strategy(), core 2.1.0. */
    private val engineSweepMs = mapOf(
        ScanMode.TURBO to 45_000L,
        ScanMode.PRECISE to 120_000L,
        ScanMode.VERIFIED to 60_000L,
        ScanMode.ULTRA to 180_000L,
    )

    /**
     * What lib.rs run_masque does before that sweep with --quick-reconnect:
     * re-verify up to lastconn::RECENT_CAP = 8 remembered gateways, one at a
     * time, 5 s each.
     */
    private val ringMs = 8 * 5_000L

    private val identityProtocols = listOf(Protocol.MASQUE, Protocol.MIM, Protocol.WIREGUARD, Protocol.GOOL)

    @Test
    fun a_masque_budget_outlasts_the_ring_plus_the_sweep_in_every_mode() {
        for (protocol in listOf(Protocol.MASQUE, Protocol.MIM)) {
            for (mode in ScanMode.entries) {
                val budget = ConnectionProfile(protocol = protocol, scanMode = mode).connectTimeoutMs(core21)
                assertTrue(budget > ringMs + engineSweepMs.getValue(mode), "$protocol/$mode waits $budget ms")
            }
        }
    }

    @Test
    fun the_ring_allowance_only_applies_when_the_engine_is_asked_to_walk_the_ring() {
        val withRing = ConnectionProfile(protocol = Protocol.MASQUE, quickReconnect = true)
        val without = withRing.copy(quickReconnect = false)
        assertEquals(
            ConnectionProfile.MASQUE_QUICK_RECONNECT_ALLOWANCE_MS,
            withRing.connectTimeoutMs(core21) - without.connectTimeoutMs(core21),
        )

        // WireGuard and Gool budgets are not touched by this fix.
        for (protocol in listOf(Protocol.WIREGUARD, Protocol.GOOL)) {
            assertEquals(
                ConnectionProfile(protocol = protocol, quickReconnect = false).connectTimeoutMs(core21),
                ConnectionProfile(protocol = protocol, quickReconnect = true).connectTimeoutMs(core21),
                "$protocol",
            )
        }
    }

    /**
     * REGRESSION (fix/ech-from-core): the switch used to reach no engine that
     * could use it. MASQUE never got --ech, and WireGuard / Gool got it with
     * nothing in the engine reading it.
     */
    @Test
    fun ech_reaches_every_protocol_when_switched_on() {
        for (protocol in identityProtocols) {
            for (http2 in listOf(false, true)) {
                val on = ConnectionProfile(protocol = protocol, masqueHttp2 = http2, ech = true)
                assertTrue(on.sendsEch, "$protocol h2=$http2")
                assertTrue(on.toArgs(core21).windowed(2).contains(listOf("--ech", "auto")), "$protocol h2=$http2")
                assertEquals("auto", on.toEnv()["AETHER_ECH"], "$protocol h2=$http2")
            }
        }
    }

    @Test
    fun ech_stays_off_until_switched_on() {
        for (protocol in Protocol.entries) {
            val off = ConnectionProfile(protocol = protocol)
            assertFalse(off.sendsEch, "$protocol")
            assertFalse("--ech" in off.toArgs(core21), "$protocol")
            assertFalse(off.toEnv().containsKey("AETHER_ECH"), "$protocol")
        }
    }

    /**
     * The WARP API's camouflaged route offers ECH whether or not the switch is
     * on, so every session says where the key lives: ip.gs over 8.8.8.8, which
     * answers from Iran.
     */
    @Test
    fun every_session_tells_the_engine_where_the_ech_key_lives() {
        for (protocol in Protocol.entries) {
            for (ech in listOf(false, true)) {
                val env = ConnectionProfile(protocol = protocol, ech = ech).toEnv()
                assertEquals("ip.gs", env["AETHER_ECH_DOMAIN"], "$protocol ech=$ech")
                assertEquals("udp://8.8.8.8", env["AETHER_ECH_DNS"], "$protocol ech=$ech")
            }
        }
    }

    /** The lookup settings are environment only: an older core ignores them instead of refusing to start. */
    @Test
    fun the_ech_lookup_never_becomes_an_argument() {
        val args = ConnectionProfile(protocol = Protocol.MASQUE, ech = true).toArgs(core21)
        assertFalse(args.any { it.startsWith("--ech-") }, "$args")
        assertFalse(args.any { it.contains("ip.gs") || it.contains("8.8.8.8") }, "$args")
    }
}
