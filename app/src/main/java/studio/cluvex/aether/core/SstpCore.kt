package studio.cluvex.aether.core

import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import studio.cluvex.aether.model.SstpConfig

/**
 * SSTP (Secure Socket Tunneling Protocol, MS-SSTP) client core for Aether+.
 *
 * ### What SSTP is, on the wire
 *
 * 1. TCP + TLS to the server (normally port 443).
 * 2. One HTTP request, `SSTP_DUPLEX_POST /sra_{BA195980-...}/`, answered with
 *    `HTTP/1.1 200`. From here on the TLS stream carries SSTP packets.
 * 3. SSTP control: Call Connect Request -> Call Connect Ack (which carries the
 *    crypto binding nonce).
 * 4. PPP inside SSTP data packets: LCP, then PAP or CHAP, then the SSTP Call
 *    Connected message (crypto binding), then IPCP for an address and DNS.
 * 5. IP packets in both directions until either side disconnects.
 *
 * ### Why there is no SOCKS5 proxy here
 *
 * SSTP is a LAYER 3 tunnel: what comes out of it are IPv4 packets. A SOCKS5
 * proxy hands out TCP byte streams, and turning one into the other needs a
 * full user-space TCP/IP stack. The first draft of this file wrote SOCKS
 * stream bytes into the tunnel as if they were IP packets, which can never
 * work. Instead the core exposes the packet plane directly:
 *
 *  - [tunnelInfo]: address, DNS and MTU to build the TUN with, once IPCP is up
 *  - [sendIpPacket] / [onIpPacket]: the raw packet plane
 *  - [bridgeTun]: pumps a VpnService TUN fd both ways until the session ends
 *
 * ### VpnService integration (follow-up)
 *
 * ```
 * SstpCore.start(config, serviceScope)
 * val info = SstpCore.tunnelInfo.filterNotNull().first()
 * val tun = Builder().addAddress(info.localIp, 32).setMtu(info.mtu)
 *     .addRoute("0.0.0.0", 0).apply { info.dnsServers.forEach(::addDnsServer) }
 *     .establish()
 * protect() the SSTP socket BEFORE establish(), or route the server IP out.
 * serviceScope.launch { SstpCore.bridgeTun(tun) }
 * ```
 *
 * ### Threading
 *
 * Everything blocking runs on [Dispatchers.IO]. Writes are serialised by one
 * lock per session. [start] and [stop] are cheap and safe to call from the
 * main thread: sockets are never closed there (an SSLSocket close writes a
 * TLS alert, which StrictMode would kill the process for).
 */
object SstpCore {

    private const val TAG = "SstpCore"

    // ---------------------------------------------------------------- SSTP --
    private const val SSTP_VERSION = 0x10
    private const val SSTP_FLAG_CONTROL = 0x01

    /** The SSTP length field is 12 bits and includes the 4-byte header. */
    private const val SSTP_MAX_PACKET = 0x0FFF

    /** SSTP header (4) + PPP address/control (2) + PPP protocol (2). */
    private const val MAX_PPP_FRAME = SSTP_MAX_PACKET - 8

    private const val SSTP_URI = "/sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/"

    private const val MSG_CALL_CONNECT_REQUEST = 0x0001
    private const val MSG_CALL_CONNECT_ACK = 0x0002
    private const val MSG_CALL_CONNECT_NAK = 0x0003
    private const val MSG_CALL_CONNECTED = 0x0004
    private const val MSG_CALL_ABORT = 0x0005
    private const val MSG_CALL_DISCONNECT = 0x0006
    private const val MSG_CALL_DISCONNECT_ACK = 0x0007
    private const val MSG_ECHO_REQUEST = 0x0008
    private const val MSG_ECHO_RESPONSE = 0x0009

    private const val ATTR_ENCAPSULATED_PROTOCOL_ID = 0x01
    private const val ATTR_STATUS_INFO = 0x02
    private const val ATTR_CRYPTO_BINDING = 0x03
    private const val ATTR_CRYPTO_BINDING_REQ = 0x04

    private const val CERT_HASH_SHA1 = 0x01
    private const val CERT_HASH_SHA256 = 0x02

    /** Reserved(3) + hash protocol(1) + nonce(32) + cert hash(32) + compound MAC(32). */
    private const val CRYPTO_BINDING_LENGTH = 100

    /** Header(4) + type(2) + count(2) + attribute header(4) + 3 + 1 + 32 + 32. */
    private const val COMPOUND_MAC_OFFSET = 80

    private const val CMK_SEED = "SSTP inner method derived CMK"

    // ----------------------------------------------------------------- PPP --
    private const val PPP_IP = 0x0021
    private const val PPP_IPCP = 0x8021
    private const val PPP_LCP = 0xC021
    private const val PPP_PAP = 0xC023
    private const val PPP_CHAP = 0xC223

    private const val CONF_REQ = 1
    private const val CONF_ACK = 2
    private const val CONF_NAK = 3
    private const val CONF_REJ = 4
    private const val TERM_REQ = 5
    private const val TERM_ACK = 6
    private const val CODE_REJ = 7
    private const val PROTO_REJ = 8
    private const val ECHO_REQ = 9
    private const val ECHO_REP = 10

    private const val LCP_OPT_MRU = 1
    private const val LCP_OPT_ACCM = 2
    private const val LCP_OPT_AUTH = 3
    private const val LCP_OPT_MAGIC = 5
    private const val LCP_OPT_PFC = 7
    private const val LCP_OPT_ACFC = 8

