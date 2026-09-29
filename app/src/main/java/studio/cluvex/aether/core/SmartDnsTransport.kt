package studio.cluvex.aether.core

import studio.cluvex.aether.model.SmartDnsProtocol
import studio.cluvex.aether.model.SmartDnsServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * How the Smart DNS provider is reached.
 *
 *  - [TUNNEL]: through the engine, so the provider sees a WARP address. The
 *    default, and what a provider that serves anyone expects.
 *  - [DIRECT]: straight out of the phone (the app's own sockets are outside
 *    the VPN), for providers that only answer registered / Iranian addresses.
 */
enum class SmartDnsPath {
    TUNNEL,
    DIRECT,
    ;

    val other: SmartDnsPath
        get() = if (this == TUNNEL) DIRECT else TUNNEL
}

/**
 * Resolves DNS messages against the Smart DNS servers, over plain DNS, DNS
 * over TLS or DNS over HTTPS, on the current [path] - and moves between the
 * paths on its own.
 *
 * ### The automatic direct path
 *
 * Every query is tried on the current path first. When that fails and the
 * OTHER path answers, the answer is used, and after [SWITCH_AFTER] such
 * queries in a row the other path becomes the current one. It is sticky and
 * symmetric: a provider that starts answering through the tunnel again wins
 * the session back the same way. [probe] makes the first decision up front, so
 * a whitelisting provider does not cost the first page load two timeouts.
 *
 * A REFUSED answer counts as a failure: it is exactly what a provider that
 * does not serve this source address says.
 *
 * ### Connections
 *
 * DoT, DoH and plain-over-TCP connections are pooled per path and server, and
 * a reused one is only trusted when the answer's transaction id matches (DoT,
 * TCP) - DoH answers get the query's id written back, because RFC 8484 lets a
 * server answer with id 0. Certificates are verified with the platform trust
 * store and the platform hostname verifier.
 */
