package studio.cluvex.aether.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RFC-agnostic Psiphon udpgw framing used over the tunnel's SSH channel. */
internal object PsiphonUdpgw {
    const val FLAG_REBIND = 1 shl 1
    const val FLAG_DNS = 1 shl 2
    const val FLAG_IPV6 = 1 shl 3
    const val MAX_PAYLOAD = 32 * 1024

    data class Packet(
        val connectionId: Int,
        val address: InetAddress,
        val port: Int,
        val payload: ByteArray,
        val rebind: Boolean = false,
        val dns: Boolean = false,
    )

    fun encode(packet: Packet): ByteArray {
        require(packet.connectionId in 0..65535)
        require(packet.port in 1..65535)
        require(packet.payload.size <= MAX_PAYLOAD)
        val ip = packet.address.address
        require(ip.size == 4 || ip.size == 16)
        var flags = 0
        if (packet.rebind) flags = flags or FLAG_REBIND
        if (packet.dns) flags = flags or FLAG_DNS
        if (ip.size == 16) flags = flags or FLAG_IPV6
        val preamble = 7 + ip.size
        val size = preamble - 2 + packet.payload.size
        require(size <= 65535)
        val out = ByteBuffer.allocate(2 + size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(size.toShort())
        out.put(flags.toByte())
        out.putShort(packet.connectionId.toShort())
        out.put(ip)
        out.order(ByteOrder.BIG_ENDIAN).putShort(packet.port.toShort())
        out.put(packet.payload)
        return out.array()
    }

    fun decode(input: DataInputStream): Packet? {
        val size = try { input.readUnsignedShortLE() } catch (_: IOException) { return null }
        if (size < 9) return null
        val body = ByteArray(size)
        input.readFully(body)
        val flags = body[0].toInt() and 0xff
        if ((flags and 1) != 0) return decode(input)
        val ipv6 = (flags and FLAG_IPV6) != 0
        val addressSize = if (ipv6) 16 else 4
        if (size < 7 + addressSize) return null
        val address = InetAddress.getByAddress(body.copyOfRange(5, 5 + addressSize))
        val portOffset = 5 + addressSize
        val port = ((body[portOffset].toInt() and 0xff) shl 8) or
            (body[portOffset + 1].toInt() and 0xff)
        return Packet(
            connectionId = ((body[3].toInt() and 0xff) shl 8) or (body[4].toInt() and 0xff),
            address = address,
            port = port,
            payload = body.copyOfRange(portOffset + 2, body.size),
            rebind = (flags and FLAG_REBIND) != 0,
            dns = (flags and FLAG_DNS) != 0,
        )
    }

    private fun DataInputStream.readUnsignedShortLE(): Int {
        val lo = readUnsignedByte()
        val hi = readUnsignedByte()
        return lo or (hi shl 8)
    }
}

/** Opens the Psiphon udpgw channel through its localhost SOCKS endpoint. */
internal class PsiphonUdpgwChannel(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val gatewayPort: Int = 7300,
) : AutoCloseable {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    @Synchronized
    fun open() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(upstreamHost, upstreamPort), 10_000)
        s.soTimeout = 30_000
        val din = DataInputStream(s.getInputStream())
        val dout = DataOutputStream(s.getOutputStream())
        dout.write(byteArrayOf(5, 1, 0))
        dout.flush()
        require(din.readUnsignedByte() == 5 && din.readUnsignedByte() == 0)
        dout.write(socksConnect(gatewayPort))
        dout.flush()
        require(din.readUnsignedByte() == 5 && din.readUnsignedByte() == 0)
        skipSocksAddress(din)
        socket = s
        input = din
        output = dout
    }

    @Synchronized
    fun send(packet: PsiphonUdpgw.Packet) {
        open()
        output!!.write(PsiphonUdpgw.encode(packet))
        output!!.flush()
    }

    @Synchronized
    fun receive(): PsiphonUdpgw.Packet? = input?.let { PsiphonUdpgw.decode(it) }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun socksConnect(port: Int): ByteArray = byteArrayOf(
        5, 1, 0, 1, 127, 0, 0, 1,
        (port shr 8).toByte(), port.toByte(),
    )

    private fun skipSocksAddress(input: DataInputStream) {
        when (input.readUnsignedByte()) {
            1 -> input.skipBytes(4)
            4 -> input.skipBytes(16)
            3 -> input.skipBytes(input.readUnsignedByte())
            else -> error("invalid SOCKS5 address")
        }
        input.skipBytes(2)
    }
}
