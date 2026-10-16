package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // There is no decoy mode (its record was an empty ClientHello, which a
        // TLS 1.3 server cannot ignore), so the decoy-shaped names mean OFF.
        assertEquals(SpoofMode.OFF, SpoofMode.fromStored("fake_client_hello"))
        assertEquals(SpoofMode.OFF, SpoofMode.fromStored("custom_decoy"))
    }

    @Test
    fun `a profile saved with the removed decoy mode reads back as off`() {
        assertEquals(SpoofMode.OFF, SpoofMode.fromStored("decoy"))
        assertEquals(SpoofMode.OFF, SpoofMode.fromStored("DECOY"))
        // And nothing can emit it any more.
        assertFalse(SpoofMode.entries.any { it.engineValue == "decoy" })
        val profile = ConnectionProfile(
            protocol = Protocol.MASQUE,
            masqueHttp2 = true,
            spoofMode = SpoofMode.fromStored("decoy") ?: SpoofMode.OFF,
        )
        assertFalse(profile.toEnv().containsKey("AETHER_MASQUE_H2_SPOOF"))
    }

    @Test
    fun `unknown values keep the profile default instead of guessing`() {
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
        // OFF and a blank SNI are asked for explicitly: neither is the
        // default any more (see the defaults test below).
        val profile = ConnectionProfile(
            protocol = Protocol.MASQUE,
            masqueHttp2 = true,
            spoofMode = SpoofMode.OFF,
            spoofSni = "",
        )
        assertEquals(SpoofMode.OFF, profile.effectiveSpoofMode)
        assertFalse(profile.toEnv().containsKey("AETHER_MASQUE_H2_SPOOF"))
        assertFalse(profile.toEnv().containsKey("AETHER_MASQUE_SNI"))
    }

    /**
     * The 1.5.5 defaults: MASQUE over HTTP/2 on both IP families, the Precise
     * scan, SNI-split spoofing (the first mode in the picker) and
     * speedtest.net as the SNI. Pinned end to end, because a default is only
     * real once it reaches the engine's argv and environment.
     */
    @Test
    fun `the default profile is masque h2 dual stack with sni split on speedtest net`() {
        val d = ConnectionProfile()
        assertEquals(Protocol.MASQUE, d.protocol)
        assertTrue(d.masqueHttp2)
        assertEquals(IpVersion.BOTH, d.ipVersion)
        assertEquals(ScanMode.PRECISE, d.scanMode)
        assertEquals(SpoofMode.SNI_SPLIT, d.spoofMode)
        assertEquals(ConnectionProfile.DEFAULT_SPOOF_SNI, d.spoofSni)
        assertEquals("speedtest.net", ConnectionProfile.DEFAULT_SPOOF_SNI)

        val args = d.toArgs("2.1.0")
        assertTrue("--masque" in args, "$args")
        assertTrue("--dual" in args, "$args")
        assertTrue(ScanMode.PRECISE.engineFlag in args, "$args")

        val env = d.toEnv()
        assertEquals("1", env["AETHER_MASQUE_HTTP2"])
        assertEquals("sni_split", env["AETHER_MASQUE_H2_SPOOF"])
        assertEquals("speedtest.net", env["AETHER_MASQUE_SNI"])
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
