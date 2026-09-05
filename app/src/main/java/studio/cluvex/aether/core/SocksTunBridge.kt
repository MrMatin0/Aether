package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import studio.cluvex.aether.core.tunnel.Tunables
import studio.cluvex.aether.core.tunnel.dns.DnsInspector
import studio.cluvex.aether.core.tunnel.net.AppUidGuard
import studio.cluvex.aether.core.tunnel.net.FlowKey
import studio.cluvex.aether.core.tunnel.net.IpPacketReader
import studio.cluvex.aether.core.tunnel.net.IpProto
import studio.cluvex.aether.core.tunnel.net.PacketFactory
import studio.cluvex.aether.core.tunnel.net.TcpFlags
import studio.cluvex.aether.core.tunnel.net.TunPipe
import studio.cluvex.aether.core.tunnel.net.UdpEmitter
import studio.cluvex.aether.core.tunnel.net.UnderlyingNetworks
import studio.cluvex.aether.core.tunnel.session.SessionEnv
import studio.cluvex.aether.core.tunnel.session.TcpSession
import studio.cluvex.aether.core.tunnel.session.UdpSession
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A userspace TCP/UDP-over-SOCKS5 bridge for Android's VpnService TUN.
 *
 * This class is now ONLY an orchestrator: it owns the TUN reader thread, the
 * session maps, and the demultiplexing of packets onto sessions. Packet
 * building, checksums, SOCKS5, DNS, per-app UID gating, network probing, the
 * TUN write queues and the sessions themselves all live in their own files —
 * see `net/`, `socks/`, `dns/` and `session/`.
 */
