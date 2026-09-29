package studio.cluvex.aether.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Wire format of the udpgw protocol exactly as psiphon-tunnel-core's server
 * speaks it (`psiphon/server/udp.go`: `readUdpgwMessage` and
 * `writeUdpgwPreamble`), which is badvpn's udpgw protocol:
 *
 * ```
 * | size u16 LE | flags u8 | conn_id u16 LE | addr 4 or 16 | port u16 BE | payload |
 * ```
 *
 * `size` counts every byte after itself. IPv6 is signalled by [FLAG_IPV6],
 * not by an address-type byte. Note the MIXED endianness: size and conn_id are
 * little-endian, the port is network order.
 */
internal object UdpgwCodec {
    const val FLAG_KEEPALIVE = 1 shl 0
    const val FLAG_REBIND = 1 shl 1
    const val FLAG_DNS = 1 shl 2
    const val FLAG_IPV6 = 1 shl 3

    /** `udpgwProtocolMaxPayloadSize` on the server; larger datagrams are refused there. */
    const val MAX_PAYLOAD = 32_768

    /** flags + conn_id. */
    private const val HEADER = 3
    private const val MAX_BODY = HEADER + 16 + 2 + MAX_PAYLOAD

    class Message(
        val flags: Int,
        val connId: Int,
        val address: ByteArray,
        val port: Int,
        val payload: ByteArray,
    ) {
        val isKeepalive: Boolean get() = (flags and FLAG_KEEPALIVE) != 0
    }

    /** One framed message, or null when the values cannot be put on the wire. */
    fun encode(flags: Int, connId: Int, address: ByteArray, port: Int, payload: ByteArray): ByteArray? {
        if (address.size != 4 && address.size != 16) return null
        if (connId < 0 || connId > 0xFFFF) return null
        if (port < 0 || port > 0xFFFF) return null
        if (payload.size > MAX_PAYLOAD) return null
        val wireFlags = if (address.size == 16) flags or FLAG_IPV6 else flags and FLAG_IPV6.inv()
        val size = HEADER + address.size + 2 + payload.size
        val out = ByteArray(2 + size)
        out[0] = (size and 0xFF).toByte()
        out[1] = ((size shr 8) and 0xFF).toByte()
        out[2] = wireFlags.toByte()
        out[3] = (connId and 0xFF).toByte()
        out[4] = ((connId shr 8) and 0xFF).toByte()
        System.arraycopy(address, 0, out, 5, address.size)
        val portAt = 5 + address.size
        out[portAt] = ((port shr 8) and 0xFF).toByte()
        out[portAt + 1] = (port and 0xFF).toByte()
        System.arraycopy(payload, 0, out, portAt + 2, payload.size)
        return out
    }

    /** Header-only keepalive; the Psiphon server reads and ignores it. */
    fun keepalive(): ByteArray = byteArrayOf(HEADER.toByte(), 0, FLAG_KEEPALIVE.toByte(), 0, 0)

    /** Parses one message BODY (everything after the size prefix). */
    fun decodeBody(body: ByteArray): Message? {
        if (body.size < HEADER) return null
        val flags = body[0].toInt() and 0xFF
        val connId = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        if ((flags and FLAG_KEEPALIVE) != 0) {
            return Message(flags, connId, ByteArray(0), 0, ByteArray(0))
        }
        val addressSize = if ((flags and FLAG_IPV6) != 0) 16 else 4
        val portAt = HEADER + addressSize
        val payloadAt = portAt + 2
        if (body.size < payloadAt) return null
        val address = body.copyOfRange(HEADER, portAt)
        val port = ((body[portAt].toInt() and 0xFF) shl 8) or (body[portAt + 1].toInt() and 0xFF)
        return Message(flags, connId, address, port, body.copyOfRange(payloadAt, body.size))
    }

    /** Reads one message: null on a clean EOF, IOException on anything that is not udpgw. */
    fun readMessage(input: InputStream): Message? {
        val sizeBytes = readExact(input, 2) ?: return null
        val size = (sizeBytes[0].toInt() and 0xFF) or ((sizeBytes[1].toInt() and 0xFF) shl 8)
        if (size < HEADER || size > MAX_BODY) throw IOException("invalid udpgw message size $size")
        val body = readExact(input, size) ?: throw IOException("truncated udpgw message")
        return decodeBody(body) ?: throw IOException("malformed udpgw message")
    }

