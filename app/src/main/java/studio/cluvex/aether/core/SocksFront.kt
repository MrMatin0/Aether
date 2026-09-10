package studio.cluvex.aether.core

import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** How the front answers a DNS query it has intercepted. */
internal sealed interface DnsRoute {

    /**
     * A real UDP DNS server on loopback: tor's own `DNSPort`. Preferred when
     * available, because tor resolves the name inside the network (no exit-side
     * DNS leak) and there is no extra circuit to build per query.
     */
    data class LocalUdp(val host: String, val port: Int) : DnsRoute

    /**
     * DNS over TCP (RFC 7766) through the upstream SOCKS5, to [resolvers]
     * (`ip` or `ip:port`). Works through ANY SOCKS5 proxy that can CONNECT,
     * which is exactly what Psiphon's local proxy is.
     */
    data class OverSocksTcp(val resolvers: List<String>) : DnsRoute
}

/**
 * A loopback SOCKS5 front that adds the ONE thing the TUN forwarder needs and
 * neither Psiphon's nor Tor's local proxy provides: an answer to UDP DNS.
 *
 * ### The problem this exists to solve
 *
 * hev-socks5-tunnel forwards the device's TCP through SOCKS5 CONNECT and its
 * UDP through SOCKS5 UDP ASSOCIATE. Psiphon's local SOCKS5 implements CONNECT
 * only (it carries UDP through its own `udpgw` side channel instead), and tor
 * carries no UDP at all, by design. Point the forwarder straight at either one
 * and the result is the failure mode [Diagnostics] already has a comment about:
 * TCP passes every check, DNS never resolves, and the app says "connected"
 * while no site opens.
 *
 * ### What it does
 *
 *  - **CONNECT** is passed through byte for byte: the front performs its own
 *    greeting with the client, then greets upstream and replays the client's
 *    request verbatim, so remote DNS resolution (ATYP=DOMAIN, which is how the
 *    tunnel avoids leaking names) still happens inside the tunnel and this class
 *    never has to understand addresses it forwards.
 *  - **UDP ASSOCIATE** is accepted, and datagrams for port 53 are answered out
 *    of band via [DnsRoute]. The reply is wrapped in the request's own SOCKS5
 *    UDP header, which is what identifies the sender to the client.
 *  - **Any other UDP** is dropped, because there is nothing honest to do with
 *    it: neither core can carry it. Every dropped datagram is COUNTED, and the
 *    first UDP/443 one says what it means in the log - see below.
 *  - **BIND** is refused with a proper SOCKS5 error rather than a dead socket.
 *
 * ### 1.4.7: why apps said "no internet" on a Psiphon exit
 *
 * Two of the three root causes were here (the third was the TUN's IPv6 address;
 * see TunFactory). Full analysis in `docs/PSIPHON_APP_CONNECTIVITY.md`.
 *
 *  - **AAAA was forwarded to a resolver that answers it.** A Psiphon exit is
 *    IPv4-only in practice, so every AAAA record handed to an app is a
 *    connection that must fail first. The front now probes the exit ONCE, up
 *    front, and answers AAAA locally with NODATA only once the exit has PROVEN
 *    it cannot dial IPv6 ([exitIpv6]). Unknown means "leave it alone": guessing
 *    would break a v6-capable exit to fix a v4-only one.
 *  - **Every name cost a new connection.** A fresh SOCKS5 handshake plus a
 *    fresh TCP connection per lookup, through two chained tunnels, with eight
 *    workers - so a page needing thirty names simply did not resolve, while
 *    Telegram (hard-coded IPs) and a warm Instagram (cached addresses) kept
 *    working. Connections are pooled per resolver and reused, and a reused one
 *    is only trusted when the answer's transaction id matches the question's.
 *
 * ### The QUIC gap, stated rather than hidden
 *
 * UDP/443 still cannot be carried: this front's upstream is a CONNECT-only
 * SOCKS5, so there is no path for a datagram and no way to return an ICMP
 * port-unreachable that would make a client fall back fast. Clients that pin
 * HTTP/3 for their own origins read that as a dead link. Carrying it needs a
 * udpgw-style relay on the Psiphon hop, which is tracked separately - what this
 * class does now is make the drop VISIBLE instead of silent.
 *
 * ### Security posture
 *
 * Bound to loopback ONLY, always. Everything that connects to it is already
 * inside this app's sandbox (the in-process TUN forwarder, or an app the user
 * pointed at the proxy port), and concurrency is capped so a runaway client
 * cannot exhaust the process's threads.
 */
