package studio.cluvex.aether.core.tunnel.session

import android.os.SystemClock
import studio.cluvex.aether.core.DnsMap
import studio.cluvex.aether.core.LogRepository
import studio.cluvex.aether.core.tunnel.Tunables
import studio.cluvex.aether.core.tunnel.net.Bytes
import studio.cluvex.aether.core.tunnel.net.FlowKey
import studio.cluvex.aether.core.tunnel.net.IpProto
import studio.cluvex.aether.core.tunnel.socks.Socks5
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One proxied UDP flow.
 *
 * Two sockets are involved when tunnelling: a TCP control socket that holds the
 * SOCKS5 UDP ASSOCIATE open, and the datagram socket that talks to the relay.
 * Both must outlive the association and both must be closed on teardown.
 */
internal class UdpSession(
    val key: FlowKey,
    private val env: SessionEnv,
    private val version: Int,
    private val clientIp: ByteArray,
    private val serverIp: ByteArray,
    private val clientPort: Int,
    private val serverPort: Int,
    val uid: Int,
    private val onClosed: (UdpSession) -> Unit
) {
    private val fromClient = LinkedBlockingQueue<ByteArray>(Tunables.UDP_QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)

    /**
     * Volatile and unconditionally closed in [close]: the bridge can stop while
     * this thread is still resolving/associating, before these fields are
     * assigned — the old CAS-first close leaked BOTH sockets on that interleaving.
     */
    @Volatile
    private var controlSocket: Socket? = null

    @Volatile
    private var relaySocket: DatagramSocket? = null

    private val lastActivity = AtomicLong(SystemClock.elapsedRealtime())

    fun queue(payload: ByteArray) {
        if (!closed.get() && fromClient.offer(payload)) touch()
    }

    fun run() {
        try {
            val targetIp = InetAddress.getByAddress(serverIp).hostAddress.orEmpty()
            val knownDomain = DnsMap.get(targetIp)

            // CHEAP SHORT-CIRCUIT FIRST: an unidentifiable QUIC flow is dropped
            // when domain rules are active, because it cannot be classified. This
            // check used to run AFTER resolving the rule and probing for an
            // underlying network, so every such flow paid for work it discarded.
            if (serverPort == IpProto.HTTPS_PORT &&
                knownDomain == null &&
                env.routingEngine.hasDomainRules()
            ) {
                return
            }

            val plan = FlowRouter(env).plan(
                targetIp = targetIp,
                port = serverPort,
                ipVersion = version,
                knownDomain = knownDomain,
                protocolLabel = "UDP"
            )
            if (plan.unroutable || plan.blocked) return

            val relay = associate(plan, targetIp, knownDomain) ?: return
            env.submit { pumpDownstream(relay.socket, plan.direct) }
            pumpUpstream(relay)
        } catch (e: Exception) {
            if (env.isRunning() && !closed.get()) {
                LogRepository.w("[Routing] UDP session failed: ${e.localizedMessage}")
            }
        } finally {
            close()
        }
    }

    /** Where datagrams go, and the SOCKS header they need (empty when direct). */
    private class Relay(
        val socket: DatagramSocket,
        val destination: InetSocketAddress,
        val header: ByteArray
    )

    private fun associate(plan: FlowRouter.Plan, targetIp: String, domain: String?): Relay? {
        val relayHost: InetAddress
        val relayPort: Int
        val header: ByteArray

        if (plan.direct) {
            relayHost = InetAddress.getByName(targetIp)
            relayPort = serverPort
            header = EMPTY_HEADER
        } else {
            val control = Socket()
            controlSocket = control
            runCatching { env.vpnService.protect(control) }
            control.tcpNoDelay = true
            // The ASSOCIATE exchange below is plain blocking reads.
            runCatching { control.soTimeout = Tunables.DIAL_READ_TIMEOUT_MS }
            control.connect(
                InetSocketAddress(env.socksHost, env.socksPort),
                Tunables.DIAL_CONNECT_TIMEOUT_MS
            )

            val ins = control.getInputStream()
            val out = control.getOutputStream()
            if (!Socks5.greet(ins, out)) return null

            val anyAddress = if (version == 4) ByteArray(4) else ByteArray(16)
            out.write(Socks5.request(Socks5.CMD_UDP_ASSOCIATE, null, anyAddress, 0))
            out.flush()

            val bound = Socks5.readReply(ins) ?: return null
            // A wildcard BND.ADDR (or a hostname we deliberately do NOT resolve —
            // that would be an out-of-tunnel DNS lookup) means "same host as the
            // control connection", which is the proxy we are already talking to.
            relayHost = when {
                bound is Socks5.Bound.Address && !bound.address.isAnyLocalAddress -> bound.address
                else -> InetAddress.getByName(env.socksHost)
            }
            relayPort = bound.port
            header = Socks5.udpHeader(domain, serverIp, serverPort)
        }

        val socket = DatagramSocket()
        relaySocket = socket
        if (plan.direct) {
            val network = requireNotNull(plan.directNetwork)
            network.bindSocket(socket)
            LogRepository.i(
                "[Routing] DIRECT_UDP_BOUND domain=${plan.resolvedDomain ?: "unknown"} " +
                    "ip=$targetIp via=${env.networks.label(network)}"
            )
        } else {
            runCatching { env.vpnService.protect(socket) }
        }
        socket.soTimeout = Tunables.UDP_RECEIVE_TIMEOUT_MS

        return Relay(socket, InetSocketAddress(relayHost, relayPort), header)
    }

    private fun pumpUpstream(relay: Relay) {
        while (!closed.get() && env.isRunning()) {
            val payload = fromClient.poll(Tunables.SESSION_POLL_SECONDS, TimeUnit.SECONDS)
            if (payload == null) {
                if (idleFor() > Tunables.UDP_IDLE_TIMEOUT_MS) return
                continue
            }

            val framed = if (relay.header.isEmpty()) payload else {
                ByteArray(relay.header.size + payload.size).also {
                    System.arraycopy(relay.header, 0, it, 0, relay.header.size)
                    System.arraycopy(payload, 0, it, relay.header.size, payload.size)
                }
            }
            relay.socket.send(DatagramPacket(framed, framed.size, relay.destination))
            touch()
        }
    }

    private fun pumpDownstream(socket: DatagramSocket, direct: Boolean) {
        try {
            val buffer = ByteArray(65535)
            // One DatagramPacket for the whole session: `receive` only needs its
            // length reset, and allocating a fresh one per datagram made the UDP
            // path allocate twice for every packet.
            val datagram = DatagramPacket(buffer, buffer.size)

            while (!closed.get() && env.isRunning()) {
                try {
                    datagram.setData(buffer, 0, buffer.size)
                    socket.receive(datagram)
                    touch()

                    val sourcePort: Int
                    val payload: ByteArray
                    val sourceAddress: ByteArray

                    if (direct) {
                        sourcePort = datagram.port
                        sourceAddress = datagram.address.address
                        payload = datagram.data.copyOfRange(
                            datagram.offset,
                            datagram.offset + datagram.length
                        )
                    } else {
                        val parsed = Socks5.parseDatagram(datagram.data, datagram.length) ?: continue
                        val from = parsed.source
                        // A hostname source is not addressable back into the TUN.
                        if (from !is Socks5.Bound.Address) continue
                        sourcePort = from.port
                        sourceAddress = from.address.address
                        payload = parsed.payload
                    }

                    if (sourcePort == IpProto.DNS_PORT) env.dns.learnFromResponse(payload)
                    deliver(sourceAddress, sourcePort, payload)
                } catch (_: SocketTimeoutException) {
                    if (idleFor() > Tunables.UDP_IDLE_TIMEOUT_MS) return
                } catch (_: Exception) {
                    return
                }
            }
        } finally {
            close()
        }
    }

    /** Oversized datagrams are fragmented, not discarded — see [UdpEmitter]. */
    private fun deliver(sourceIp: ByteArray, sourcePort: Int, payload: ByteArray) {
        when {
            version == 4 && sourceIp.size == 4 ->
                env.udpOut.emit4(
                    Bytes.toInt(sourceIp), Bytes.toInt(clientIp), sourcePort, clientPort, payload
                )
            version == 6 && sourceIp.size == 16 ->
                env.udpOut.emit6(sourceIp, clientIp, sourcePort, clientPort, payload)
        }
    }

    fun close() {
        if (!closed.getAndSet(true)) onClosed(this)
        runCatching { controlSocket?.close() }
        runCatching { relaySocket?.close() }
    }

    private fun touch() = lastActivity.set(SystemClock.elapsedRealtime())
    private fun idleFor(): Long = SystemClock.elapsedRealtime() - lastActivity.get()

    private companion object {
        val EMPTY_HEADER = ByteArray(0)
    }
}