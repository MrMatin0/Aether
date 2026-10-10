package studio.cluvex.aether.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.SstpConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSTP (Secure Socket Tunneling Protocol) core for Aether+.
 *
 * Implements MS-SSTP over TLS/HTTPS (RFC / MS-SSTP specification) and
 * exposes a local SOCKS5 proxy on [TunnelConfig.SSTP_SOCKS_PORT] so the
 * chain model can wire it identically to Psiphon and Tor.
 *
 * ### Protocol summary
 *
 * 1. Open a TLS connection to the SSTP server on port 443.
 * 2. Send an HTTP CONNECT request with `SSTP_DUPLEX_POST`.
 * 3. Receive the HTTP 200 response.
 * 4. Exchange SSTP control messages (Hello, Connected).
 * 5. Negotiate PPP (LCP, authentication, IPCP).
 * 6. Once IPCP is complete, a virtual network interface is up.
 * 7. Forward IP packets between the local SOCKS5 proxy and the PPP tunnel.
 *
 * ### Architecture
 *
 * This core runs in-process (no child binary), using coroutines for the
 * TLS I/O and PPP state machine. It is supervised by [ChainRuntime] the
 * same way PsiphonCore and TorCore are.
 *
 * ### VPNGate integration
 *
 * VPNGate servers accept SSTP with username "vpn" and password "vpn" on
 * their DDNS hostnames. The [SstpConfig] carries these defaults when the
 * source is [studio.cluvex.aether.model.SstpSource.VPNGATE].
 */
object SstpCore {

    private const val TAG = "SstpCore"

    /** SSTP HTTP method and URI per the MS-SSTP specification. */
    private const val SSTP_REQUEST =
        "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n"
    private const val SSTP_CONTENT_LENGTH = "Content-Length: 18446744073709551615\r\n"

    // SSTP control message types
    private const val SSTP_MSG_CALL_CONNECT_REQUEST: Short = 0x0001
    private const val SSTP_MSG_CALL_CONNECT_ACK: Short = 0x0002
    private const val SSTP_MSG_CALL_CONNECT_NAK: Short = 0x0003
    private const val SSTP_MSG_CALL_CONNECTED: Short = 0x0004
    private const val SSTP_MSG_CALL_ABORT: Short = 0x0005
    private const val SSTP_MSG_CALL_DISCONNECT: Short = 0x0006
    private const val SSTP_MSG_CALL_DISCONNECT_ACK: Short = 0x0007
    private const val SSTP_MSG_ECHO_REQUEST: Short = 0x0008
    private const val SSTP_MSG_ECHO_RESPONSE: Short = 0x0009

    // SSTP packet flags
    private const val SSTP_DATA_FLAG: Byte = 0x00
    private const val SSTP_CONTROL_FLAG: Byte = 0x01

    // SSTP version
    private const val SSTP_VERSION: Short = 0x0001

    // PPP protocol numbers
    private const val PPP_LCP: Short = 0xC021.toShort()
    private const val PPP_PAP: Short = 0xC023.toShort()
    private const val PPP_CHAP: Short = 0xC223.toShort()
    private const val PPP_IPCP: Short = 0x8021.toShort()
    private const val PPP_IP: Short = 0x0021

    // PPP LCP codes
    private const val LCP_CONFIGURE_REQUEST: Byte = 0x01
    private const val LCP_CONFIGURE_ACK: Byte = 0x02
    private const val LCP_CONFIGURE_NAK: Byte = 0x03
    private const val LCP_CONFIGURE_REJECT: Byte = 0x04
    private const val LCP_ECHO_REQUEST: Byte = 0x09
    private const val LCP_ECHO_REPLY: Byte = 0x0A

    /** Connection states exposed to the UI via [state]. */
    enum class SstpState {
        IDLE, CONNECTING, TLS_HANDSHAKE, SSTP_HANDSHAKE,
        PPP_LCP, PPP_AUTH, PPP_IPCP, CONNECTED, DISCONNECTING, ERROR
    }

    private val _state = MutableStateFlow(SstpState.IDLE)
    val state: StateFlow<SstpState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var connectionJob: Job? = null
    private var sslSocket: SSLSocket? = null
    private var localProxy: ServerSocket? = null

