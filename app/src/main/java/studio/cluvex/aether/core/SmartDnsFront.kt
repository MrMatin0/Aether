package studio.cluvex.aether.core

import studio.cluvex.aether.model.SmartDnsProtocol
import studio.cluvex.aether.model.SmartDnsServer
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The chain entry of an Aether-only session while Smart DNS is active: a
 * loopback SOCKS5 in front of the engine that answers the device's DNS itself.
 *
 * ### Why a front, and not the provider's address on the TUN
 *
 * Android's resolver only speaks plain DNS on port 53 to the addresses a VPN
 * advertises. DoH and DoT need something on the phone that speaks them, and
 * reaching the provider DIRECTLY (for providers that only answer registered /
 * Iranian addresses) needs something that can open a socket outside the
 * tunnel - the engine can only do that for fixed `--route-direct` rules. So the
 * TUN advertises [TunnelConfig.SMART_DNS_RESOLVER], hev forwards everything to
 * this front, and:
 *
 *  - **DNS to the virtual resolver** (UDP ASSOCIATE datagrams, or a CONNECT to
 *    port 53) is answered here by [SmartDnsResolver] over plain DNS, DoH or
 *    DoT. AAAA, HTTPS and SVCB are answered NODATA locally, so an app is never
 *    handed an address that skips the provider's proxy.
 *  - **CONNECT** is relayed to the engine verbatim. On the direct path, a
 *    target inside one of the provider's detected proxy ranges is dialled
 *    straight out instead (see [SmartDnsProxies]). A DOMAIN target (proxy
 *    mode, LAN sharing) is resolved through Smart DNS first, unless the user
 *    has domain routing rules the engine must still see.
 *  - **Every other datagram** is relayed to the engine's own UDP ASSOCIATE,
 *    unchanged.
 *
 * ### Cost
 *
 * While Smart DNS is active every TCP connection is relayed by two threads
 * here on its way to the engine, as it already is on a Psiphon or Tor entry.
 * That is the price of the feature, which is why it is off by default.
 *
 * Bound to loopback ONLY. Concurrency is capped.
 */
