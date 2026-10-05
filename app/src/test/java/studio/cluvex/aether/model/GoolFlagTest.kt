package studio.cluvex.aether.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Core 2.3.0 moved WireGuard-in-WireGuard gool from `--gool` to
 * `--gool-classic` and gave `--gool` to WireGuard inside MASQUE. The app's
 * GOOL must keep meaning what it always meant on every core it can ship with.
 */
class GoolFlagTest {
    private val gool = ConnectionProfile(protocol = Protocol.GOOL)

    @Test
    fun olderCoresKeepTheFlagTheyParse() {
        for (core in listOf("2.0.0", "2.1.0", "2.2.9")) {
            assertEquals(core, "--gool", gool.toArgs(core).first())
        }
    }

    @Test
    fun core230AndLaterGetTheClassicGool() {
        for (core in listOf("2.3.0", "v2.3.0", "2.3.1", "3.0.0")) {
            assertEquals(core, "--gool-classic", gool.toArgs(core).first())
        }
    }

    @Test
    fun anUnknownCoreGetsTheFlagEveryCoreParses() {
        assertEquals("--gool", gool.toArgs("unknown").first())
    }

    @Test
    fun otherProtocolsNeverEmitAGoolFlag() {
        for (protocol in Protocol.entries.filter { it != Protocol.GOOL }) {
            val args = ConnectionProfile(protocol = protocol).toArgs("2.3.0")
            assertFalse(protocol.name, args.any { it.startsWith("--gool") })
        }
    }
}
