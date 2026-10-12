package studio.cluvex.aether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smart DNS takes ONE server now. What is pinned here: a stored value is read
 * as exactly one server, a list saved by an older build collapses to the entry
 * that build asked first, and every preset carries a country to flag.
 */
class SmartDnsSingleTest {

    @Test
    fun aSinglePresetIsStoredAsItsTokenAndReadBack() {
        val stored = SmartDnsPresets.single("xbox-dns")
        assertEquals("preset:xbox-dns", stored)
        val primary = assertNotNull(SmartDnsPresets.primary(stored))
        assertTrue(primary.isPreset)
        assertEquals("xbox-dns", SmartDnsPresets.fromToken(primary.alias)?.id)
    }

    @Test
    fun anOlderListIsReadAsItsFirstUsableEntry() {
        val legacy = "preset:nope, preset:udp-03, preset:geohide-eu, 203.0.113.53"
        assertEquals("preset:udp-03", SmartDnsPresets.primary(legacy)?.label)
        assertEquals("203.0.113.53", SmartDnsPresets.primary("127.0.0.1, 203.0.113.53, 198.51.100.1")?.label)
        assertNull(SmartDnsPresets.primary(""))
        assertNull(SmartDnsPresets.primary("localhost, 0.0.0.0"))
    }

    @Test
    fun aTypedServerIsStoredWithItsSchemeSoItKeepsItsTransport() {
        val dot = assertNotNull(SmartDnsPresets.primary("dns.example.com", SmartDnsProtocol.DOT))
        val stored = SmartDnsPresets.storedFor(dot)
        assertEquals("tls://dns.example.com", stored)
        assertEquals(SmartDnsProtocol.DOT, SmartDnsPresets.primary(stored)?.protocol)

        val preset = assertNotNull(SmartDnsPresets.DEFAULT.server)
        assertEquals(SmartDnsPresets.DEFAULT.token, SmartDnsPresets.storedFor(preset))
    }

    @Test
    fun everyPresetHasACountryToFlag() {
        SmartDnsPresets.ALL.forEach { preset ->
            assertEquals(2, preset.country.length, preset.id)
            assertTrue(preset.country.all { it in 'A'..'Z' }, preset.id)
        }
        assertEquals("EU", SmartDnsPresets.byId("geohide-eu")?.country)
        assertEquals("US", SmartDnsPresets.byId("geohide-us")?.country)
        assertEquals("RU", SmartDnsPresets.byId("udp-01")?.country)
    }
}
