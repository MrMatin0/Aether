package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

class SmartAutoTest {
    private fun fingerprint(dpi: DpiClass, iran: Boolean) = NetworkFingerprint(
        dpiClass = dpi,
        udpOk = dpi == DpiClass.OPEN || dpi == DpiClass.SNI_FILTERING,
        tlsSniOk = dpi == DpiClass.OPEN || dpi == DpiClass.UDP_THROTTLED,
        operatorName = "test",
        iranCellular = iran,
        edgeLatencyMs = mapOf("162.159.192.0/24" to 20L),
    )

    @Test
    fun offStaysOffForEveryNetworkAndEveryCandidateIncludingLastResort() {
        for (dpi in DpiClass.entries) {
            for (iran in listOf(false, true)) {
                val user = ConnectionProfile(protocol = Protocol.AUTO, noize = Noize.OFF)
                val plan = SmartAuto.buildPlan(user, fingerprint(dpi, iran))
                assertTrue(plan.isNotEmpty())
                plan.forEach { candidate ->
                    assertEquals(Noize.OFF, candidate.profile.noize, "$dpi/iran=$iran/${candidate.label}")
                    assertTrue(candidate.label.contains("noize=off"))
                    assertTrue(candidate.profile.toArgs().windowed(2).contains(listOf("--noize", "off")))
                }
                assertEquals(user.scanMode, plan.last().profile.scanMode)
                assertEquals(EndpointMode.AUTO, plan.last().profile.endpointMode)
                assertTrue(plan.last().label.contains("last resort"))
            }
        }
    }

    @Test
    fun enabledObfuscationStillGetsNetworkTuningAndStrongerUserChoicesAreKept() {
        val fp = fingerprint(DpiClass.HOSTILE, true)
        val tuned = SmartAuto.buildPlan(ConnectionProfile(noize = Noize.LIGHT), fp)
        assertTrue(tuned.all { it.profile.noize != Noize.OFF })
        assertTrue(tuned.any { it.profile.noize == Noize.AGGRESSIVE })

        for (dpi in DpiClass.entries) {
            val plan = SmartAuto.buildPlan(
                ConnectionProfile(noize = Noize.AGGRESSIVE),
                fingerprint(dpi, false),
            )
            assertTrue(plan.all { it.profile.noize == Noize.AGGRESSIVE })
        }
    }

    @Test
    fun offDoesNotRemoveTransportFallbackOrOverwriteAPinnedEndpoint() {
        val user = ConnectionProfile(
            noize = Noize.OFF,
            endpointMode = EndpointMode.MANUAL_PEER,
            manualPeer = "162.159.197.1:443",
            scanMode = ScanMode.ULTRA,
        )
        val plan = SmartAuto.buildPlan(user, fingerprint(DpiClass.HOSTILE, true))
        val first = plan.first().profile
        assertEquals(Protocol.MASQUE, first.protocol)
        assertTrue(first.masqueHttp2)
        assertTrue(first.fragment)
        assertTrue(first.ech)
        plan.forEach {
            assertEquals(Noize.OFF, it.profile.noize)
            assertEquals(user.endpointMode, it.profile.endpointMode)
            assertEquals(user.manualPeer, it.profile.manualPeer)
        }
    }
}
