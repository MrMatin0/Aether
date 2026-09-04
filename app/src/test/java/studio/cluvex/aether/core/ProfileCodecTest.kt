package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth

/**
 * [ProfileCodec] is the ONLY channel between the UI and the VpnService, so a
 * field it forgets is a setting the user configures, sees persisted, and the
 * engine never learns about. That class of bug has already shipped twice, and
 * it is invisible in review because the code still compiles — so it gets a test
 * that fails when a field is added to the model and not to the codec.
 */
class ProfileCodecTest {

    private val populated = ConnectionProfile(
        protocol = Protocol.MASQUE,
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
    )

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
}
