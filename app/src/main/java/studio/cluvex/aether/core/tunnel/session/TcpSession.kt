package studio.cluvex.aether.core.tunnel.session

import android.os.SystemClock
import studio.cluvex.aether.core.DnsMap
import studio.cluvex.aether.core.LogRepository
import studio.cluvex.aether.core.TrafficSniffer
import studio.cluvex.aether.core.tunnel.Tunables
import studio.cluvex.aether.core.tunnel.net.Bytes
import studio.cluvex.aether.core.tunnel.net.FlowKey
import studio.cluvex.aether.core.tunnel.net.IpProto
import studio.cluvex.aether.core.tunnel.net.TcpFlags
import studio.cluvex.aether.core.tunnel.socks.Socks5
import studio.cluvex.aether.model.RoutingMode
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One proxied TCP connection: a tiny TCP endpoint facing the app on one side and
 * a SOCKS5 (or direct) socket on the other.
 *
 * Now a top-level class with an explicit [SessionEnv] instead of an `inner class`
 * reaching into the bridge.
 */
internal class TcpSession(
    val key: FlowKey,
    private val env: SessionEnv,
    private val version: Int,
    private val clientIp: ByteArray,
    private val serverIp: ByteArray,
    private val clientPort: Int,
    private val serverPort: Int,
    initialSeq: Long,
    val uid: Int,
    /** Removes this session from the bridge's map. Called exactly once. */
    private val onClosed: (TcpSession) -> Unit
) {
    private val fromClient = LinkedBlockingDeque<ByteArray>(Tunables.TCP_QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)

    /** Volatile: [close] runs on the TUN reader thread while this thread dials. */
    @Volatile
    private var upstream: Socket? = null

    private val mySeq = AtomicLong((100_000..900_000).random().toLong())
    private val myAck = AtomicLong(nextSeq(initialSeq, 1))
    private val connected = AtomicBoolean(false)

    /**
     * True once a SYN/ACK has been handed to the TUN. From the app's point of
     * view the connection is open from that moment, so its data must be accepted
     * even while the upstream leg is still being dialled — see [onSegment].
     */
    private val synAcked = AtomicBoolean(false)

    /** Guards against sending both a FIN and an RST for the same session. */
    private val peerNotified = AtomicBoolean(false)
    private val clientClosed = AtomicBoolean(false)
    private val outputShutdown = AtomicBoolean(false)

    private val createdAt = SystemClock.elapsedRealtime()
    private val lastActivity = AtomicLong(SystemClock.elapsedRealtime())

    fun isConnected(): Boolean = connected.get()

    /** See [Tunables.STALE_UNCONNECTED_MS] for why the threshold is what it is. */
    fun isStaleUnconnected(): Boolean =
        !connected.get() &&
            SystemClock.elapsedRealtime() - createdAt > Tunables.STALE_UNCONNECTED_MS

    /** Re-answers a retransmitted client SYN; no-op once the SYN/ACK is out. */
    fun resendSynAck() {
        if (!synAcked.get()) sendSynAck()
    }

    // ---------------------------------------------------------------- inbound

    /**
     * Feeds one segment that arrived from the TUN into this session.
     *
     * HANDSHAKE FIX: the gate used to be [connected], which is only set after the
     * upstream leg is fully dialled. The domain-sniffing path deliberately
     * answers the SYN *before* dialling and then waits for the client's first
     * segment to read the TLS SNI out of it — but that segment hit this gate and
     * was thrown away, so the wait ALWAYS ran out (a full second added to every
     * fresh port 80/443 connection) and the client had to retransmit its
     * ClientHello on its own RTO.
     *
     * PAYLOAD-BEFORE-FIN FIX: the FIN branch used to return before the payload was
     * looked at, so a segment carrying data *and* FIN — the normal way a client
     * ends a request — lost that data and was acked as if it had never been sent.
     */
    fun onSegment(seq: Long, payload: ByteArray?, flags: TcpFlags) {
        if (closed.get()) return
        touch()

        if (flags.rst) {
            close()
            return
        }

        if (!synAcked.get()) {
            // Upstream leg still dialling. A retransmitted initial SYN is
            // expected here and is answered by resendSynAck() upstream.
            if (flags.fin) close()
            return
        }

        if (flags.syn) {
            // SYN on an ESTABLISHED session: the peer crashed or rebooted into
            // the same 4-tuple. Reset so its new connect attempt fails fast
            // instead of hanging until its own timeout.
            sendReset()
            close()
            return
        }

        val body = payload?.takeIf { it.isNotEmpty() }

        // A bare ACK carries nothing to acknowledge; replying to one would start
        // an endless ACK ping-pong with the client.
        if (body == null && !flags.fin) return

        // No reassembly queue here, so anything out of order gets a duplicate ACK
        // and has to be retransmitted.
        if (seq != myAck.get()) {
            sendAck()
            return
        }

        // Queue full: dup-ACK without advancing, so the client retransmits.
        if (body != null && !fromClient.offer(body)) {
            sendAck()
            return
        }

        var next = nextSeq(seq, body?.size ?: 0)
        if (flags.fin) {
            next = nextSeq(next, 1)
            clientClosed.set(true)
        }
        myAck.set(next)
        sendAck()
    }

    // --------------------------------------------------------------- outbound

    private fun sendAck() = emit(TcpFlags.ACK, mySeq.get())

    /** Answers the client's SYN. Idempotent: the second call is a no-op. */
    private fun sendSynAck() {
        if (!synAcked.compareAndSet(false, true)) return
        // SEQ IS CONSUMED FIRST: the old code enqueued the SYN/ACK and only THEN
        // advanced mySeq, so a concurrent sendAck() on the TUN reader thread could
        // read the pre-increment value and emit an ACK with a stale sequence
        // number. Reserving the number atomically closes that window.
        emit(TcpFlags.SYN_ACK, consumeSeq())
    }

    /** Half-closes towards the app (upstream saw EOF). */
    private fun sendFin() {
        if (!peerNotified.compareAndSet(false, true)) return
        emit(TcpFlags.FIN_ACK, consumeSeq())
    }

    /**
     * Tears the connection down towards the app.
     *
     * FAIL-FAST: every upstream failure (SOCKS greeting refused, CONNECT refused,
     * direct dial refused, mid-stream I/O error) used to return silently, leaving
     * the app with a socket that looked perfectly healthy. It then waited out its
     * own timeout — tens of seconds of "the page just hangs" — instead of getting
     * an instant error it could retry on.
     */
    private fun sendReset() {
        if (!peerNotified.compareAndSet(false, true)) return
        emit(TcpFlags.RST_ACK, mySeq.get())
    }

    /** Builds and queues one control segment. All four senders share this. */
    private fun emit(flags: Int, seq: Long) {
        val packet = if (version == 4) {
            env.packets.tcp4(
                Bytes.toInt(serverIp), Bytes.toInt(clientIp),
                serverPort, clientPort, null, seq, myAck.get(), flags
            )
        } else {
            env.packets.tcp6(
                serverIp, clientIp, serverPort, clientPort, null, seq, myAck.get(), flags
            )
        }
        env.tun.offer(packet, critical = true)
    }

    // ------------------------------------------------------------- session run

    fun run() {
        try {
            val targetIp = InetAddress.getByAddress(serverIp).hostAddress.orEmpty()
            val cachedDomain = DnsMap.get(targetIp)
            val router = FlowRouter(env)

            var plan = router.plan(
                targetIp = targetIp,
                port = serverPort,
                ipVersion = version,
                knownDomain = cachedDomain,
                protocolLabel = "TCP",
                log = false
            )
            var sniffedDomain: String? = null

            if (!shouldSniffDomain(plan.mode, cachedDomain)) {
                plan = router.plan(
                    targetIp = targetIp,
                    port = serverPort,
                    ipVersion = version,
                    knownDomain = cachedDomain,
                    protocolLabel = "TCP"
                )
            } else {
                sniffedDomain = sniffFirstSegment(targetIp)
                if (sniffedDomain != null) {
                    plan = router.plan(
                        targetIp = targetIp,
                        port = serverPort,
                        ipVersion = version,
                        knownDomain = null,
                        sniHost = sniffedDomain.takeIf { serverPort == IpProto.HTTPS_PORT },
                        httpHost = sniffedDomain.takeIf { serverPort == IpProto.HTTP_PORT },
                        protocolLabel = "TCP"
                    )
                }
            }

            if (plan.unroutable || plan.blocked) {
                sendReset()
                return
            }

            val socket = openUpstream(plan, targetIp, sniffedDomain ?: cachedDomain) ?: return

            connected.set(true)
            touch()
            // Handshake over — restore unlimited blocking reads for the data phase
            // (the idle reaper owns liveness from here on).
            runCatching { socket.soTimeout = 0 }

            if (closed.get()) return
            sendSynAck()

            env.submit { pumpDownstream(socket.input) }
            pumpUpstream(socket.output)
        } catch (e: Exception) {
            if (env.isRunning() && !closed.get()) {
                LogRepository.w("[Routing] TCP session failed: ${e.localizedMessage}")
                sendReset()
            }
        } finally {
            close()
        }
    }

    /**
     * Reading the TLS SNI / HTTP Host header out of the first segment means
     * answering the SYN before the upstream leg even exists, so it is only worth
     * doing when a domain can actually change the verdict. Aether drives this
     * bridge with an EMPTY rule set (it is activated for per-app blocking; the
     * real routing rules live in the engine), where the detour decided nothing and
     * only added latency to every fresh connection.
     */
    private fun shouldSniffDomain(mode: RoutingMode, cachedDomain: String?): Boolean =
        mode == RoutingMode.TUNNEL &&
            cachedDomain == null &&
            (serverPort == IpProto.HTTP_PORT || serverPort == IpProto.HTTPS_PORT) &&
            env.routingEngine.hasDomainRules()

    private fun sniffFirstSegment(targetIp: String): String? {
        sendSynAck()
        val first = fromClient.poll(Tunables.SNIFF_WAIT_MS, TimeUnit.MILLISECONDS) ?: return null
        return try {
            TrafficSniffer.sniffDomain(first, serverPort)?.also { DnsMap.put(targetIp, it) }
        } finally {
            // Put it back regardless: the upstream leg still has to send it.
            fromClient.addFirst(first)
        }
    }

    /** The upstream leg's streams, once it is connected. */
    private class Upstream(val socket: Socket, val input: InputStream, val output: OutputStream) {
        var soTimeout: Int
            get() = socket.soTimeout
            set(value) { socket.soTimeout = value }
    }

    private fun openUpstream(plan: FlowRouter.Plan, targetIp: String, domain: String?): Upstream? {
        val socket = Socket()
        upstream = socket
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.receiveBufferSize = Tunables.UPSTREAM_SOCKET_BUFFER
        socket.sendBufferSize = Tunables.UPSTREAM_SOCKET_BUFFER

        // Bound the handshake phase: connect() carries a timeout, but the SOCKS
        // greeting/reply reads below are plain blocking reads — a stalled proxy
        // would wedge this session thread forever with no RST ever reaching the
        // app. Cleared once connected so long-lived idle flows are not cut.
        runCatching { socket.soTimeout = Tunables.DIAL_READ_TIMEOUT_MS }

        if (plan.direct) {
            val network = requireNotNull(plan.directNetwork)
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(targetIp, serverPort), Tunables.DIAL_CONNECT_TIMEOUT_MS)
            LogRepository.i(
                "[Routing] DIRECT_CONNECTED domain=${plan.resolvedDomain ?: "unknown"} " +
                    "ip=$targetIp via=${env.networks.label(network)} " +
                    "local=${socket.localAddress.hostAddress}"
            )
            return Upstream(socket, socket.getInputStream(), buffered(socket))
        }

        runCatching { env.vpnService.protect(socket) }
        socket.connect(
            InetSocketAddress(env.socksHost, env.socksPort),
            Tunables.DIAL_CONNECT_TIMEOUT_MS
        )
        val ins = socket.getInputStream()
        val out = buffered(socket)

        if (!Socks5.greet(ins, out)) {
            sendReset()
            return null
        }
        out.write(Socks5.request(Socks5.CMD_CONNECT, domain, serverIp, serverPort))
        out.flush()
        if (Socks5.readReply(ins) == null) {
            sendReset()
            return null
        }
        return Upstream(socket, ins, out)
    }

    private fun buffered(socket: Socket): OutputStream =
        BufferedOutputStream(socket.getOutputStream(), Tunables.UPSTREAM_WRITE_BUFFER)

    /** Client -> upstream. Runs on the session thread. */
    private fun pumpUpstream(out: OutputStream) {
        while (!closed.get() && env.isRunning()) {
            val chunk = fromClient.poll(Tunables.SESSION_POLL_SECONDS, TimeUnit.SECONDS)

            if (chunk == null) {
                maybeShutdownOutput()
                if (idleFor() > Tunables.TCP_IDLE_TIMEOUT_MS) {
                    close()
                    return
                }
                continue
            }

            out.write(chunk)
            // Coalesce whatever else is already queued into the same flush.
            var batched = 0
            while (batched++ < Tunables.UPSTREAM_WRITE_BATCH) {
                out.write(fromClient.poll() ?: break)
            }
            out.flush()
            touch()
            maybeShutdownOutput()
        }
    }

    /** Upstream -> client, segmented to the MTU. Runs on its own pool thread. */
    private fun pumpDownstream(ins: InputStream) {
        try {
            val buffer = ByteArray(Tunables.UPSTREAM_READ_BUFFER)
            val maxPayload = env.packets.maxTcpPayload(version)

            while (!closed.get() && env.isRunning()) {
                val read = ins.read(buffer)
                if (read <= 0) {
                    sendFin()
                    return
                }
                touch()

                var offset = 0
                while (offset < read) {
                    val chunkLen = minOf(maxPayload, read - offset)

                    // ZERO-COPY: the segment is written straight out of the read
                    // buffer. Cutting a `chunk` array out first and then copying
                    // the whole TCP segment AGAIN just to checksum it meant every
                    // downloaded byte moved three times and allocated twice — pure
                    // GC pressure on the hottest path in the app.
                    val packet = if (version == 4) {
                        env.packets.tcp4(
                            Bytes.toInt(serverIp), Bytes.toInt(clientIp),
                            serverPort, clientPort, buffer,
                            mySeq.get(), myAck.get(), TcpFlags.PSH_ACK, offset, chunkLen
                        )
                    } else {
                        env.packets.tcp6(
                            serverIp, clientIp, serverPort, clientPort, buffer,
                            mySeq.get(), myAck.get(), TcpFlags.PSH_ACK, offset, chunkLen
                        )
                    }

                    // STREAM INTEGRITY: the sequence number may only advance for a
                    // segment the writer really accepted. Dropping one silently
                    // (queue full) left a hole no retransmission could ever fill,
                    // and the connection hung until it timed out. If even the
                    // back-pressured enqueue fails, tear the session down — an
                    // honest reset beats a stalled socket.
                    if (!env.tun.offer(packet)) {
                        LogRepository.w("TUN queue saturated — resetting stalled TCP session")
                        sendReset()
                        close()
                        return
                    }
                    advanceSeq(chunkLen)
                    offset += chunkLen
                }
            }
        } catch (_: Exception) {
            if (env.isRunning() && !closed.get()) sendReset()
        } finally {
            close()
        }
    }

    /** Propagates the client's FIN upstream once everything queued has been sent. */
    private fun maybeShutdownOutput() {
        if (!clientClosed.get() || fromClient.isNotEmpty()) return
        if (!outputShutdown.compareAndSet(false, true)) return
        runCatching { upstream?.shutdownOutput() }
    }

    fun close() {
        // The CAS only guards the map removal. Resource release runs
        // UNCONDITIONALLY: close() can race the dialling thread, which observes
        // `upstream == null` here and creates the socket a moment later — that
        // socket's own finally-block then still closes it, instead of
        // early-returning on a taken CAS.
        if (!closed.getAndSet(true)) onClosed(this)
        runCatching { upstream?.close() }
    }

    // -------------------------------------------------------------- seq helpers

    private fun touch() = lastActivity.set(SystemClock.elapsedRealtime())
    private fun idleFor(): Long = SystemClock.elapsedRealtime() - lastActivity.get()

    /** Reserves the current sequence number and advances past it by one. */
    private fun consumeSeq(): Long = mySeq.getAndUpdate { nextSeq(it, 1) }

    /** Atomic advance. The old `set(get() + n)` was a racy read-modify-write. */
    private fun advanceSeq(n: Int) = mySeq.updateAndGet { nextSeq(it, n) }

    private companion object {
        /** TCP sequence space is 32-bit and wraps. */
        fun nextSeq(base: Long, delta: Int): Long = (base + delta) and 0xFFFFFFFFL
    }
}