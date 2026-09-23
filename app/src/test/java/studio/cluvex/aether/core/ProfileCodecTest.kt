package studio.cluvex.aether.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorBridgeMode

/**
 * [ProfileCodec] is the ONLY channel between the UI and the VpnService, so a
 * field it forgets is a setting the user configures, sees persisted, and the
 * engine never learns about. That class of bug has now shipped three times
 * (1.2.3, 1.5.0 and the whole engine v2.0.0 block), and it is invisible in
 * review because the code still compiles.
 *
 * The previous version of this file CLAIMED to catch it and did not: it compared
 * a hand-written fixture against its own round trip, so it only ever checked the
 * fields somebody had remembered to put in the fixture. Fourteen v2.0.0 fields
 * were missing from both the codec and the fixture, and every assertion passed.
 *
 * So the fixture is no longer trusted to be complete - it is CHECKED, by
 * [everyFieldOfTheModelIsCarriedByTheFixture], which walks the model's declared
 * fields and fails on any that is still at its default value here. Adding a
 * field to [ConnectionProfile] therefore breaks that test until the fixture
 * covers it, and the round trip below then breaks until the codec does.
 */
class ProfileCodecTest {

    /**
     * Every non-secret field of [ConnectionProfile], each set to something that
     * is NOT its default. The codec is dumb transport and must round-trip
     * whatever it is handed; the rules about which fields are honoured together
     * live in the model.
     */
    private val populated = ConnectionProfile(
        // MIM rather than MASQUE: it is the protocol whose extra fields
        // (mimOuterPeer / mimInnerPeer) the round trip has to carry.
        protocol = Protocol.MIM,
        scanMode = ScanMode.ULTRA,
        ipVersion = IpVersion.BOTH,
        quickReconnect = false,
        masqueHttp2 = true,
        lanShare = true,
        noize = Noize.AGGRESSIVE,
        endpointMode = EndpointMode.MANUAL_PEER,
        manualPeer = "188.114.96.1:2408",
        manualRange = "188.114.96.0/24",
        keepalive = 25,
        fragment = true,
        ech = true,
        mtu = 1420,
        proxyMode = true,
        splitMode = SplitMode.EXCLUDE,
        splitApps = listOf("com.example.a", "com.example.b"),
        dnsServers = "1.1.1.1,9.9.9.9",
        team = "acme",
        teamAuth = TeamAuth.SERVICE_TOKEN,
        accessClientId = "client-id",
        accessEmail = "someone@example.com",
        gateway = true,
        routeBlock = "ads.example.com",
        routeDirect = "bank.example.ir",
        killSwitch = true,
        strictKillSwitch = true,
        ipv6LeakProtection = false,
        smartReconnect = false,
        reconnectRetryLimit = 9,
        fragmentSize = "16-32",
        fragmentDelay = "2-10",
        noDataCheck = true,
        tlsGroups = "X25519:P-256",
        validateSecs = 30,
        reconnectSecs = 15,
        noProfileRetry = true,
        coreLogLevel = CoreLogLevel.DEBUG,
        blockedApps = listOf("com.example.blocked"),
        // ---- 1.5.0: chained cores ----
        chain = ChainMode.TOR_OVER_PSIPHON_OVER_AETHER,
        psiphonRegion = "nl",
        // Single line on purpose: a pasted config folds its newlines to spaces,
        // so a multi-line document round-trips as JSON but not byte-for-byte.
        psiphonConfig = "{\"PropagationChannelId\":\"ABCD1234\",\"SponsorId\":\"EF567890\"}",
        torExitCountry = "se",
        torStrictNodes = true,
        // ---- 1.4.7: Tor bridges ----
        torBridgeMode = TorBridgeMode.CUSTOM,
        torBridgeTransport = BridgeTransport.SNOWFLAKE,
        // TWO lines, and one of them carrying commas: that combination is what
        // the '|' fold exists for, and a comma fold would silently corrupt it.
        torBridgeLines = listOf(
            "obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq iat-mode=0",
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fronts=app.example.com,www.example.com ice=stun:a.example:3478,stun:b.example:3478",
        ).joinToString("\n"),
        // ---- 2.0.0: engine v2.0.0 ----
        mimOuterPeer = "162.159.192.1:2408",
        mimInnerPeer = "188.114.97.2:934",
        quicV2Opener = false,
        socketMark = "0xff",
        maxClients = 256,
        halfCloseSecs = 45,
        tcpKeepaliveSecs = 90,
        tcpConnectSecs = 20,
    )

