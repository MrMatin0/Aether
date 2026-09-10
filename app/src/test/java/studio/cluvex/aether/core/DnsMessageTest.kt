package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsMessageTest {

    // ----------------------------------------------------------------- setup

    private fun query(
        name: String = "www.instagram.com",
        type: Int = DnsMessage.TYPE_AAAA,
        id: Int = 0x1234,
        recursionDesired: Boolean = true,
        questionCount: Int = 1,
        isResponse: Boolean = false,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add(((id shr 8) and 0xFF).toByte())
        out.add((id and 0xFF).toByte())
        var flagsHigh = 0
        if (isResponse) flagsHigh = flagsHigh or 0x80
        if (recursionDesired) flagsHigh = flagsHigh or 0x01
        out.add(flagsHigh.toByte())
        out.add(0x00.toByte())
        out.add(((questionCount shr 8) and 0xFF).toByte())
        out.add((questionCount and 0xFF).toByte())
        repeat(6) { out.add(0x00.toByte()) }
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0x00.toByte())
        out.add(((type shr 8) and 0xFF).toByte())
        out.add((type and 0xFF).toByte())
        out.add(0x00.toByte())
        out.add(0x01.toByte())
        return out.toByteArray()
    }

    // ------------------------------------------------------------ what was asked

    @Test
    fun readsTheQuestionType() {
        assertEquals(DnsMessage.TYPE_AAAA, DnsMessage.questionType(query(type = DnsMessage.TYPE_AAAA)))
        assertEquals(DnsMessage.TYPE_A, DnsMessage.questionType(query(type = DnsMessage.TYPE_A)))
        assertEquals(65, DnsMessage.questionType(query(type = 65)))
    }

    @Test
    fun onlyAaaaCountsAsAnIpv6Question() {
        assertTrue(DnsMessage.isAaaaQuery(query(type = DnsMessage.TYPE_AAAA)))
        assertFalse(DnsMessage.isAaaaQuery(query(type = DnsMessage.TYPE_A)))
        // HTTPS/SVCB records can carry IPv6 hints, but they are not an address
        // question and answering NODATA to one would hide the whole record.
        assertFalse(DnsMessage.isAaaaQuery(query(type = 65)))
    }

    @Test
    fun readsTheTransactionId() {
        assertEquals(0x1234, DnsMessage.transactionId(query(id = 0x1234)))
        assertEquals(0xFFFF, DnsMessage.transactionId(query(id = 0xFFFF)))
        assertNull(DnsMessage.transactionId(ByteArray(11)))
    }

    // ------------------------------------------------------------------ NODATA

    @Test
    fun nodataAnswersTheQuestionItWasAsked() {
        val asked = query(type = DnsMessage.TYPE_AAAA, id = 0xBEEF)
        val answer = DnsMessage.nodataResponse(asked)!!

        // Same id, or the client throws the reply away.
        assertEquals(0xBEEF, DnsMessage.transactionId(answer))
        // QR=1, RD echoed back, RA=1, RCODE=0 (NOERROR - this is NODATA, not NXDOMAIN).
        assertEquals(0x81, answer[2].toInt() and 0xFF)
        assertEquals(0x80, answer[3].toInt() and 0xFF)
        // One question, no records at all.
        assertEquals(1, ((answer[4].toInt() and 0xFF) shl 8) or (answer[5].toInt() and 0xFF))
        assertEquals(0, ((answer[6].toInt() and 0xFF) shl 8) or (answer[7].toInt() and 0xFF))
        assertEquals(0, ((answer[8].toInt() and 0xFF) shl 8) or (answer[9].toInt() and 0xFF))
        assertEquals(0, ((answer[10].toInt() and 0xFF) shl 8) or (answer[11].toInt() and 0xFF))
        // The question section is echoed byte for byte.
        assertEquals(asked.size, answer.size)
        assertTrue(asked.copyOfRange(12, asked.size).contentEquals(answer.copyOfRange(12, answer.size)))
    }

    @Test
    fun nodataDoesNotInventRecursionAvailableForAClientThatDidNotAskForIt() {
        val answer = DnsMessage.nodataResponse(query(recursionDesired = false))!!
        assertEquals(0x80, answer[2].toInt() and 0xFF)
    }

    @Test
    fun nodataDropsAnythingTrailingTheQuestion() {
        // An EDNS OPT record in the additional section must not be echoed back
        // as if it were part of the question.
        val asked = query() + byteArrayOf(0x00, 0x00, 0x29, 0x10, 0x00)
        val answer = DnsMessage.nodataResponse(asked)!!
        assertEquals(asked.size - 5, answer.size)
    }

    // ------------------------------------------------- messages it refuses to touch

    @Test
    fun refusesAnAnswerRatherThanRewritingIt() {
        assertNull(DnsMessage.questionType(query(isResponse = true)))
        assertNull(DnsMessage.nodataResponse(query(isResponse = true)))
    }

    @Test
    fun refusesAnythingButExactlyOneQuestion() {
        assertNull(DnsMessage.questionType(query(questionCount = 0)))
        assertNull(DnsMessage.questionType(query(questionCount = 2)))
        assertNull(DnsMessage.nodataResponse(query(questionCount = 2)))
    }

    @Test
    fun refusesACompressionPointerInsideTheQuestion() {
        // Illegal in a question, and the classic way a hand-written parser is
        // walked off the end of its buffer.
        val poisoned = query().also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.questionType(poisoned))
        assertNull(DnsMessage.nodataResponse(poisoned))
    }

    @Test
    fun refusesATruncatedMessage() {
        val full = query()
        assertNull(DnsMessage.questionType(ByteArray(0)))
        assertNull(DnsMessage.questionType(ByteArray(16)))
        // Cut inside the name, and cut just before QTYPE/QCLASS.
        assertNull(DnsMessage.questionType(full.copyOf(full.size - 6)))
        assertNull(DnsMessage.questionType(full.copyOf(full.size - 1)))
        assertNull(DnsMessage.nodataResponse(full.copyOf(full.size - 1)))
    }

    @Test
    fun refusesALabelThatRunsPastTheEnd() {
        val poisoned = query().also { it[12] = 0x3F.toByte() }
        assertNull(DnsMessage.questionType(poisoned))
    }

    @Test
    fun acceptsTheRootNameAndTheShortestLegalQuery() {
        val root = query(name = "")
        assertEquals(DnsMessage.TYPE_AAAA, DnsMessage.questionType(root))
        assertEquals(17, root.size)
        assertEquals(17, DnsMessage.nodataResponse(root)!!.size)
    }

    @Test
    fun acceptsAMaximumLengthLabel() {
        val label = "a".repeat(63)
        assertEquals(
            DnsMessage.TYPE_A,
            DnsMessage.questionType(query(name = "$label.example.com", type = DnsMessage.TYPE_A)),
        )
    }
}