    private const val CHAP_MD5 = 0x05

    private const val IPCP_OPT_ADDRESS = 0x03
    private const val IPCP_OPT_DNS1 = 0x81
    private const val IPCP_OPT_DNS2 = 0x83

    private const val AUTH_NONE = 0
    private const val AUTH_PAP = 1
    private const val AUTH_CHAP_MD5 = 2

    private const val DEFAULT_PEER_MRU = 1500
    private const val ZERO: Byte = 0

    // -------------------------------------------------------------- timing --
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000
    private const val CALL_ACK_TIMEOUT_MS = 15_000L
    private const val NEGOTIATION_TIMEOUT_MS = 45_000L
    private const val RETRANSMIT_MS = 3_000L
    private const val MAX_RETRANSMITS = 8
    private const val MAX_AUTH_NAKS = 4
    private const val KEEPALIVE_MS = 20_000L
    private const val IDLE_TIMEOUT_MS = 70_000L
    private const val FRAME_QUEUE = 256
    private const val TUN_READ_BUFFER = 32_767

    private val EMPTY = ByteArray(0)
    private val HTTP_OK = Regex("^HTTP/1\\.[01] 200\\b")

    /** Connection states exposed to the UI via [state]. */
    enum class SstpState {
        IDLE, CONNECTING, TLS_HANDSHAKE, SSTP_HANDSHAKE,
        PPP_LCP, PPP_AUTH, PPP_IPCP, CONNECTED, DISCONNECTING, ERROR,
    }

    /** What the VpnService needs to build the TUN once the PPP link is up. */
    data class TunnelInfo(
        val localIp: String,
        val peerIp: String?,
        val dnsServers: List<String>,
        val mtu: Int,
    )

    private val _state = MutableStateFlow(SstpState.IDLE)
    val state: StateFlow<SstpState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _tunnelInfo = MutableStateFlow<TunnelInfo?>(null)
    val tunnelInfo: StateFlow<TunnelInfo?> = _tunnelInfo.asStateFlow()

    /** True when the core has a live SSTP tunnel. */
    val isConnected: Boolean get() = _state.value == SstpState.CONNECTED

    /**
     * Receives every IPv4 packet that comes out of the tunnel. Called on an IO
     * thread; keep it non-suspending and fast (a TUN write). [bridgeTun] sets
     * and clears it for you.
     */
    @Volatile
    var onIpPacket: ((ByteArray) -> Unit)? = null

    @Volatile
    private var active: Session? = null
    private var connectionJob: Job? = null

