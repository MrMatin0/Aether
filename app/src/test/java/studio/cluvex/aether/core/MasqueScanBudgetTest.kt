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

    @Test
    fun ech_never_reaches_a_masque_transport() {
        for (protocol in listOf(Protocol.MASQUE, Protocol.MIM)) {
            for (http2 in listOf(false, true)) {
                val profile = ConnectionProfile(protocol = protocol, masqueHttp2 = http2, ech = true)
                assertFalse(profile.sendsEch, "$protocol h2=$http2")
                assertFalse("--ech" in profile.toArgs(core21), "$protocol h2=$http2")
            }
        }
    }

    @Test
    fun ech_still_reaches_wireguard_and_gool() {
        for (protocol in listOf(Protocol.WIREGUARD, Protocol.GOOL)) {
            val on = ConnectionProfile(protocol = protocol, ech = true)
            assertTrue(on.sendsEch, "$protocol")
            assertTrue(on.toArgs(core21).windowed(2).contains(listOf("--ech", "auto")), "$protocol")
            assertFalse("--ech" in ConnectionProfile(protocol = protocol).toArgs(core21), "$protocol")
        }
    }
}
