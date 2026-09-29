package studio.cluvex.aether.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The SOCKS5 (RFC 1928) bytes the Smart DNS front and its resolver speak, in
 * one place. Pure JVM, no Android. Deliberately small: no authentication (every
 * listener involved is loopback-only), IPv4 and DOMAIN addressing, CONNECT and
 * UDP ASSOCIATE.
 */
internal object Socks5Wire {

    const val VERSION: Byte = 5
    const val CMD_CONNECT = 1
    const val CMD_UDP_ASSOCIATE = 3
    const val ATYP_IPV4 = 1
    const val ATYP_DOMAIN = 3
    const val ATYP_IPV6 = 4

    const val REP_SUCCEEDED: Byte = 0
    const val REP_GENERAL_FAILURE: Byte = 1
    const val REP_HOST_UNREACHABLE: Byte = 4
    const val REP_CONNECTION_REFUSED: Byte = 5
    const val REP_COMMAND_NOT_SUPPORTED: Byte = 7

    /**
     * The four bytes of a dotted-quad IPv4 literal, or null for anything else.
     * A pure string check: it never calls InetAddress, so it can never trigger
     * a name lookup.
     */
    fun ipv4Bytes(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    fun ipv4Text(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

    fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun portBytes(port: Int): ByteArray = byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())

    /** Exactly [size] bytes, or null on EOF / error. */
    fun readExact(input: InputStream, size: Int): ByteArray? {
        if (size < 0) return null
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val read = try {
                input.read(out, done, size - done)
            } catch (_: Exception) {
                return null
            }
            if (read < 0) return null
            done += read
        }
        return out
    }

    /** DST.ADDR for [atyp]: 4 or 16 bytes, or LEN + name for DOMAIN. */
    fun readAddress(input: InputStream, atyp: Int): ByteArray? = when (atyp) {
        ATYP_IPV4 -> readExact(input, 4)
        ATYP_IPV6 -> readExact(input, 16)
        ATYP_DOMAIN -> {
            val lengthByte = readExact(input, 1)
            if (lengthByte == null) {
                null
            } else {
                val host = readExact(input, lengthByte[0].toInt() and 0xFF)
                if (host == null) null else lengthByte + host
            }
        }
        else -> null
    }

    /** Server side of the greeting: accept "no authentication". */
    fun acceptGreeting(input: InputStream, output: OutputStream): Boolean {
        val head = readExact(input, 2) ?: return false
        if (head[0] != VERSION) return false
        val count = head[1].toInt() and 0xFF
        if (count > 0 && readExact(input, count) == null) return false
        output.write(byteArrayOf(VERSION, 0))
        output.flush()
        return true
    }

    /** Client side of the greeting; true when the proxy accepted "no authentication". */
    fun greet(input: InputStream, output: OutputStream): Boolean {
        output.write(byteArrayOf(VERSION, 1, 0))
        output.flush()
        val reply = readExact(input, 2) ?: return false
        return reply[0] == VERSION && reply[1] == 0.toByte()
    }

    /**
     * Reads a reply; returns BND.ADDR (raw, as [readAddress] returns it) and
     * BND.PORT on success, null on any failure code or truncation.
     */
    fun readReply(input: InputStream): Pair<ByteArray, Int>? {
        val head = readExact(input, 4) ?: return null
        if (head[0] != VERSION || head[1] != REP_SUCCEEDED) return null
        val address = readAddress(input, head[3].toInt() and 0xFF) ?: return null
        val port = readExact(input, 2) ?: return null
        return address to u16(port, 0)
    }

    /**
     * A request for [host]:[port]. An IPv4 literal is sent as ATYP=IPV4,
     * anything else as ATYP=DOMAIN for the proxy to resolve.
     */
    fun request(command: Int, host: String, port: Int): ByteArray {
        ipv4Bytes(host)?.let { return requestIpv4(command, it, port) }
        val name = host.toByteArray(Charsets.US_ASCII)
        if (name.isEmpty() || name.size > 255) throw IllegalArgumentException("bad SOCKS5 host name")
        val out = ByteArray(5 + name.size + 2)
        out[0] = VERSION
        out[1] = command.toByte()
        out[2] = 0
        out[3] = ATYP_DOMAIN.toByte()
        out[4] = name.size.toByte()
        System.arraycopy(name, 0, out, 5, name.size)
        out[out.size - 2] = ((port shr 8) and 0xFF).toByte()
        out[out.size - 1] = (port and 0xFF).toByte()
        return out
    }