class SocksTunBridge(
    private val vpnService: VpnService,
    private val tunDescriptor: ParcelFileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 1819,
    private val mtu: Int = 1280,
    blockedPackagesProvider: () -> Set<String>,
    private val routingEngine: RoutingEngine
) {
    data class Stats(val txBytes: Long = 0, val rxBytes: Long = 0)

    private val isRunning = AtomicBoolean(false)
    private val threadSeq = AtomicInteger(0)

    @Volatile
    private var readThread: Thread? = null

    /**
     * One thread per session leg (session loop + its reader), so the pool HAS to
     * be unbounded: a bounded pool would deadlock, because a session thread
     * submits its own reader and then waits for it. Threads are named and marked
     * daemon so they show up usefully in a thread dump and never keep the process
     * alive after teardown.
     *
     * [stop] shuts the pool down for good, so [start] must build a fresh one.
     */
    private var executor = newSessionExecutor()

    private val tcpSessions = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udpSessions = ConcurrentHashMap<FlowKey, UdpSession>()

    /** Bytes read from the TUN, i.e. upstream traffic from the app's view. */
    private val txBytes = AtomicLong(0)

    private val connectivityManager by lazy {
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val packets = PacketFactory(mtu)
    private val tun = TunPipe(isRunning::get)
    private val udpOut = UdpEmitter(mtu, packets, tun)
    private val networks by lazy { UnderlyingNetworks(connectivityManager) }
    private val dns = DnsInspector(routingEngine)
    private val uids by lazy {
        AppUidGuard(connectivityManager, vpnService.packageManager, blockedPackagesProvider)
    }

    private val env by lazy {
        SessionEnv(
            vpnService = vpnService,
            socksHost = socksHost,
            socksPort = socksPort,
            routingEngine = routingEngine,
            packets = packets,
            tun = tun,
            udpOut = udpOut,
            networks = networks,
            dns = dns,
            isRunning = isRunning::get,
            submit = { executor.execute(it) }
        )
    }

    // ------------------------------------------------------------- lifecycle

    fun start() {
        if (isRunning.getAndSet(true)) return

        LogRepository.i("Initializing tunnel bridge (MTU=$mtu)...")
        active = this

        // A stop()/start() cycle needs a FRESH pool: shutdownNow() leaves the old
        // executor rejecting every submission forever, and each rejected session
        // launch was swallowed as a generic TUN read error — so a restarted bridge
        // silently forwarded nothing.
        executor = newSessionExecutor()

        tun.start(FileOutputStream(tunDescriptor.fileDescriptor))
        readThread = Thread(::readTunLoop, "Aether-TunReader").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        if (active === this) active = null

        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()

        executor.shutdownNow()
        readThread?.interrupt()
        readThread = null
        tun.stop()
    }

    fun getStats(): Stats = Stats(txBytes.get(), tun.bytesWritten.get())

    private fun newSessionExecutor() = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "Aether-Flow-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    // ------------------------------------------------------------- TUN reader

    /**
     * SPIN FIX: `read` returning -1 (the TUN fd was closed under us) or failing
     * repeatedly used to be treated as "nothing to do" and the loop went straight
     * back to read() — a busy loop that outlived the session. EOF now ends the
     * loop, and repeated errors are backed off before they can burn the battery.
     * The fd itself is put in BLOCKING mode by the VpnService when this bridge is
     * used (see establishTun), so an idle tunnel parks here instead of spinning on
     * the EAGAIN-as-0 reads Android returns for a non-blocking fd.
     */
    private fun readTunLoop() {
        FileInputStream(tunDescriptor.fileDescriptor).use { input ->
            val buffer = ByteArray(mtu + HEADROOM)
            awaitEngine()

            var consecutiveErrors = 0
            while (isRunning.get()) {
                try {
                    val read = input.read(buffer)
                    if (read < 0) return
                    consecutiveErrors = 0
                    if (read == 0) continue

                    txBytes.addAndGet(read.toLong())
                    dispatch(buffer, read)
                } catch (_: InterruptedException) {
                    return
                } catch (e: Exception) {
                    if (!isRunning.get()) return
                    LogRepository.w("TUN read error: ${e.message}")
                    if (++consecutiveErrors >= Tunables.MAX_CONSECUTIVE_TUN_ERRORS) {
                        LogRepository.e(
                            "TUN reader giving up after $consecutiveErrors consecutive errors"
                        )
                        return
                    }
                    runCatching { Thread.sleep(Tunables.TUN_ERROR_BACKOFF_MS) }
                }
            }
        }
    }

    /**
     * Waits for the engine's local SOCKS5 listener to answer before the first
     * packet is forwarded.
     *
     * NOTE: the loop used to `return@repeat` on success, which is Kotlin for
     * *continue* — so a healthy engine still got all 25 probe connects instead of
     * one. A plain loop with a real break keeps it to a single connect.
     */
    private fun awaitEngine() {
        repeat(Tunables.CORE_WAIT_ATTEMPTS) {
            val ready = runCatching {
                Socket().use { probe ->
                    runCatching { vpnService.protect(probe) }
                    probe.connect(
                        InetSocketAddress(socksHost, socksPort),
                        Tunables.CORE_PROBE_TIMEOUT_MS
                    )
                }
            }.isSuccess

            if (ready) {
                LogRepository.i("Link synchronization complete")
                return
            }
            if (!isRunning.get()) return
            runCatching { Thread.sleep(Tunables.CORE_WAIT_INTERVAL_MS) }.getOrElse { return }
        }
        LogRepository.w(
            "Engine SOCKS5 port did not answer before the first packet — forwarding anyway"
        )
    }

    // ---------------------------------------------------------- demultiplexing

    /**
     * Routes one TUN frame to its session.
     *
     * This replaces `processIpv4` + `processIpv6`, which were ~120 lines EACH and
     * ~85% identical. Everything below is version-agnostic; the only places that
     * still care about v4 vs v6 are packet construction and the flow key, both of
     * which handle it internally.
     */
    private fun dispatch(packet: ByteArray, len: Int) {
        val l3 = IpPacketReader.parse(packet, len) ?: return
        when (l3.proto) {
            IpProto.TCP -> dispatchTcp(packet, len, l3)
            IpProto.UDP -> dispatchUdp(packet, len, l3)
        }
    }

    private fun dispatchTcp(packet: ByteArray, len: Int, l3: IpPacketReader.L3Header) {
        val tcp = IpPacketReader.tcp(packet, len, l3) ?: return
        val key = FlowKey.of(IpProto.TCP, l3, tcp.srcPort, tcp.dstPort)
        val existing = tcpSessions[key]

        if (existing != null && !isReplacedBy(key, existing, tcp.flags)) {
            if (uids.isBlocked(existing.uid)) {
                dropTcp(key, existing)
                return
            }
            existing.onSegment(tcp.seq, tcp.payload, tcp.flags)
            return
        }

        // Only a connection request opens a session; anything else for an unknown
        // flow is a stray segment and is ignored.
        if (!tcp.flags.isInitialSyn) return

        val uid = uids.ownerUid(
            OsConstants.IPPROTO_TCP, l3.srcIp, tcp.srcPort, l3.dstIp, tcp.dstPort
        )
        if (uids.isBlocked(uid)) {
            refuse(l3, tcp)
            return
        }

        val session = TcpSession(
            key = key,
            env = env,
            version = l3.version,
            clientIp = l3.srcIp,
            serverIp = l3.dstIp,
            clientPort = tcp.srcPort,
            serverPort = tcp.dstPort,
            initialSeq = tcp.seq,
            uid = uid,
            onClosed = { tcpSessions.remove(it.key, it) }
        )
        tcpSessions[key] = session
        executor.execute(session::run)
    }

    /**
     * Handles a SYN that arrives for a session which never finished connecting.
     *
     * Returns true when the caller should build a NEW session (the old one is
     * stale and has been discarded). Returns false when the existing session
     * handles it — either as normal traffic, or by re-answering the SYN, because a
     * dropped SYN RTO makes the client wait a full extra timeout for an answer we
     * already owe it.
     */
    private fun isReplacedBy(key: FlowKey, session: TcpSession, flags: TcpFlags): Boolean {
        if (!flags.isInitialSyn || session.isConnected()) return false
        if (!session.isStaleUnconnected()) {
            session.resendSynAck()
            return false
        }
        dropTcp(key, session)
        return true
    }

    /** Answers a blocked app's SYN with an RST so it fails instantly. */
    private fun refuse(l3: IpPacketReader.L3Header, tcp: IpPacketReader.TcpHeader) {
        val ack = (tcp.seq + 1) and 0xFFFFFFFFL
        val rst = if (l3.version == 4) {
            packets.tcp4(
                l3.dstIp4, l3.srcIp4, tcp.dstPort, tcp.srcPort, null, 0, ack, TcpFlags.RST_ACK
            )
        } else {
            packets.tcp6(
                l3.dstIp, l3.srcIp, tcp.dstPort, tcp.srcPort, null, 0, ack, TcpFlags.RST_ACK
            )
        }
        tun.offer(rst, critical = true)
    }

    private fun dropTcp(key: FlowKey, session: TcpSession) {
        tcpSessions.remove(key, session)
        session.close()
    }

    private fun dispatchUdp(packet: ByteArray, len: Int, l3: IpPacketReader.L3Header) {
        val udp = IpPacketReader.udp(packet, len, l3) ?: return

        if (udp.dstPort == IpProto.DNS_PORT) {
            val nxDomain = dns.blockResponseFor(udp.payload, l3.dstIp)
            if (nxDomain != null) {
                emitDnsAnswer(l3, udp.srcPort, nxDomain)
                return
            }
        }
        if (udp.srcPort == IpProto.DNS_PORT) dns.learnFromResponse(udp.payload)

        val key = FlowKey.of(IpProto.UDP, l3, udp.srcPort, udp.dstPort)
        val existing = udpSessions[key]

        if (existing != null) {
            if (uids.isBlocked(existing.uid)) {
                udpSessions.remove(key, existing)
                existing.close()
                return
            }
            existing.queue(udp.payload)
            return
        }

        val uid = uids.ownerUid(
            OsConstants.IPPROTO_UDP, l3.srcIp, udp.srcPort, l3.dstIp, udp.dstPort
        )
        if (uids.isBlocked(uid)) return

        val session = UdpSession(
            key = key,
            env = env,
            version = l3.version,
            clientIp = l3.srcIp,
            serverIp = l3.dstIp,
            clientPort = udp.srcPort,
            serverPort = udp.dstPort,
            uid = uid,
            onClosed = { udpSessions.remove(it.key, it) }
        )
        udpSessions[key] = session
        executor.execute(session::run)
    }

    private fun emitDnsAnswer(l3: IpPacketReader.L3Header, clientPort: Int, answer: ByteArray) {
        val response = if (l3.version == 4) {
            packets.udp4(l3.dstIp4, l3.srcIp4, IpProto.DNS_PORT, clientPort, answer)
        } else {
            packets.udp6(l3.dstIp, l3.srcIp, IpProto.DNS_PORT, clientPort, answer)
        }
        tun.offer(response, critical = true)
    }

    companion object {
        /**
         * The bridge that is currently reading the TUN, or null. Published so the
         * traffic meter has a byte source in per-app-blocking mode, where
         * hev-socks5-tunnel — and therefore its stats API — never runs.
         */
        @Volatile
        var active: SocksTunBridge? = null
            private set

        /** Slack above the MTU so an oversized frame is never silently truncated. */
        private const val HEADROOM = 200
    }
}