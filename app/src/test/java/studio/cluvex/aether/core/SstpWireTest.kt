package studio.cluvex.aether.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SstpWireTest {

    @Test
    fun acceptsHttp200() {
        assertTrue(SstpCore.isHttpOk("HTTP/1.1 200 OK\r\nContent-Length: 18446744073709551615\r\n\r\n"))
        assertTrue(SstpCore.isHttpOk("HTTP/1.0 200\r\n\r\n"))
    }

    @Test
    fun rejectsEverythingElse() {
        assertFalse(SstpCore.isHttpOk("HTTP/1.1 403 Forbidden\r\n\r\n"))
        assertFalse(SstpCore.isHttpOk("HTTP/1.1 2000 Weird\r\n\r\n"))
        assertFalse(SstpCore.isHttpOk(""))
    }

    @Test
    fun readsTheHeaderAndNotOneByteMore() {
        val bytes = "HTTP/1.1 200 OK\r\nX: y\r\n\r\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x10, 0x01)
        val input = ByteArrayInputStream(bytes)
        val header = SstpCore.readHttpHeader(input)
        assertTrue(header.endsWith("\r\n\r\n"))
        assertEquals(0x10, input.read())
    }

    @Test
    fun duplexPostIsWellFormed() {
        val text = String(SstpCore.duplexPostRequest("vpn.example"), Charsets.US_ASCII)
        assertTrue(text.startsWith("SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n"))
        assertTrue(text.contains("\r\nHost: vpn.example\r\n"))
        assertTrue(text.contains("\r\nContent-Length: 18446744073709551615\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }
}
