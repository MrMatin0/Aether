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
import studio.cluvex.aether.model.PsiphonProtocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SmartDnsProtocol
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.SpoofMode
import studio.cluvex.aether.model.SstpConfig
import studio.cluvex.aether.model.SstpSource
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
        // ---- Psiphon CDN fronting ----
        psiphonProtocol = PsiphonProtocol.CDN_FRONTING,
        // Single line: the codec folds newlines to commas, which the parser
        // treats the same way, but a byte-for-byte round trip needs one line.
        psiphonCdnEdgeIps = "23.215.0.206,104.16.0.0/24",
        psiphonCdnSni = "www.example.com",
        // ---- 2.2.0: MASQUE spoofing ----
        spoofMode = SpoofMode.SNI_SPLIT,
        spoofSni = "speed.cloudflare.com",
        // ---- Smart DNS ----
        // DoH rather than the PLAIN default, so the protocol travels too.
        // Single line, for the same reason as the CDN edge list above, and
        // VALID entries on purpose: the model caches the parsed list in a
        // private field, which the reflection check below also walks, and an
        // unparseable fixture would leave it at the default (empty).
        smartDns = true,
        smartDnsProtocol = SmartDnsProtocol.DOH,
        smartDnsServers = "https://dns.example.com/dns-query,192.0.2.53",
        // ---- SSTP ----
        // Every field but the password moved off its default. The password
        // stays at the default on purpose: it never travels (see
        // sstpPasswordNeverEntersThePayload), so the round trip can only
        // compare equal when the fixture leaves it where decoding puts it.
        sstpConfig = SstpConfig(
            hostname = "vpn.example.com",
            port = 8443,
            username = "alice",
            verifyCert = false,
            customSni = "cdn.example.com",
            mru = 1452,
            mtu = 1380,
            source = SstpSource.VPNGATE,
            countryCode = "JP",
            countryName = "Japan",
        ),
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

    /** Same teeth, one level down: every SSTP field except the password is varied. */
    @Test
    fun everySstpFieldIsCarriedByTheFixture() {
        val defaults = SstpConfig()
        val untouched = SstpConfig::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .filterNot { it.name == "password" }
            .onEach { it.isAccessible = true }
            .filter { it.get(populated.sstpConfig) == it.get(defaults) }
            .map { it.name }
        assertTrue(untouched.isEmpty(), "SstpConfig fields still at their default in the fixture: $untouched")
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
    fun sstpPasswordNeverEntersThePayload() {
        val withPassword = populated.copy(
            sstpConfig = populated.sstpConfig.copy(password = "correct-horse-battery"),
        )
        val payload = ProfileCodec.encode(withPassword)
        assertFalse(payload.contains("correct-horse-battery"))
        val decoded = ProfileCodec.decode(payload)
        // The public VPN Gate default, which the service replaces with the
        // sealed password before it dials.
        assertEquals(SstpConfig.VPNGATE_PASSWORD, decoded.sstpConfig.password)
        assertEquals("alice", decoded.sstpConfig.username)
    }

    @Test
    fun aPayloadFromBeforeSstpHasNoSstpServer() {
        val decoded = ProfileCodec.decode("protocol=MASQUE\nmtu=1280")
        assertEquals(SstpConfig(), decoded.sstpConfig)
        assertFalse(decoded.sstpConfig.isUsable)
        // An out-of-range port is a typo, not a setting.
        assertEquals(SstpConfig.DEFAULT_PORT, ProfileCodec.decode("sstpPort=70000").sstpConfig.port)
        assertEquals(SstpSource.MANUAL, ProfileCodec.decode("sstpSource=nonsense").sstpConfig.source)
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

    @Test
    fun aPayloadFromBeforeSmartDnsKeepsItOff() {
        val decoded = ProfileCodec.decode("protocol=MASQUE\nmtu=1280")
        assertFalse(decoded.smartDns)
        assertEquals(SmartDnsProtocol.PLAIN, decoded.smartDnsProtocol)
        assertEquals("", decoded.smartDnsServers)
        assertFalse(decoded.usesSmartDns)
    }

    /**
     * The manual direct switch and proxy list were retired by the DoH / DoT
     * rework: the front picks its path by itself. A payload from the build
     * that had them must still decode, keep the rest of its Smart DNS setup,
     * and never have those keys written back.
     */
    @Test
    fun retiredSmartDnsKeysAreIgnored() {
        val decoded = ProfileCodec.decode(
            "protocol=MASQUE\nsmartDns=true\nsmartDnsServers=192.0.2.53\n" +
                "smartDnsDirect=true\nsmartDnsProxies=192.0.2.10,198.51.100.0/24",
        )
        assertTrue(decoded.smartDns)
        assertEquals("192.0.2.53", decoded.smartDnsServers)
        assertEquals(SmartDnsProtocol.PLAIN, decoded.smartDnsProtocol)
        assertTrue(decoded.usesSmartDns)
        val reEncoded = ProfileCodec.encode(decoded)
        assertFalse(reEncoded.contains("smartDnsDirect"))
        assertFalse(reEncoded.contains("smartDnsProxies"))
    }

    @Test
    fun smartDnsProtocolAcceptsHandWrittenAliases() {
        assertEquals(SmartDnsProtocol.DOH, ProfileCodec.decode("smartDnsProtocol=https").smartDnsProtocol)
        assertEquals(SmartDnsProtocol.DOT, ProfileCodec.decode("smartDnsProtocol=tls").smartDnsProtocol)
        assertEquals(SmartDnsProtocol.PLAIN, ProfileCodec.decode("smartDnsProtocol=udp").smartDnsProtocol)
        assertEquals(SmartDnsProtocol.PLAIN, ProfileCodec.decode("smartDnsProtocol=nonsense").smartDnsProtocol)
    }

    @Test
    fun psiphonProtocolAcceptsHandWrittenAliases() {
        assertEquals(PsiphonProtocol.CDN_FRONTING, ProfileCodec.decode("psiphonProtocol=cdn").psiphonProtocol)
        assertEquals(PsiphonProtocol.CDN_FRONTING, ProfileCodec.decode("psiphonProtocol=cdn-fronting").psiphonProtocol)
        assertEquals(PsiphonProtocol.DIRECT, ProfileCodec.decode("psiphonProtocol=direct").psiphonProtocol)
        assertEquals(PsiphonProtocol.AUTO, ProfileCodec.decode("psiphonProtocol=nonsense").psiphonProtocol)
        assertEquals(PsiphonProtocol.AUTO, ProfileCodec.decode("protocol=MASQUE").psiphonProtocol)
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