    private fun readExact(input: InputStream, size: Int): ByteArray? {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val read = input.read(out, done, size - done)
            if (read < 0) return null
            done += read
        }
        return out
    }
}

/**
 * ONE multiplexed udpgw session, shared by every UDP association a
 * [SocksFront] serves.
 *
 * ### Why one, and only one
 *
 * The Psiphon server keeps a single udpgw channel per client: "This channel
 * will replace any previously existing udpgw channel for this client"
 * (`handleUdpgwChannel`). A session per association would therefore have each
 * new app flow silently kill every other app's UDP. So flows are multiplexed
 * here by conn_id, and all of them die and are re-created together when the
 * session drops.
 *
 * ### Flow ids
 *
 * A flow is (association, destination ip, destination port). Its first
 * datagram carries [UdpgwCodec.FLAG_REBIND], which tells the server to drop any
 * port forward it still holds under that id - necessary because ids are reused
 * after eviction and after a reconnect. Replies are matched on conn_id AND
 * address, since the server documents that a stale reply for a recycled id can
 * still be in flight.
 *
 * ### Failure posture
 *
 * UDP is lossy by contract, so every failure here is a dropped datagram, never
 * a blocked caller: the dial happens outside the lock, a concurrent sender
 * during a dial is simply refused, and failed dials back off up to
 * [MAX_BACKOFF_MS]. Worst case this is exactly the old behaviour (drop).
 */