    /**
     * The teeth. A round trip can only prove what the fixture actually varies,
     * so this is the test that makes the fixture cover the model - by reflection
     * over the declared fields rather than by anybody's memory.
     *
     * Java reflection on purpose: it needs no kotlin-reflect on the test
     * classpath, and a data class's properties are its declared instance fields.
     * Statics and synthetics are skipped (the Compose compiler adds a `$stable`
     * field to this class), and the two Zero Trust secrets are skipped because
     * they must NEVER travel in the payload - see the secrets test below.
     */
    @Test
    fun everyFieldOfTheModelIsCarriedByTheFixture() {
        val defaults = ConnectionProfile()
        val untouched = ConnectionProfile::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .filterNot { it.name in SECRET_FIELDS }
            .onEach { it.isAccessible = true }
            .filter { it.get(populated) == it.get(defaults) }
            .map { it.name }

        assertTrue(
            untouched.isEmpty(),
            "these ConnectionProfile fields are still at their default in this " +
                "test's fixture, so the round trip below cannot tell whether " +
                "ProfileCodec transports them: $untouched. Give each one a " +
                "non-default value here, then make the codec carry it.",
        )
    }

    @Test
    fun everyNonSecretFieldSurvivesTheRoundTrip() {
        // Secrets deliberately never travel (see the secret test below), so
        // compare against the same profile with the secret fields blank.
        assertEquals(populated, ProfileCodec.decode(ProfileCodec.encode(populated)))
    }

    @Test
    fun zeroTrustSecretsNeverEnterThePayload() {
        // Intent extras show up in system service dumps, so the service must
        // re-read these from the Keystore-sealed store instead.
        val withSecrets = populated.copy(
            accessClientSecret = "super-secret-value",
            accessToken = "eyJhbGciOiJIUzI1NiJ9.token",
        )
        val payload = ProfileCodec.encode(withSecrets)
        assertFalse(payload.contains("super-secret-value"))
        assertFalse(payload.contains("eyJhbGciOiJIUzI1NiJ9.token"))
        val decoded = ProfileCodec.decode(payload)
        assertEquals("", decoded.accessClientSecret)
        assertEquals("", decoded.accessToken)
    }

    @Test
    fun anEmptyOrNullPayloadDecodesToDefaults() {
        assertEquals(ConnectionProfile(), ProfileCodec.decode(null))
        assertEquals(ConnectionProfile(), ProfileCodec.decode(""))
        assertEquals(ConnectionProfile(), ProfileCodec.decode("   "))
    }

    @Test
    fun unknownKeysAreIgnoredAndMissingKeysFallBackToDefaults() {
        // Forward/backward tolerance is the codec's contract: an older build
        // must be able to decode a newer build's payload without crashing.
        val decoded = ProfileCodec.decode("protocol=GOOL\nsomethingFromTheFuture=42\nmtu=1380")
        assertEquals(Protocol.GOOL, decoded.protocol)
        assertEquals(1380, decoded.mtu)
        assertEquals(ConnectionProfile().scanMode, decoded.scanMode)
    }

    @Test
    fun aPayloadFromBeforeTheV2FieldsKeepsTheirModelDefaults() {
        // The one v2.0.0 default that is ON: a payload written by a build that
        // had no quicV2 key must not be read as "the user switched it off",
        // which would send --no-quic-v2 and cost a round trip on every HTTP/3
        // handshake for a setting nobody touched.
        val decoded = ProfileCodec.decode("protocol=MASQUE\nmtu=1280")
        assertTrue(decoded.quicV2Opener, "the QUIC v2 opener must default to on")
        assertEquals(Protocol.MASQUE, decoded.protocol)
        assertEquals(0, decoded.maxClients)
    }