internal class SmartDnsResolver(
    private val servers: List<SmartDnsServer>,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val autoFailover: Boolean = true,
    private val onPathChange: (SmartDnsPath) -> Unit = {},
) {
    @Volatile
    var path: SmartDnsPath = SmartDnsPath.TUNNEL
        private set

    @Volatile
    private var closed = false

    private val streak = AtomicInteger(0)
    private val pathLock = Any()

    private class Pooled(val socket: Socket, val idleSince: Long)

    private val pool = HashMap<String, ArrayDeque<Pooled>>()
    private val poolLock = Any()
    private val warned = ConcurrentHashMap<String, Boolean>()

    private val tlsFactory: SSLSocketFactory by lazy {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    /** An answer to [query], or null when no server answered on either path. */
    fun resolve(query: ByteArray): ByteArray? {
        if (closed || servers.isEmpty()) return null
        val primary = path
        val first = resolveOn(primary, query, PRIMARY_BUDGET_MS)
        if (first != null) {
            streak.set(0)
            return first
        }
        if (!autoFailover || closed) return null
        val secondary = primary.other
        val second = resolveOn(secondary, query, SECONDARY_BUDGET_MS) ?: return null
        if (streak.incrementAndGet() >= SWITCH_AFTER) {
            switchTo(secondary, "the provider stopped answering ${describe(primary)} but answers ${describe(secondary)}")
        }
        return second
    }

    /**
     * Decides the first path before any app asks: the tunnel when the provider
     * answers there, direct when it only answers there. Blocking; run it on a
     * background thread.
     */
    fun probe() {
        if (!autoFailover || servers.isEmpty()) return
        val query = DnsMessage.buildQuery(PROBE_NAME, DnsMessage.TYPE_A, PROBE_ID) ?: return
        if (resolveOn(SmartDnsPath.TUNNEL, query, PROBE_BUDGET_MS) != null) {
            DiagnosticsLog.i(TAG, "Smart DNS answers through the tunnel - staying on the tunnel path.")
            return
        }
        if (closed) return
        if (resolveOn(SmartDnsPath.DIRECT, query, PROBE_BUDGET_MS) != null) {
            switchTo(
                SmartDnsPath.DIRECT,
                "the provider does not answer through the tunnel but answers from this network",
            )
        } else {
            DiagnosticsLog.w(
                TAG,
                "Smart DNS: no server answered through the tunnel or directly yet - every query " +
                    "keeps trying both paths.",
            )
        }
    }

    /**
     * An answer to [query] on [path], trying the servers in order for at most
     * [budgetMs] in total. Never switches paths.
     */
    fun resolveOn(path: SmartDnsPath, query: ByteArray, budgetMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + budgetMs
        for (server in servers) {
            if (closed) return null
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            val timeout = remaining.coerceIn(MIN_ATTEMPT_MS, ATTEMPT_TIMEOUT_MS).toInt()
            val answer = runCatching { exchange(path, server, query, timeout) }.getOrNull()
            if (answer != null && isUsable(query, answer)) {
                warned.remove(warnKey(path, server))
                return answer
            }
            warnOnce(path, server)
        }
        return null
    }

    fun close() {
        closed = true
        synchronized(poolLock) {
            pool.values.forEach { queue -> queue.forEach { runCatching { it.socket.close() } } }
            pool.clear()
        }
    }

    // ------------------------------------------------------------ switching

    private fun switchTo(target: SmartDnsPath, reason: String) {
        synchronized(pathLock) {
            if (path == target) return
            path = target
            streak.set(0)
        }
        DiagnosticsLog.i(TAG, "Smart DNS now reaches the provider ${describe(target)}: $reason.")
        runCatching { onPathChange(target) }
    }

    private fun describe(path: SmartDnsPath): String =
        if (path == SmartDnsPath.TUNNEL) "through the tunnel" else "directly"

    private fun isUsable(query: ByteArray, answer: ByteArray): Boolean =
        DnsMessage.isResponse(answer) &&
            DnsMessage.transactionId(answer) == DnsMessage.transactionId(query) &&
            DnsMessage.rcode(answer) != DnsMessage.RCODE_REFUSED

    private fun warnKey(path: SmartDnsPath, server: SmartDnsServer): String = "${path.name}|${server.label}"

    private fun warnOnce(path: SmartDnsPath, server: SmartDnsServer) {
        if (warned.putIfAbsent(warnKey(path, server), true) == null) {
            DiagnosticsLog.w(TAG, "Smart DNS server ${server.label} did not answer ${describe(path)}.")
        }
    }

    // ------------------------------------------------------------ exchanges

    private fun exchange(
        path: SmartDnsPath,
        server: SmartDnsServer,
        query: ByteArray,
        timeoutMs: Int,
    ): ByteArray? = when (server.protocol) {
        SmartDnsProtocol.PLAIN -> {
            val answer = datagram(path, server, query, timeoutMs)
            if (answer != null && DnsMessage.isTruncated(answer)) {
                stream(path, server, query, timeoutMs, tls = false) ?: answer
            } else {
                answer
            }
        }
        SmartDnsProtocol.DOT -> stream(path, server, query, timeoutMs, tls = true)
        SmartDnsProtocol.DOH -> doh(path, server, query, timeoutMs)
    }

    /** Plain DNS over UDP: a socket of our own directly, or a UDP ASSOCIATE through the engine. */
    private fun datagram(
        path: SmartDnsPath,
        server: SmartDnsServer,
        query: ByteArray,
        timeoutMs: Int,
    ): ByteArray? {
        val address = Socks5Wire.ipv4Bytes(server.host) ?: return null
        if (path == SmartDnsPath.TUNNEL) return datagramThroughUpstream(address, server.port, query, timeoutMs)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(query, query.size, InetAddress.getByAddress(address), server.port))
            return receiveAnswer(socket, query) { buffer, length -> buffer.copyOf(length) }
        }
    }

    private fun datagramThroughUpstream(
        address: ByteArray,
        port: Int,
        query: ByteArray,
        timeoutMs: Int,
    ): ByteArray? {
        Socket().use { control ->
            control.tcpNoDelay = true
            control.connect(InetSocketAddress(upstreamHost, upstreamPort), timeoutMs)
            control.soTimeout = timeoutMs
            val input = control.getInputStream()
            val output = control.getOutputStream()
            if (!Socks5Wire.greet(input, output)) return null
            output.write(Socks5Wire.requestIpv4(Socks5Wire.CMD_UDP_ASSOCIATE, ANY_ADDRESS, 0))
            output.flush()
            val (bound, relayPort) = Socks5Wire.readReply(input) ?: return null
            val relay = Socks5Wire.relayAddress(bound, upstreamHost)
            DatagramSocket(InetSocketAddress(TunnelConfig.SOCKS_HOST, 0)).use { socket ->
                socket.soTimeout = timeoutMs
                val packet = Socks5Wire.udpHeader(address, port) + query
                socket.send(DatagramPacket(packet, packet.size, relay, relayPort))
                return receiveAnswer(socket, query) { buffer, length ->
                    Socks5Wire.parseUdp(buffer, length)?.payload
                }
            }
        }
    }

    /** The first datagram on [socket] that answers [query]; late answers to earlier questions are skipped. */
    private fun receiveAnswer(
        socket: DatagramSocket,
        query: ByteArray,
        unwrap: (ByteArray, Int) -> ByteArray?,
    ): ByteArray? {
        val buffer = ByteArray(MAX_MESSAGE_BYTES)
        val wanted = DnsMessage.transactionId(query)
        for (attempt in 0 until 4) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val message = unwrap(buffer, packet.length) ?: continue
            if (DnsMessage.transactionId(message) == wanted) return message
        }
        return null
    }

    /** Length-prefixed DNS on a stream: DoT when [tls], plain DNS over TCP otherwise. */
    private fun stream(
        path: SmartDnsPath,
        server: SmartDnsServer,
        query: ByteArray,
        timeoutMs: Int,
        tls: Boolean,
    ): ByteArray? {
        val key = "${path.name}|${if (tls) "dot" else "tcp"}|${server.host}:${server.port}"
        val pooled = borrow(key)
        if (pooled != null) {
            val answer = runCatching { framed(pooled, query, timeoutMs) }.getOrNull()
            if (answer != null) {
                release(key, pooled)
                return answer
            }
            runCatching { pooled.close() }
        }
        val fresh = open(path, server.host, server.port, tls, timeoutMs)
        val answer = runCatching { framed(fresh, query, timeoutMs) }.getOrNull()
        if (answer != null) release(key, fresh) else runCatching { fresh.close() }
        return answer
    }

    private fun framed(socket: Socket, query: ByteArray, timeoutMs: Int): ByteArray {
        socket.soTimeout = timeoutMs
        val output = socket.getOutputStream()
        // One write, so a TLS stack puts the length and the message in one record.
        output.write(Socks5Wire.portBytes(query.size) + query)
        output.flush()
        val input = socket.getInputStream()
        val length = Socks5Wire.readExact(input, 2) ?: throw IOException("truncated DNS length")
        val size = Socks5Wire.u16(length, 0)
        if (size < 12) throw IOException("bad DNS length")
        val answer = Socks5Wire.readExact(input, size) ?: throw IOException("truncated DNS answer")
        if (DnsMessage.transactionId(answer) != DnsMessage.transactionId(query)) {
            throw IOException("DNS answer id does not match the question")
        }
        return answer
    }

    /** DNS over HTTPS: an HTTP/1.1 POST of application/dns-message, on a pooled TLS connection. */
    private fun doh(
        path: SmartDnsPath,
        server: SmartDnsServer,
        query: ByteArray,
        timeoutMs: Int,
    ): ByteArray? {
        val key = "${path.name}|doh|${server.host}:${server.port}"
        val pooled = borrow(key)
        if (pooled != null) {
            val result = runCatching { dohOnce(pooled, server, query, timeoutMs) }.getOrNull()
            if (result != null) {
                keepOrClose(key, pooled, result.second)
                return result.first
            }
            runCatching { pooled.close() }
        }
        val fresh = open(path, server.host, server.port, true, timeoutMs)
        val result = runCatching { dohOnce(fresh, server, query, timeoutMs) }.getOrNull()
        if (result == null) {
            runCatching { fresh.close() }
            return null
        }
        keepOrClose(key, fresh, result.second)
        return result.first
    }

    /** The answer, and whether the connection may be reused. */
    private fun dohOnce(
        socket: Socket,
        server: SmartDnsServer,
        query: ByteArray,
        timeoutMs: Int,
    ): Pair<ByteArray, Boolean> {
        socket.soTimeout = timeoutMs
        val output = socket.getOutputStream()
        output.write(DohWire.request(server, query))
        output.flush()
        val response = DohWire.readResponse(socket.getInputStream()) ?: throw IOException("no HTTP response")
        if (response.status != 200) throw IOException("HTTP ${response.status}")
        val answer = response.body
        if (answer.size < 12) throw IOException("short DNS answer")
        answer[0] = query[0]
        answer[1] = query[1]
        return answer to !response.close
    }

    private fun keepOrClose(key: String, socket: Socket, reusable: Boolean) {
        if (reusable) {
            release(key, socket)
        } else {
            runCatching { socket.close() }
        }
    }

    /**
     * A connected stream to [host]:[port] on [path], wrapped in verified TLS
     * when [tls]. Throws on any failure; the caller's runCatching owns that.
     */
    private fun open(path: SmartDnsPath, host: String, port: Int, tls: Boolean, timeoutMs: Int): Socket {
        val raw: Socket = if (path == SmartDnsPath.DIRECT) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw error
            }
            socket
        } else {
            Socks5Wire.dial(upstreamHost, upstreamPort, host, port, timeoutMs, timeoutMs)
                ?: throw IOException("could not reach $host:$port through the tunnel")
        }
        raw.soTimeout = timeoutMs
        if (!tls) return raw
        try {
            val secure = tlsFactory.createSocket(raw, host, port, true) as SSLSocket
            secure.soTimeout = timeoutMs
            secure.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, secure.session)) {
                runCatching { secure.close() }
                throw SSLPeerUnverifiedException("certificate does not match $host")
            }
            return secure
        } catch (error: IOException) {
            runCatching { raw.close() }
            throw error
        }
    }

    // ---------------------------------------------------------------- pool

    private fun borrow(key: String): Socket? {
        synchronized(poolLock) {
            val queue = pool[key] ?: return null
            var entry = queue.pollLast()
            while (entry != null) {
                val socket = entry.socket
                val idleFor = System.currentTimeMillis() - entry.idleSince
                val stale = socket.isClosed || socket.isInputShutdown || idleFor > POOL_IDLE_MS
                if (!stale) return socket
                runCatching { socket.close() }
                entry = queue.pollLast()
            }
        }
        return null
    }

    private fun release(key: String, socket: Socket) {
        if (closed || socket.isClosed) {
            runCatching { socket.close() }
            return
        }
        synchronized(poolLock) {
            val queue = pool.getOrPut(key) { ArrayDeque() }
            if (queue.size >= POOL_MAX) {
                runCatching { socket.close() }
                return
            }
            queue.addLast(Pooled(socket, System.currentTimeMillis()))
        }
    }

    private companion object {
        const val TAG = "smartdns"

        /** How long the current path gets, and then the other one. */
        const val PRIMARY_BUDGET_MS = 4_000L
        const val SECONDARY_BUDGET_MS = 4_000L
        const val PROBE_BUDGET_MS = 8_000L
        const val ATTEMPT_TIMEOUT_MS = 3_000L
        const val MIN_ATTEMPT_MS = 500L

        /** Queries in a row the other path must rescue before it becomes the path. */
        const val SWITCH_AFTER = 2

        const val POOL_IDLE_MS = 30_000L
        const val POOL_MAX = 4
        const val MAX_MESSAGE_BYTES = 65_535

        /** Covered by every sanctions DNS provider, and geo-blocked for Iran. */
        const val PROBE_NAME = "gemini.google.com"
        const val PROBE_ID = 0x5D05

        val ANY_ADDRESS = ByteArray(4)
    }
}

