package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The spoofing profile contract.
 *
 * WHY THIS IS PINNED: every one of these modes changes the bytes the engine
 * puts on the wire, and the env vars are read on every connect. A wrong name
 * here and the engine runs with spoofing silently off (an unknown value is
 * ignored by design); a mode emitted on the wrong transport and the UI is
 * promising something the wire cannot deliver.
 */
class SpoofModeTest {

    @Test
    fun `engine values round trip`() {
        SpoofMode.entries.forEach { mode ->
            assertEquals(mode, SpoofMode.fromStored(mode.engineValue))
            assertEquals(mode, SpoofMode.fromStored(mode.name))
        }
    }

    @Test
    fun `the reference repo option names are read as what they actually do`() {
        // wrong_seq never touched a TCP sequence number; it splits the stream.
        assertEquals(SpoofMode.STREAM_SPLIT, SpoofMode.fromStored("wrong_seq"))
        // fake_client_hello is the decoy idea, done correctly here.
        assertEquals(SpoofMode.DECOY, SpoofMode.fromStored("fake_client_hello"))
        assertEquals(SpoofMode.DECOY, SpoofMode.fromStored("custom_decoy"))
    }

    @Test
    fun `unknown values keep the off default instead of guessing`() {
        assertNull(SpoofMode.fromStored(null))
        assertNull(SpoofMode.fromStored(""))
        assertNull(SpoofMode.fromStored("  "))
        assertNull(SpoofMode.fromStored("quantum_entanglement"))
    }

    @Test
    fun `spoofing is only emitted on the masque http2 carrier`() {
        val base = ConnectionProfile(spoofMode = SpoofMode.SNI_SPLIT)

        // MASQUE + HTTP/2: the mode reaches the engine.
        val h2 = base.copy(protocol = Protocol.MASQUE, masqueHttp2 = true)
        assertEquals(SpoofMode.SNI_SPLIT, h2.effectiveSpoofMode)
        assertEquals("sni_split", h2.toEnv()["AETHER_MASQUE_H2_SPOOF"])

        // MASQUE over HTTP/3: the ClientHello sits inside QUIC frames, so the
        // mode is not sent - but the stored choice is kept for later.
        val h3 = base.copy(protocol = Protocol.MASQUE, masqueHttp2 = false)
        assertEquals(SpoofMode.OFF, h3.effectiveSpoofMode)
        assertFalse(h3.toEnv().containsKey("AETHER_MASQUE_H2_SPOOF"))
        assertEquals(SpoofMode.SNI_SPLIT, h3.spoofMode)

        // WireGuard and gool have no ClientHello at all.
        val wg = base.copy(protocol = Protocol.WIREGUARD)
        assertEquals(SpoofMode.OFF, wg.effectiveSpoofMode)
        assertFalse(wg.toEnv().containsKey("AETHER_MASQUE_H2_SPOOF"))
    }

    @Test
    fun `off emits nothing at all`() {
        val profile = ConnectionProfile(protocol = Protocol.MASQUE, masqueHttp2 = true)
        assertEquals(SpoofMode.OFF, profile.effectiveSpoofMode)
        assertFalse(profile.toEnv().containsKey("AETHER_MASQUE_H2_SPOOF"))
    }

    @Test
    fun `custom sni is validated and only emitted for masque`() {
        val good = ConnectionProfile(protocol = Protocol.MASQUE, spoofSni = " Speed.Cloudflare.COM ")
        assertEquals("speed.cloudflare.com", good.effectiveSpoofSni)
        assertEquals("speed.cloudflare.com", good.toEnv()["AETHER_MASQUE_SNI"])

        // Garbage is dropped, not sent into a TLS record.
        val bad = ConnectionProfile(protocol = Protocol.MASQUE, spoofSni = "https://not a name")
        assertEquals("", bad.effectiveSpoofSni)
        assertFalse(bad.toEnv().containsKey("AETHER_MASQUE_SNI"))

        // A bare label hides nothing and is never a real edge name.
        val oneLabel = ConnectionProfile(protocol = Protocol.MASQUE, spoofSni = "localhost")
        assertEquals("", oneLabel.effectiveSpoofSni)

        // WireGuard has no hello to carry it.
        val wg = ConnectionProfile(protocol = Protocol.WIREGUARD, spoofSni = "speed.cloudflare.com")
        assertEquals("", wg.effectiveSpoofSni)
        assertFalse(wg.toEnv().containsKey("AETHER_MASQUE_SNI"))
    }

    @Test
    fun `a valid sni is emitted even with spoofing off`() {
        // The SNI is its own setting: shaping the hello and choosing the name
        // are independent, and "default mode + custom name" is a normal setup.
        val profile = ConnectionProfile(
            protocol = Protocol.MASQUE,
            masqueHttp2 = false,
            spoofMode = SpoofMode.OFF,
            spoofSni = "speed.cloudflare.com",
        )
        assertFalse(profile.toEnv().containsKey("AETHER_MASQUE_H2_SPOOF"))
        assertEquals("speed.cloudflare.com", profile.toEnv()["AETHER_MASQUE_SNI"])
    }
}
