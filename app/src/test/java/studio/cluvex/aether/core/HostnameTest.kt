package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostnameTest {

    @Test
    fun ordinaryHostnamesAreRepresentable() {
        assertTrue(Hostname.isRepresentable("example.com"))
        assertTrue(Hostname.isRepresentable("a.b.c.d.example.co.uk"))
        assertTrue(Hostname.isRepresentable("cdn-1.example.com"))
        assertTrue(Hostname.isRepresentable("_dmarc.example.com"))
        assertTrue(Hostname.isRepresentable("localhost"))
    }

    @Test
    fun namesLongerThanTheLengthByteAreRefused() {
        val label = "a".repeat(60)
        val tooLong = List(5) { label }.joinToString(".")
        assertTrue(tooLong.length > Hostname.MAX_WIRE_LENGTH)
        // This is the case that used to be truncated onto the wire by
        // `length.toByte()`, producing a corrupted SOCKS5 request.
        assertFalse(Hostname.isRepresentable(tooLong))
    }

    @Test
    fun overlongSingleLabelsAreRefused() {
        assertFalse(Hostname.isRepresentable("a".repeat(Hostname.MAX_LABEL_LENGTH + 1)))
        assertTrue(Hostname.isRepresentable("a".repeat(Hostname.MAX_LABEL_LENGTH)))
    }

    @Test
    fun nonAsciiAndControlBytesAreRefused() {
        assertFalse(Hostname.isRepresentable("exämple.com"))
        assertFalse(Hostname.isRepresentable("example.com"))
        assertFalse(Hostname.isRepresentable("exa mple.com"))
        assertFalse(Hostname.isRepresentable("example.com\r\nHost: evil"))
    }

    @Test
    fun emptyLabelsAreRefused() {
        assertFalse(Hostname.isRepresentable(""))
        assertFalse(Hostname.isRepresentable("."))
        assertFalse(Hostname.isRepresentable(".example.com"))
        assertFalse(Hostname.isRepresentable("example..com"))
    }

    @Test
    fun sanitizeNormalisesCaseAndTheRootDot() {
        assertEquals("example.com", Hostname.sanitize("  Example.COM.  "))
        assertEquals("example.com", Hostname.sanitize("example.com"))
    }

    @Test
    fun sanitizeRejectsWhatItCannotNormalise() {
        assertNull(Hostname.sanitize(null))
        assertNull(Hostname.sanitize(""))
        assertNull(Hostname.sanitize(".."))
        assertNull(Hostname.sanitize("a".repeat(300)))
        assertNull(Hostname.sanitize("exämple.com"))
    }
}
