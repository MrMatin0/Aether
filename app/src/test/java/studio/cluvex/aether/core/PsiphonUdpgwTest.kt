package studio.cluvex.aether.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PsiphonUdpgwTest {

    @Test
    fun ipv4FrameMatchesPsiphonsLayout() {
        // size u16 LE | flags | conn_id u16 LE | ip | port u16 BE | payload
        val frame = UdpgwCodec.encode(
            UdpgwCodec.FLAG_REBIND,
            0x0102,
            byteArrayOf(1, 2, 3, 4),
            443,
            byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )
        assertContentEquals(
            byteArrayOf(
                11, 0,
                0x02,
                0x02, 0x01,
                1, 2, 3, 4,
                0x01, 0xBB.toByte(),
                0xAA.toByte(), 0xBB.toByte(),
            ),
            frame,
        )
    }

    @Test
    fun ipv6SetsTheFlagAndCarriesSixteenBytes() {
        val address = ByteArray(16) { it.toByte() }
        val frame = assertNotNull(UdpgwCodec.encode(0, 7, address, 53, byteArrayOf(9)))
        assertEquals(3 + 16 + 2 + 1, (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8))
        assertEquals(UdpgwCodec.FLAG_IPV6, frame[2].toInt() and UdpgwCodec.FLAG_IPV6)
        val decoded = assertNotNull(UdpgwCodec.decodeBody(frame.copyOfRange(2, frame.size)))
        assertContentEquals(address, decoded.address)
        assertEquals(53, decoded.port)
        assertEquals(7, decoded.connId)
    }

    @Test
    fun ipv4FlagIsClearedEvenIfTheCallerSetsIt() {
        val frame = assertNotNull(
            UdpgwCodec.encode(UdpgwCodec.FLAG_IPV6, 1, byteArrayOf(1, 2, 3, 4), 1, ByteArray(0)),
        )
        assertEquals(0, frame[2].toInt() and UdpgwCodec.FLAG_IPV6)
    }

    @Test
    fun roundTripsThroughAStream() {
        val payload = ByteArray(1_200) { (it * 7).toByte() }
        val frame = assertNotNull(UdpgwCodec.encode(0, 0xFFFF, byteArrayOf(8, 8, 4, 4), 3478, payload))
        val message = assertNotNull(UdpgwCodec.readMessage(ByteArrayInputStream(frame)))
        assertEquals(0xFFFF, message.connId)
        assertContentEquals(byteArrayOf(8, 8, 4, 4), message.address)
        assertEquals(3478, message.port)
        assertContentEquals(payload, message.payload)
        assertFalse(message.isKeepalive)
    }

    @Test
    fun keepaliveIsHeaderOnly() {
        val frame = UdpgwCodec.keepalive()
        assertContentEquals(byteArrayOf(3, 0, UdpgwCodec.FLAG_KEEPALIVE.toByte(), 0, 0), frame)
        val message = assertNotNull(UdpgwCodec.readMessage(ByteArrayInputStream(frame)))
        assertTrue(message.isKeepalive)
    }

    @Test
    fun valuesThatCannotGoOnTheWireAreRefused() {
        assertNull(UdpgwCodec.encode(0, 1, ByteArray(5), 1, ByteArray(0)))
        assertNull(UdpgwCodec.encode(0, 70_000, byteArrayOf(1, 2, 3, 4), 1, ByteArray(0)))
        assertNull(UdpgwCodec.encode(0, 1, byteArrayOf(1, 2, 3, 4), 70_000, ByteArray(0)))
        assertNull(
            UdpgwCodec.encode(0, 1, byteArrayOf(1, 2, 3, 4), 1, ByteArray(UdpgwCodec.MAX_PAYLOAD + 1)),
        )
    }

    @Test
    fun truncatedAndMalformedInputIsNotMistakenForAMessage() {
        assertNull(UdpgwCodec.readMessage(ByteArrayInputStream(ByteArray(0))))
        // Declares an IPv4 message but carries no room for the address.
        assertNull(UdpgwCodec.decodeBody(byteArrayOf(0, 1, 0, 1, 2)))
        assertFailsWith<IOException> {
            UdpgwCodec.readMessage(ByteArrayInputStream(byteArrayOf(2, 0, 0, 0)))
        }
        assertFailsWith<IOException> {
            UdpgwCodec.readMessage(ByteArrayInputStream(byteArrayOf(9, 0, 0, 1, 0)))
        }
    }

    @Test
    fun muxReusesAFlowAndRebindsOnlyNewOnes() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val seen = LinkedBlockingQueue<UdpgwCodec.Message>()
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    while (true) {
                        val message = UdpgwCodec.readMessage(input) ?: break
                        if (message.isKeepalive) continue
                        seen.put(message)
                        // Echo, the way the server answers: same id, same address.
                        val reply = UdpgwCodec.encode(
                            0,
                            message.connId,
                            message.address,
                            message.port,
                            message.payload,
                        ) ?: break
                        output.write(reply)
                        output.flush()
                    }
                }
            }
        }
        val mux = UdpgwMux(
            dial = { Socket(server.inetAddress, server.localPort) },
            info = {},
            warn = {},
        )
        try {
            val replies = LinkedBlockingQueue<ByteArray>()
            val owner = Any()
            val sink = UdpgwMux.Sink { _, _, payload -> replies.put(payload) }
            val first = byteArrayOf(1, 2, 3, 4)
            val second = byteArrayOf(5, 6, 7, 8)

            assertTrue(mux.send(owner, sink, first, 443, byteArrayOf(1)))
            assertTrue(mux.send(owner, sink, first, 443, byteArrayOf(2)))
            assertTrue(mux.send(owner, sink, second, 443, byteArrayOf(3)))

            val a = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
            val b = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
            val c = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
            assertEquals(UdpgwCodec.FLAG_REBIND, a.flags and UdpgwCodec.FLAG_REBIND)
            assertEquals(0, b.flags and UdpgwCodec.FLAG_REBIND)
            assertEquals(a.connId, b.connId)
            assertNotEquals(a.connId, c.connId)
            assertEquals(UdpgwCodec.FLAG_REBIND, c.flags and UdpgwCodec.FLAG_REBIND)

            assertContentEquals(byteArrayOf(1), replies.poll(5, TimeUnit.SECONDS))
            assertContentEquals(byteArrayOf(2), replies.poll(5, TimeUnit.SECONDS))
            assertContentEquals(byteArrayOf(3), replies.poll(5, TimeUnit.SECONDS))
        } finally {
            mux.close()
            server.close()
        }
    }

    @Test
    fun muxRefusesWhenItCannotDial() {
        val mux = UdpgwMux(dial = { null }, info = {}, warn = {})
        val sink = UdpgwMux.Sink { _, _, _ -> }
        assertFalse(mux.send(Any(), sink, byteArrayOf(1, 2, 3, 4), 443, byteArrayOf(1)))
        // Backing off: the next datagram is dropped without another dial.
        assertFalse(mux.send(Any(), sink, byteArrayOf(1, 2, 3, 4), 443, byteArrayOf(1)))
        mux.close()
    }
}
