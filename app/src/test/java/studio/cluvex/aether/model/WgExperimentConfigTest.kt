package studio.cluvex.aether.model

import org.junit.Test
import org.junit.Assert.assertEquals
import studio.cluvex.aether.data.WgExperimentConfig

class WgExperimentConfigTest {
    @Test
    fun defaultsAreExplicitlyOff() {
        val env = WgExperimentConfig().toEnv(Protocol.WIREGUARD)
        assertEquals("0", env["AETHER_WG_PORT_HOP"])
        assertEquals("0", env["AETHER_WG_DATA_PADDING"])
    }

    @Test
    fun switchesAreIndependent() {
        for (hop in listOf(false, true)) for (pad in listOf(false, true)) {
            val env = WgExperimentConfig(hop, pad).toEnv(Protocol.WIREGUARD)
            assertEquals(if (hop) "1" else "0", env["AETHER_WG_PORT_HOP"])
            assertEquals(if (pad) "1" else "0", env["AETHER_WG_DATA_PADDING"])
        }
    }

    @Test
    fun otherTransportsCannotActivateExperiments() {
        for (protocol in Protocol.entries.filter { it != Protocol.WIREGUARD }) {
            val env = WgExperimentConfig(true, true).toEnv(protocol)
            assertEquals("0", env["AETHER_WG_PORT_HOP"])
            assertEquals("0", env["AETHER_WG_DATA_PADDING"])
        }
    }
}