internal class SocksFront(
    private val listenPort: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val dns: DnsRoute,
) {
    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    private val liveClients = AtomicInteger(0)

    /**
     * Whether the exit can dial IPv6 at all: true, false, or null for "not
     * known yet".
     *
     * THREE STATES ON PURPOSE. AAAA is only suppressed on a proven `false`; an
     * unknown result must behave exactly like the old code did, because a wrong
     * guess here would take IPv6 away from an exit that has it.
     */
    @Volatile
    private var exitIpv6: Boolean? = null

    // Session accounting. Cheap, and the difference between a bug report that
    // can be diagnosed and one that cannot.
    private val quicDropped = AtomicLong(0)
    private val otherUdpDropped = AtomicLong(0)
    private val aaaaSuppressed = AtomicLong(0)
    private val dnsDialled = AtomicLong(0)
    private val dnsReused = AtomicLong(0)

    /** An idle, already-tunnelled TCP connection to one DNS resolver. */
    private class PooledDns(val socket: Socket, val idleSince: Long)

    private val dnsPool = HashMap<String, ArrayDeque<PooledDns>>()
    private val dnsPoolLock = Any()

    /**
     * DNS resolution is blocking I/O with a hard timeout, and one stalled
     * resolver must not stall the datagram pump. Bounded on purpose: DNS is
     * bursty but tiny, and an unbounded pool here is a thread bomb triggered by
     * whatever the device happens to be resolving.
     */
    private val resolvers = Executors.newFixedThreadPool(DNS_WORKERS) { runnable ->
        Thread(runnable, "socks-front-dns").apply { isDaemon = true }
    }

    /**
     * Binds the listener and returns true when it is accepting. Synchronous on
     * purpose: in a chain this port IS the tunnel, so a bind failure has to be
     * ground truth for the caller rather than something discovered later.
     */
    fun start(): Boolean {
        if (running) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(TunnelConfig.SOCKS_HOST, listenPort), BACKLOG)
            }
        }.getOrElse { error ->
            DiagnosticsLog.e(TAG, "Could not bind the SOCKS front on port $listenPort: $error")
            return false
        }
        server = socket
        running = true
        DiagnosticsLog.i(
            TAG,
            "SOCKS front on ${TunnelConfig.SOCKS_HOST}:$listenPort -> $upstreamHost:$upstreamPort " +
                "(dns: ${describeDns()})",
        )
        thread(name = "socks-front-accept", isDaemon = true) { acceptLoop(socket) }
        startIpv6Probe()
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { server?.close() }
        server = null
        resolvers.shutdownNow()
        closeDnsPool()
        DiagnosticsLog.i(
            TAG,
            "SOCKS front stopped. Session: udp/443 (QUIC) dropped=${quicDropped.get()}, " +
                "other udp dropped=${otherUdpDropped.get()}, " +
                "AAAA answered NODATA=${aaaaSuppressed.get()}, " +
                "dns connections dialled=${dnsDialled.get()} reused=${dnsReused.get()}",
        )
    }

    // --------------------------------------------------------------- accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                if (!running || socket.isClosed) break
                // Transient (fd pressure): backing off beats exiting the loop
                // and leaving a listener that is silently dead.
                runCatching { Thread.sleep(ACCEPT_BACKOFF_MS) }
                continue
            }
            if (liveClients.incrementAndGet() > MAX_CLIENTS) {
                liveClients.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            thread(name = "socks-front-conn", isDaemon = true) {
                try {
                    client.tcpNoDelay = true
                    serve(client)
                } catch (_: Exception) {
                    // Per-connection failures are non-fatal by design.
                } finally {
                    runCatching { client.close() }
                    liveClients.decrementAndGet()
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        // Greeting: VER NMETHODS METHODS...
        val head = readExact(input, 2) ?: return
        if (head[0] != VERSION) return
        val methodCount = head[1].toInt() and 0xFF
        if (methodCount > 0 && readExact(input, methodCount) == null) return
        // No authentication: the listener is loopback-only, so a credential here
        // would protect nothing that the socket permission does not already.
        output.write(byteArrayOf(VERSION, 0))
        output.flush()

        // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
        val requestHead = readExact(input, 4) ?: return
        if (requestHead[0] != VERSION) return
        val command = requestHead[1].toInt() and 0xFF
        val addressBytes = readAddress(input, requestHead[3].toInt() and 0xFF) ?: return
        val portBytes = readExact(input, 2) ?: return
        val request = requestHead + addressBytes + portBytes

        when (command) {
            CMD_CONNECT -> passThroughConnect(client, request)
            CMD_UDP_ASSOCIATE -> serveUdpAssociate(client)
            else -> {
                DiagnosticsLog.d(TAG, "Refusing unsupported SOCKS5 command $command")
                output.write(errorReply(REP_COMMAND_NOT_SUPPORTED))
                output.flush()
            }
        }
    }

    // -------------------------------------------------------------- CONNECT

    /**
     * Relays a CONNECT to the upstream proxy without interpreting it.
     *
     * The request bytes are replayed exactly as received, so a DOMAIN target
     * stays a DOMAIN target and the upstream core keeps doing the name
     * resolution inside the tunnel. The upstream's reply is streamed straight
     * back, which means its success or failure codes reach the client unchanged.
     */
    private fun passThroughConnect(client: Socket, request: ByteArray) {
        val upstream = runCatching {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(upstreamHost, upstreamPort), DIAL_TIMEOUT_MS)
                soTimeout = IDLE_TIMEOUT_MS
            }
        }.getOrElse {
            runCatching {
                val out = client.getOutputStream()
                out.write(errorReply(REP_GENERAL_FAILURE))
                out.flush()
            }
            return
        }
        try {
            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()
            upOut.write(byteArrayOf(VERSION, 1, 0))
            upOut.flush()
            val greeting = readExact(upIn, 2)
            if (greeting == null || greeting[0] != VERSION || greeting[1] != 0.toByte()) {
                val out = client.getOutputStream()
                out.write(errorReply(REP_GENERAL_FAILURE))
                out.flush()
                return
            }
            upOut.write(request)
            upOut.flush()
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    // -------------------------------------------------------- UDP ASSOCIATE

    /**
     * Accepts an association and pumps datagrams for as long as the client
     * holds the TCP control connection open, which is what RFC 1928 says the
     * lifetime of an association is.
     */
    private fun serveUdpAssociate(client: Socket) {
        val relay = runCatching {
            DatagramSocket(InetSocketAddress(TunnelConfig.SOCKS_HOST, 0))
        }.getOrElse {
            runCatching {
                val out = client.getOutputStream()
                out.write(errorReply(REP_GENERAL_FAILURE))
                out.flush()
            }
            return
        }
        try {
            val reply = ByteArray(10)
            reply[0] = VERSION
            reply[1] = 0
            reply[2] = 0
            reply[3] = ATYP_IPV4.toByte()
            // 127.0.0.1 - the client is in this app's own sandbox by definition.
            reply[4] = 127
            reply[5] = 0
            reply[6] = 0
            reply[7] = 1
            reply[8] = ((relay.localPort shr 8) and 0xFF).toByte()
            reply[9] = (relay.localPort and 0xFF).toByte()
            val out = client.getOutputStream()
            out.write(reply)
            out.flush()

            val pump = thread(name = "socks-front-udp", isDaemon = true) { udpLoop(relay) }
            // Park on the control connection: when it ends, the association ends.
            drainUntilEof(client.getInputStream())
            relay.close()
            runCatching { pump.join(UDP_JOIN_MS) }
        } finally {
            runCatching { relay.close() }
        }
    }

    private fun udpLoop(relay: DatagramSocket) {
        val buffer = ByteArray(UDP_BUFFER_BYTES)
        while (running && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (_: Exception) {
                return
            }
            val datagram = parseUdpRequest(packet.data, packet.length) ?: continue
            if (datagram.port != DNS_PORT) {
                // Honest silence: neither Psiphon's SOCKS5 nor tor can carry
                // this, and a fake answer would be worse than a dropped packet.
                // Counted and, for QUIC, explained once - a silent drop is what
                // made this cost three bug reports to find.
                noteUndeliverableUdp(datagram.port)
                continue
            }
            val source = packet.address
            val sourcePort = packet.port
            runCatching {
                resolvers.execute { resolveAndReply(relay, source, sourcePort, datagram) }
            }
        }
    }

    private fun noteUndeliverableUdp(port: Int) {
        if (port != QUIC_PORT) {
            otherUdpDropped.incrementAndGet()
            return
        }
        if (quicDropped.incrementAndGet() == 1L) {
            DiagnosticsLog.w(
                TAG,
                "UDP/443 (QUIC) cannot be carried through this chain's entry core, so it is " +
                    "being dropped. Apps that pin HTTP/3 for their own origins (Instagram, " +
                    "YouTube, CapCut, the AI apps) can read a black hole as \"no internet\" " +
                    "instead of falling back to TCP. See docs/PSIPHON_APP_CONNECTIVITY.md.",
            )
        }
    }

    private fun resolveAndReply(
        relay: DatagramSocket,
        clientAddress: InetAddress,
        clientPort: Int,
        datagram: UdpRequest,
    ) {
        // AAAA GUARD: only on a PROVEN IPv4-only exit, and only for a query this
        // code fully parsed. Anything else is forwarded exactly as before.
        if (exitIpv6 == false && DnsMessage.isAaaaQuery(datagram.payload)) {
            val nodata = DnsMessage.nodataResponse(datagram.payload)
            if (nodata != null) {
                aaaaSuppressed.incrementAndGet()
                sendReply(relay, clientAddress, clientPort, datagram, nodata)
                return
            }
        }
        val route = dns
        val answer = when (route) {
            is DnsRoute.LocalUdp -> resolveViaLocalUdp(route, datagram.payload)
            is DnsRoute.OverSocksTcp -> resolveViaSocksTcp(route, datagram.payload)
        } ?: return
        sendReply(relay, clientAddress, clientPort, datagram, answer)
    }

    /**
     * Sends [answer] back under the request's OWN SOCKS5 UDP header, i.e. "this
     * came from the address you asked about", which is what the client matches
     * the reply on.
     */
    private fun sendReply(
        relay: DatagramSocket,
        clientAddress: InetAddress,
        clientPort: Int,
        datagram: UdpRequest,
        answer: ByteArray,
    ) {
        val out = datagram.header + answer
        runCatching { relay.send(DatagramPacket(out, out.size, clientAddress, clientPort)) }
    }

    /** tor's DNSPort: one UDP round trip on loopback. */
    private fun resolveViaLocalUdp(route: DnsRoute.LocalUdp, query: ByteArray): ByteArray? =
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = DNS_TIMEOUT_MS
                socket.send(
                    DatagramPacket(
                        query,
                        query.size,
                        InetAddress.getByName(route.host),
                        route.port,
                    ),
                )
                val buffer = ByteArray(UDP_BUFFER_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                buffer.copyOf(response.length)
            }
        }.getOrNull()

    /**
     * DNS over TCP through the upstream SOCKS5, on a POOLED connection.
     *
     * Resolvers are tried in order, because a single filtered resolver must not
     * be the whole device's DNS. Per resolver: reuse an idle connection if there
     * is one, and dial a fresh one if the reused one fails - a pooled connection
     * can be closed by the far end at any time, and that must cost one retry
     * rather than one failed lookup.
     */
    private fun resolveViaSocksTcp(route: DnsRoute.OverSocksTcp, query: ByteArray): ByteArray? {
        for (entry in route.resolvers) {
            val target = HostPort.parse(entry, DNS_PORT) ?: continue
            val key = target.authority()

            val pooled = borrowDns(key)
            if (pooled != null) {
                val answer = exchangeDns(pooled, query)
                if (answer != null) {
                    dnsReused.incrementAndGet()
                    releaseDns(key, pooled)
                    return answer
                }
                runCatching { pooled.close() }
            }

            val fresh = dialThroughUpstream(target, DNS_TIMEOUT_MS) ?: continue
            val answer = exchangeDns(fresh, query)
            if (answer != null) {
                dnsDialled.incrementAndGet()
                releaseDns(key, fresh)
                return answer
            }
            runCatching { fresh.close() }
        }
        return null
    }

    /**
     * One RFC 7766 query/answer exchange on an established connection.
     *
     * The transaction-id check is what makes REUSE safe: if an earlier query on
     * this connection timed out, its late answer is the next thing in the
     * stream, and handing that to the caller would answer one name with
     * another's addresses. A mismatch fails the exchange, and the caller then
     * closes the connection rather than returning it to the pool.
     */
    private fun exchangeDns(socket: Socket, query: ByteArray): ByteArray? = runCatching {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(
            byteArrayOf(
                ((query.size shr 8) and 0xFF).toByte(),
                (query.size and 0xFF).toByte(),
            ),
        )
        output.write(query)
        output.flush()
        val length = readExact(input, 2) ?: throw IOException("truncated DNS length")
        val size = ((length[0].toInt() and 0xFF) shl 8) or (length[1].toInt() and 0xFF)
        if (size <= 0 || size > UDP_BUFFER_BYTES) throw IOException("bad DNS length")
        val answer = readExact(input, size) ?: throw IOException("truncated DNS answer")
        val asked = DnsMessage.transactionId(query)
        if (asked != null && DnsMessage.transactionId(answer) != asked) {
            throw IOException("DNS answer id does not match the question")
        }
        answer
    }.getOrNull()

    // ----------------------------------------------------------- dns pooling

    /**
     * Takes an idle connection for [key] out of the pool, or null when there is
     * no usable one.
     *
     * The socket is bound to an immutable local before it is touched: the
     * previous shape held the entry in a nullable `var` and dereferenced it from
     * inside a lambda, which is a smart cast on a captured, reassigned local -
     * exactly the construct whose rules differ between Kotlin frontends, in a
     * file that only ever gets compiled by CI.
     */
    private fun borrowDns(key: String): Socket? {
        synchronized(dnsPoolLock) {
            val queue = dnsPool[key] ?: return null
            var entry = queue.pollLast()
            while (entry != null) {
                val socket = entry.socket
                val idleFor = System.currentTimeMillis() - entry.idleSince
                val stale = socket.isClosed || socket.isInputShutdown || idleFor > DNS_IDLE_MS
                if (!stale) return socket
                runCatching { socket.close() }
                entry = queue.pollLast()
            }
        }
        return null
    }

    private fun releaseDns(key: String, socket: Socket) {
        if (!running || socket.isClosed) {
            runCatching { socket.close() }
            return
        }
        synchronized(dnsPoolLock) {
            val queue = dnsPool.getOrPut(key) { ArrayDeque() }
            if (queue.size >= DNS_POOL_MAX) {
                runCatching { socket.close() }
                return
            }
            queue.addLast(PooledDns(socket, System.currentTimeMillis()))
        }
    }

    private fun closeDnsPool() = synchronized(dnsPoolLock) {
        dnsPool.values.forEach { queue ->
            queue.forEach { runCatching { it.socket.close() } }
        }
        dnsPool.clear()
    }

    // -------------------------------------------------------- ipv6 capability

    /**
     * Asks the upstream ONCE, on a background thread, whether it can dial IPv6
     * at all.
     *
     * UP FRONT AND ONCE, deliberately. Deciding this lazily means the first two
     * app flows of every session pay for the discovery, which is exactly the
     * latency a user reads as "it connects but nothing opens". Only run for a
     * CONNECT-only upstream we own the resolver path for (Psiphon); tor answers
     * AAAA itself and can dial IPv6, so its route is left alone.
     */
    private fun startIpv6Probe() {
        if (dns !is DnsRoute.OverSocksTcp) return
        thread(name = "socks-front-v6probe", isDaemon = true) {
            val capable = probeExitIpv6()
            exitIpv6 = capable
            when (capable) {
                true -> DiagnosticsLog.i(
                    TAG,
                    "The exit can dial IPv6 - AAAA answers are passed through unchanged.",
                )
                false -> DiagnosticsLog.i(
                    TAG,
                    "The exit cannot dial IPv6 - AAAA queries are answered NODATA locally, so " +
                        "apps stop preferring addresses that can never connect.",
                )
                null -> DiagnosticsLog.w(
                    TAG,
                    "Could not establish whether the exit can dial IPv6 (the upstream did not " +
                        "answer) - leaving AAAA queries alone.",
                )
            }
        }
    }

    /**
     * true = a v6 destination was reachable, false = the upstream answered and
     * refused every one, null = the upstream itself never answered, so nothing
     * was proven either way.
     */
    private fun probeExitIpv6(): Boolean? {
        var reachedUpstream = false
        for (literal in IPV6_PROBES) {
            if (!running) return null
            val target = HostPort.parse(literal, PROBE_PORT) ?: continue
            val socket = Socket()
            val connected = runCatching {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(upstreamHost, upstreamPort), DIAL_TIMEOUT_MS)
                socket.soTimeout = PROBE_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                output.write(byteArrayOf(VERSION, 1, 0))
                output.flush()
                val greeting = readExact(input, 2) ?: throw IOException("SOCKS5 greeting failed")
                if (greeting[0] != VERSION || greeting[1] != 0.toByte()) {
                    throw IOException("SOCKS5 greeting refused")
                }
                // The upstream is alive and speaking SOCKS5: whatever it says
                // about this destination is now evidence.
                reachedUpstream = true
                output.write(connectRequest(target.host, target.port))
                output.flush()
                skipReply(input)
            }.getOrDefault(false)
            runCatching { socket.close() }
            if (connected) return true
        }
        return if (reachedUpstream) false else null
    }

    /**
     * Opens a TCP connection to [target] THROUGH the upstream SOCKS5 and
     * returns it with the handshake already done, or null when any step failed.
     */
    private fun dialThroughUpstream(target: HostPort, readTimeoutMs: Int): Socket? {
        val socket = Socket()
        val ready = runCatching {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(upstreamHost, upstreamPort), DIAL_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(VERSION, 1, 0))
            output.flush()
            val greeting = readExact(input, 2) ?: throw IOException("SOCKS5 greeting failed")
            if (greeting[0] != VERSION || greeting[1] != 0.toByte()) {
                throw IOException("SOCKS5 greeting refused")
            }
            output.write(connectRequest(target.host, target.port))
            output.flush()
            if (!skipReply(input)) throw IOException("SOCKS5 connect refused")
        }.isSuccess
        if (ready) return socket
        runCatching { socket.close() }
        return null
    }

    // ------------------------------------------------------------ protocol

    private class UdpRequest(val header: ByteArray, val port: Int, val payload: ByteArray)

    /**
     * Splits a SOCKS5 UDP request into its header, destination port and
     * payload. Fragmented datagrams (FRAG != 0) are rejected: there is no
     * reassembly here, and forwarding one fragment as a whole message would
     * corrupt it.
     */
    private fun parseUdpRequest(data: ByteArray, length: Int): UdpRequest? {
        if (length < 10) return null
        if ((data[2].toInt() and 0xFF) != 0) return null
        val bodyStart = when (data[3].toInt() and 0xFF) {
            ATYP_IPV4 -> 10
            ATYP_IPV6 -> 22
            ATYP_DOMAIN -> 5 + (data[4].toInt() and 0xFF) + 2
            else -> return null
        }
        if (length < bodyStart) return null
        val port = ((data[bodyStart - 2].toInt() and 0xFF) shl 8) or
            (data[bodyStart - 1].toInt() and 0xFF)
        return UdpRequest(
            header = data.copyOf(bodyStart),
            port = port,
            payload = data.copyOfRange(bodyStart, length),
        )
    }

    /**
     * Builds a CONNECT request for a resolver entry.
     *
     * A numeric literal is encoded as ATYP=IPV4/IPV6 and anything else is sent
     * as ATYP=DOMAIN for the upstream to resolve. The literal test is a pure
     * string check that runs BEFORE any InetAddress call, because
     * `getByName` on a name performs a blocking system DNS lookup - outside the
     * tunnel, on the DNS path, which is precisely the leak this class exists to
     * prevent.
     */
    private fun connectRequest(host: String, port: Int): ByteArray {
        val literal = if (host.none { it.isLetter() } || host.contains(':')) {
            runCatching { InetAddress.getByName(host).address }.getOrNull()
        } else {
            null
        }
        val address: ByteArray
        val atyp: Int
        if (literal != null) {
            address = literal
            atyp = if (address.size == 4) ATYP_IPV4 else ATYP_IPV6
        } else {
            val encoded = host.toByteArray(Charsets.US_ASCII)
            address = ByteArray(1 + encoded.size).also {
                it[0] = encoded.size.toByte()
                System.arraycopy(encoded, 0, it, 1, encoded.size)
            }
            atyp = ATYP_DOMAIN
        }
        val out = ByteArray(4 + address.size + 2)
        out[0] = VERSION
        out[1] = CMD_CONNECT.toByte()
        out[2] = 0
        out[3] = atyp.toByte()
        System.arraycopy(address, 0, out, 4, address.size)
        out[out.size - 2] = ((port shr 8) and 0xFF).toByte()
        out[out.size - 1] = (port and 0xFF).toByte()
        return out
    }

    /** Reads and discards a SOCKS5 reply; false when it is not a success. */
    private fun skipReply(input: InputStream): Boolean {
        val head = readExact(input, 4) ?: return false
        if (head[0] != VERSION || head[1] != 0.toByte()) return false
        return readAddress(input, head[3].toInt() and 0xFF) != null &&
            readExact(input, 2) != null
    }

    private fun readAddress(input: InputStream, atyp: Int): ByteArray? = when (atyp) {
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

    private fun errorReply(code: Byte): ByteArray =
        byteArrayOf(VERSION, code, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

    private fun readExact(input: InputStream, size: Int): ByteArray? {
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

    private fun relay(client: Socket, upstream: Socket) {
        val reverse = thread(isDaemon = true) { pipe(upstream, client) }
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

    private fun describeDns(): String = when (val route = dns) {
        is DnsRoute.LocalUdp -> "tor DNSPort ${route.host}:${route.port}"
        is DnsRoute.OverSocksTcp -> "DNS over TCP via ${route.resolvers.joinToString(",")}"
    }

    private companion object {
        const val TAG = "front"

        const val VERSION: Byte = 5
        const val CMD_CONNECT = 1
        const val CMD_UDP_ASSOCIATE = 3
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4
        const val REP_GENERAL_FAILURE: Byte = 1
        const val REP_COMMAND_NOT_SUPPORTED: Byte = 7

        const val DNS_PORT = 53
        const val DNS_TIMEOUT_MS = 6_000
        const val DNS_WORKERS = 8

        /**
         * How long an idle tunnelled DNS connection may be reused for, and how
         * many may be kept per resolver.
         *
         * Both are small on purpose: the point is to make the twenty names of
         * one page load cost one connection instead of twenty, not to hold
         * sockets open across a whole session.
         */
        const val DNS_IDLE_MS = 30_000L
        const val DNS_POOL_MAX = 4

        /** The port every dropped-datagram report in the field is about. */
        const val QUIC_PORT = 443

        /**
         * Where the IPv6 capability probe dials. Two operators, both anycast,
         * both answering on 443, and both written as LITERALS so the probe
         * itself can never trigger a name lookup.
         */
        val IPV6_PROBES = listOf("[2606:4700:4700::1111]", "[2001:4860:4860::8888]")
        const val PROBE_PORT = 443
        const val PROBE_TIMEOUT_MS = 8_000

        const val BACKLOG = 32
        const val MAX_CLIENTS = 256
        const val ACCEPT_BACKOFF_MS = 200L
        const val DIAL_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 300_000
        const val RELAY_BUFFER_BYTES = 64 * 1024
        const val RELAY_JOIN_MS = 10_000L
        const val UDP_BUFFER_BYTES = 65_535
        const val UDP_JOIN_MS = 2_000L
    }
}
