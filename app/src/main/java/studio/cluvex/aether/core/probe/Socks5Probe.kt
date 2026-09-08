package studio.cluvex.aether.core.probe

import studio.cluvex.aether.core.Hostname
import studio.cluvex.aether.core.tunnel.socks.Socks5
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The SOCKS5 client the probes dial through.
 *
 * WHY IT SENDS A DOMAIN: with ATYP = 0x03 the destination travels as a NAME, so
 * DNS is resolved REMOTELY by the engine - exactly what a real browser tab does
 * through the tunnel. That makes a geolocation probe a true end-to-end test of
 * DNS + TCP + the proxy path in one shot.
 *
 * The greeting and framing come from [Socks5], the codec the userspace tunnel
 * already uses, instead of a second hand-rolled copy. What stays local is the
 * REPLY handling: this path deliberately surfaces the rep= code, because
 * Diagnostics reads "rejected (rep=1)" as the engine's inner tunnel still being
 * cold rather than as a failure.
 */
internal object Socks5Probe {

    private const val VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val RSV = 0x00
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04
    private const val PORT_BYTES = 2

    /** SOCKS5 greeting only - proves the engine speaks SOCKS5 at all. */
    fun handshakeOnly(socksHost: String, socksPort: Int, timeoutMs: Int): Boolean =
        runCatching {
            dial(socksHost, socksPort, timeoutMs).use {
                Socks5.greet(it.getInputStream(), it.getOutputStream())
            }
        }.getOrDefault(false)

    /**
     * Full CONNECT to an IP literal - proves the proxy can open an outbound TCP
     * connection, with no DNS involved.
     */
    fun canReachIpv4(
        socksHost: String,
        socksPort: Int,
        destIp: String,
        destPort: Int,
        timeoutMs: Int,
    ): Boolean = runCatching {
        ipv4Octets(destIp) // reject a malformed literal before dialling anything
        connect(socksHost, socksPort, destIp, destPort, useDomain = false, timeoutMs = timeoutMs)
            .use { true }
    }.getOrDefault(false)

    /**
     * Opens a TCP connection to [destHost]:[destPort] THROUGH the proxy and
     * returns the live socket (the caller closes it). Throws on any failure.
     */
    fun connect(
        socksHost: String,
        socksPort: Int,
        destHost: String,
        destPort: Int,
        useDomain: Boolean,
        timeoutMs: Int,
    ): Socket {
        val socket = dial(socksHost, socksPort, timeoutMs)
        try {
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            if (!Socks5.greet(input, out)) throw IOException("SOCKS5 auth negotiation failed")
            out.write(connectRequest(destHost, destPort, useDomain))
            out.flush()
            consumeReply(input)
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Validates an IPv4 literal for the ATYP = 0x01 address form and returns
     * its four octets.
     *
     * Every octet must be numeric and in range: a silent `and 0xFF` truncation
     * would quietly redirect a probe meant for 1.1.1.999 at 1.1.1.231 and then
     * report a PASS against the wrong host.
     */
    fun ipv4Octets(host: String): IntArray {
        val parts = host.split('.')
        if (parts.size != 4) throw IOException("bad IPv4 literal: $host")
        return IntArray(4) { index ->
            val octet = parts[index]
            val value = octet.toIntOrNull()
                ?: throw IOException("bad IPv4 literal: $host (non-numeric octet '$octet')")
            if (value < 0 || value > 255) {
                throw IOException("bad IPv4 literal: $host (octet out of range: $value)")
            }
            value
        }
    }

    /**
     * Connects to the proxy itself.
     *
     * FD-LEAK FIX: the old helper built the socket, connected inline and let a
     * failed connect propagate with the socket still open, so every refused
     * probe leaked a descriptor - on a path that runs on every reconnect and
     * every IP refresh.
     */
    private fun dial(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun connectRequest(destHost: String, destPort: Int, useDomain: Boolean): ByteArray {
        val request = ByteArrayOutputStream(32)
        request.write(VERSION)
        request.write(CMD_CONNECT)
        request.write(RSV)
        if (useDomain) {
            val host = destHost.toByteArray(Charsets.US_ASCII)
            // LENGTH-FIELD GUARD: ATYP = 0x03 encodes the host length in ONE
            // byte, and write(int) keeps only the low 8 bits. A 256+ byte host
            // therefore wrote a truncated length, and the proxy read a short
            // domain followed by trailing garbage where it expected the port -
            // reported back as a plain "CONNECT rejected", which is a lie about
            // whose fault it was.
            if (host.isEmpty() || host.size > Hostname.MAX_WIRE_LENGTH) {
                throw IOException("SOCKS5 domain not representable: ${host.size} bytes")
            }
            request.write(ATYP_DOMAIN)
            request.write(host.size)
            request.write(host)
        } else {
            request.write(ATYP_IPV4)
            ipv4Octets(destHost).forEach { request.write(it) }
        }
        request.write((destPort ushr 8) and 0xFF)
        request.write(destPort and 0xFF)
        return request.toByteArray()
    }

    /** Reads VER, REP, RSV, ATYP and then discards BND.ADDR + BND.PORT. */
    private fun consumeReply(input: DataInputStream) {
        val head = ByteArray(4)
        input.readFully(head)
        val version = head[0].toInt() and 0xFF
        if (version != VERSION) throw IOException("SOCKS5 bad reply version ($version)")
        val rep = head[1].toInt() and 0xFF
        if (rep != 0x00) throw IOException("SOCKS5 CONNECT rejected (rep=$rep)")
        val addressBytes = when (head[3].toInt() and 0xFF) {
            ATYP_IPV4 -> 4
            ATYP_IPV6 -> 16
            ATYP_DOMAIN -> input.readUnsignedByte()
            else -> throw IOException("SOCKS5 bad ATYP in reply")
        }
        input.readFully(ByteArray(addressBytes + PORT_BYTES))
    }
}