    /**
     * MASQUE-in-MASQUE used to be `mim=true` beside `protocol=MASQUE`. A pending
     * Intent or a saved setup from that build must keep building two hops,
     * and the old switch must stay meaningless next to any other protocol, as
     * it always was.
     */
    @Test
    fun theRetiredMimSwitchMigratesToTheMimProtocol() {
        assertEquals(Protocol.MIM, ProfileCodec.decode("protocol=MASQUE\nmim=true").protocol)
        assertEquals(Protocol.MASQUE, ProfileCodec.decode("protocol=MASQUE\nmim=false").protocol)
        assertEquals(Protocol.WIREGUARD, ProfileCodec.decode("protocol=WIREGUARD\nmim=true").protocol)
        assertEquals(Protocol.GOOL, ProfileCodec.decode("protocol=GOOL\nmim=true").protocol)
        // And the new encoding never writes the retired key.
        assertFalse(ProfileCodec.encode(populated).lineSequence().any { it.startsWith("mim=") })
    }

    /**
     * The app runs ONE Tor: the bundled core behind the chain modes. A payload
     * from the build that also exposed the engine's own tor must decode without
     * error and without resurrecting it anywhere in the engine's argv.
     */
    @Test
    fun retiredEngineTorKeysAreIgnored() {
        val decoded = ProfileCodec.decode(
            "protocol=MASQUE\nengineTor=REVERSE\nengineTorBridges=CUSTOM\n" +
                "engineTorBridgeLines=obfs4 1.2.3.4:443 ABCDEF cert=x iat-mode=0\n" +
                "engineTorCountry=ir\nengineTorBind=1820",
        )
        assertEquals(Protocol.MASQUE, decoded.protocol)
        assertFalse(decoded.toArgs().any { it.startsWith("--tor") })
        assertFalse(decoded.toEnv().containsKey("AETHER_TOR_COUNTRY"))
        assertFalse(ProfileCodec.encode(decoded).contains("engineTor"))
    }

    @Test
    fun garbageValuesFallBackInsteadOfThrowing() {
        val decoded = ProfileCodec.decode("protocol=NOT_A_PROTOCOL\nmtu=banana\nkill=maybe")
        assertEquals(ConnectionProfile().protocol, decoded.protocol)
        assertEquals(ConnectionProfile().mtu, decoded.mtu)
        assertEquals(ConnectionProfile().killSwitch, decoded.killSwitch)
    }

    @Test
    fun embeddedNewlinesCannotForgeAnotherField() {
        // The payload is line-framed, so an unflattened newline in a free-text
        // field would truncate the value and turn the remainder into a key -
        // one containing '=' could shadow a real setting.
        val decoded = ProfileCodec.decode(
            ProfileCodec.encode(populated.copy(routeBlock = "a.example.com\nkill=false")),
        )
        assertTrue(decoded.killSwitch, "an injected line must not override another field")
        assertFalse(decoded.routeBlock.contains("\n"))
    }

    @Test
    fun theLegacyPipeFormatStillDecodes() {
        // 1.0/1.1 payloads are still sitting in pending Intent extras.
        val decoded = ProfileCodec.decode("WIREGUARD|THOROUGH|V6|true|false|true")
        assertEquals(Protocol.WIREGUARD, decoded.protocol)
        assertEquals(ScanMode.PRECISE, decoded.scanMode, "retired mode must migrate, not reset")
        assertEquals(IpVersion.V6, decoded.ipVersion)
        assertTrue(decoded.quickReconnect)
        assertFalse(decoded.masqueHttp2)
        assertTrue(decoded.lanShare)
    }

    private companion object {
        /**
         * The fields that must never appear in a payload. The VpnService reads
         * them from the Keystore-sealed [studio.cluvex.aether.data.SecretStore].
         */
        val SECRET_FIELDS = setOf("accessClientSecret", "accessToken")
    }
}