internal class UdpgwMux(
    private val dial: () -> Socket?,
    private val info: (String) -> Unit = { DiagnosticsLog.i(TAG, it) },
    private val warn: (String) -> Unit = { DiagnosticsLog.w(TAG, it) },
) {
    /** Where a reply for a flow is delivered. */
    fun interface Sink {
        fun deliver(address: ByteArray, port: Int, payload: ByteArray)
    }

    private data class FlowKey(val owner: Any, val address: String, val port: Int)

    private class Flow(val key: FlowKey, val sink: Sink, val address: ByteArray, val port: Int) {
        var lastUsed: Long = System.currentTimeMillis()
    }

    private val lock = Any()
    private val flows = HashMap<Int, Flow>()
    private val byKey = HashMap<FlowKey, Int>()
    private var nextId = 0
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var connecting = false
    private var failures = 0
    private var retryAt = 0L
    private var everOpened = false

    @Volatile
    private var closed = false

    private val sessions = AtomicLong(0)
    private val datagramsUp = AtomicLong(0)
    private val datagramsDown = AtomicLong(0)

    /**
     * Sends one datagram for [owner] to [address]:[port]. False means it was
     * dropped (no session, bad input, write failure) and the caller should
     * account for it as undeliverable.
     */
    fun send(owner: Any, sink: Sink, address: ByteArray, port: Int, payload: ByteArray): Boolean {
        if (closed) return false
        if (address.size != 4 && address.size != 16) return false
        if (payload.size > UdpgwCodec.MAX_PAYLOAD) return false
        val out = synchronized(lock) { output } ?: connect() ?: return false
        synchronized(lock) {
            // The session changed between the read above and now.
            if (output !== out) return false
            val key = FlowKey(owner, String(address, Charsets.ISO_8859_1), port)
            val existing = byKey[key]
            val id: Int
            val flags: Int
            if (existing != null) {
                id = existing
                flags = 0
            } else {
                id = allocateLocked(Flow(key, sink, address.copyOf(), port)) ?: return false
                flags = UdpgwCodec.FLAG_REBIND
            }
            flows[id]?.lastUsed = System.currentTimeMillis()
            val frame = UdpgwCodec.encode(flags, id, address, port, payload) ?: return false
            return try {
                out.write(frame)
                out.flush()
                datagramsUp.incrementAndGet()
                true
            } catch (e: IOException) {
                dropLocked("write failed: ${e.message}")
                false
            }
        }
    }

    /** Forgets every flow of [owner]; called when its association ends. */
    fun release(owner: Any) {
        synchronized(lock) {
            val iterator = flows.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.key.owner === owner) {
                    byKey.remove(entry.value.key)
                    iterator.remove()
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            dropLocked("stopped")
            flows.clear()
            byKey.clear()
        }
    }

    fun summary(): String =
        "udpgw sessions=${sessions.get()} datagrams up=${datagramsUp.get()} down=${datagramsDown.get()}"

    // ------------------------------------------------------------- session

    private fun connect(): OutputStream? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            output?.let { return it }
            if (closed || connecting || now < retryAt) return null
            connecting = true
        }
        // Outside the lock on purpose: a dial can take the full dial timeout,
        // and other senders must drop, not queue, while it runs.
        val fresh = runCatching { dial() }.getOrNull()
        val streams = fresh?.let { s ->
            runCatching {
                // The reader parks on this socket for the whole session.
                s.soTimeout = 0
                s.tcpNoDelay = true
                s.getInputStream() to s.getOutputStream()
            }.getOrNull()
        }
        var opened = false
        synchronized(lock) {
            connecting = false
            if (fresh != null && streams != null && !closed) {
                socket = fresh
                output = streams.second
                failures = 0
                opened = true
                sessions.incrementAndGet()
                info(
                    if (everOpened) {
                        "udpgw session re-opened."
                    } else {
                        "udpgw session open: non-DNS UDP (QUIC, calls, games) is now carried " +
                            "through Psiphon instead of being dropped."
                    },
                )
                everOpened = true
            } else if (!closed) {
                failures++
                retryAt = System.currentTimeMillis() + backoffMs(failures)
                if (failures == 1 || failures % REPORT_EVERY == 0) {
                    warn(
                        "Could not open Psiphon's udpgw relay (attempt $failures) - non-DNS UDP " +
                            "is dropped until it opens.",
                    )
                }
            }
        }
        if (!opened || fresh == null || streams == null) {
            runCatching { fresh?.close() }
            return null
        }
        thread(name = "udpgw-read", isDaemon = true) { readLoop(fresh, streams.first) }
        thread(name = "udpgw-keepalive", isDaemon = true) { keepaliveLoop(fresh) }
        return streams.second
    }

    private fun readLoop(owned: Socket, input: InputStream) {
        val reason = try {
            while (!closed) {
                val message = UdpgwCodec.readMessage(input) ?: break
                if (message.isKeepalive) continue
                val flow = synchronized(lock) {
                    flows[message.connId]
                        ?.takeIf { it.port == message.port && it.address.contentEquals(message.address) }
                        ?.also { it.lastUsed = System.currentTimeMillis() }
                } ?: continue
                datagramsDown.incrementAndGet()
                runCatching { flow.sink.deliver(flow.address, flow.port, message.payload) }
            }
            "closed by the far end"
        } catch (e: Exception) {
            "read failed: ${e.message}"
        }
        synchronized(lock) {
            if (socket === owned) dropLocked(reason)
        }
    }

    private fun keepaliveLoop(owned: Socket) {
        val frame = UdpgwCodec.keepalive()
        while (!closed) {
            try {
                Thread.sleep(KEEPALIVE_MS)
            } catch (_: InterruptedException) {
                return
            }
            synchronized(lock) {
                if (socket !== owned) return
                val out = output ?: return
                try {
                    out.write(frame)
                    out.flush()
                } catch (e: IOException) {
                    dropLocked("keepalive failed: ${e.message}")
                    return
                }
            }
        }
    }

    /** Must hold [lock]. Every flow dies with the session: the server forgets them too. */
    private fun dropLocked(reason: String) {
        val had = socket ?: return
        runCatching { had.close() }
        socket = null
        output = null
        flows.clear()
        byKey.clear()
        retryAt = System.currentTimeMillis() + RECONNECT_DELAY_MS
        if (!closed) warn("udpgw session lost ($reason) - reconnecting on the next datagram.")
    }

    /** Must hold [lock]. Evicts the least recently used flow at [MAX_FLOWS]. */
    private fun allocateLocked(flow: Flow): Int? {
        if (flows.size >= MAX_FLOWS) {
            val oldest = flows.entries.minByOrNull { it.value.lastUsed }
            if (oldest != null) {
                flows.remove(oldest.key)
                byKey.remove(oldest.value.key)
            }
        }
        repeat(ID_SPACE) {
            val candidate = nextId
            nextId = (nextId + 1) and 0xFFFF
            if (!flows.containsKey(candidate)) {
                flows[candidate] = flow
                byKey[flow.key] = candidate
                return candidate
            }
        }
        return null
    }

    private fun backoffMs(attempt: Int): Long =
        minOf(MAX_BACKOFF_MS, 1_000L shl minOf(attempt - 1, 5))

    private companion object {
        const val TAG = "udpgw"
        const val MAX_FLOWS = 1_024
        const val ID_SPACE = 65_536
        const val KEEPALIVE_MS = 10_000L
        const val RECONNECT_DELAY_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val REPORT_EVERY = 10
    }
}
