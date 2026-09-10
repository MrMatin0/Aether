package studio.cluvex.aether.core

import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.TorBridgeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The order tor tries bridges in.
 *
 * Two properties here are not preferences, they are the difference between a
 * working Tor hop and a dead one:
 *
 *  - a rung whose transport has no PT binary must NEVER be built, because tor
 *    treats a `Bridge obfs4 ...` line with no `ClientTransportPlugin` as a fatal
 *    configuration error and exits during startup;
 *  - the ladder must never be empty, because [TorCore] takes its first element.
 */
class BridgePlanTest {

    private val obfs4A =
        "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=x iat-mode=0"
    private val obfs4B =
        "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=y iat-mode=1"
    private val snowflake =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://x.invalid"
    private val webtunnel =
        "webtunnel 192.0.2.5:443 4352E58420E68F5E40BF7C74FAEB9D42088F6272 url=https://y.invalid"
    private val meek = "meek_lite 192.0.2.20:80 url=https://z.invalid front=w.invalid"
    private val vanilla = "1.2.3.4:443 4352E58420E68F5E40BF7C74FAEB9D42088F6272"

    /** A build that shipped all three PT binaries. */
    private val everything = setOf("obfs4", "meek_lite", "snowflake", "webtunnel")

    /** A build with lyrebird only, i.e. no snowflake and no webtunnel. */
    private val lyrebirdOnly = setOf("obfs4", "meek_lite")

    private val catalog = mapOf(
        BridgeTransport.OBFS4 to listOf(obfs4A, obfs4B),
        BridgeTransport.SNOWFLAKE to listOf(snowflake),
        BridgeTransport.WEBTUNNEL to listOf(webtunnel),
        BridgeTransport.MEEK to listOf(meek),
    )

    private fun plan(
        profile: ConnectionProfile = ConnectionProfile(),
        supported: Set<String> = everything,
        catalog: Map<BridgeTransport, List<String>> = this.catalog,
    ) = BridgePlan.of(profile, catalog, supported)

    @Test
    fun `bridges off is one attempt with no bridges`() {
        val ladder = plan(ConnectionProfile(torBridgeMode = TorBridgeMode.OFF))
        assertEquals(1, ladder.size)
        assertFalse(ladder.first().usesBridges)
        assertEquals("no bridges", ladder.first().label)
    }

    @Test
    fun `bridges off ignores a list it is still carrying`() {
        // The lines are KEPT so turning bridges back on needs no second fetch.
        val ladder = plan(
            ConnectionProfile(torBridgeMode = TorBridgeMode.OFF, torBridgeLines = obfs4A),
        )
        assertEquals(1, ladder.size)
        assertFalse(ladder.first().usesBridges)
    }

    @Test
    fun `a default profile gets the built-in ladder, obfs4 first`() {
        // THE point of the feature: no lines of its own, and still three rungs.
        val ladder = plan()
        assertEquals(listOf("obfs4", "snowflake", "webtunnel"), ladder.map { it.label })
        assertEquals(listOf(obfs4A, obfs4B), ladder.first().lines)
        assertTrue(ladder.all { it.usesBridges })
    }

    @Test
    fun `the profile's own bridges are always the first rung`() {
        // A personal bridge from moat is scarce and was requested for a reason.
        val ladder = plan(ConnectionProfile(torBridgeLines = vanilla))
        assertEquals(listOf(vanilla), ladder.first().lines)
        assertEquals("vanilla", ladder.first().label)
        assertEquals("obfs4", ladder[1].label)
    }

    @Test
    fun `the chosen transport leads the fallbacks`() {
        val ladder = plan(
            ConnectionProfile(torBridgeTransport = BridgeTransport.SNOWFLAKE),
        )
        assertEquals(listOf("snowflake", "obfs4", "webtunnel"), ladder.map { it.label })
    }

    @Test
    fun `a transport this build cannot launch is never put on a rung`() {
        val ladder = plan(supported = lyrebirdOnly)
        assertEquals(listOf("obfs4", "meek_lite"), ladder.map { it.label })
        assertFalse(ladder.any { rung -> rung.lines.any { it.startsWith("snowflake") } })
        assertFalse(ladder.any { rung -> rung.lines.any { it.startsWith("webtunnel") } })
    }

    @Test
    fun `unrunnable lines are dropped from a rung without dropping the rung`() {
        val ladder = plan(
            profile = ConnectionProfile(torBridgeLines = "$obfs4A\n$snowflake"),
            supported = lyrebirdOnly,
        )
        assertEquals(listOf(obfs4A), ladder.first().lines)
    }

    @Test
    fun `a profile whose every line is unrunnable still gets a ladder`() {
        // Otherwise the session would be configured with nothing at all on a
        // network that needs bridges, purely because of what the user pasted.
        val ladder = plan(
            profile = ConnectionProfile(torBridgeLines = snowflake),
            supported = lyrebirdOnly,
        )
        assertEquals(listOf("obfs4", "meek_lite"), ladder.map { it.label })
    }

    @Test
    fun `the built-in list the profile already carries is not tried twice`() {
        // The common case, not an edge one: seeding copies exactly these lines in.
        val ladder = plan(ConnectionProfile(torBridgeLines = "$obfs4A\n$obfs4B"))
        assertEquals(listOf("obfs4", "snowflake", "webtunnel"), ladder.map { it.label })
        assertEquals(3, ladder.size)
    }

    @Test
    fun `the ladder is capped`() {
        val ladder = plan(ConnectionProfile(torBridgeLines = vanilla))
        assertEquals(BridgePlan.MAX_ATTEMPTS, ladder.size)
        assertTrue(ladder.size <= BridgePlan.MAX_ATTEMPTS)
    }

    @Test
    fun `a build with no transports and no catalogue still returns one attempt`() {
        val ladder = plan(supported = emptySet(), catalog = emptyMap())
        assertEquals(1, ladder.size)
        assertFalse(ladder.first().usesBridges)
    }

    @Test
    fun `a junk line cannot become a rung of its own`() {
        val ladder = plan(
            ConnectionProfile(torBridgeLines = "obfs4 1.2.3.4:443 cert=x\nExitNodes {ru}"),
        )
        assertFalse(ladder.any { rung -> rung.lines.any { it.contains("ExitNodes") } })
    }

    @Test
    fun `every line on every rung is canonical`() {
        // The torrc filters again, but a rung that carries raw text would make
        // the log and the configuration disagree about what was tried.
        val ladder = plan(ConnectionProfile(torBridgeLines = "  Bridge $obfs4A  "))
        assertEquals(listOf(obfs4A), ladder.first().lines)
    }
}