/**
 * The HTTP/1.1 half of DNS over HTTPS (RFC 8484), pure and testable.
 *
 * Reads byte by byte up to the end of the headers and then exactly the body,
 * never further: the connection is pooled, and a buffered reader that read
 * ahead would swallow the start of the next response.
 */
internal object DohWire {

    const val MAX_BODY_BYTES = 65_535
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_LINE_BYTES = 1024

    class Head(val status: Int, val contentLength: Int?, val chunked: Boolean, val close: Boolean)

    class Response(val status: Int, val body: ByteArray, val close: Boolean)

    /** The POST for [query] to [server]. */
    fun request(server: SmartDnsServer, query: ByteArray): ByteArray {
        val host = if (server.port == SmartDnsProtocol.DOH.defaultPort) server.host else "${server.host}:${server.port}"
        val path = server.path.ifEmpty { "/dns-query" }
        val head = "POST $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: Aether\r\n" +
            "Accept: application/dns-message\r\n" +
            "Content-Type: application/dns-message\r\n" +
            "Content-Length: ${query.size}\r\n" +
            "\r\n"
        return head.toByteArray(Charsets.US_ASCII) + query
    }

    /** Status line and the three headers that decide framing, or null when it is not HTTP. */
    fun parseHead(text: String): Head? {
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        val statusLine = lines.firstOrNull() ?: return null
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
        val status = parts[1].toIntOrNull() ?: return null
        var length: Int? = null
        var chunked = false
        var close = parts[0] == "HTTP/1.0"
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim().lowercase()
            when (name) {
                "content-length" -> length = value.toIntOrNull()
                "transfer-encoding" -> chunked = value.contains("chunked")
                "connection" -> {
                    if (value.contains("close")) close = true
                    if (value.contains("keep-alive")) close = false
                }
            }
        }
        return Head(status, length, chunked, close)
    }

    /**
     * One response from [input]: interim 1xx responses are skipped, the body is
     * read by Content-Length, chunked encoding, or to EOF.
     */
    fun readResponse(input: InputStream): Response? {
        for (attempt in 0 until 4) {
            val text = readHead(input) ?: return null
            val head = parseHead(text) ?: return null
            if (head.status in 100..199) continue
            val length = head.contentLength
            val body = when {
                head.chunked -> readChunked(input)
                length != null -> if (length in 0..MAX_BODY_BYTES) Socks5Wire.readExact(input, length) else null
                else -> readToEnd(input)
            } ?: return null
            val close = head.close || (!head.chunked && length == null)
            return Response(head.status, body, close)
        }
        return null
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return String(out.toByteArray(), Charsets.ISO_8859_1)
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (out.length < MAX_LINE_BYTES) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(b.toChar())
        }
        return null
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (out.size() <= MAX_BODY_BYTES) {
            val line = readLine(input) ?: return null
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size < 0) return null
            if (size == 0) {
                for (trailer in 0 until 32) {
                    val text = readLine(input) ?: return null
                    if (text.isEmpty()) return out.toByteArray()
                }
                return null
            }
            if (out.size() + size > MAX_BODY_BYTES) return null
            val chunk = Socks5Wire.readExact(input, size) ?: return null
            out.write(chunk, 0, chunk.size)
            readLine(input) ?: return null
        }
        return null
    }

    private fun readToEnd(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BODY_BYTES) return null
            out.write(buffer, 0, read)
        }
    }
}