    fun requestIpv4(command: Int, address: ByteArray, port: Int): ByteArray {
        val out = ByteArray(10)
        out[0] = VERSION
        out[1] = command.toByte()
        out[2] = 0
        out[3] = ATYP_IPV4.toByte()
        System.arraycopy(address, 0, out, 4, 4)
        out[8] = ((port shr 8) and 0xFF).toByte()
        out[9] = (port and 0xFF).toByte()
        return out
    }

    /** A reply with an all-zero IPv4 bound address. */
    fun reply(code: Byte): ByteArray = byteArrayOf(VERSION, code, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

    /** A reply naming [address]:[port] as the bound address. */
    fun replyBound(code: Byte, address: ByteArray, port: Int): ByteArray {
        val out = reply(code)
        System.arraycopy(address, 0, out, 4, 4)
        out[8] = ((port shr 8) and 0xFF).toByte()
        out[9] = (port and 0xFF).toByte()
        return out
    }

    /**
     * A TCP connection to [host]:[port] THROUGH the SOCKS5 proxy at
     * [proxyHost]:[proxyPort], handshake done, or null when any step failed.
     */
    fun dial(
        proxyHost: String,
        proxyPort: Int,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Socket? {
        val socket = Socket()
        val ready = runCatching {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            if (!greet(input, output)) throw IOException("SOCKS5 greeting refused")
            output.write(request(CMD_CONNECT, host, port))
            output.flush()
            readReply(input) ?: throw IOException("SOCKS5 connect refused")
        }.isSuccess
        if (ready) return socket
        runCatching { socket.close() }
        return null
    }

    /**
     * One SOCKS5 UDP datagram. [address] is 4 or 16 bytes, or the bare name for
     * ATYP=DOMAIN. [header] is everything before the payload, which is what a
     * reply to this datagram must carry back.
     */
    class UdpDatagram(
        val header: ByteArray,
        val atyp: Int,
        val address: ByteArray,
        val port: Int,
        val payload: ByteArray,
    )

    /**
     * Splits a SOCKS5 UDP datagram. Fragments (FRAG != 0) are rejected: there
     * is no reassembly, and one fragment forwarded as a whole would be garbage.
     */
    fun parseUdp(data: ByteArray, length: Int): UdpDatagram? {
        if (length < 10 || length > data.size) return null
        if ((data[2].toInt() and 0xFF) != 0) return null
        val atyp = data[3].toInt() and 0xFF
        val bodyStart = when (atyp) {
            ATYP_IPV4 -> 10
            ATYP_IPV6 -> 22
            ATYP_DOMAIN -> 5 + (data[4].toInt() and 0xFF) + 2
            else -> return null
        }
        if (length < bodyStart) return null
        val address = when (atyp) {
            ATYP_IPV4 -> data.copyOfRange(4, 8)
            ATYP_IPV6 -> data.copyOfRange(4, 20)
            else -> data.copyOfRange(5, bodyStart - 2)
        }
        return UdpDatagram(
            header = data.copyOf(bodyStart),
            atyp = atyp,
            address = address,
            port = u16(data, bodyStart - 2),
            payload = data.copyOfRange(bodyStart, length),
        )
    }

    /** RSV RSV FRAG ATYP=IPV4 ADDR PORT, for [address] (4 bytes). */
    fun udpHeader(address: ByteArray, port: Int): ByteArray {
        val out = ByteArray(10)
        out[3] = ATYP_IPV4.toByte()
        System.arraycopy(address, 0, out, 4, 4)
        out[8] = ((port shr 8) and 0xFF).toByte()
        out[9] = (port and 0xFF).toByte()
        return out
    }

    /**
     * Where to send datagrams for an association: the relay the proxy named,
     * or [fallbackHost] (a literal) when it named the unspecified address or a
     * domain, which is common for a loopback proxy.
     */
    fun relayAddress(bound: ByteArray, fallbackHost: String): InetAddress {
        val usable = (bound.size == 4 || bound.size == 16) && bound.any { it != 0.toByte() }
        return if (usable) InetAddress.getByAddress(bound) else InetAddress.getByName(fallbackHost)
    }
}