internal class SmartDnsFront(
    private val listenPort: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val protocol: SmartDnsProtocol,
    private val servers: List<SmartDnsServer>,
    private val rewriteDomains: Boolean,
) {
    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    private val liveClients = AtomicInteger(0)
    private val clients: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val associations: MutableSet<Association> =
        Collections.newSetFromMap(ConcurrentHashMap<Association, Boolean>())

    private val resolver = SmartDnsResolver(
        servers = servers,
        upstreamHost = upstreamHost,
        upstreamPort = upstreamPort,
        autoFailover = true,
        onPathChange = { path -> pathChanged(path) },
    )

    /** The provider's proxy /24s, only while the path is DIRECT. */
    @Volatile
    private var directNets: Set<Int> = emptySet()
    private val learning = AtomicBoolean(false)
    private val queryIds = AtomicInteger(1)

    private class CachedName(val address: ByteArray?, val expiresAt: Long)

    private val nameCache = ConcurrentHashMap<String, CachedName>()

    private val queries = AtomicLong(0)
    private val nodata = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val rewritten = AtomicLong(0)
    private val directConnects = AtomicLong(0)
    private val udpDropped = AtomicLong(0)

    /** Resolution is blocking I/O with a timeout; bounded so a burst cannot become a thread bomb. */
    private val workers = Executors.newFixedThreadPool(DNS_WORKERS) { runnable ->
        Thread(runnable, "smart-dns-query").apply { isDaemon = true }
    }

    /** Binds the listener; true when it is accepting. Synchronous: this port IS the tunnel. */
    fun start(): Boolean {
        if (running) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(TunnelConfig.SOCKS_HOST, listenPort), BACKLOG)
            }
        }.getOrElse { error ->
            DiagnosticsLog.e(TAG, "Could not bind the Smart DNS front on port $listenPort: $error")
            return false
        }
        server = socket
        running = true
        SmartDnsRuntime.begin(protocol)
        DiagnosticsLog.i(
            TAG,
            "Smart DNS front on ${TunnelConfig.SOCKS_HOST}:$listenPort -> $upstreamHost:$upstreamPort " +
                "(${protocol.name}: ${servers.joinToString(", ") { it.label }}; virtual resolver " +
                "${TunnelConfig.SMART_DNS_RESOLVER}; name rewrite " +
                (if (rewriteDomains) "on" else "off, domain routing rules present") + ")",
        )
        spawn("smart-dns-accept") { acceptLoop(socket) }
        spawn("smart-dns-probe") { resolver.probe() }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { server?.close() }
        server = null
        workers.shutdownNow()
        resolver.close()
        associations.forEach { it.close() }
        associations.clear()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        nameCache.clear()
        SmartDnsRuntime.reset()
        DiagnosticsLog.i(
            TAG,
            "Smart DNS front stopped. Session: queries=${queries.get()}, answered NODATA=${nodata.get()}, " +
                "failed=${failures.get()}, names rewritten=${rewritten.get()}, " +
                "direct connects=${directConnects.get()}, udp dropped=${udpDropped.get()}",
        )
    }

    // --------------------------------------------------------------- accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                if (!running || socket.isClosed) break
                runCatching { Thread.sleep(ACCEPT_BACKOFF_MS) }
                continue
            }
            if (liveClients.incrementAndGet() > MAX_CLIENTS) {
                liveClients.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            clients.add(client)
            spawn("smart-dns-conn") {
                try {
                    client.tcpNoDelay = true
                    serve(client)
                } catch (_: Exception) {
                    // Per-connection failures are non-fatal by design.
                } finally {
                    runCatching { client.close() }
                    clients.remove(client)
                    liveClients.decrementAndGet()
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        if (!Socks5Wire.acceptGreeting(input, output)) return

        // VER CMD RSV ATYP DST.ADDR DST.PORT
        val head = Socks5Wire.readExact(input, 4) ?: return
        if (head[0] != Socks5Wire.VERSION) return
        val command = head[1].toInt() and 0xFF
        val atyp = head[3].toInt() and 0xFF
        val address = Socks5Wire.readAddress(input, atyp) ?: return
        val portBytes = Socks5Wire.readExact(input, 2) ?: return
        val port = Socks5Wire.u16(portBytes, 0)

        when (command) {
            Socks5Wire.CMD_CONNECT -> connect(client, atyp, address, port, head + address + portBytes)
            Socks5Wire.CMD_UDP_ASSOCIATE -> udpAssociate(client)
            else -> {
                output.write(Socks5Wire.reply(Socks5Wire.REP_COMMAND_NOT_SUPPORTED))
                output.flush()
            }
        }
    }

    // -------------------------------------------------------------- CONNECT

    private fun connect(client: Socket, atyp: Int, address: ByteArray, port: Int, request: ByteArray) {
        val output = client.getOutputStream()
        if (atyp == Socks5Wire.ATYP_IPV4 && address.contentEquals(RESOLVER)) {
            if (port == DNS_PORT) {
                output.write(Socks5Wire.reply(Socks5Wire.REP_SUCCEEDED))
                output.flush()
                serveDnsOverTcp(client)
            } else {
                // Private DNS in "automatic" mode tries DoT on 853 first; a fast
                // refusal sends it straight to the plain port.
                output.write(Socks5Wire.reply(Socks5Wire.REP_CONNECTION_REFUSED))
                output.flush()
            }
            return
        }

        var target: ByteArray? = if (atyp == Socks5Wire.ATYP_IPV4) address else null
        var upstreamRequest = request
        if (atyp == Socks5Wire.ATYP_DOMAIN && rewriteDomains && address.size > 1) {
            val name = String(address, 1, address.size - 1, Charsets.US_ASCII)
            val resolved = resolveName(name)
            if (resolved != null) {
                target = resolved
                upstreamRequest = Socks5Wire.requestIpv4(Socks5Wire.CMD_CONNECT, resolved, port)
                rewritten.incrementAndGet()
            }
        }

        val literal = target
        if (literal != null && isDirect(literal)) {
            connectDirect(client, literal, port)
            return
        }
        passThrough(client, upstreamRequest)
    }

    private fun isDirect(address: ByteArray): Boolean {
        if (resolver.path != SmartDnsPath.DIRECT) return false
        val net = SmartDnsProxies.net24(address) ?: return false
        return net in directNets
    }

    /** A Smart DNS answer for [name], cached briefly; null keeps the name for the engine. */
    private fun resolveName(name: String): ByteArray? {
        Socks5Wire.ipv4Bytes(name)?.let { return it }
        val key = name.trim().trimEnd('.').lowercase()
        val now = System.currentTimeMillis()
        val cached = nameCache[key]
        if (cached != null && cached.expiresAt > now) return cached.address
        val query = DnsMessage.buildQuery(key, DnsMessage.TYPE_A, nextId()) ?: return null
        val answer = resolver.resolve(query)
        val first = answer?.let { DnsMessage.answerIpv4s(it).firstOrNull() }
        val bytes = first?.let { Socks5Wire.ipv4Bytes(it) }
        if (nameCache.size >= NAME_CACHE_MAX) nameCache.clear()
        val ttl = if (bytes != null) NAME_TTL_MS else NEGATIVE_TTL_MS
        nameCache[key] = CachedName(bytes, now + ttl)
        return bytes
    }

    private fun connectDirect(client: Socket, address: ByteArray, port: Int) {
        val output = client.getOutputStream()
        val remote = Socket()
        try {
            val connected = runCatching {
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(InetAddress.getByAddress(address), port), DIAL_TIMEOUT_MS)
            }.isSuccess
            if (!connected) {
                output.write(Socks5Wire.reply(Socks5Wire.REP_HOST_UNREACHABLE))
                output.flush()
                return
            }
            directConnects.incrementAndGet()
            output.write(Socks5Wire.reply(Socks5Wire.REP_SUCCEEDED))
            output.flush()
            bridge(client, remote)
        } finally {
            runCatching { remote.close() }
        }
    }

    /** Relays a CONNECT to the engine; the engine's own reply reaches the client unchanged. */
    private fun passThrough(client: Socket, request: ByteArray) {
        val upstream = Socket()
        try {
            val ready = runCatching {
                upstream.tcpNoDelay = true
                upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), DIAL_TIMEOUT_MS)
                upstream.soTimeout = IDLE_TIMEOUT_MS
                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()
                if (!Socks5Wire.greet(upIn, upOut)) throw IOException("engine refused the greeting")
                upOut.write(request)
                upOut.flush()
            }.isSuccess
            if (!ready) {
                runCatching {
                    val output = client.getOutputStream()
                    output.write(Socks5Wire.reply(Socks5Wire.REP_GENERAL_FAILURE))
                    output.flush()
                }
                return
            }
            bridge(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** RFC 7766 DNS over TCP to the virtual resolver, answered here. */
    private fun serveDnsOverTcp(client: Socket) {
        client.soTimeout = DNS_TCP_IDLE_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()
        while (running) {
            val length = Socks5Wire.readExact(input, 2) ?: return
            val size = Socks5Wire.u16(length, 0)
            if (size < 12) return
            val query = Socks5Wire.readExact(input, size) ?: return
            val answer = answerQuery(query) ?: return
            output.write(Socks5Wire.portBytes(answer.size) + answer)
            output.flush()
        }
    }

    // ---------------------------------------------------------------- DNS

    /** The answer the device gets for [query]: NODATA, the provider's answer, or SERVFAIL. */
    private fun answerQuery(query: ByteArray): ByteArray? {
        queries.incrementAndGet()
        val type = DnsMessage.questionType(query)
        if (type == DnsMessage.TYPE_AAAA || type == DnsMessage.TYPE_HTTPS || type == DnsMessage.TYPE_SVCB) {
            val empty = DnsMessage.nodataResponse(query)
            if (empty != null) {
                nodata.incrementAndGet()
                return empty
            }
        }
        val answer = resolver.resolve(query)
        if (answer != null) return answer
        failures.incrementAndGet()
        return DnsMessage.servfailResponse(query)
    }

    private fun nextId(): Int = queryIds.getAndIncrement() and 0xFFFF

    // -------------------------------------------------------- UDP ASSOCIATE

    /** The engine side of one association: its own control connection and relay. */
    private class UpstreamUdp(
        val control: Socket,
        val socket: DatagramSocket,
        val relay: InetAddress,
        val relayPort: Int,
    ) {
        @Volatile
        var closed = false

        fun close() {
            closed = true
            runCatching { socket.close() }
            runCatching { control.close() }
        }
    }

    /** One client association; its engine-side twin is opened on the first non-DNS datagram. */
    private inner class Association(val relay: DatagramSocket) {
        @Volatile
        var clientAddress: InetAddress? = null

        @Volatile
        var clientPort: Int = 0

        @Volatile
        private var upstream: UpstreamUdp? = null

        @Volatile
        private var retryAt = 0L

        @Volatile
        private var closed = false

        private val lock = Any()

        fun toClient(data: ByteArray, length: Int) {
            val to = clientAddress ?: return
            runCatching { relay.send(DatagramPacket(data, 0, length, to, clientPort)) }
        }

        fun forward(data: ByteArray, length: Int) {
            val up = upstreamOrOpen()
            if (up == null) {
                udpDropped.incrementAndGet()
                return
            }
            runCatching { up.socket.send(DatagramPacket(data, 0, length, up.relay, up.relayPort)) }
        }

        private fun upstreamOrOpen(): UpstreamUdp? {
            val current = upstream
            if (current != null && !current.closed) return current
            synchronized(lock) {
                val again = upstream
                if (again != null && !again.closed) return again
                if (closed || System.currentTimeMillis() < retryAt) return null
                val opened = openUpstream(this)
                if (opened == null) retryAt = System.currentTimeMillis() + UPSTREAM_RETRY_MS
                upstream = opened
                return opened
            }
        }

        fun close() {
            closed = true
            runCatching { relay.close() }
            upstream?.close()
            upstream = null
        }
    }

    private fun udpAssociate(client: Socket) {
        val relay = runCatching { DatagramSocket(InetSocketAddress(TunnelConfig.SOCKS_HOST, 0)) }.getOrNull()
        if (relay == null) {
            runCatching {
                val output = client.getOutputStream()
                output.write(Socks5Wire.reply(Socks5Wire.REP_GENERAL_FAILURE))
                output.flush()
            }
            return
        }
        val association = Association(relay)
        associations.add(association)
        try {
            val output = client.getOutputStream()
            output.write(Socks5Wire.replyBound(Socks5Wire.REP_SUCCEEDED, LOOPBACK, relay.localPort))
            output.flush()
            val pump = spawn("smart-dns-udp") { udpLoop(association) }
            // RFC 1928: the association lives exactly as long as this connection.
            drainUntilEof(client.getInputStream())
            association.close()
            runCatching { pump.join(UDP_JOIN_MS) }
        } finally {
            association.close()
            associations.remove(association)
        }
    }

    private fun udpLoop(association: Association) {
        val relay = association.relay
        val buffer = ByteArray(UDP_BUFFER_BYTES)
        while (running && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (_: Exception) {
                return
            }
            association.clientAddress = packet.address
            association.clientPort = packet.port
            val length = packet.length
            val datagram = Socks5Wire.parseUdp(buffer, length) ?: continue
            if (datagram.atyp == Socks5Wire.ATYP_IPV4 && datagram.address.contentEquals(RESOLVER)) {
                if (datagram.port == DNS_PORT) answerDatagram(association, datagram)
                continue
            }
            association.forward(buffer, length)
        }
    }

    private fun answerDatagram(association: Association, datagram: Socks5Wire.UdpDatagram) {
        runCatching {
            workers.execute {
                val answer = answerQuery(datagram.payload)
                if (answer != null) {
                    val out = datagram.header + answer
                    association.toClient(out, out.size)
                }
            }
        }
    }

    /** The engine's UDP ASSOCIATE for one client association, with its reply pump. */
    private fun openUpstream(association: Association): UpstreamUdp? {
        val control = Socket()
        val bound = runCatching {
            control.tcpNoDelay = true
            control.connect(InetSocketAddress(upstreamHost, upstreamPort), DIAL_TIMEOUT_MS)
            control.soTimeout = DIAL_TIMEOUT_MS
            val input = control.getInputStream()
            val output = control.getOutputStream()
            if (!Socks5Wire.greet(input, output)) throw IOException("engine refused the greeting")
            output.write(Socks5Wire.requestIpv4(Socks5Wire.CMD_UDP_ASSOCIATE, ANY_ADDRESS, 0))
            output.flush()
            Socks5Wire.readReply(input) ?: throw IOException("engine refused UDP ASSOCIATE")
        }.getOrNull()
        if (bound == null) {
            runCatching { control.close() }
            return null
        }
        val socket = runCatching { DatagramSocket(InetSocketAddress(TunnelConfig.SOCKS_HOST, 0)) }.getOrNull()
        if (socket == null) {
            runCatching { control.close() }
            return null
        }
        val relayAddress = runCatching { Socks5Wire.relayAddress(bound.first, upstreamHost) }.getOrNull()
        if (relayAddress == null) {
            runCatching { socket.close() }
            runCatching { control.close() }
            return null
        }
        val up = UpstreamUdp(control, socket, relayAddress, bound.second)
        runCatching { control.soTimeout = 0 }
        spawn("smart-dns-udp-up") {
            val buffer = ByteArray(UDP_BUFFER_BYTES)
            while (running && !up.closed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    break
                }
                // Already a SOCKS5 UDP reply naming the original destination.
                association.toClient(buffer, packet.length)
            }
            up.close()
        }
        spawn("smart-dns-udp-ctl") {
            runCatching { drainUntilEof(control.getInputStream()) }
            up.close()
        }
        return up
    }

    // ------------------------------------------------------ direct path

    private fun pathChanged(path: SmartDnsPath) {
        SmartDnsRuntime.setPath(path)
        nameCache.clear()
        if (path == SmartDnsPath.DIRECT) {
            learnProxies()
        } else {
            directNets = emptySet()
            SmartDnsRuntime.setProxyRanges(0)
        }
    }

    /**
     * Detects the provider's proxy ranges by comparing its answers for a few
     * covered names with an ordinary resolver's (through the tunnel).
     */
    private fun learnProxies() {
        if (!running || !learning.compareAndSet(false, true)) return
        spawn("smart-dns-learn") {
            try {
                val ordinary = SmartDnsResolver(ORDINARY_SERVERS, upstreamHost, upstreamPort, autoFailover = false)
                val observations = try {
                    SmartDnsProxies.PROBES.mapNotNull { (name, owner) ->
                        val query = DnsMessage.buildQuery(name, DnsMessage.TYPE_A, nextId())
                        if (query == null || !running) {
                            null
                        } else {
                            val smart = resolver.resolveOn(SmartDnsPath.DIRECT, query, LEARN_BUDGET_MS)
                                ?.let { DnsMessage.answerIpv4s(it) }.orEmpty()
                            val plain = ordinary.resolveOn(SmartDnsPath.TUNNEL, query, LEARN_BUDGET_MS)
                                ?.let { DnsMessage.answerIpv4s(it) }.orEmpty()
                            if (smart.isEmpty() || plain.isEmpty()) null else SmartDnsProxies.Observation(owner, smart, plain)
                        }
                    }
                } finally {
                    ordinary.close()
                }
                val nets = SmartDnsProxies.select(observations)
                if (running && resolver.path == SmartDnsPath.DIRECT) {
                    directNets = nets.toSet()
                    SmartDnsRuntime.setProxyRanges(nets.size)
                    if (nets.isEmpty()) {
                        DiagnosticsLog.w(
                            TAG,
                            "Smart DNS direct path: could not identify the provider's proxy ranges " +
                                "(${observations.size} probes answered) - covered sites still go through the tunnel.",
                        )
                    } else {
                        DiagnosticsLog.i(
                            TAG,
                            "Smart DNS direct path: provider proxy ranges " +
                                nets.joinToString(", ") { SmartDnsProxies.describe(it) } + " go direct as well.",
                        )
                    }
                }
            } finally {
                learning.set(false)
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    private fun spawn(name: String, block: () -> Unit): Thread =
        Thread(null, Runnable { block() }, name, THREAD_STACK_BYTES).apply {
            isDaemon = true
            start()
        }

    private fun drainUntilEof(input: InputStream) {
        val scratch = ByteArray(256)
        while (running) {
            val read = try {
                input.read(scratch)
            } catch (_: Exception) {
                return
            }
            if (read < 0) return
        }
    }

    private fun bridge(client: Socket, upstream: Socket) {
        val reverse = spawn("smart-dns-pipe") { pipe(upstream, client) }
        pipe(client, upstream)
        runCatching { reverse.join(RELAY_JOIN_MS) }
    }

    private fun pipe(from: Socket, to: Socket) {
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
        }
    }

    private companion object {
        const val TAG = "smartdns"

        const val DNS_PORT = 53
        const val DNS_WORKERS = 8
        const val DNS_TCP_IDLE_MS = 30_000

        const val BACKLOG = 64
        const val MAX_CLIENTS = 1024
        const val ACCEPT_BACKOFF_MS = 200L
        const val DIAL_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 300_000
        const val RELAY_BUFFER_BYTES = 32 * 1024
        const val RELAY_JOIN_MS = 10_000L
        const val UDP_BUFFER_BYTES = 65_535
        const val UDP_JOIN_MS = 2_000L
        const val UPSTREAM_RETRY_MS = 2_000L
        const val THREAD_STACK_BYTES = 256L * 1024L

        const val NAME_CACHE_MAX = 512
        const val NAME_TTL_MS = 60_000L
        const val NEGATIVE_TTL_MS = 10_000L
        const val LEARN_BUDGET_MS = 5_000L

        val RESOLVER: ByteArray = requireNotNull(Socks5Wire.ipv4Bytes(TunnelConfig.SMART_DNS_RESOLVER))
        val LOOPBACK: ByteArray = byteArrayOf(127, 0, 0, 1)
        val ANY_ADDRESS = ByteArray(4)

        /** An ordinary resolver, reached through the tunnel, to compare the provider's answers with. */
        val ORDINARY_SERVERS: List<SmartDnsServer> =
            TunnelConfig.DNS_SERVERS.map { SmartDnsServer(SmartDnsProtocol.PLAIN, it, SmartDnsProtocol.PLAIN.defaultPort) }
    }
}