    /** Closes sockets off the caller's thread. Never cancelled. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val insecureFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        context.socketFactory
    }

    /**
     * Starts an SSTP session. Progress is published on [state] and
     * [statusMessage]; [tunnelInfo] becomes non-null when IPCP is up.
     */
    @Synchronized
    fun start(config: SstpConfig, scope: CoroutineScope) {
        stop()
        if (!config.isUsable) {
            _state.value = SstpState.ERROR
            _statusMessage.value = "No SSTP server configured"
            return
        }
        val session = Session(config)
        active = session
        DiagnosticsLog.i(TAG, "Starting SSTP to ${config.hostname}:${config.port} (sni=${config.effectiveSni}, verify=${config.verifyCert})")
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                session.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = describe(e, config)
                DiagnosticsLog.e(TAG, "SSTP session ended: $reason")
                session.publish(SstpState.ERROR, reason)
            } finally {
                session.close()
                if (active === session) _tunnelInfo.value = null
            }
        }
    }

    /** Tears the session down. Safe from any thread. */
    @Synchronized
    fun stop() {
        val session = active
        active = null
        connectionJob?.cancel()
        connectionJob = null
        if (session != null) cleanupScope.launch { session.close() }
        _tunnelInfo.value = null
        _state.value = SstpState.IDLE
        _statusMessage.value = ""
    }

    /**
     * Sends one IPv4 packet into the tunnel. Returns false when there is no
     * live tunnel or the packet does not fit the negotiated MTU.
     */
    fun sendIpPacket(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): Boolean =
        active?.sendIp(packet, offset, length) ?: false

    /**
     * Pumps [tun] both ways until the fd is closed or the caller is cancelled.
     * Only IPv4 goes into the tunnel: SSTP here negotiates IPCP, not IPV6CP.
     */
    suspend fun bridgeTun(tun: ParcelFileDescriptor) {
        withContext(Dispatchers.IO) {
            val toTun = FileOutputStream(tun.fileDescriptor)
            val fromTun = FileInputStream(tun.fileDescriptor)
            val tunLock = Any()
            onIpPacket = { packet ->
                try {
                    synchronized(tunLock) { toTun.write(packet) }
                } catch (_: IOException) {
                }
            }
            try {
                val buffer = ByteArray(TUN_READ_BUFFER)
                while (isActive) {
                    val n = fromTun.read(buffer)
                    if (n < 0) break
                    if (n > 0 && ((buffer[0].toInt() ushr 4) and 0x0F) == 4) sendIpPacket(buffer, 0, n)
                }
            } catch (_: IOException) {
            } finally {
                onIpPacket = null
            }
        }
    }

    // ======================================================= wire helpers ==

    /** The SSTP_DUPLEX_POST request, exactly as MS-SSTP 2.2.1 spells it. */
    internal fun duplexPostRequest(host: String): ByteArray = buildString {
        append("SSTP_DUPLEX_POST ").append(SSTP_URI).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append("\r\n")
        append("SSTPCORRELATIONID: {")
            .append(UUID.randomUUID().toString().uppercase(Locale.ROOT))
            .append("}\r\n")
        append("Content-Length: 18446744073709551615\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    /**
     * Reads an HTTP response header up to and including the blank line, and
     * not one byte further: whatever follows is already SSTP. Bounded, so a
     * hostile server cannot make us buffer forever.
     */
    internal fun readHttpHeader(input: InputStream, limit: Int = 8_192): String {
        val out = ByteArrayOutputStream(256)
        var window = 0
        while (out.size() < limit) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed during the HTTP handshake")
            out.write(b)
            window = (window shl 8) or b
            if (window == 0x0D0A0D0A) return out.toString("US-ASCII")
        }
        throw SstpException("HTTP response header too large")
    }

    internal fun isHttpOk(header: String): Boolean =
        HTTP_OK.containsMatchIn(header.lineSequence().firstOrNull().orEmpty())

    /**
     * Accept-anything TLS, ONLY for servers the user explicitly marked as
     * unverified (VPN Gate relays present self-signed certificates) and for
     * the credential-free reachability probe.
     */
    internal fun insecureSocketFactory(): SSLSocketFactory = insecureFactory

    private fun describe(e: Throwable, config: SstpConfig): String = when (e) {
        is SstpException -> e.message ?: "SSTP error"
        is UnknownHostException -> "Could not resolve ${config.hostname}"
        is SocketTimeoutException -> "Timed out reaching ${config.hostname}:${config.port}"
        is ConnectException -> "Connection refused by ${config.hostname}:${config.port}"
        is SSLHandshakeException -> "TLS handshake failed: ${e.message ?: "unknown error"}"
        is EOFException -> "The server closed the connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ======================================================== byte helpers ==

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 8).toByte()
        b[at + 1] = value.toByte()
    }

    private fun u16Bytes(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32Bytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun ipString(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    /** PAP Nak / CHAP Failure text, for the error the user actually sees. */
    private fun peerMessage(data: ByteArray, lengthPrefixed: Boolean): String {
        if (data.isEmpty()) return ""
        if (!lengthPrefixed) return String(data, Charsets.UTF_8).trim()
        val length = minOf(data[0].toInt() and 0xFF, data.size - 1)
        return String(data, 1, length, Charsets.UTF_8).trim()
    }

    // ===================================================== packet models ==

    private class Frame(val control: Boolean, val body: ByteArray)

    private class Attribute(val id: Int, val value: ByteArray)

    private class ControlMessage(val type: Int, val attributes: List<Attribute>) {
        fun attribute(id: Int): ByteArray? = attributes.firstOrNull { it.id == id }?.value
    }

    private class PppFrame(val protocol: Int, val info: ByteArray)

    private class PppControl(val code: Int, val id: Int, val data: ByteArray)

    private class Option(val type: Int, val data: ByteArray) {
        fun encode(): ByteArray {
            val out = ByteArray(2 + data.size)
            out[0] = type.toByte()
            out[1] = (2 + data.size).toByte()
            data.copyInto(out, 2)
            return out
        }
    }

    private enum class Phase { LINK, AUTH, NETWORK }

    private fun buildControl(type: Int, attributes: List<Attribute>): ByteArray {
        val total = 8 + attributes.sumOf { 4 + it.value.size }
        val out = ByteArray(total)
        out[0] = SSTP_VERSION.toByte()
        out[1] = SSTP_FLAG_CONTROL.toByte()
        putU16(out, 2, total)
        putU16(out, 4, type)
        putU16(out, 6, attributes.size)
        var offset = 8
        for (attribute in attributes) {
            out[offset] = 0
            out[offset + 1] = attribute.id.toByte()
            putU16(out, offset + 2, 4 + attribute.value.size)
            attribute.value.copyInto(out, offset + 4)
            offset += 4 + attribute.value.size
        }
        return out
    }

    private fun parseControl(body: ByteArray): ControlMessage? {
        if (body.size < 4) return null
        val type = u16(body, 0)
        val count = u16(body, 2)
        val attributes = ArrayList<Attribute>(minOf(count, 16))
        var offset = 4
        for (i in 0 until count) {
            if (offset + 4 > body.size) break
            val id = body[offset + 1].toInt() and 0xFF
            val length = u16(body, offset + 2) and SSTP_MAX_PACKET
            if (length < 4 || offset + length > body.size) break
            attributes += Attribute(id, body.copyOfRange(offset + 4, offset + length))
            offset += length
        }
        return ControlMessage(type, attributes)
    }

    /** Accepts frames with or without address/control and with PFC. */
    private fun parsePpp(body: ByteArray): PppFrame? {
        var offset = 0
        if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0x03.toByte()) offset = 2
        if (offset >= body.size) return null
        val protocol: Int
        if ((body[offset].toInt() and 0x01) == 1) {
            protocol = body[offset].toInt() and 0xFF
            offset += 1
        } else {
            if (offset + 2 > body.size) return null
            protocol = u16(body, offset)
            offset += 2
        }
        return PppFrame(protocol, body.copyOfRange(offset, body.size))
    }

    private fun parsePppControl(info: ByteArray): PppControl? {
        if (info.size < 4) return null
        val length = u16(info, 2)
        if (length < 4 || length > info.size) return null
        return PppControl(info[0].toInt() and 0xFF, info[1].toInt() and 0xFF, info.copyOfRange(4, length))
    }

    private fun parseOptions(data: ByteArray): List<Option>? {
        val options = ArrayList<Option>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 2 > data.size) return null
            val type = data[offset].toInt() and 0xFF
            val length = data[offset + 1].toInt() and 0xFF
            if (length < 2 || offset + length > data.size) return null
            options += Option(type, data.copyOfRange(offset + 2, offset + length))
            offset += length
        }
        return options
    }

    // ============================================================ session ==

    /** One connection attempt. Never reused: a new [start] makes a new one. */
    private class Session(val config: SstpConfig) {
        private val closed = AtomicBoolean(false)
        private val writeLock = Any()
        private val random = SecureRandom()

        @Volatile private var raw: Socket? = null
        @Volatile private var ssl: SSLSocket? = null
        private lateinit var input: DataInputStream
        private lateinit var output: OutputStream
        private var serverCert: ByteArray? = null

        @Volatile private var connected = false
        @Volatile private var peerClosed = false
        @Volatile private var lastRxMs = now()

        // SSTP crypto binding request, from the Call Connect Ack.
        private var hashBitmask = CERT_HASH_SHA256
        private var nonce = ByteArray(32)

        // PPP state.
        private var phase = Phase.LINK
        private var pppId = random.nextInt(256)
        private var retransmits = 0

        private var lcpReqId = -1
        private var lcpOurOpen = false
        private var lcpPeerOpen = false
        private var sendMru = true
        private var mru = config.effectiveMru
        private var sendMagic = true
        private var magic = newMagic()
        private var peerMru = DEFAULT_PEER_MRU
        private var authProtocol = AUTH_NONE
        private var authNaks = 0

        private var papId = -1
        private var authenticated = false

        private var ipcpReqId = -1
        private var ipcpOurOpen = false
        private var ipcpPeerOpen = false
        private val localIp = ByteArray(4)
        private val dns1 = ByteArray(4)
        private val dns2 = ByteArray(4)
        private var askDns1 = true
        private var askDns2 = true
        private var peerIp: ByteArray? = null
        @Volatile private var tunnelMtu = config.effectiveMtu

        /** Stale sessions (stopped or replaced) never touch the public state. */
        fun publish(state: SstpState, message: String) {
            if (active !== this) return
            _state.value = state
            _statusMessage.value = message
        }

        suspend fun run() {
            coroutineScope {
                // The finally runs BEFORE coroutineScope waits for its children,
                // so closing the socket here is what unblocks the reader.
                try {
                    open()
                    val frames = Channel<Frame>(FRAME_QUEUE)
                    launch { readLoop(frames) }
                    requestCall(frames)
                    negotiate(frames)
                    val info = buildTunnelInfo()
                    connected = true
                    if (active === this@Session) _tunnelInfo.value = info
                    publish(SstpState.CONNECTED, "Connected, local address ${info.localIp}")
                    DiagnosticsLog.i(
                        TAG,
                        "SSTP up: ${info.localIp} via ${config.hostname}:${config.port}, mtu=${info.mtu}, " +
                            "dns=${info.dnsServers.joinToString()}, auth=${authName()}",
                    )
                    launch { keepAlive() }
                    dataPlane(frames)
                } finally {
                    close()
                }
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (connected && !peerClosed) {
                try {
                    sendControl(MSG_CALL_DISCONNECT)
                } catch (_: Exception) {
                }
            }
            connected = false
            try { ssl?.close() } catch (_: Exception) { }
            try { raw?.close() } catch (_: Exception) { }
        }

        fun sendIp(packet: ByteArray, offset: Int, length: Int): Boolean {
            if (!connected || closed.get()) return false
            if (length <= 0 || length > tunnelMtu || offset < 0 || offset + length > packet.size) return false
            return try {
                sendPpp(PPP_IP, packet, offset, length)
                true
            } catch (_: IOException) {
                false
            }
        }

        // ------------------------------------------------ TCP, TLS, HTTP --

        private fun open() {
            val host = config.hostname.trim()
            publish(SstpState.CONNECTING, "Connecting to $host:${config.port}")
            val socket = Socket()
            raw = socket
            if (closed.get()) throw SstpException("Cancelled")
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, config.port), CONNECT_TIMEOUT_MS)

            val sni = config.effectiveSni
            publish(SstpState.TLS_HANDSHAKE, "TLS handshake with $sni")
            val factory = if (config.verifyCert) SSLSocketFactory.getDefault() as SSLSocketFactory else insecureFactory
            val tls = factory.createSocket(socket, sni, config.port, true) as SSLSocket
            ssl = tls
            applySni(tls, sni)
            tls.soTimeout = HANDSHAKE_TIMEOUT_MS
            tls.startHandshake()
            // An SSLSocket checks the chain but NOT the name. Without this a
            // valid certificate for any other site would be accepted.
            if (config.verifyCert && !HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, tls.session)) {
                throw SstpException("The server certificate does not match $sni")
            }
            serverCert = tls.session.peerCertificates.firstOrNull()?.encoded

            publish(SstpState.SSTP_HANDSHAKE, "Opening the SSTP channel")
            input = DataInputStream(BufferedInputStream(tls.inputStream, 16 * 1024))
            output = tls.outputStream
            write(duplexPostRequest(host))
            val header = readHttpHeader(input)
            if (!isHttpOk(header)) {
                val status = header.lineSequence().firstOrNull().orEmpty().take(80)
                throw SstpException("The server refused SSTP ($status)")
            }
            // From here the idle watchdog owns liveness, not a read timeout
            // that could fire in the middle of a frame.
            tls.soTimeout = 0
            lastRxMs = now()
        }

        private fun applySni(socket: SSLSocket, sni: String) {
            val ipLiteral = sni.contains(':') || (sni.isNotEmpty() && sni.all { it.isDigit() || it == '.' })
            if (sni.isBlank() || ipLiteral) return
            try {
                val params = socket.sslParameters
                params.serverNames = listOf(SNIHostName(sni))
                socket.sslParameters = params
            } catch (_: Exception) {
            }
        }

        // ------------------------------------------------------ SSTP I/O --

        private fun write(packet: ByteArray) {
            synchronized(writeLock) {
                output.write(packet)
                output.flush()
            }
        }

        private fun sendControl(type: Int, attributes: List<Attribute> = emptyList()) {
            write(buildControl(type, attributes))
        }

        private fun readFrame(): Frame? {
            val version = input.read()
            if (version < 0) return null
            val flags = input.readUnsignedByte()
            val length = input.readUnsignedShort() and SSTP_MAX_PACKET
            if (version != SSTP_VERSION) {
                throw SstpException("Unsupported SSTP version 0x${Integer.toHexString(version)}")
            }
            if (length < 4) throw SstpException("Malformed SSTP packet")
            val body = ByteArray(length - 4)
            input.readFully(body)
            return Frame((flags and SSTP_FLAG_CONTROL) != 0, body)
        }

        private suspend fun readLoop(frames: SendChannel<Frame>) {
            var failure: Throwable? = null
            try {
                while (true) {
                    val frame = readFrame() ?: break
                    lastRxMs = now()
                    frames.send(frame)
                }
                failure = SstpException("The server closed the connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            } finally {
                frames.close(if (closed.get()) null else failure)
            }
        }

        private fun onCommonControl(message: ControlMessage) {
            when (message.type) {
                MSG_ECHO_REQUEST -> sendControl(MSG_ECHO_RESPONSE)
                MSG_ECHO_RESPONSE, MSG_CALL_DISCONNECT_ACK -> Unit
                MSG_CALL_ABORT -> {
                    peerClosed = true
                    throw SstpException("The server aborted the call${statusSuffix(message)}")
                }
                MSG_CALL_DISCONNECT -> {
                    try {
                        sendControl(MSG_CALL_DISCONNECT_ACK)
                    } catch (_: Exception) {
                    }
                    peerClosed = true
                    throw SstpException("The server disconnected${statusSuffix(message)}")
                }
                else -> DiagnosticsLog.d(TAG, "Ignoring SSTP control message 0x${Integer.toHexString(message.type)}")
            }
        }

        private fun statusSuffix(message: ControlMessage): String {
            val status = message.attribute(ATTR_STATUS_INFO) ?: return ""
            if (status.size < 8) return ""
            val code = ((status[4].toInt() and 0xFF) shl 24) or ((status[5].toInt() and 0xFF) shl 16) or
                ((status[6].toInt() and 0xFF) shl 8) or (status[7].toInt() and 0xFF)
            return " (status $code)"
        }

        private suspend fun requestCall(frames: ReceiveChannel<Frame>) {
            sendControl(
                MSG_CALL_CONNECT_REQUEST,
                listOf(Attribute(ATTR_ENCAPSULATED_PROTOCOL_ID, u16Bytes(1))),
            )
            val deadline = now() + CALL_ACK_TIMEOUT_MS
            while (true) {
                val left = deadline - now()
                if (left <= 0) throw SstpException("The server did not answer the SSTP call request")
                val frame = withTimeoutOrNull(left) { frames.receive() } ?: continue
                if (!frame.control) continue
                val message = parseControl(frame.body) ?: continue
                when (message.type) {
                    MSG_CALL_CONNECT_ACK -> {
                        val request = message.attribute(ATTR_CRYPTO_BINDING_REQ)
                        if (request != null && request.size >= 36) {
                            hashBitmask = request[3].toInt() and 0xFF
                            nonce = request.copyOfRange(4, 36)
                        }
                        return
                    }
                    MSG_CALL_CONNECT_NAK -> throw SstpException("The server refused the SSTP call${statusSuffix(message)}")
                    else -> onCommonControl(message)
                }
            }
        }

        /** MS-SSTP 3.2.5.2: crypto binding over the TLS certificate. */
        private fun sendCallConnected() {
            val sha256 = (hashBitmask and CERT_HASH_SHA256) != 0 || (hashBitmask and CERT_HASH_SHA1) == 0
            val hashProtocol = if (sha256) CERT_HASH_SHA256 else CERT_HASH_SHA1
            val digest = if (sha256) "SHA-256" else "SHA-1"
            val macAlgorithm = if (sha256) "HmacSHA256" else "HmacSHA1"
            val macLength = if (sha256) 32 else 20

            val binding = ByteArray(CRYPTO_BINDING_LENGTH)
            binding[3] = hashProtocol.toByte()
            nonce.copyInto(binding, 4)
            val cert = serverCert
            if (cert != null) MessageDigest.getInstance(digest).digest(cert).copyInto(binding, 36)
            val packet = buildControl(MSG_CALL_CONNECTED, listOf(Attribute(ATTR_CRYPTO_BINDING, binding)))

            // PAP and CHAP derive no keys, so the higher-layer key is all zeros.
            val hlak = ByteArray(32)
            val seed = CMK_SEED.toByteArray(Charsets.US_ASCII) + byteArrayOf(macLength.toByte(), 0, 1)
            val cmk = hmac(macAlgorithm, hlak, seed).copyOf(macLength)
            hmac(macAlgorithm, cmk, packet).copyInto(packet, COMPOUND_MAC_OFFSET)
            write(packet)
        }

        // ------------------------------------------------------- PPP I/O --

        private fun nextId(): Int {
            pppId = (pppId + 1) and 0xFF
            return pppId
        }

        private fun newMagic(): Int {
            var value: Int
            do {
                value = random.nextInt()
            } while (value == 0)
            return value
        }

        private fun sendPpp(protocol: Int, payload: ByteArray, offset: Int, length: Int) {
            val total = 8 + length
            if (total > SSTP_MAX_PACKET) throw SstpException("PPP frame too large")
            val packet = ByteArray(total)
            packet[0] = SSTP_VERSION.toByte()
            packet[1] = 0
            putU16(packet, 2, total)
            packet[4] = 0xFF.toByte()
            packet[5] = 0x03
            putU16(packet, 6, protocol)
            System.arraycopy(payload, offset, packet, 8, length)
            write(packet)
        }

        private fun sendPppControl(protocol: Int, code: Int, id: Int, data: ByteArray) {
            val info = ByteArray(4 + data.size)
            info[0] = code.toByte()
            info[1] = id.toByte()
            putU16(info, 2, 4 + data.size)
            data.copyInto(info, 4)
            sendPpp(protocol, info, 0, info.size)
        }

        private fun protocolReject(frame: PppFrame) {
            val room = maxOf(0, peerMru - 6)
            val rejected = if (frame.info.size > room) frame.info.copyOf(room) else frame.info
            sendPppControl(PPP_LCP, PROTO_REJ, nextId(), u16Bytes(frame.protocol) + rejected)
        }

        // --------------------------------------------------- negotiation --

        private suspend fun negotiate(frames: ReceiveChannel<Frame>) {
            publish(SstpState.PPP_LCP, "Negotiating the PPP link")
            sendLcpRequest()
            val deadline = now() + NEGOTIATION_TIMEOUT_MS
            while (!(ipcpOurOpen && ipcpPeerOpen)) {
                val left = deadline - now()
                if (left <= 0) throw SstpException(stalledMessage())
                val frame = withTimeoutOrNull(minOf(left, RETRANSMIT_MS)) { frames.receive() }
                if (frame == null) {
                    retransmit()
                    continue
                }
                if (frame.control) {
                    val message = parseControl(frame.body)
                    if (message != null) onCommonControl(message)
                    continue
                }
                val ppp = parsePpp(frame.body) ?: continue
                when (ppp.protocol) {
                    PPP_LCP -> onLcp(ppp)
                    PPP_PAP -> onPap(ppp)
                    PPP_CHAP -> onChap(ppp)
                    PPP_IPCP -> onIpcp(ppp)
                    PPP_IP -> Unit
                    else -> {
                        if (lcpOurOpen && lcpPeerOpen) protocolReject(ppp)
                    }
                }
                advance()
            }
        }

        private fun stalledMessage(): String = when (phase) {
            Phase.LINK -> "PPP link negotiation timed out"
            Phase.AUTH -> "PPP sign-in timed out"
            Phase.NETWORK -> "The server did not assign an IP address in time"
        }

        private fun retransmit() {
            retransmits += 1
            if (retransmits > MAX_RETRANSMITS) throw SstpException(stalledMessage())
            when (phase) {
                Phase.LINK -> {
                    if (!lcpOurOpen) sendLcpRequest(fresh = false)
                }
                Phase.AUTH -> {
                    if (authProtocol == AUTH_PAP) sendPap()
                }
                Phase.NETWORK -> {
                    if (!ipcpOurOpen) sendIpcpRequest(fresh = false)
                }
            }
        }

        private fun advance() {
            if (phase != Phase.LINK || !lcpOurOpen || !lcpPeerOpen) return
            phase = Phase.AUTH
            retransmits = 0
            when (authProtocol) {
                AUTH_PAP -> {
                    publish(SstpState.PPP_AUTH, "Signing in (PAP)")
                    sendPap()
                }
                AUTH_CHAP_MD5 -> publish(SstpState.PPP_AUTH, "Signing in (CHAP)")
                else -> onAuthenticated()
            }
        }

        private fun onAuthenticated() {
            if (authenticated) return
            authenticated = true
            phase = Phase.NETWORK
            retransmits = 0
            sendCallConnected()
            publish(SstpState.PPP_IPCP, "Requesting an IP address")
            sendIpcpRequest()
        }

        private fun authName(): String = when (authProtocol) {
            AUTH_PAP -> "PAP"
            AUTH_CHAP_MD5 -> "CHAP-MD5"
            else -> "none"
        }

        // ----------------------------------------------------------- LCP --

        private fun sendLcpRequest(fresh: Boolean = true) {
            if (fresh || lcpReqId < 0) lcpReqId = nextId()
            val options = ByteArrayOutputStream()
            if (sendMru) options.write(Option(LCP_OPT_MRU, u16Bytes(mru)).encode())
            if (sendMagic) options.write(Option(LCP_OPT_MAGIC, u32Bytes(magic)).encode())
            // No auth option on purpose: in OUR request it would mean "the
            // server must authenticate to me", which is the wrong way round.
            sendPppControl(PPP_LCP, CONF_REQ, lcpReqId, options.toByteArray())
        }

        private fun onLcp(frame: PppFrame) {
            val packet = parsePppControl(frame.info) ?: return
            when (packet.code) {
                CONF_REQ -> onPeerLcpRequest(packet)
                CONF_ACK -> {
                    if (packet.id == lcpReqId) lcpOurOpen = true
                }
                CONF_NAK -> {
                    if (packet.id != lcpReqId) return
                    for (option in parseOptions(packet.data).orEmpty()) {
                        when (option.type) {
                            LCP_OPT_MRU -> {
                                val suggested = if (option.data.size == 2) u16(option.data, 0) else -1
                                if (suggested in SstpConfig.MIN_MTU..SstpConfig.MAX_MTU) mru = suggested else sendMru = false
                            }
                            LCP_OPT_MAGIC -> magic = newMagic()
                        }
                    }
                    sendLcpRequest()
                }
                CONF_REJ -> {
                    if (packet.id != lcpReqId) return
                    for (option in parseOptions(packet.data).orEmpty()) {
                        when (option.type) {
                            LCP_OPT_MRU -> sendMru = false
                            LCP_OPT_MAGIC -> sendMagic = false
                        }
                    }
                    sendLcpRequest()
                }
                TERM_REQ -> {
                    sendPppControl(PPP_LCP, TERM_ACK, packet.id, EMPTY)
                    peerClosed = true
                    throw SstpException("The server closed the PPP link")
                }
                ECHO_REQ -> {
                    if (lcpOurOpen && lcpPeerOpen) {
                        val extra = if (packet.data.size > 4) packet.data.copyOfRange(4, packet.data.size) else EMPTY
                        sendPppControl(PPP_LCP, ECHO_REP, packet.id, u32Bytes(if (sendMagic) magic else 0) + extra)
                    }
                }
                PROTO_REJ, CODE_REJ -> DiagnosticsLog.d(TAG, "LCP reject (code ${packet.code}) from the server")
                else -> Unit
            }
        }

        private fun onPeerLcpRequest(packet: PppControl) {
            val options = parseOptions(packet.data) ?: return
            val rejected = ByteArrayOutputStream()
            val nak = ByteArrayOutputStream()
            var auth = AUTH_NONE
            var mruFromPeer = DEFAULT_PEER_MRU
            for (option in options) {
                when (option.type) {
                    LCP_OPT_MRU -> {
                        if (option.data.size == 2) mruFromPeer = u16(option.data, 0) else rejected.write(option.encode())
                    }
                    LCP_OPT_ACCM, LCP_OPT_MAGIC, LCP_OPT_PFC, LCP_OPT_ACFC -> Unit
                    LCP_OPT_AUTH -> {
                        val protocol = if (option.data.size >= 2) u16(option.data, 0) else -1
                        val algorithm = if (option.data.size >= 3) option.data[2].toInt() and 0xFF else -1
                        if (protocol == PPP_PAP) {
                            auth = AUTH_PAP
                        } else if (protocol == PPP_CHAP && algorithm == CHAP_MD5) {
                            auth = AUTH_CHAP_MD5
                        } else {
                            // MS-CHAPv2 and friends: ask for PAP instead.
                            nak.write(Option(LCP_OPT_AUTH, u16Bytes(PPP_PAP)).encode())
                        }
                    }
                    else -> rejected.write(option.encode())
                }
            }
            if (rejected.size() > 0) {
                sendPppControl(PPP_LCP, CONF_REJ, packet.id, rejected.toByteArray())
            } else if (nak.size() > 0) {
                authNaks += 1
                if (authNaks > MAX_AUTH_NAKS) {
                    throw SstpException("The server requires a sign-in method that is not supported yet (only PAP and CHAP are)")
                }
                sendPppControl(PPP_LCP, CONF_NAK, packet.id, nak.toByteArray())
            } else {
                authProtocol = auth
                peerMru = mruFromPeer.coerceIn(SstpConfig.MIN_MTU, MAX_PPP_FRAME)
                sendPppControl(PPP_LCP, CONF_ACK, packet.id, packet.data)
                lcpPeerOpen = true
            }
        }

        // ---------------------------------------------------------- auth --

        private fun sendPap() {
            if (papId < 0) papId = nextId()
            val user = config.username.toByteArray(Charsets.UTF_8)
            val pass = config.password.toByteArray(Charsets.UTF_8)
            if (user.size > 255 || pass.size > 255) throw SstpException("Username or password is too long")
            val data = ByteArray(2 + user.size + pass.size)
            data[0] = user.size.toByte()
            user.copyInto(data, 1)
            data[1 + user.size] = pass.size.toByte()
            pass.copyInto(data, 2 + user.size)
            sendPppControl(PPP_PAP, 1, papId, data)
        }

        private fun onPap(frame: PppFrame) {
            if (phase != Phase.AUTH || authProtocol != AUTH_PAP) return
            val packet = parsePppControl(frame.info) ?: return
            if (packet.id != papId) return
            when (packet.code) {
                2 -> onAuthenticated()
                3 -> {
                    val text = peerMessage(packet.data, lengthPrefixed = true)
                    throw SstpException("Sign-in rejected by the server" + if (text.isNotEmpty()) ": $text" else "")
                }
            }
        }

        private fun onChap(frame: PppFrame) {
            if (phase != Phase.AUTH || authProtocol != AUTH_CHAP_MD5) return
            val packet = parsePppControl(frame.info) ?: return
            when (packet.code) {
                1 -> {
                    if (packet.data.isEmpty()) return
                    val size = packet.data[0].toInt() and 0xFF
                    if (1 + size > packet.data.size) return
                    val challenge = packet.data.copyOfRange(1, 1 + size)
                    val md5 = MessageDigest.getInstance("MD5")
                    md5.update(packet.id.toByte())
                    md5.update(config.password.toByteArray(Charsets.UTF_8))
                    md5.update(challenge)
                    val response = md5.digest()
                    val user = config.username.toByteArray(Charsets.UTF_8)
                    val data = ByteArray(1 + response.size + user.size)
                    data[0] = response.size.toByte()
                    response.copyInto(data, 1)
                    user.copyInto(data, 1 + response.size)
                    sendPppControl(PPP_CHAP, 2, packet.id, data)
                }
                3 -> onAuthenticated()
                4 -> {
                    val text = peerMessage(packet.data, lengthPrefixed = false)
                    throw SstpException("Sign-in rejected by the server" + if (text.isNotEmpty()) ": $text" else "")
                }
            }
        }

        // ---------------------------------------------------------- IPCP --

        private fun sendIpcpRequest(fresh: Boolean = true) {
            if (fresh || ipcpReqId < 0) ipcpReqId = nextId()
            val options = ByteArrayOutputStream()
            options.write(Option(IPCP_OPT_ADDRESS, localIp).encode())
            if (askDns1) options.write(Option(IPCP_OPT_DNS1, dns1).encode())
            if (askDns2) options.write(Option(IPCP_OPT_DNS2, dns2).encode())
            sendPppControl(PPP_IPCP, CONF_REQ, ipcpReqId, options.toByteArray())
        }

        private fun onIpcp(frame: PppFrame) {
            if (phase != Phase.NETWORK) return
            val packet = parsePppControl(frame.info) ?: return
            when (packet.code) {
                CONF_REQ -> {
                    val options = parseOptions(packet.data) ?: return
                    val rejected = ByteArrayOutputStream()
                    for (option in options) {
                        if (option.type == IPCP_OPT_ADDRESS && option.data.size == 4) {
                            peerIp = option.data
                        } else {
                            rejected.write(option.encode())
                        }
                    }
                    if (rejected.size() > 0) {
                        sendPppControl(PPP_IPCP, CONF_REJ, packet.id, rejected.toByteArray())
                    } else {
                        sendPppControl(PPP_IPCP, CONF_ACK, packet.id, packet.data)
                        ipcpPeerOpen = true
                    }
                }
                CONF_ACK -> {
                    if (packet.id != ipcpReqId) return
                    if (localIp.all { it == ZERO }) throw SstpException("The server did not assign an IP address")
                    ipcpOurOpen = true
                }
                CONF_NAK -> {
                    if (packet.id != ipcpReqId) return
                    for (option in parseOptions(packet.data).orEmpty()) {
                        if (option.data.size != 4) continue
                        when (option.type) {
                            IPCP_OPT_ADDRESS -> option.data.copyInto(localIp)
                            IPCP_OPT_DNS1 -> option.data.copyInto(dns1)
                            IPCP_OPT_DNS2 -> option.data.copyInto(dns2)
                        }
                    }
                    sendIpcpRequest()
                }
                CONF_REJ -> {
                    if (packet.id != ipcpReqId) return
                    for (option in parseOptions(packet.data).orEmpty()) {
                        when (option.type) {
                            IPCP_OPT_ADDRESS -> throw SstpException("The server refused to assign an IP address")
                            IPCP_OPT_DNS1 -> askDns1 = false
                            IPCP_OPT_DNS2 -> askDns2 = false
                        }
                    }
                    sendIpcpRequest()
                }
                TERM_REQ -> {
                    sendPppControl(PPP_IPCP, TERM_ACK, packet.id, EMPTY)
                    throw SstpException("The server closed the IP layer")
                }
                else -> Unit
            }
        }

        private fun buildTunnelInfo(): TunnelInfo {
            tunnelMtu = minOf(config.effectiveMtu, peerMru)
            val dns = ArrayList<String>(2)
            if (askDns1 && dns1.any { it != ZERO }) dns += ipString(dns1)
            if (askDns2 && dns2.any { it != ZERO }) dns += ipString(dns2)
            val peer = peerIp
            return TunnelInfo(
                localIp = ipString(localIp),
                peerIp = if (peer != null) ipString(peer) else null,
                dnsServers = dns,
                mtu = tunnelMtu,
            )
        }

        // ---------------------------------------------------- data plane --

        private suspend fun dataPlane(frames: ReceiveChannel<Frame>) {
            while (true) {
                val frame = frames.receive()
                if (frame.control) {
                    val message = parseControl(frame.body)
                    if (message != null) onCommonControl(message)
                    continue
                }
                val ppp = parsePpp(frame.body) ?: continue
                when (ppp.protocol) {
                    PPP_IP -> deliver(ppp.info)
                    PPP_LCP -> onLcp(ppp)
                    PPP_IPCP, PPP_PAP, PPP_CHAP -> Unit
                    else -> protocolReject(ppp)
                }
            }
        }

        private fun deliver(packet: ByteArray) {
            val sink = onIpPacket ?: return
            try {
                sink(packet)
            } catch (e: Exception) {
                DiagnosticsLog.w(TAG, "IP packet sink failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private suspend fun keepAlive() {
            while (true) {
                delay(KEEPALIVE_MS)
                if (now() - lastRxMs > IDLE_TIMEOUT_MS) throw SstpException("The server stopped responding")
                sendControl(MSG_ECHO_REQUEST)
            }
        }
    }

    class SstpException(message: String) : IOException(message)
}