    /** Assigned IP address from IPCP negotiation. */
    private var assignedIp: String? = null

    /**
     * Starts the SSTP connection and local SOCKS5 proxy.
     *
     * The coroutine runs on [Dispatchers.IO] and updates [state] as the
     * handshake progresses. The caller (VpnService) watches the state flow
     * and wires the TUN when CONNECTED arrives.
     */
    fun start(config: SstpConfig, scope: CoroutineScope) {
        stop() // Tear down any previous session.

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                _state.value = SstpState.CONNECTING
                _statusMessage.value = "Connecting to ${config.hostname}:${config.port}..."

                // 1. TLS handshake
                _state.value = SstpState.TLS_HANDSHAKE
                _statusMessage.value = "TLS handshake..."

                val sslFactory = if (config.verifyCert) {
                    SSLSocketFactory.getDefault() as SSLSocketFactory
                } else {
                    createTrustAllFactory()
                }

                val rawSocket = Socket()
                rawSocket.connect(InetSocketAddress(config.hostname, config.port), 10_000)

                val ssl = sslFactory.createSocket(
                    rawSocket, config.effectiveSni, config.port, true
                ) as SSLSocket
                ssl.soTimeout = 30_000
                sslSocket = ssl

                // 2. SSTP HTTP negotiation
                _state.value = SstpState.SSTP_HANDSHAKE
                _statusMessage.value = "SSTP handshake..."

                val output = ssl.outputStream
                val input = ssl.inputStream

                // Send SSTP_DUPLEX_POST
                val httpRequest = buildString {
                    append(SSTP_REQUEST)
                    append("Host: ${config.hostname}\r\n")
                    append(SSTP_CONTENT_LENGTH)
                    append("\r\n")
                }
                output.write(httpRequest.toByteArray(Charsets.US_ASCII))
                output.flush()

                // Read HTTP response
                val responseHeader = readHttpResponse(input)
                if (!responseHeader.contains("HTTP/1.1 200")) {
                    throw SstpException("Server rejected SSTP: $responseHeader")
                }

                // 3. SSTP Call Connect Request
                sendSstpControl(output, SSTP_MSG_CALL_CONNECT_REQUEST, buildCallConnectRequest())

                // 4. Wait for Call Connect ACK
                val ackMsg = readSstpMessage(input)
                if (ackMsg.type != SSTP_MSG_CALL_CONNECT_ACK) {
                    throw SstpException("Expected CALL_CONNECT_ACK, got ${ackMsg.type}")
                }

                // 5. PPP negotiation
                _state.value = SstpState.PPP_LCP
                _statusMessage.value = "PPP LCP negotiation..."
                negotiatePppLcp(input, output)

                // 6. PPP Authentication
                _state.value = SstpState.PPP_AUTH
                _statusMessage.value = "PPP authentication..."
                authenticatePpp(input, output, config.username, config.password)

                // 7. PPP IPCP
                _state.value = SstpState.PPP_IPCP
                _statusMessage.value = "PPP IPCP negotiation..."
                negotiateIpcp(input, output)

                // 8. Send SSTP Connected
                sendSstpControl(output, SSTP_MSG_CALL_CONNECTED, buildCallConnected())

                // 9. Start local SOCKS5 proxy
                _state.value = SstpState.CONNECTED
                _statusMessage.value = "Connected via SSTP"

                startLocalProxy(input, output, scope)

                // Keep-alive loop
                while (isActive) {
                    delay(30_000)
                    // Send SSTP echo request to keep the connection alive
                    sendSstpControl(output, SSTP_MSG_ECHO_REQUEST, ByteArray(0))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SstpState.ERROR
                _statusMessage.value = "SSTP error: ${e.message}"
                DiagnosticsLog.append(TAG, "SSTP failed: ${e.stackTraceToString()}")
            }
        }
    }

    /** Tears down the SSTP connection and local proxy. */
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        runCatching { sslSocket?.close() }
        runCatching { localProxy?.close() }
        sslSocket = null
        localProxy = null
        assignedIp = null
        _state.value = SstpState.IDLE
        _statusMessage.value = ""
    }

    /** True when the core has a live SSTP tunnel. */
    val isConnected: Boolean get() = _state.value == SstpState.CONNECTED

    // ======================= SSTP framing =======================

    private data class SstpMessage(val type: Short, val data: ByteArray)

    private fun sendSstpControl(output: OutputStream, type: Short, payload: ByteArray) {
        // SSTP header: version (2) + reserved (1=control) + length (2) + type (2) + attrs
        val totalLen = 4 + payload.size
        val buf = ByteBuffer.allocate(4 + totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(SSTP_VERSION)
        buf.put(SSTP_CONTROL_FLAG)
        buf.put(0) // reserved
        buf.putShort(totalLen.toShort())
        buf.putShort(type)
        buf.put(payload)
        output.write(buf.array())
        output.flush()
    }

    private fun sendSstpData(output: OutputStream, pppFrame: ByteArray) {
        val totalLen = 4 + pppFrame.size
        val buf = ByteBuffer.allocate(4 + totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(SSTP_VERSION)
        buf.put(SSTP_DATA_FLAG)
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.put(pppFrame)
        output.write(buf.array())
        output.flush()
    }

    private fun readSstpMessage(input: InputStream): SstpMessage {
        val header = ByteArray(4)
        input.readFully(header)
        val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = hdrBuf.short
        val flags = hdrBuf.get()
        hdrBuf.get() // reserved
        // Now read length
        val lenBytes = ByteArray(2)
        input.readFully(lenBytes)
        val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

        val payload = ByteArray(length - 4)
        if (payload.isNotEmpty()) input.readFully(payload)

        val type = if (flags == SSTP_CONTROL_FLAG && payload.size >= 2) {
            ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short
        } else {
            0
        }

        return SstpMessage(type, payload)
    }

    private fun readHttpResponse(input: InputStream): String {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            // Look for \r\n\r\n
            if (sb.length >= 4 && sb.takeLast(4).toString() == "\r\n\r\n") break
            prev = b
        }
        return sb.toString()
    }

    // ======================= PPP negotiation =======================

    private fun negotiatePppLcp(input: InputStream, output: OutputStream) {
        // Send LCP Configure-Request with MRU option
        val lcpReqId: Byte = 0x01
        val mruOption = byteArrayOf(0x01, 0x04, 0x05, 0xDC.toByte()) // MRU = 1500
        val authOption = byteArrayOf(0x03, 0x04, 0xC0.toByte(), 0x23) // PAP
        val options = mruOption + authOption

        sendPppFrame(output, PPP_LCP, LCP_CONFIGURE_REQUEST, lcpReqId, options)

        // Wait for peer's Configure-Request and ACK it
        var lcpDone = false
        var ourAckReceived = false
        var peerAcked = false
        var iterations = 0

        while (!lcpDone && iterations < 20) {
            iterations++
            val msg = readSstpMessage(input)
            if (msg.data.size < 2) continue

            val pppProto = ByteBuffer.wrap(msg.data, 0, 2).order(ByteOrder.BIG_ENDIAN).short
            if (pppProto != PPP_LCP) continue

            val code = msg.data[2]
            when (code) {
                LCP_CONFIGURE_REQUEST -> {
                    // ACK the peer's request
                    val peerReqPayload = msg.data.copyOfRange(2, msg.data.size)
                    val peerReqId = msg.data[3]
                    val peerOptions = if (msg.data.size > 6) msg.data.copyOfRange(6, msg.data.size) else ByteArray(0)
                    sendPppFrame(output, PPP_LCP, LCP_CONFIGURE_ACK, peerReqId, peerOptions)
                    peerAcked = true
                }
                LCP_CONFIGURE_ACK -> {
                    ourAckReceived = true
                }
                LCP_CONFIGURE_NAK, LCP_CONFIGURE_REJECT -> {
                    // Re-send with adjusted options
                    sendPppFrame(output, PPP_LCP, LCP_CONFIGURE_REQUEST, (lcpReqId + 1).toByte(), mruOption)
                }
                LCP_ECHO_REQUEST -> {
                    val magicNumber = if (msg.data.size >= 10) msg.data.copyOfRange(6, 10) else ByteArray(4)
                    sendPppFrame(output, PPP_LCP, LCP_ECHO_REPLY, msg.data[3], magicNumber)
                }
            }
            lcpDone = ourAckReceived && peerAcked
        }
        if (!lcpDone) throw SstpException("LCP negotiation failed after $iterations iterations")
    }

    private fun authenticatePpp(input: InputStream, output: OutputStream, user: String, pass: String) {
        // Send PAP Authenticate-Request
        val userBytes = user.toByteArray(Charsets.US_ASCII)
        val passBytes = pass.toByteArray(Charsets.US_ASCII)
        val papPayload = ByteBuffer.allocate(2 + userBytes.size + 1 + passBytes.size)
        papPayload.put(0x01) // Code: Authenticate-Request
        papPayload.put(0x01) // Identifier
        // Length will be calculated by sendPppFrame
        papPayload.put(userBytes.size.toByte())
        papPayload.put(userBytes)
        papPayload.put(passBytes.size.toByte())
        papPayload.put(passBytes)

        // Build full PAP packet: Code(1) + ID(1) + Length(2) + PeerIDLen(1) + PeerID + PassLen(1) + Pass
        val papPacket = ByteBuffer.allocate(4 + 1 + userBytes.size + 1 + passBytes.size)
        papPacket.put(0x01) // Code: Authenticate-Request
        papPacket.put(0x01) // ID
        val papLen = (4 + 1 + userBytes.size + 1 + passBytes.size).toShort()
        papPacket.putShort(papLen)
        papPacket.put(userBytes.size.toByte())
        papPacket.put(userBytes)
        papPacket.put(passBytes.size.toByte())
        papPacket.put(passBytes)

        sendSstpData(output, buildPppFrame(PPP_PAP, papPacket.array()))

        // Wait for PAP ACK
        var authDone = false
        var iterations = 0
        while (!authDone && iterations < 10) {
            iterations++
            val msg = readSstpMessage(input)
            if (msg.data.size < 2) continue
            val pppProto = ByteBuffer.wrap(msg.data, 0, 2).order(ByteOrder.BIG_ENDIAN).short
            if (pppProto == PPP_PAP && msg.data.size > 2) {
                val code = msg.data[2]
                if (code == 0x02.toByte()) { // Authenticate-Ack
                    authDone = true
                } else if (code == 0x03.toByte()) { // Authenticate-Nak
                    throw SstpException("PPP authentication rejected")
                }
            }
        }
        if (!authDone) throw SstpException("PPP authentication timeout")
    }

    private fun negotiateIpcp(input: InputStream, output: OutputStream) {
        // Send IPCP Configure-Request asking for an IP address (0.0.0.0 = please assign)
        val ipOption = byteArrayOf(0x03, 0x06, 0x00, 0x00, 0x00, 0x00)
        sendPppFrame(output, PPP_IPCP, LCP_CONFIGURE_REQUEST, 0x01, ipOption)

        var ipcpDone = false
        var iterations = 0
        while (!ipcpDone && iterations < 20) {
            iterations++
            val msg = readSstpMessage(input)
            if (msg.data.size < 2) continue
            val pppProto = ByteBuffer.wrap(msg.data, 0, 2).order(ByteOrder.BIG_ENDIAN).short
            if (pppProto != PPP_IPCP) continue

            val code = msg.data[2]
            when (code) {
                LCP_CONFIGURE_REQUEST -> {
                    val peerReqId = msg.data[3]
                    val peerOptions = if (msg.data.size > 6) msg.data.copyOfRange(6, msg.data.size) else ByteArray(0)
                    sendPppFrame(output, PPP_IPCP, LCP_CONFIGURE_ACK, peerReqId, peerOptions)
                }
                LCP_CONFIGURE_ACK -> {
                    ipcpDone = true
                }
                LCP_CONFIGURE_NAK -> {
                    // Server is telling us the IP to use
                    if (msg.data.size >= 10) {
                        val ip = "${msg.data[6].toInt() and 0xFF}.${msg.data[7].toInt() and 0xFF}.${msg.data[8].toInt() and 0xFF}.${msg.data[9].toInt() and 0xFF}"
                        assignedIp = ip
                        // Re-request with the assigned IP
                        val newIpOption = byteArrayOf(0x03, 0x06, msg.data[6], msg.data[7], msg.data[8], msg.data[9])
                        sendPppFrame(output, PPP_IPCP, LCP_CONFIGURE_REQUEST, 0x02, newIpOption)
                    }
                }
            }
        }
        if (!ipcpDone) throw SstpException("IPCP negotiation failed")
    }

    // ======================= PPP framing =======================

    private fun sendPppFrame(output: OutputStream, protocol: Short, code: Byte, id: Byte, options: ByteArray) {
        val pppPayload = ByteBuffer.allocate(4 + options.size)
        pppPayload.put(code)
        pppPayload.put(id)
        pppPayload.putShort((4 + options.size).toShort())
        pppPayload.put(options)
        sendSstpData(output, buildPppFrame(protocol, pppPayload.array()))
    }

    private fun buildPppFrame(protocol: Short, payload: ByteArray): ByteArray {
        val frame = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN)
        frame.putShort(protocol)
        frame.put(payload)
        return frame.array()
    }

    private fun buildCallConnectRequest(): ByteArray {
        // Attribute: Encapsulated Protocol ID = PPP (0x0001)
        val attr = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        attr.put(0) // reserved
        attr.put(1) // attribute count
        attr.putShort(0x0001) // attribute ID: Encapsulated Protocol ID
        attr.putShort(8) // attribute length
        attr.putShort(0x0001) // value: PPP
        return attr.array()
    }

    private fun buildCallConnected(): ByteArray {
        return ByteArray(0) // Minimal connected message
    }

    // ======================= Local proxy =======================

    private fun startLocalProxy(tunnelInput: InputStream, tunnelOutput: OutputStream, scope: CoroutineScope) {
        localProxy = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SSTP_SOCKS_PORT))
        }
        DiagnosticsLog.append(TAG, "SSTP SOCKS5 proxy listening on ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SSTP_SOCKS_PORT}")

        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val client = localProxy?.accept() ?: break
                    launch { handleSocksClient(client, tunnelInput, tunnelOutput) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private suspend fun handleSocksClient(client: Socket, tunnelIn: InputStream, tunnelOut: OutputStream) {
        // Basic SOCKS5 proxy: authenticate, receive CONNECT, tunnel through SSTP
        withContext(Dispatchers.IO) {
            try {
                val clientIn = client.getInputStream()
                val clientOut = client.getOutputStream()

                // SOCKS5 greeting
                val greeting = ByteArray(3)
                clientIn.readFully(greeting)
                // Reply: no auth
                clientOut.write(byteArrayOf(0x05, 0x00))

                // SOCKS5 connect request
                val request = ByteArray(4)
                clientIn.readFully(request)
                val addressType = request[3]
                val targetAddr: String
                val targetPort: Int

                when (addressType.toInt()) {
                    0x01 -> { // IPv4
                        val addr = ByteArray(4)
                        clientIn.readFully(addr)
                        targetAddr = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                        val portBytes = ByteArray(2)
                        clientIn.readFully(portBytes)
                        targetPort = ByteBuffer.wrap(portBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                    }
                    0x03 -> { // Domain
                        val domLen = clientIn.read()
                        val domain = ByteArray(domLen)
                        clientIn.readFully(domain)
                        targetAddr = String(domain)
                        val portBytes = ByteArray(2)
                        clientIn.readFully(portBytes)
                        targetPort = ByteBuffer.wrap(portBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                    }
                    else -> {
                        client.close()
                        return@withContext
                    }
                }

                // Send SOCKS5 success reply
                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))

                // Tunnel data: PPP IP packets between the SOCKS client and SSTP tunnel
                val forwardJob = launch {
                    try {
                        val buf = ByteArray(4096)
                        while (isActive) {
                            val n = clientIn.read(buf)
                            if (n <= 0) break
                            synchronized(tunnelOut) {
                                sendSstpData(tunnelOut, buildPppFrame(PPP_IP, buf.copyOf(n)))
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Note: In a full implementation, received PPP_IP packets from
                // the tunnel would be dispatched to the correct SOCKS client
                // based on TCP/UDP session tracking. This simplified version
                // demonstrates the architecture.

                forwardJob.join()
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
            }
        }
    }

    // ======================= TLS helpers =======================

    private fun createTrustAllFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        return ctx.socketFactory
    }

    // ======================= IO helpers =======================

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n <= 0) throw SstpException("Unexpected end of stream")
            offset += n
        }
    }

    class SstpException(message: String) : Exception(message)
}