/**
 * Finds the provider's SNI-proxy ranges, so the direct path can send them
 * straight out too: a provider that checks the source address on its resolver
 * checks it on its proxy as well.
 *
 * The rule is deliberately conservative. A /24 counts only when the Smart DNS
 * answers put it behind at least TWO different companies' names (a real
 * CDN range is one company's; a Smart DNS proxy answers for everyone), and
 * its /16 appears in none of the ordinary resolver's answers for the same
 * names. A false positive would send one of those companies' real servers
 * around the tunnel, so doubt means "not a proxy".
 */
internal object SmartDnsProxies {

    class Observation(val owner: String, val smart: List<String>, val ordinary: List<String>)

    /** Names geo-blocked for Iran, one per company, that sanctions DNS providers cover. */
    val PROBES: List<Pair<String, String>> = listOf(
        "gemini.google.com" to "google",
        "chatgpt.com" to "openai",
        "claude.ai" to "anthropic",
        "hub.docker.com" to "docker",
        "developer.nvidia.com" to "nvidia",
        "www.spotify.com" to "spotify",
    )

    /** The proxy /24s in [observations], ascending, as [net24] values. */
    fun select(observations: List<Observation>): List<Int> {
        val ordinary16 = observations.flatMap { it.ordinary }.mapNotNull { net24(it) }.map { it shr 8 }.toSet()
        val owners = HashMap<Int, MutableSet<String>>()
        for (observation in observations) {
            for (address in observation.smart) {
                val net = net24(address) ?: continue
                if ((net shr 8) in ordinary16) continue
                owners.getOrPut(net) { HashSet() }.add(observation.owner)
            }
        }
        return owners.filter { it.value.size >= 2 }.keys.sorted()
    }

    /** The /24 of a dotted quad as a 24-bit number, or null. */
    fun net24(address: String): Int? = Socks5Wire.ipv4Bytes(address)?.let { net24(it) }

    fun net24(address: ByteArray): Int? {
        if (address.size != 4) return null
        return ((address[0].toInt() and 0xFF) shl 16) or
            ((address[1].toInt() and 0xFF) shl 8) or
            (address[2].toInt() and 0xFF)
    }

    fun describe(net: Int): String = "${(net shr 16) and 0xFF}.${(net shr 8) and 0xFF}.${net and 0xFF}.0/24"
}
