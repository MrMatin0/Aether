package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hop order is the whole feature.
 *
 * Get it backwards and "Tor over Aether" silently becomes "Aether over Tor",
 * which cannot be built at all (the engine has no upstream-proxy option) - and
 * the failure would show up as a connect timeout three minutes later, not as a
 * wrong answer. So it is pinned here, on the JVM, where it costs nothing.
 */
class ChainModeTest {

    @Test
    fun `aether is always the internet-facing hop`() {
        ChainMode.entries.filter { it.usesAether }.forEach { mode ->
            assertEquals(
                Hop.AETHER,
                mode.hops.first(),
                "${mode.name} must dial out through Aether, not into it",
            )
        }
    }

    @Test
    fun `proteus is always the internet-facing hop`() {
        ChainMode.entries.filter { it.usesProteus }.forEach { mode ->
            assertEquals(
                Hop.PROTEUS,
                mode.hops.first(),
                "${mode.name} must dial out through Proteus, not into it",
            )
            assertNull(mode.upstreamOf(Hop.PROTEUS))
            // Both have no upstream option, so they can never share a chain.
            assertFalse(mode.usesAether, "${mode.name} stacks two internet-facing cores")
        }
    }

    @Test
    fun `tor is always the entry hop when it is used`() {
        ChainMode.entries.filter { it.usesTor }.forEach { mode ->
            assertEquals(Hop.TOR, mode.entryHop, "${mode.name} must enter through Tor")
        }
    }

    @Test
    fun `every mode has a unique hop set and no duplicate hops`() {
        val sets = ChainMode.entries.map { it.hops.toSet() }
        assertEquals(sets.size, sets.distinct().size, "two modes describe the same chain")
        ChainMode.entries.forEach { mode ->
            assertEquals(
                mode.hops.size,
                mode.hops.distinct().size,
                "${mode.name} runs the same core twice",
            )
        }
    }

    @Test
    fun `upstream of the full chain walks device to internet`() {
        val mode = ChainMode.TOR_OVER_PSIPHON_OVER_AETHER
        assertEquals(Hop.PSIPHON, mode.upstreamOf(Hop.TOR))
        assertEquals(Hop.AETHER, mode.upstreamOf(Hop.PSIPHON))
        // The internet-facing hop has no upstream: it dials out directly.
        assertNull(mode.upstreamOf(Hop.AETHER))
    }

    @Test
    fun `a single-core mode has no upstream at all`() {
        assertNull(ChainMode.PSIPHON.upstreamOf(Hop.PSIPHON))
        assertFalse(ChainMode.PSIPHON.isChained)
        assertTrue(ChainMode.TOR_OVER_PSIPHON.isChained)
        assertFalse(ChainMode.PROTEUS.isChained)
        assertTrue(ChainMode.TOR_OVER_PROTEUS.isChained)
    }

    @Test
    fun `path label reads from the device outwards`() {
        assertEquals(
            "Tor \u2192 Psiphon \u2192 Aether \u2192 internet",
            ChainMode.TOR_OVER_PSIPHON_OVER_AETHER.pathLabel(),
        )
        assertEquals("Aether \u2192 internet", ChainMode.AETHER.pathLabel())
        assertEquals("Proteus \u2192 internet", ChainMode.PROTEUS.pathLabel())
        assertEquals("Tor \u2192 Proteus \u2192 internet", ChainMode.TOR_OVER_PROTEUS.pathLabel())
        assertEquals(Hop.PROTEUS, ChainMode.TOR_OVER_PROTEUS.upstreamOf(Hop.TOR))
    }

    @Test
    fun `stored names round trip`() {
        ChainMode.entries.forEach { mode ->
            assertEquals(mode, ChainMode.fromStored(mode.name))
        }
    }

    @Test
    fun `hand written aliases are understood`() {
        assertEquals(ChainMode.PSIPHON_OVER_AETHER, ChainMode.fromStored("aether+psiphon"))
        assertEquals(ChainMode.TOR_OVER_AETHER, ChainMode.fromStored("tor-aether"))
        assertEquals(ChainMode.TOR_OVER_PSIPHON, ChainMode.fromStored("psiphon_tor"))
        assertEquals(ChainMode.TOR_OVER_PSIPHON_OVER_AETHER, ChainMode.fromStored("ALL"))
        assertEquals(ChainMode.AETHER, ChainMode.fromStored("off"))
        assertEquals(ChainMode.PROTEUS, ChainMode.fromStored("proteus"))
        assertEquals(ChainMode.TOR_OVER_PROTEUS, ChainMode.fromStored("proteus+tor"))
        assertEquals(ChainMode.TOR_OVER_PROTEUS, ChainMode.fromStored("tor-proteus"))
    }

    @Test
    fun `nonsense does not silently become a chain`() {
        assertNull(ChainMode.fromStored(null))
        assertNull(ChainMode.fromStored(""))
        assertNull(ChainMode.fromStored("   "))
        assertNull(ChainMode.fromStored("wireguard"))
    }
}
