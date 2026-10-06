package studio.cluvex.aether.core.auto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.core.DpiClass
import studio.cluvex.aether.core.NetworkFingerprint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.ScanMode

/**
 * The ladder's merge rules, which used to be a nested function inside a 90-line
 * buildPlan and could only be checked by reading a connect attempt's log.
 */
class StrategyLadderTest {

    private fun fingerprint(dpi: DpiClass) = NetworkFingerprint(
        dpiClass = dpi,
        udpOk = true,
        tlsSniOk = true,
        operatorName = "test",
        iranCellular = false,
        edgeLatencyMs = mapOf(
            "188.114.96.0/24" to 30L,
            "162.159.192.0/24" to 20L,
            "8.6.112.0/24" to -1L,
        ),
    )

    /**
     * REGRESSION: the merge compared Noize.ordinal, and the enum is declared in
     * UI order (OFF, LIGHT, FIREWALL, BALANCED, GFW, AGGRESSIVE), so a FIREWALL
     * user was silently moved DOWN to BALANCED here.
     */
    @Test
    fun `a firewall user is never downgraded to balanced`() {
        val plan = StrategyLadder.build(
            ConnectionProfile(noize = Noize.FIREWALL),
            fingerprint(DpiClass.SNI_FILTERING),
        )
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.none { it.profile.noize == Noize.BALANCED }, plan.joinToString { it.label })
    }

    @Test
    fun `a balanced user is upgraded where the ladder asks for more`() {
        val plan = StrategyLadder.build(
            ConnectionProfile(noize = Noize.BALANCED),
            fingerprint(DpiClass.SNI_FILTERING),
        )
        assertTrue(plan.any { it.profile.noize == Noize.FIREWALL })
    }

    @Test
    fun `off stays off on every rung including the last resort`() {
        for (dpi in DpiClass.entries) {
            val plan = StrategyLadder.build(ConnectionProfile(noize = Noize.OFF), fingerprint(dpi))
            assertTrue(plan.all { it.profile.noize == Noize.OFF }, "$dpi")
            assertTrue(plan.last().label.contains("last resort"))
        }
    }

    @Test
    fun `candidates scan only the ranges that answered, fastest first`() {
        val plan = StrategyLadder.build(ConnectionProfile(), fingerprint(DpiClass.OPEN))
        val first = plan.first().profile
        assertEquals(EndpointMode.MANUAL_RANGE, first.endpointMode)
        assertEquals("162.159.192.0/24, 188.114.96.0/24", first.manualRange)
        assertFalse(first.manualRange.contains("8.6.112.0/24"))
        assertEquals(ScanMode.TURBO, first.scanMode)
    }

    @Test
    fun `the last resort restores the user's own scan mode and full ranges`() {
        val user = ConnectionProfile(scanMode = ScanMode.ULTRA)
        val last = StrategyLadder.build(user, fingerprint(DpiClass.HOSTILE)).last()
        assertEquals(ScanMode.ULTRA, last.profile.scanMode)
        assertEquals(EndpointMode.AUTO, last.profile.endpointMode)
        assertTrue(last.label.contains("full built-in ranges"))
    }

    /** A hand-pinned endpoint is never overwritten, and never mislabelled. */
    @Test
    fun `a pinned peer survives the whole ladder`() {
        val user = ConnectionProfile(
            endpointMode = EndpointMode.MANUAL_PEER,
            manualPeer = "162.159.197.1:443",
        )
        val plan = StrategyLadder.build(user, fingerprint(DpiClass.HOSTILE))
        plan.forEach {
            assertEquals(EndpointMode.MANUAL_PEER, it.profile.endpointMode)
            assertEquals(user.manualPeer, it.profile.manualPeer)
            assertFalse(it.label.contains("full built-in ranges"))
        }
    }

    /**
     * fix/masque-scan: three MASQUE rungs asked for ECH, which the engine then
     * fetched over DNS before scanning and put on an HTTP/3 tunnel handshake
     * its scan had never tested - and the WARP MASQUE endpoint does not accept
     * it. A user who switched ECH on still does not get it on MASQUE.
     */
    @Test
    fun `no masque rung asks the engine for ech`() {
        for (dpi in DpiClass.entries) {
            val plan = StrategyLadder.build(ConnectionProfile(ech = true), fingerprint(dpi))
            plan.filter { it.profile.protocol.isMasque }.forEach {
                assertFalse("--ech" in it.profile.toArgs(), "$dpi: ${it.label}")
                assertFalse(it.label.contains("· ech"), "$dpi: ${it.label}")
            }
        }
    }

    /**
     * Every rung scans on turbo (45 s in the engine). perf/fast-connect capped
     * the engine's remembered-gateway re-check at lastconn::VERIFY_CAP = 3
     * gateways (5 s each), so a rung must outlast 45 s + 15 s - and no more.
     */
    @Test
    fun `masque turbo rungs outlast the remembered gateway ring plus the sweep`() {
        for (dpi in DpiClass.entries) {
            val rungs = StrategyLadder.build(ConnectionProfile(), fingerprint(dpi))
                .filter { it.profile.protocol.isMasque && it.profile.scanMode == ScanMode.TURBO }
            assertTrue(rungs.isNotEmpty(), "$dpi")
            rungs.forEach { assertTrue(it.timeoutMs > 45_000L + 3 * 5_000L, "$dpi: ${it.label}") }
        }
    }

    /**
     * perf/fast-connect: no single rung - the last resort included - may wait
     * longer than the whole session is allowed to take, and the turbo rungs
     * together must leave room for more than one of them.
     */
    @Test
    fun `every rung fits inside the session budget`() {
        for (dpi in DpiClass.entries) {
            for (mode in ScanMode.entries) {
                val plan = StrategyLadder.build(ConnectionProfile(scanMode = mode), fingerprint(dpi))
                plan.forEach {
                    assertTrue(it.timeoutMs < StrategyLadder.SESSION_BUDGET_MS, "$dpi/$mode: ${it.label}")
                }
                val turbo = plan.filter { it.profile.scanMode == ScanMode.TURBO }
                turbo.forEach {
                    assertTrue(it.timeoutMs * 2 <= StrategyLadder.SESSION_BUDGET_MS, "$dpi/$mode: ${it.label}")
                }
            }
        }
    }
}
