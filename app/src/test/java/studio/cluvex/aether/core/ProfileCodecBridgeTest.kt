package studio.cluvex.aether.core

import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.TorBridgeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codec is the ONLY channel between the UI and the VpnService, so a field it
 * forgets is a field the user configures, sees persisted, and the cores never
 * hear about. That has shipped in this app once already (the 1.2.3 block), which
 * is why every new field gets a round-trip test.
 */
class ProfileCodecBridgeTest {

    private val obfs4 = "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=x iat-mode=0"

    private val snowflake =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478 utls-imitate=hellorandomizedalpn"

    @Test
    fun `bridge settings survive a round trip`() {
        val profile = ConnectionProfile(
            torBridgeMode = TorBridgeMode.REQUESTED,
            torBridgeTransport = BridgeTransport.SNOWFLAKE,
            torBridgeLines = "$obfs4\n$snowflake",
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(profile))
        assertEquals(profile.torBridgeMode, decoded.torBridgeMode)
        assertEquals(profile.torBridgeTransport, decoded.torBridgeTransport)
        assertEquals(profile.torBridgeLines, decoded.torBridgeLines)
    }

    @Test
    fun `a snowflake line keeps its comma separated parameters`() {
        // THE reason bridge lines do not use the free-text fold: that one joins
        // lines with a comma, and this line is full of them. The transport a user
        // reaches for when obfs4 is blocked would be the one that arrived broken.
        val decoded = ProfileCodec.decode(
            ProfileCodec.encode(ConnectionProfile(torBridgeLines = snowflake)),
        )
        assertEquals(snowflake, decoded.torBridgeLines)
        assertEquals(1, decoded.torBridgeLines.lines().size)
        assertTrue(decoded.torBridgeLines.contains("fronts=app.datapacket.com,www.datapacket.com"))
    }

    @Test
    fun `two bridges stay two bridges`() {
        val decoded = ProfileCodec.decode(
            ProfileCodec.encode(ConnectionProfile(torBridgeLines = "$obfs4\n$snowflake")),
        )
        assertEquals(2, decoded.torBridgeLines.lines().size)
        assertEquals(2, BridgeLine.parseAll(decoded.torBridgeLines).size)
    }

    @Test
    fun `an older payload with no bridge keys picks up the current default`() {
        // A payload without these keys can only come from a build that predates
        // them, and "off" is no longer what this app defaults to: bridges are on
        // with the built-in list, and the ladder resolves the lines at connect
        // time (see BridgePlan). What must NOT happen is inventing lines here -
        // the codec transports a profile, it does not decide one.
        val legacy = "protocol=MASQUE\nscan=PRECISE\nchain=TOR"
        val decoded = ProfileCodec.decode(legacy)
        assertEquals(TorBridgeMode.BUILTIN, decoded.torBridgeMode)
        assertEquals(BridgeTransport.OBFS4, decoded.torBridgeTransport)
        assertEquals("", decoded.torBridgeLines)
        assertTrue(decoded.activeBridgeLines().isEmpty())
        assertTrue(decoded.usesBridges)
    }

    @Test
    fun `a hand written config can spell the mode and the transport its own way`() {
        val hand = "protocol=AUTO\ntorBridgeMode=manual\ntorBridgeTransport=meek-azure\ntorBridges=$obfs4"
        val decoded = ProfileCodec.decode(hand)
        assertEquals(TorBridgeMode.CUSTOM, decoded.torBridgeMode)
        assertEquals(BridgeTransport.MEEK, decoded.torBridgeTransport)
        assertEquals(obfs4, decoded.torBridgeLines)
    }

    @Test
    fun `bridges off means the cores are given nothing, list or no list`() {
        val profile = ConnectionProfile(
            torBridgeMode = TorBridgeMode.OFF,
            torBridgeLines = obfs4,
        )
        assertTrue(profile.activeBridgeLines().isEmpty())
        // The list is KEPT, so turning bridges back on does not mean fetching
        // them again.
        assertEquals(obfs4, ProfileCodec.decode(ProfileCodec.encode(profile)).torBridgeLines)
    }
}
