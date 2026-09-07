package studio.cluvex.aether.core

import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codec is the ONLY channel between the UI and the VpnService, so a field it
 * forgets is a field the user configures, sees persisted, and the cores never
 * hear about. That bug has already shipped once in this app (the 1.2.3 block),
 * so the chain fields get a round-trip test.
 */
class ProfileCodecChainTest {

    @Test
    fun `chain settings survive a round trip`() {
        val profile = ConnectionProfile(
            chain = ChainMode.TOR_OVER_PSIPHON_OVER_AETHER,
            psiphonRegion = "NL",
            torExitCountry = "de",
            torStrictNodes = true,
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(profile))
        assertEquals(profile.chain, decoded.chain)
        assertEquals(profile.psiphonRegion, decoded.psiphonRegion)
        assertEquals(profile.torExitCountry, decoded.torExitCountry)
        assertEquals(profile.torStrictNodes, decoded.torStrictNodes)
    }

    @Test
    fun `a pasted multi-line psiphon config stays valid json`() {
        // The payload is line-framed and free-text fields fold newlines into
        // COMMAS. Doing that to JSON produces a document no parser accepts, so
        // this field folds to spaces instead - which is what makes the test
        // worth having.
        val config = """
            {
              "PropagationChannelId": "ABCD1234",
              "SponsorId": "EF567890"
            }
        """.trimIndent()
        val decoded = ProfileCodec.decode(
            ProfileCodec.encode(ConnectionProfile(psiphonConfig = config)),
        )

        // Single line (the framing survived) and no comma was spliced in
        // between the braces and the first key.
        assertEquals(1, decoded.psiphonConfig.lines().size)
        assertFalseContains(decoded.psiphonConfig, "{,")
        assertTrue(decoded.psiphonConfig.contains("\"PropagationChannelId\": \"ABCD1234\""))
        assertTrue(decoded.psiphonConfig.contains("\"SponsorId\": \"EF567890\""))
        assertTrue(decoded.psiphonConfig.trim().startsWith("{"))
        assertTrue(decoded.psiphonConfig.trim().endsWith("}"))
    }

    @Test
    fun `an older payload with no chain keys stays on aether`() {
        val legacy = "protocol=MASQUE\nscan=PRECISE\nip=V4"
        val decoded = ProfileCodec.decode(legacy)
        assertEquals(ChainMode.AETHER, decoded.chain)
        assertEquals("", decoded.psiphonConfig)
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertTrue(!haystack.contains(needle), "\"$needle\" must not appear in: $haystack")
    }
}
