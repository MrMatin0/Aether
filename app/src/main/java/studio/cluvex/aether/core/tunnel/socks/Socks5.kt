package studio.cluvex.aether.core.tunnel.socks

import studio.cluvex.aether.core.tunnel.net.Bytes
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * A minimal SOCKS5 client: greeting, CONNECT/ASSOCIATE requests, reply parsing
 * and the UDP request header.
 *
 * This is a pure protocol codec with no Android dependency, which is why it is
 * an `object` on its own — the same six methods were private members of the
 * bridge, making both untestable.
 */
internal object Socks5 {

    const val CMD_CONNECT = 1
    const val CMD_UDP_ASSOCIATE = 3

    private const val VERSION: Byte = 5
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4

    /**
     * Where a SOCKS reply says to send traffic.
     *
     * Modelled as a sealed type on purpose. The old code eagerly called
     * `InetAddress.getByName()` on a DOMAIN reply, which fired a BLOCKING,
     * OUT-OF-TUNNEL system DNS lookup on the session thread: a DNS leak and a
     * stall, to produce an address the CONNECT path then threw away anyway.
     * Now the caller decides what a hostname means.
     */
    sealed interface Bound {
        val port: Int

        data class Address(val address: InetAddress, override val port: Int) : Bound
        data class Hostname(val host: String, override val port: Int) : Bound
    }

    /** No-auth greeting. Returns false unless the proxy accepts method 0x00. */
    fun greet(ins: InputStream, out: OutputStream): Boolean {
        out.write(byteArrayOf(VERSION, 1, 0))
        out.flush()
        val response = ByteArray(2)
        if (!readExact(ins, response)) return false
        return response[0] == VERSION && response[1] == 0.toByte()
    }

    /** A CONNECT or UDP ASSOCIATE request. Prefers [domain] when one is known. */
    fun request(command: Int, domain: String?, address: ByteArray, port: Int): ByteArray =
        buildAddressed(
            prefix = byteArrayOf(VERSION, command.toByte(), 0),
            domain = domain,
            address = address,
            port = port
        )

    /** The 10+ byte header that prefixes every datagram sent to a UDP relay. */
    fun udpHeader(domain: String?, address: ByteArray, port: Int): ByteArray =
        buildAddressed(
            prefix = byteArrayOf(0, 0, 0),
            domain = domain,
            address = address,
            port = port
        )

    fun readReply(ins: InputStream): Bound? {
        val header = ByteArray(4)
        if (!readExact(ins, header)) return null
        if (header[0] != VERSION || header[1] != 0.toByte()) return null

        return when (header[3].toInt() and 0xFF) {
            ATYP_IPV4 -> {
                val b = ByteArray(6)
                if (!readExact(ins, b)) return null
                Bound.Address(InetAddress.getByAddress(b.copyOfRange(0, 4)), Bytes.short(b, 4))
            }
            ATYP_IPV6 -> {
                val b = ByteArray(18)
                if (!readExact(ins, b)) return null
                Bound.Address(InetAddress.getByAddress(b.copyOfRange(0, 16)), Bytes.short(b, 16))
            }
            ATYP_DOMAIN -> {
                val lenByte = ByteArray(1)
                if (!readExact(ins, lenByte)) return null
                val host = ByteArray(lenByte[0].toInt() and 0xFF)
                if (!readExact(ins, host)) return null
                val portBytes = ByteArray(2)
                if (!readExact(ins, portBytes)) return null
                Bound.Hostname(String(host), Bytes.short(portBytes, 0))
            }
            else -> null
        }
    }

    /** Parsed inbound relay datagram: who sent it, and the bare payload. */
    class Datagram(val source: Bound, val payload: ByteArray)

    fun parseDatagram(data: ByteArray, len: Int): Datagram? {
        if (len < 4) return null

        // FRAG != 0 marks a FRAGMENTED datagram. There is no reassembly here, so
        // one fragment's payload slice would be relayed as the whole datagram.
        if ((data[2].toInt() and 0xFF) != 0) return null

        val source: Bound
        val bodyStart: Int

        when (data[3].toInt() and 0xFF) {
            ATYP_IPV4 -> {
                if (len < 10) return null
                source = Bound.Address(
                    InetAddress.getByAddress(data.copyOfRange(4, 8)),
                    Bytes.short(data, 8)
                )
                bodyStart = 10
            }
            ATYP_IPV6 -> {
                if (len < 22) return null
                source = Bound.Address(
                    InetAddress.getByAddress(data.copyOfRange(4, 20)),
                    Bytes.short(data, 20)
                )
                bodyStart = 22
            }
            ATYP_DOMAIN -> {
                if (len < 5) return null
                val hostLen = data[4].toInt() and 0xFF
                if (len < 5 + hostLen + 2) return null
                source = Bound.Hostname(
                    String(data, 5, hostLen),
                    Bytes.short(data, 5 + hostLen)
                )
                bodyStart = 5 + hostLen + 2
            }
            else -> return null
        }

        if (len < bodyStart) return null
        return Datagram(source, data.copyOfRange(bodyStart, len))
    }

    fun readExact(ins: InputStream, into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val count = ins.read(into, offset, into.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    /**
     * Shared body of [request] and [udpHeader]: the two differ ONLY in their
     * 3-byte prefix, yet the original had two near-identical 20-line methods.
     */
    private fun buildAddressed(
        prefix: ByteArray,
        domain: String?,
        address: ByteArray,
        port: Int
    ): ByteArray {
        val addressBytes: ByteArray
        val atyp: Int

        if (domain != null) {
            val encoded = domain.toByteArray()
            // The length is a single byte, so a longer name cannot be encoded.
            if (encoded.size > 255) return buildAddressed(prefix, null, address, port)
            addressBytes = ByteArray(1 + encoded.size).also {
                it[0] = encoded.size.toByte()
                System.arraycopy(encoded, 0, it, 1, encoded.size)
            }
            atyp = ATYP_DOMAIN
        } else {
            addressBytes = address
            atyp = if (address.size == 4) ATYP_IPV4 else ATYP_IPV6
        }

        val out = ByteArray(prefix.size + 1 + addressBytes.size + 2)
        System.arraycopy(prefix, 0, out, 0, prefix.size)
        out[prefix.size] = atyp.toByte()
        System.arraycopy(addressBytes, 0, out, prefix.size + 1, addressBytes.size)
        Bytes.setShort(out, prefix.size + 1 + addressBytes.size, port)
        return out
    }
}