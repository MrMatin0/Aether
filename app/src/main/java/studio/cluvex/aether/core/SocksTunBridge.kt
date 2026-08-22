package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import studio.cluvex.aether.model.RoutingMode

sealed interface FlowKey

data class FlowKey4(
    val proto: Int,
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int
) : FlowKey

data class FlowKey6(
    val proto: Int,
    val srcIp: ByteArray,
    val srcPort: Int,
    val dstIp: ByteArray,
    val dstPort: Int
) : FlowKey {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlowKey6) return false
        return proto == other.proto &&
                srcIp.contentEquals(other.srcIp) &&
                srcPort == other.srcPort &&
                dstIp.contentEquals(other.dstIp) &&
                dstPort == other.dstPort
    }

    override fun hashCode(): Int {
        var result = proto
        result = 31 * result + srcIp.contentHashCode()
        result = 31 * result + srcPort
        result = 31 * result + dstIp.contentHashCode()
        result = 31 * result + dstPort
        return result
    }
}

class SocksTunBridge(
    private val vpnService: VpnService,
    private val tunDescriptor: ParcelFileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 1819,
    private val mtu: Int = 1280,
    private val blockedPackagesProvider: () -> Set<String>,
    private val routingEngine: RoutingEngine
) {
    data class Stats(val txBytes: Long = 0, val rxBytes: Long = 0)

    private val isRunning = AtomicBoolean(false)
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private val threadSeq = AtomicInteger(0)

    /**
     * One thread per session leg (session loop + its reader), so the pool has to
     * be unbounded — a bounded pool would deadlock, because a session thread
     * submits its own reader and then waits for it. Threads are named and marked
     * daemon so they show up usefully in a thread dump and never keep the
     * process alive after teardown.
     */
    private var executor = newSessionExecutor()

    /** [stop] shuts the pool down for good; [start] must build a fresh one. */
    private fun newSessionExecutor() = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "Aether-Flow-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    /**
     * Packets headed FOR the TUN, split by delivery guarantee:
     *
     *  - [tunOutputQueue] carries DATA segments. It applies back-pressure: the
     *    sequence number of a data segment is committed as soon as it is
     *    enqueued ([TcpSession.readFromSocks]), so a silently dropped one would
     *    leave a hole no retransmission could ever fill.
     *  - [tunControlQueue] carries control packets (SYN/ACK, ACK, FIN, RST, DNS
     *    answers). When it saturates, evicting another CONTROL packet is
     *    recoverable (peers retry all of these); evicting DATA never was — that
     *    was exactly the stream corruption the old single-queue eviction caused.
     */
    private val tunOutputQueue = LinkedBlockingQueue<ByteArray>(32768)
    private val tunControlQueue = LinkedBlockingQueue<ByteArray>(4096)
    private val tcpSessions = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udpSessions = ConcurrentHashMap<FlowKey, UdpSession>()
    private val connectivityManager by lazy { vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val connectionOwnerResolver by lazy { ConnectionOwnerResolver(connectivityManager) }
    private val packageManager by lazy { vpnService.packageManager }
    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)

    /** Identification field for the IP fragments this bridge emits. */
    private val ipIdSeq = AtomicInteger((0..0xFFFF).random())

    @Volatile
    private var cachedBlockedPackages: Set<String> = emptySet()

    @Volatile
    private var cachedBlockedUids: Set<Int> = emptySet()

    private data class IPv6Transport(
        val nextHeader: Int,
        val offset: Int
    )

    private data class SocksAddress(
        val address: InetAddress,
        val port: Int
    )

    fun start() {
        if (isRunning.getAndSet(true)) return

        LogRepository.i("Initializing tunnel bridge (MTU=$mtu)...")
        active = this
        // A stop()/start() cycle needs a FRESH pool: shutdownNow() leaves the
        // old executor rejecting every submission forever, and each rejected
        // session launch was swallowed as a generic TUN read error — so a
        // restarted bridge silently forwarded nothing.
        executor = newSessionExecutor()

        writeThread = Thread({
            val fos = FileOutputStream(tunDescriptor.fileDescriptor)
            while (isRunning.get()) {
                try {
                    // THROUGHPUT: park on the queue only while it is empty. Once
                    // there is work, drain it in a tight loop instead of paying a
                    // timed poll (lock + park + timer arm) for every single
                    // packet — at download rates the queue is never empty, so
                    // that overhead was charged ~10k times a second for nothing.
                    // Control packets are always drained first.
                    var packet: ByteArray? = tunControlQueue.poll()
                    if (packet == null) packet = tunOutputQueue.poll(500, TimeUnit.MILLISECONDS)
                    if (packet == null) continue
                    var written = 0L
                    while (packet != null) {
                        fos.write(packet)
                        written += packet.size
                        packet = tunControlQueue.poll() ?: tunOutputQueue.poll()
                    }
                    rxBytes.addAndGet(written)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) LogRepository.w("TUN write error: ${e.message}")
                }
            }
        }, "Aether-TunWriter").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }

        readThread = Thread({
            val fis = FileInputStream(tunDescriptor.fileDescriptor)
            val buffer = ByteArray(mtu + 200)
            waitForCore()
            // SPIN FIX: `read` returning -1 (the TUN fd was closed under us) or
            // failing repeatedly used to be treated as "nothing to do" and the
            // loop went straight back to read() — a busy loop that outlived the
            // session. EOF now ends the loop, and repeated errors are backed off
            // before they can burn the battery. The fd itself is put in BLOCKING
            // mode by the VpnService when this bridge is used (see
            // establishTun), so an idle tunnel parks here instead of spinning on
            // the EAGAIN-as-0 reads Android returns for a non-blocking fd.
            var errors = 0
            while (isRunning.get()) {
                try {
                    val n = fis.read(buffer)
                    if (n < 0) break
                    errors = 0
                    if (n == 0) continue
                    txBytes.addAndGet(n.toLong())
                    processPacket(buffer, n)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    LogRepository.w("TUN read error: ${e.message}")
                    if (++errors >= MAX_CONSECUTIVE_TUN_ERRORS) {
                        LogRepository.e("TUN reader giving up after $errors consecutive errors")
                        break
                    }
                    runCatching { Thread.sleep(TUN_ERROR_BACKOFF_MS) }
                }
            }
        }, "Aether-TunReader").apply {
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
        writeThread?.interrupt()
        readThread = null
        writeThread = null
        tunOutputQueue.clear()
        tunControlQueue.clear()
    }

    fun getStats(): Stats = Stats(txBytes.get(), rxBytes.get())

    /**
     * Waits for the engine's local SOCKS5 listener to answer before the first
     * packet is forwarded.
     *
     * NOTE: the loop used to `return@repeat` on success, which is Kotlin for
     * *continue* — so a healthy engine still got all 25 probe connects instead
     * of one. A plain loop with a real break keeps it to a single connect.
     */
    private fun waitForCore() {
        var attempts = 0
        while (attempts++ < CORE_WAIT_ATTEMPTS) {
            val ready = runCatching {
                Socket().use { s ->
                    runCatching { vpnService.protect(s) }
                    s.connect(InetSocketAddress(socksHost, socksPort), CORE_PROBE_TIMEOUT_MS)
                }
            }.isSuccess
            if (ready) {
                LogRepository.i("Link synchronization complete")
                return
            }
            if (!isRunning.get()) return
            runCatching { Thread.sleep(CORE_WAIT_INTERVAL_MS) }.getOrElse { return }
        }
        LogRepository.w("Engine SOCKS5 port did not answer before the first packet — forwarding anyway")
    }

    /**
     * Hands a packet to the TUN writer.
     *
     * [critical] packets (SYN/ACK, RST, FIN, DNS answers) go to the dedicated
     * control queue, which the writer always drains first. When IT saturates,
     * evicting another control packet is honest: the peer retransmits every
     * one of these (a lost ACK rides a dup-ACK, a lost SYN/ACK rides the
     * client's SYN RTO, FIN/RST loss falls back to idle reapers). The old
     * single-queue eviction could instead drop a DATA segment whose sequence
     * number was already committed — a permanent hole that hung the stream.
     * Data itself applies BACK-PRESSURE and never gets evicted. Returns false
     * when the packet could not be queued at all.
     */
    private fun enqueueTun(data: ByteArray, critical: Boolean = false): Boolean {
        if (critical) {
            if (tunControlQueue.offer(data)) return true
            tunControlQueue.poll()
            return tunControlQueue.offer(data)
        }
        if (tunOutputQueue.offer(data)) return true
        return runCatching {
            tunOutputQueue.offer(data, TUN_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    private fun processPacket(packet: ByteArray, len: Int) {
        if (len < 1) return
        when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> processIpv4(packet, len)
            6 -> processIpv6(packet, len)
        }
    }

    private fun processIpv4(packet: ByteArray, len: Int) {
        if (len < 20) return
        val proto = packet[9].toInt() and 0xFF
        if (proto != 6 && proto != 17) return

        val hLen = (packet[0].toInt() and 0x0F) * 4
        if (len < hLen) return

        // FRAGMENT GUARD: only an unfragmented datagram (or the first fragment,
        // which this stack cannot complete on its own) carries a transport
        // header. A later fragment starts with payload bytes, and reading those
        // as ports/flags invented a flow — complete with its own session and
        // two threads — out of arbitrary data. Fragments are dropped instead.
        val fragmentField = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        val moreFragments = (packet[6].toInt() and 0x20) != 0
        if (fragmentField != 0 || moreFragments) return

        val srcIp = getInt(packet, 12)
        val dstIp = getInt(packet, 16)

        if (proto == 6) {
            if (len < hLen + 20) return

            val srcPort = getShort(packet, hLen)
            val dstPort = getShort(packet, hLen + 2)
            val key = FlowKey4(6, srcIp, srcPort, dstIp, dstPort)
            val session = tcpSessions[key]

            if (session != null) {
                val flags = packet[hLen + 13].toInt() and 0xFF
                val isPureSyn = (flags and 0x02) != 0 && (flags and 0x10) == 0

                if (isPureSyn && !session.isConnected()) {
                    if (session.isStaleUnconnected()) {
                        // Long-unanswered SYN: recycle the session so the next
                        // retransmission builds a fresh one.
                        tcpSessions.remove(key, session)
                        session.close()
                    } else {
                        // Still within the dial window: re-answer instead of
                        // dropping. A dropped SYN RTO makes the client wait a
                        // full extra timeout for an answer we already owe it.
                        session.resendSynAck()
                        return
                    }
                } else {
                    if (isUidBlocked(session.uid)) {
                        tcpSessions.remove(key, session)
                        session.close()
                        return
                    }

                    val seq = getLong(packet, hLen + 4)
                    val tcpDataOffset = hLen + ((packet[hLen + 12].toInt() and 0xF0) shr 2)
                    val payloadLen = if (len > tcpDataOffset) len - tcpDataOffset else 0
                    val payload = if (payloadLen > 0) packet.copyOfRange(tcpDataOffset, len) else null

                    session.handleFromTun(seq, payload, flags)
                    return
                }
            }

            val flags = packet[hLen + 13].toInt() and 0xFF
            if ((flags and 0x02) != 0 && (flags and 0x10) == 0) {
                val srcBytes = intToBytes(srcIp)
                val dstBytes = intToBytes(dstIp)
                val uid = ownerUid(OsConstants.IPPROTO_TCP, srcBytes, srcPort, dstBytes, dstPort)
                val seq = getLong(packet, hLen + 4)

                if (isUidBlocked(uid)) {
                    enqueueTun(buildTcp4(dstIp, srcIp, dstPort, srcPort, null, 0, (seq + 1) and 0xFFFFFFFFL, 0x14), true)
                    return
                }

                val newSession = TcpSession(key, 4, srcBytes, dstBytes, srcPort, dstPort, seq, uid)
                tcpSessions[key] = newSession
                executor.execute { newSession.run() }
            }
        } else {
            if (len < hLen + 8) return

            val srcPort = getShort(packet, hLen)
            val dstPort = getShort(packet, hLen + 2)
            // ONE copy per datagram: the same payload used to be cut out of the
            // buffer up to three times (DNS guard, DNS sniffer, session queue).
            val payload = packet.copyOfRange(hLen + 8, len)

            if (dstPort == 53 && isDnsBlocked(payload, intToBytes(dstIp))) {
                val nxResponse = buildDnsNXResponse(payload)
                if (nxResponse != null) {
                    enqueueTun(buildUdp4(dstIp, srcIp, 53, srcPort, nxResponse), true)
                    return
                }
            }

            if (srcPort == 53) sniffDnsResponse(payload)

            val key = FlowKey4(17, srcIp, srcPort, dstIp, dstPort)
            val session = udpSessions[key]

            if (session != null) {
                if (isUidBlocked(session.uid)) {
                    udpSessions.remove(key, session)
                    session.close()
                    return
                }
                session.queue(payload)
            } else {
                val srcBytes = intToBytes(srcIp)
                val dstBytes = intToBytes(dstIp)
                val uid = ownerUid(OsConstants.IPPROTO_UDP, srcBytes, srcPort, dstBytes, dstPort)
                if (isUidBlocked(uid)) return

                val newSession = UdpSession(key, 4, srcBytes, dstBytes, srcPort, dstPort, uid)
                udpSessions[key] = newSession
                executor.execute { newSession.run() }
                newSession.queue(payload)
            }
        }
    }

    private fun processIpv6(packet: ByteArray, len: Int) {
        val transport = ipv6Transport(packet, len) ?: return
        val srcIp = packet.copyOfRange(8, 24)
        val dstIp = packet.copyOfRange(24, 40)
        val offset = transport.offset

        if (len < offset + 4) return

        val srcPort = getShort(packet, offset)
        val dstPort = getShort(packet, offset + 2)

        if (transport.nextHeader == 6) {
            if (len < offset + 20) return

            val key = FlowKey6(6, srcIp, srcPort, dstIp, dstPort)
            val session = tcpSessions[key]

            if (session != null) {
                val flags = packet[offset + 13].toInt() and 0xFF
                val isPureSyn = (flags and 0x02) != 0 && (flags and 0x10) == 0

                if (isPureSyn && !session.isConnected()) {
                    if (session.isStaleUnconnected()) {
                        // Long-unanswered SYN: recycle the session so the next
                        // retransmission builds a fresh one.
                        tcpSessions.remove(key, session)
                        session.close()
                    } else {
                        // Still within the dial window: re-answer instead of
                        // dropping. A dropped SYN RTO makes the client wait a
                        // full extra timeout for an answer we already owe it.
                        session.resendSynAck()
                        return
                    }
                } else {
                    if (isUidBlocked(session.uid)) {
                        tcpSessions.remove(key, session)
                        session.close()
                        return
                    }

                    val seq = getLong(packet, offset + 4)
                    val tcpDataOffset = offset + ((packet[offset + 12].toInt() and 0xF0) shr 2)
                    val payloadLen = if (len > tcpDataOffset) len - tcpDataOffset else 0
                    val payload = if (payloadLen > 0) packet.copyOfRange(tcpDataOffset, len) else null

                    session.handleFromTun(seq, payload, flags)
                    return
                }
            }

            val flags = packet[offset + 13].toInt() and 0xFF
            if ((flags and 0x02) != 0 && (flags and 0x10) == 0) {
                val uid = ownerUid(OsConstants.IPPROTO_TCP, srcIp, srcPort, dstIp, dstPort)
                val seq = getLong(packet, offset + 4)

                if (isUidBlocked(uid)) {
                    enqueueTun(buildTcp6(dstIp, srcIp, dstPort, srcPort, null, 0, (seq + 1) and 0xFFFFFFFFL, 0x14), true)
                    return
                }

                val newSession = TcpSession(key, 6, srcIp, dstIp, srcPort, dstPort, seq, uid)
                tcpSessions[key] = newSession
                executor.execute { newSession.run() }
            }
        } else if (transport.nextHeader == 17) {
            if (len < offset + 8) return

            // ONE copy per datagram (see the IPv4 branch).
            val payload = packet.copyOfRange(offset + 8, len)

            if (dstPort == 53 && isDnsBlocked(payload, dstIp)) {
                val nxResponse = buildDnsNXResponse(payload)
                if (nxResponse != null) {
                    enqueueTun(buildUdp6(dstIp, srcIp, 53, srcPort, nxResponse), true)
                    return
                }
            }

            if (srcPort == 53) sniffDnsResponse(payload)

            val key = FlowKey6(17, srcIp, srcPort, dstIp, dstPort)
            val session = udpSessions[key]

            if (session != null) {
                if (isUidBlocked(session.uid)) {
                    udpSessions.remove(key, session)
                    session.close()
                    return
                }
                session.queue(payload)
            } else {
                val uid = ownerUid(OsConstants.IPPROTO_UDP, srcIp, srcPort, dstIp, dstPort)
                if (isUidBlocked(uid)) return

                val newSession = UdpSession(key, 6, srcIp, dstIp, srcPort, dstPort, uid)
                udpSessions[key] = newSession
                executor.execute { newSession.run() }
                newSession.queue(payload)
            }
        }
    }

    private fun ipv6Transport(packet: ByteArray, len: Int): IPv6Transport? {
        if (len < 40) return null
        var nextHeader = packet[6].toInt() and 0xFF
        var offset = 40

        while (true) {
            when (nextHeader) {
                6, 17 -> return IPv6Transport(nextHeader, offset)
                0, 43, 60, 135 -> {
                    if (offset + 2 > len) return null
                    val extLen = ((packet[offset + 1].toInt() and 0xFF) + 1) * 8
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += extLen
                }
                44 -> return null
                else -> return null
            }
        }
    }

    /**
     * Owning app UID of a flow, or -1 when it cannot be resolved. An
     * unresolvable UID is PERMITTED (fail-open), which is only worth a log line
     * while a block list is actually active.
     */
    private fun ownerUid(
        protocol: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ): Int {
        val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
        val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
        val uid = connectionOwnerResolver.resolve(protocol, local, remote)
        if (uid == -1 && currentBlockedUids().isNotEmpty()) {
            LogRepository.w("Flow owner UID unresolved; permitting connection")
        }
        return uid
    }

    /** True when this DNS query asks for a domain a BLOCK rule covers. */
    private fun isDnsBlocked(payload: ByteArray, dstIp: ByteArray): Boolean {
        val domain = extractDomainName(payload, payload.size) ?: return false
        val dstIpStr = runCatching { InetAddress.getByAddress(dstIp).hostAddress }.getOrNull() ?: ""
        if (routingEngine.resolve(dstIpStr, 53, domain, null, null).mode != RoutingMode.BLOCK) return false
        LogRepository.i("[DnsGuard] [Block] domain=$domain", "DnsGuard")
        return true
    }

    private fun underlyingNetwork(): Network? {
        val candidates = connectivityManager.allNetworks.filter { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    private fun supportsIpv6(network: Network): Boolean {
        return connectivityManager.getLinkProperties(network)?.routes?.any { route ->
            route.isDefaultRoute && route.destination.address is Inet6Address
        } == true
    }

    private fun networkLabel(network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "physical"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "physical"
        }
    }

    private fun isUidBlocked(uid: Int): Boolean {
        if (uid == -1) return false
        return uid in currentBlockedUids()
    }

    private fun currentBlockedUids(): Set<Int> {
        val packages = blockedPackagesProvider()
        if (packages == cachedBlockedPackages) return cachedBlockedUids

        return synchronized(this) {
            if (packages == cachedBlockedPackages) {
                cachedBlockedUids
            } else {
                val uids = mutableSetOf<Int>()
                for (pkg in packages) {
                    val uid = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0)).uid
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getApplicationInfo(pkg, 0).uid
                        }
                    }.getOrNull()
                    if (uid != null) uids.add(uid)
                }
                cachedBlockedPackages = packages
                cachedBlockedUids = uids
                uids
            }
        }
    }

    private inner class TcpSession(
        val key: FlowKey,
        val version: Int,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        initialSeq: Long,
        val uid: Int
    ) {
        private val queue = java.util.concurrent.LinkedBlockingDeque<ByteArray>(8192)
        private val isClosed = AtomicBoolean(false)

        // Volatile: close() can be called from the TUN reader thread while the
        // session thread is still dialling and has not published the socket yet.
        @Volatile
        private var sock: Socket? = null
        private val mySeq = AtomicLong((100000..900000).random().toLong())
        private val myAck = AtomicLong((initialSeq + 1) and 0xFFFFFFFFL)
        private val connected = AtomicBoolean(false)

        /**
         * True once a SYN/ACK has been handed to the TUN. From the app's point
         * of view the connection is open from that moment on, so its data must
         * be accepted even while the upstream leg is still being dialled — see
         * [handleFromTun].
         */
        private val synAcked = AtomicBoolean(false)

        /** Guards against sending both a FIN and an RST for the same session. */
        private val peerNotified = AtomicBoolean(false)
        private val clientClosed = AtomicBoolean(false)
        private val outputShutdown = AtomicBoolean(false)
        private val createdAt = SystemClock.elapsedRealtime()
        private val lastActivity = AtomicLong(SystemClock.elapsedRealtime())

        fun isConnected(): Boolean = connected.get()

        /**
         * Recycle threshold for a session whose client SYN never led anywhere.
         * Deliberately ABOVE the client's first retransmission (~1 s): the
         * deferred SYN/ACK path can legitimately spend longer than that
         * dialling upstream on high-RTT links, and killing the session mid-dial
         * just restarts the same dial from scratch — repeatedly, until the
         * client gives up entirely.
         */
        fun isStaleUnconnected(): Boolean =
            !connected.get() && (SystemClock.elapsedRealtime() - createdAt > STALE_UNCONNECTED_MS)

        /** Re-answers a retransmitted client SYN; no-op once the SYN/ACK is out. */
        fun resendSynAck() {
            if (!synAcked.get()) sendSynAck()
        }

        /**
         * Feeds one segment that arrived from the TUN into this session.
         *
         * HANDSHAKE FIX: the gate used to be [connected], which is only set
         * after the upstream SOCKS leg is fully dialled. The domain-sniffing
         * path deliberately answers the SYN *before* dialling and then waits for
         * the client's first segment to read the TLS SNI out of it — but that
         * segment hit this gate and was thrown away, so the wait ALWAYS ran out
         * (a full second added to every fresh port 80/443 connection) and the
         * client had to retransmit its ClientHello on its own RTO. Accepting
         * data as soon as the SYN/ACK is out fixes both.
         *
         * PAYLOAD-BEFORE-FIN FIX: the FIN branch used to return before the
         * payload was looked at, so a segment carrying data *and* FIN — the
         * normal way a client ends a request — lost that data and acked as if
         * it had never been sent.
         */
        fun handleFromTun(seq: Long, payload: ByteArray?, flags: Int) {
            if (isClosed.get()) return

            lastActivity.set(SystemClock.elapsedRealtime())

            if ((flags and 0x04) != 0) {
                close()
                return
            }

            if (!synAcked.get()) {
                // Upstream leg still dialling: a retransmitted initial SYN is
                // expected here and is answered by [resendSynAck] upstream.
                if ((flags and 0x01) != 0) {
                    close()
                }
                return
            }

            if ((flags and 0x02) != 0) {
                // SYN on an ESTABLISHED session: the peer crashed or rebooted
                // into the same 4-tuple. Reset so its new connect attempt fails
                // fast instead of hanging until its own timeout.
                sendReset()
                close()
                return
            }

            val fin = (flags and 0x01) != 0
            val body = payload?.takeIf { it.isNotEmpty() }
            val length = body?.size ?: 0
            // A bare ACK carries nothing to acknowledge; replying to one would
            // start an endless ACK ping-pong with the client.
            if (body == null && !fin) return

            // No reassembly queue here, so anything out of order gets a
            // duplicate ACK and has to be retransmitted.
            if (seq != myAck.get()) {
                sendAck()
                return
            }

            if (body != null && !queue.offer(body)) {
                sendAck()
                return
            }

            var next = (seq + length) and 0xFFFFFFFFL
            if (fin) {
                next = (next + 1) and 0xFFFFFFFFL
                clientClosed.set(true)
            }
            myAck.set(next)
            sendAck()
        }

        private fun sendAck() {
            val packet = if (version == 4) {
                buildTcp4(bytesToInt(serverIp), bytesToInt(clientIp), serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x10)
            } else {
                buildTcp6(serverIp, clientIp, serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x10)
            }
            enqueueTun(packet, true)
        }

        /** Answers the client's SYN. Idempotent: the second call is a no-op. */
        private fun sendSynAck() {
            if (!synAcked.compareAndSet(false, true)) return
            val synAck = if (version == 4) {
                buildTcp4(bytesToInt(serverIp), bytesToInt(clientIp), serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x12)
            } else {
                buildTcp6(serverIp, clientIp, serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x12)
            }
            enqueueTun(synAck, true)
            mySeq.set((mySeq.get() + 1) and 0xFFFFFFFFL)
        }

        /** Half-closes the connection towards the app (upstream saw EOF). */
        private fun sendFin() {
            if (!peerNotified.compareAndSet(false, true)) return
            val fin = if (version == 4) {
                buildTcp4(bytesToInt(serverIp), bytesToInt(clientIp), serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x11)
            } else {
                buildTcp6(serverIp, clientIp, serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x11)
            }
            enqueueTun(fin, true)
            mySeq.set((mySeq.get() + 1) and 0xFFFFFFFFL)
        }

        /**
         * Tears the connection down towards the app.
         *
         * FAIL-FAST FIX: every upstream failure (SOCKS greeting refused, CONNECT
         * refused, direct dial refused, mid-stream I/O error) used to return
         * silently, leaving the app with a socket that looked perfectly healthy.
         * It then waited out its own timeout — tens of seconds of "the page just
         * hangs" — instead of getting an instant error it could retry on.
         */
        private fun sendReset() {
            if (!peerNotified.compareAndSet(false, true)) return
            val rst = if (version == 4) {
                buildTcp4(bytesToInt(serverIp), bytesToInt(clientIp), serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x14)
            } else {
                buildTcp6(serverIp, clientIp, serverPort, clientPort, null, mySeq.get(), myAck.get(), 0x14)
            }
            enqueueTun(rst, true)
        }

        fun run() {
            try {
                val targetIpStr: String = InetAddress.getByAddress(serverIp).hostAddress ?: ""
                val cachedDomain = DnsMap.get(targetIpStr)
                
                var decision = routingEngine.resolve(targetIpStr, serverPort, cachedDomain, null, null)
                var sniffedDomain: String? = null

                // Reading the TLS SNI / HTTP Host header out of the first
                // segment means answering the SYN before the upstream leg even
                // exists, so it is only worth doing when a domain can actually
                // change the verdict. Aether drives this bridge with an EMPTY
                // rule set (it is activated for per-app blocking, and the real
                // routing rules live in the engine), where the detour decided
                // nothing and only added latency to every fresh connection.
                if (decision.mode == RoutingMode.TUNNEL &&
                    cachedDomain == null &&
                    (serverPort == 80 || serverPort == 443) &&
                    routingEngine.hasDomainRules()
                ) {
                    sendSynAck()

                    val firstPacket = queue.poll(SNIFF_WAIT_MS, TimeUnit.MILLISECONDS)
                    if (firstPacket != null) {
                        sniffedDomain = TrafficSniffer.sniffDomain(firstPacket, serverPort)
                        if (sniffedDomain != null) {
                            DnsMap.put(targetIpStr, sniffedDomain)
                            decision = routingEngine.resolve(
                                targetIpStr,
                                serverPort,
                                null,
                                if (serverPort == 443) sniffedDomain else null,
                                if (serverPort == 80) sniffedDomain else null
                            )
                        }
                        queue.addFirst(firstPacket)
                    }
                }

                val requestedDirect = decision.mode == RoutingMode.DIRECT
                val directNetwork = if (requestedDirect) underlyingNetwork() else null
                val useDirect = requestedDirect && directNetwork != null && (version == 4 || supportsIpv6(directNetwork))

                if (decision.matchedRule != null) {
                    if (requestedDirect && !useDirect) {
                        LogRepository.i("[Routing] DIRECT_REJECTED domain=${decision.resolvedDomain ?: "unknown"} ip=$targetIpStr protocol=TCP reason=no_underlying_route")
                    } else {
                        LogRepository.i("[Routing] ${decision.mode.name} domain=${decision.resolvedDomain ?: "unknown"} ip=$targetIpStr protocol=TCP")
                    }
                }

                if (requestedDirect && !useDirect) {
                    sendReset()
                    close()
                    return
                }

                if (decision.mode == RoutingMode.BLOCK) {
                    sendReset()
                    close()
                    return
                }

                val s = Socket()
                sock = s
                s.tcpNoDelay = true
                s.keepAlive = true
                s.receiveBufferSize = 262144
                s.sendBufferSize = 262144
                // Bound the handshake phase: connect() carries a timeout, but
                // the SOCKS greeting/reply reads below are plain blocking reads
                // — a stalled proxy would wedge this session thread forever,
                // with no RST ever reaching the app. Cleared once connected so
                // long-lived idle flows are not cut.
                runCatching { s.soTimeout = DIAL_READ_TIMEOUT_MS }

                val ins: InputStream
                val out: OutputStream

                if (useDirect) {
                    val network = requireNotNull(directNetwork)
                    network.bindSocket(s)
                    s.connect(InetSocketAddress(targetIpStr, serverPort), 5000)
                    ins = s.getInputStream()
                    out = BufferedOutputStream(s.getOutputStream(), 131072)
                    val directNetworkType = networkLabel(network)
                    LogRepository.i("[Routing] DIRECT_CONNECTED domain=${decision.resolvedDomain ?: "unknown"} ip=$targetIpStr via=$directNetworkType local=${s.localAddress.hostAddress}")
                } else {
                    runCatching { vpnService.protect(s) }
                    s.connect(InetSocketAddress(socksHost, socksPort), 5000)
                    ins = s.getInputStream()
                    out = BufferedOutputStream(s.getOutputStream(), 131072)
                    if (!socksHandshake(ins, out)) {
                        sendReset()
                        return
                    }
                    out.write(socksRequest(1, sniffedDomain ?: cachedDomain, serverIp, serverPort))
                    out.flush()
                    if (readSocksReply(ins) == null) {
                        sendReset()
                        return
                    }
                }

                connected.set(true)
                lastActivity.set(SystemClock.elapsedRealtime())
                // Handshake over — restore unlimited blocking reads for the
                // data phase (the idle reaper owns liveness from here on).
                runCatching { s.soTimeout = 0 }

                if (isClosed.get()) return

                sendSynAck()

                executor.execute { readFromSocks(ins) }

                while (!isClosed.get() && isRunning.get()) {
                    val data = queue.poll(2, TimeUnit.SECONDS)

                    if (data == null) {
                        if (clientClosed.get() && queue.isEmpty() && outputShutdown.compareAndSet(false, true)) {
                            runCatching { sock?.shutdownOutput() }
                        }

                        if (SystemClock.elapsedRealtime() - lastActivity.get() > 300000) {
                            close()
                            break
                        }

                        continue
                    }

                    out.write(data)

                    var count = 0
                    while (count < 64) {
                        val next = queue.poll() ?: break
                        out.write(next)
                        count++
                    }

                    out.flush()
                    lastActivity.set(SystemClock.elapsedRealtime())

                    if (clientClosed.get() && queue.isEmpty() && outputShutdown.compareAndSet(false, true)) {
                        runCatching { sock?.shutdownOutput() }
                    }
                }
            } catch (exception: Exception) {
                if (isRunning.get() && !isClosed.get()) {
                    LogRepository.w("[Routing] TCP session failed: ${exception.localizedMessage}")
                    // Tell the app the connection died instead of leaving it to
                    // discover that on its own timeout.
                    sendReset()
                }
            } finally {
                close()
            }
        }

        private fun readFromSocks(ins: InputStream) {
            try {
                val buffer = ByteArray(131072)
                val maxPayload = if (version == 4) (mtu - 40).coerceAtLeast(20) else (mtu - 60).coerceAtLeast(20)

                while (!isClosed.get() && isRunning.get()) {
                    val n = ins.read(buffer)

                    if (n <= 0) {
                        sendFin()
                        break
                    }

                    lastActivity.set(SystemClock.elapsedRealtime())

                    var offset = 0
                    while (offset < n) {
                        val chunkLen = minOf(maxPayload, n - offset)

                        // ZERO-COPY: the segment is written straight out of the
                        // read buffer. Cutting a `chunk` array out first, and
                        // then copying the whole TCP segment AGAIN just to
                        // checksum it, meant every downloaded byte was moved
                        // three times and allocated twice — pure GC pressure on
                        // the hottest path in the app.
                        val packet = if (version == 4) {
                            buildTcp4(bytesToInt(serverIp), bytesToInt(clientIp), serverPort, clientPort, buffer, mySeq.get(), myAck.get(), 0x18, offset, chunkLen)
                        } else {
                            buildTcp6(serverIp, clientIp, serverPort, clientPort, buffer, mySeq.get(), myAck.get(), 0x18, offset, chunkLen)
                        }

                        // STREAM-INTEGRITY FIX: the sequence number may only
                        // advance for a segment the writer really accepted.
                        // Dropping one silently (queue full) left a hole no
                        // retransmission could ever fill, and the connection
                        // hung until it timed out. If even the back-pressured
                        // enqueue fails, tear the session down instead —
                        // an honest reset beats a stalled socket.
                        if (!enqueueTun(packet)) {
                            LogRepository.w("TUN queue saturated — resetting stalled TCP session")
                            sendReset()
                            close()
                            return
                        }
                        mySeq.set((mySeq.get() + chunkLen) and 0xFFFFFFFFL)
                        offset += chunkLen
                    }
                }
            } catch (_: Exception) {
                if (isRunning.get() && !isClosed.get()) sendReset()
            } finally {
                close()
            }
        }

        fun close() {
            // The CAS only guards the notification bookkeeping and the map
            // removal. Resource release runs UNCONDITIONALLY: close() can race
            // the dialling thread (which observes sock == null here and creates
            // the socket a moment later) — that socket's own finally-block then
            // still closes it, instead of early-returning on a taken CAS.
            if (!isClosed.getAndSet(true)) tcpSessions.remove(key, this)
            runCatching { sock?.close() }
        }
    }

    private inner class UdpSession(
        val key: FlowKey,
        val version: Int,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        val uid: Int
    ) {
        private val payloadQueue = LinkedBlockingQueue<ByteArray>(2048)
        private val isClosed = AtomicBoolean(false)

        // Volatile + unconditionally closed in [close]: stop() can race the
        // session thread while it is still resolving/associating, before these
        // fields are assigned — the old CAS-first close leaked both sockets on
        // that interleaving.
        @Volatile
        private var ctrlSock: Socket? = null

        @Volatile
        private var udpSock: DatagramSocket? = null
        private val lastActivity = AtomicLong(SystemClock.elapsedRealtime())

        fun queue(data: ByteArray) {
            if (!isClosed.get() && payloadQueue.offer(data)) {
                lastActivity.set(SystemClock.elapsedRealtime())
            }
        }

        fun run() {
            try {
                val targetIpStr: String = InetAddress.getByAddress(serverIp).hostAddress ?: ""
                val targetDomain = DnsMap.get(targetIpStr)
                
                val decision = routingEngine.resolve(targetIpStr, serverPort, targetDomain, null, null)
                val requestedDirect = decision.mode == RoutingMode.DIRECT
                val directNetwork = if (requestedDirect) underlyingNetwork() else null
                val isDirect = requestedDirect && directNetwork != null && (version == 4 || supportsIpv6(directNetwork))

                if (serverPort == 443 && targetDomain == null && routingEngine.hasDomainRules()) {
                    close()
                    return
                }

                if (decision.matchedRule != null) {
                    if (requestedDirect && !isDirect) {
                        LogRepository.i("[Routing] DIRECT_REJECTED domain=${decision.resolvedDomain ?: "unknown"} ip=$targetIpStr protocol=UDP reason=no_underlying_route")
                    } else {
                        LogRepository.i("[Routing] ${decision.mode.name} domain=${decision.resolvedDomain ?: "unknown"} ip=$targetIpStr protocol=UDP")
                    }
                }

                if (requestedDirect && !isDirect) {
                    close()
                    return
                }
                
                if (decision.mode == RoutingMode.BLOCK) {
                    close()
                    return
                }

                val ctrl = Socket()
                ctrlSock = ctrl
                runCatching { vpnService.protect(ctrl) }
                ctrl.tcpNoDelay = true
                // The ASSOCIATE exchange below is plain blocking reads.
                runCatching { ctrl.soTimeout = DIAL_READ_TIMEOUT_MS }
                
                val relayHost: InetAddress
                val relayPort: Int
                val header: ByteArray
                
                if (isDirect) {
                    relayHost = InetAddress.getByName(targetIpStr)
                    relayPort = serverPort
                    header = ByteArray(0)
                } else {
                    ctrl.connect(InetSocketAddress(socksHost, socksPort), 5000)
                    val ins = ctrl.getInputStream()
                    val out = ctrl.getOutputStream()
                    if (!socksHandshake(ins, out)) return
                    val associateAddress = if (version == 4) ByteArray(4) else ByteArray(16)
                    out.write(socksRequest(3, null, associateAddress, 0))
                    out.flush()
                    val relay = readSocksReply(ins) ?: return
                    relayHost = if (relay.address.isAnyLocalAddress) InetAddress.getByName(socksHost) else relay.address
                    relayPort = relay.port
                    header = socksUdpHeader(targetDomain, serverIp, serverPort)
                }

                val relaySocket = DatagramSocket()
                udpSock = relaySocket
                if (isDirect) {
                    val network = requireNotNull(directNetwork)
                    network.bindSocket(relaySocket)
                    val directNetworkType = networkLabel(network)
                    LogRepository.i("[Routing] DIRECT_UDP_BOUND domain=${decision.resolvedDomain ?: "unknown"} ip=$targetIpStr via=$directNetworkType")
                } else {
                    runCatching { vpnService.protect(relaySocket) }
                }
                relaySocket.soTimeout = 10000

                executor.execute { receiveFromNetwork(relaySocket, isDirect) }

                val relayAddress = InetSocketAddress(relayHost, relayPort)

                while (!isClosed.get() && isRunning.get()) {
                    val payload = payloadQueue.poll(2, TimeUnit.SECONDS)

                    if (payload == null) {
                        if (SystemClock.elapsedRealtime() - lastActivity.get() > 120000) {
                            close()
                            break
                        }
                        continue
                    }

                    val full = if (isDirect) {
                        payload
                    } else {
                        val f = ByteArray(header.size + payload.size)
                        System.arraycopy(header, 0, f, 0, header.size)
                        System.arraycopy(payload, 0, f, header.size, payload.size)
                        f
                    }

                    relaySocket.send(DatagramPacket(full, full.size, relayAddress))
                    lastActivity.set(SystemClock.elapsedRealtime())
                }
            } catch (exception: Exception) {
                if (isRunning.get() && !isClosed.get()) {
                    LogRepository.w("[Routing] UDP session failed: ${exception.localizedMessage}")
                }
            } finally {
                close()
            }
        }

        private fun receiveFromNetwork(sock: DatagramSocket, direct: Boolean) {
            try {
                val buf = ByteArray(65535)
                // One DatagramPacket for the whole session: `receive` only needs
                // its length reset, and allocating a fresh one per datagram made
                // the UDP path allocate twice for every packet.
                val datagram = DatagramPacket(buf, buf.size)

                while (!isClosed.get() && isRunning.get()) {
                    try {
                        datagram.setData(buf, 0, buf.size)
                        sock.receive(datagram)
                        lastActivity.set(SystemClock.elapsedRealtime())

                        val source: SocksAddress
                        val payload: ByteArray
                        if (direct) {
                            source = SocksAddress(datagram.address, datagram.port)
                            payload = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                        } else {
                            val parsed = parseSocksUdp(datagram.data, datagram.length) ?: continue
                            source = parsed.first
                            payload = parsed.second
                        }

                        if (source.port == 53) sniffDnsResponse(payload)
                        val srcBytes = source.address.address

                        // Oversized datagrams are fragmented, not discarded:
                        // see [emitUdp4].
                        if (version == 4 && srcBytes.size == 4) {
                            emitUdp4(bytesToInt(srcBytes), bytesToInt(clientIp), source.port, clientPort, payload)
                        } else if (version == 6 && srcBytes.size == 16) {
                            emitUdp6(srcBytes, clientIp, source.port, clientPort, payload)
                        }
                    } catch (_: SocketTimeoutException) {
                        if (SystemClock.elapsedRealtime() - lastActivity.get() > 120000) break
                    } catch (_: Exception) {
                        break
                    }
                }
            } finally {
                close()
            }
        }

        fun close() {
            if (!isClosed.getAndSet(true)) udpSessions.remove(key, this)
            runCatching { ctrlSock?.close() }
            runCatching { udpSock?.close() }
        }
    }

    private fun socksHandshake(ins: InputStream, out: OutputStream): Boolean {
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val response = ByteArray(2)
        if (!readExact(ins, response)) return false
        return response[0] == 5.toByte() && response[1] == 0.toByte()
    }

    private fun socksRequest(command: Int, domain: String?, address: ByteArray, port: Int): ByteArray {
        if (domain != null) {
            val db = domain.toByteArray()
            val req = ByteArray(7 + db.size)
            req[0] = 5
            req[1] = command.toByte()
            req[2] = 0
            req[3] = 3
            req[4] = db.size.toByte()
            System.arraycopy(db, 0, req, 5, db.size)
            req[5 + db.size] = (port shr 8).toByte()
            req[6 + db.size] = (port and 0xFF).toByte()
            return req
        }
        val req = ByteArray(6 + address.size)
        req[0] = 5
        req[1] = command.toByte()
        req[2] = 0
        req[3] = if (address.size == 4) 1 else 4
        System.arraycopy(address, 0, req, 4, address.size)
        req[4 + address.size] = (port shr 8).toByte()
        req[5 + address.size] = (port and 0xFF).toByte()
        return req
    }

    private fun readSocksReply(ins: InputStream): SocksAddress? {
        val header = ByteArray(4)
        if (!readExact(ins, header)) return null
        if (header[0] != 5.toByte() || header[1] != 0.toByte()) return null

        when (header[3].toInt() and 0xFF) {
            1 -> {
                val b = ByteArray(6)
                if (!readExact(ins, b)) return null
                return SocksAddress(InetAddress.getByAddress(b.copyOfRange(0, 4)), getShort(b, 4))
            }
            4 -> {
                val b = ByteArray(18)
                if (!readExact(ins, b)) return null
                return SocksAddress(InetAddress.getByAddress(b.copyOfRange(0, 16)), getShort(b, 16))
            }
            3 -> {
                val lenByte = ByteArray(1)
                if (!readExact(ins, lenByte)) return null
                val domainLength = lenByte[0].toInt() and 0xFF
                val domain = ByteArray(domainLength)
                if (!readExact(ins, domain)) return null
                val portBytes = ByteArray(2)
                if (!readExact(ins, portBytes)) return null
                val address = runCatching { InetAddress.getByName(String(domain)) }.getOrNull() ?: return null
                return SocksAddress(address, getShort(portBytes, 0))
            }
            else -> return null
        }
    }

    private fun parseSocksUdp(data: ByteArray, len: Int): Pair<SocksAddress, ByteArray>? {
        if (len < 4) return null
        // FRAG != 0 marks a FRAGMENTED datagram. There is no reassembly here,
        // so the payload slice of one fragment would be relayed as if it were
        // the whole datagram — refuse it instead.
        if ((data[2].toInt() and 0xFF) != 0) return null

        val atyp = data[3].toInt() and 0xFF
        val offset: Int
        val address: InetAddress
        val port: Int

        when (atyp) {
            1 -> {
                if (len < 10) return null
                address = InetAddress.getByAddress(data.copyOfRange(4, 8))
                port = getShort(data, 8)
                offset = 10
            }
            4 -> {
                if (len < 22) return null
                address = InetAddress.getByAddress(data.copyOfRange(4, 20))
                port = getShort(data, 20)
                offset = 22
            }
            3 -> {
                if (len < 5) return null
                val domainLength = data[4].toInt() and 0xFF
                if (len < 5 + domainLength + 2) return null
                val domain = data.copyOfRange(5, 5 + domainLength)
                address = runCatching { InetAddress.getByName(String(domain)) }.getOrNull() ?: return null
                port = getShort(data, 5 + domainLength)
                offset = 5 + domainLength + 2
            }
            else -> return null
        }

        if (len < offset) return null
        return Pair(SocksAddress(address, port), data.copyOfRange(offset, len))
    }

    private fun socksUdpHeader(domain: String?, address: ByteArray, port: Int): ByteArray {
        if (domain != null) {
            val db = domain.toByteArray()
            val h = ByteArray(7 + db.size)
            h[0] = 0
            h[1] = 0
            h[2] = 0
            h[3] = 3
            h[4] = db.size.toByte()
            System.arraycopy(db, 0, h, 5, db.size)
            h[5 + db.size] = (port shr 8).toByte()
            h[6 + db.size] = (port and 0xFF).toByte()
            return h
        }
        val header = ByteArray(6 + address.size)
        header[0] = 0
        header[1] = 0
        header[2] = 0
        header[3] = if (address.size == 4) 1 else 4
        System.arraycopy(address, 0, header, 4, address.size)
        header[4 + address.size] = (port shr 8).toByte()
        header[5 + address.size] = (port and 0xFF).toByte()
        return header
    }

    private fun readExact(ins: InputStream, b: ByteArray): Boolean {
        var o = 0
        while (o < b.size) {
            val c = ins.read(b, o, b.size - o)
            if (c < 0) return false
            o += c
        }
        return true
    }

    private fun sniffDnsResponse(data: ByteArray) {
        if (data.size < 12) return
        try {
            val flags = getShort(data, 2)
            if (flags and 0x8000 == 0) return 
            
            val qCount = getShort(data, 4)
            val aCount = getShort(data, 6)
            if (qCount == 0 || aCount == 0) return
            
            var pos = 12
            repeat(qCount) {
                while (pos < data.size) {
                    val l = data[pos].toInt() and 0xFF
                    if (l == 0) { pos++; break }
                    if (l >= 0xC0) { pos += 2; break }
                    pos += l + 1
                }
                pos += 4
            }
            
            val domain = extractDomainName(data, data.size) ?: return
            
            repeat(aCount) {
                if (pos + 12 > data.size) return@repeat

                if ((data[pos].toInt() and 0xFF) >= 0xC0) {
                    pos += 2
                } else {
                    while (pos < data.size) {
                        val l = data[pos].toInt() and 0xFF
                        if (l == 0) { pos++; break }
                        // A compression POINTER can also appear after one or
                        // more labels; treating its bytes as a label length
                        // landed the parse at an arbitrary offset and invented
                        // wrong IP→domain entries.
                        if (l >= 0xC0) { pos += 2; break }
                        pos += l + 1
                    }
                }

                // The name above is variable-length, so re-check the bounds
                // before reading TYPE/RDLENGTH — a truncated or malformed
                // answer used to throw and abort the whole parse.
                if (pos + 10 > data.size) return

                val type = getShort(data, pos)
                val rdLen = getShort(data, pos + 8)
                pos += 10
                
                if (type == 1 && rdLen == 4 && pos + 4 <= data.size) {
                    val ip = "${data[pos].toInt() and 0xFF}.${data[pos+1].toInt() and 0xFF}.${data[pos+2].toInt() and 0xFF}.${data[pos+3].toInt() and 0xFF}"
                    DnsMap.put(ip, domain)
                } else if (type == 28 && rdLen == 16 && pos + 16 <= data.size) {
                    val ip = InetAddress.getByAddress(data.copyOfRange(pos, pos + 16)).hostAddress ?: ""
                    DnsMap.put(ip, domain)
                }
                pos += rdLen
            }
        } catch (_: Exception) {}
    }

    private fun extractDomainName(data: ByteArray, length: Int): String? {
        if (length < 12) return null
        return try {
            val sb = StringBuilder()
            var pos = 12
            while (pos < length) {
                val l = data[pos].toInt() and 0xFF
                if (l == 0) break
                if (sb.isNotEmpty()) sb.append(".")
                if (pos + 1 + l > length) return null
                sb.append(String(data, pos + 1, l))
                pos += l + 1
            }
            sb.toString().lowercase()
        } catch (_: Exception) {
            null
        }
    }


    private fun buildDnsNXResponse(requestData: ByteArray): ByteArray? {
        if (requestData.size < 12) return null
        return try {
            val questionEnd = findDnsQuestionEnd(requestData)
            val resp = requestData.copyOfRange(0, questionEnd)
            resp[2] = (resp[2].toInt() or 0x80).toByte()
            resp[3] = ((resp[3].toInt() and 0x70) or 0x80 or 0x03).toByte()
            for (i in 6..11) resp[i] = 0
            resp
        } catch (_: Exception) {
            null
        }
    }

    private fun findDnsQuestionEnd(data: ByteArray): Int {
        var i = 12
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if (len >= 0xC0) {
                i += 2
                break
            }
            i += len + 1
        }
        return minOf(data.size, i + 4)
    }

    /**
     * Builds one IPv4/TCP packet.
     *
     * [dataOffset]/[dataLen] describe the slice of [data] to send, so a caller
     * that already holds a big read buffer does not have to cut a fresh array
     * out of it for every segment.
     */
    private fun buildTcp4(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray?,
        seq: Long,
        ack: Long,
        flags: Int,
        dataOffset: Int = 0,
        dataLen: Int = data?.size ?: 0
    ): ByteArray {
        val isSynAck = flags == 0x12
        val optLen = if (isSynAck) 4 else 0
        val dSize = if (data == null) 0 else dataLen
        val total = 40 + optLen + dSize
        val p = ByteArray(total)

        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = (total and 0xFF).toByte()
        p[8] = 64
        p[9] = 6

        setInt(p, 12, srcIp)
        setInt(p, 16, dstIp)

        val ipCk = calculateChecksum(p)
        p[10] = (ipCk shr 8).toByte()
        p[11] = (ipCk and 0xFF).toByte()

        setShort(p, 20, srcPort)
        setShort(p, 22, dstPort)
        setLong(p, 24, seq)
        setLong(p, 28, ack)

        p[32] = ((5 + optLen / 4) shl 4).toByte()
        p[33] = flags.toByte()
        p[34] = 0xFF.toByte()
        p[35] = 0xFF.toByte()

        if (isSynAck) {
            val mss = (mtu - 40).coerceAtLeast(536)
            p[40] = 2
            p[41] = 4
            p[42] = (mss shr 8).toByte()
            p[43] = (mss and 0xFF).toByte()
        }

        if (data != null && dSize > 0) {
            System.arraycopy(data, dataOffset, p, 40 + optLen, dSize)
        }

        var tcpCk = calculateTransportChecksum4(srcIp, dstIp, p, 20, total - 20, 6)
        if (tcpCk == 0) tcpCk = 0xFFFF

        p[36] = (tcpCk shr 8).toByte()
        p[37] = (tcpCk and 0xFF).toByte()

        return p
    }

    private fun buildTcp6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray?,
        seq: Long,
        ack: Long,
        flags: Int,
        dataOffset: Int = 0,
        dataLen: Int = data?.size ?: 0
    ): ByteArray {
        val isSynAck = flags == 0x12
        val optLen = if (isSynAck) 4 else 0
        val dSize = if (data == null) 0 else dataLen
        val tcpLen = 20 + optLen + dSize
        val total = 40 + tcpLen
        val p = ByteArray(total)

        p[0] = 0x60
        p[4] = (tcpLen shr 8).toByte()
        p[5] = (tcpLen and 0xFF).toByte()
        p[6] = 6
        p[7] = 64

        System.arraycopy(srcIp, 0, p, 8, 16)
        System.arraycopy(dstIp, 0, p, 24, 16)

        setShort(p, 40, srcPort)
        setShort(p, 42, dstPort)
        setLong(p, 44, seq)
        setLong(p, 48, ack)

        p[52] = ((5 + optLen / 4) shl 4).toByte()
        p[53] = flags.toByte()
        p[54] = 0xFF.toByte()
        p[55] = 0xFF.toByte()

        if (isSynAck) {
            val mss = (mtu - 60).coerceAtLeast(536)
            p[60] = 2
            p[61] = 4
            p[62] = (mss shr 8).toByte()
            p[63] = (mss and 0xFF).toByte()
        }

        if (data != null && dSize > 0) {
            System.arraycopy(data, dataOffset, p, 60 + optLen, dSize)
        }

        var tcpCk = calculateTransportChecksum6(srcIp, dstIp, p, 40, tcpLen, 6)
        if (tcpCk == 0) tcpCk = 0xFFFF

        p[56] = (tcpCk shr 8).toByte()
        p[57] = (tcpCk and 0xFF).toByte()

        return p
    }

    private fun buildUdp4(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray
    ): ByteArray {
        val udpLen = 8 + data.size
        val total = 20 + udpLen
        val p = ByteArray(total)

        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = (total and 0xFF).toByte()
        p[8] = 64
        p[9] = 17

        setInt(p, 12, srcIp)
        setInt(p, 16, dstIp)

        val ipCk = calculateChecksum(p)
        p[10] = (ipCk shr 8).toByte()
        p[11] = (ipCk and 0xFF).toByte()

        setShort(p, 20, srcPort)
        setShort(p, 22, dstPort)
        setShort(p, 24, udpLen)

        System.arraycopy(data, 0, p, 28, data.size)

        var udpCk = calculateTransportChecksum4(srcIp, dstIp, p, 20, udpLen, 17)
        if (udpCk == 0) udpCk = 0xFFFF

        p[26] = (udpCk shr 8).toByte()
        p[27] = (udpCk and 0xFF).toByte()

        return p
    }

    private fun buildUdp6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray
    ): ByteArray {
        val udpLen = 8 + data.size
        val total = 40 + udpLen
        val p = ByteArray(total)

        p[0] = 0x60
        p[4] = (udpLen shr 8).toByte()
        p[5] = (udpLen and 0xFF).toByte()
        p[6] = 17
        p[7] = 64

        System.arraycopy(srcIp, 0, p, 8, 16)
        System.arraycopy(dstIp, 0, p, 24, 16)

        setShort(p, 40, srcPort)
        setShort(p, 42, dstPort)
        setShort(p, 44, udpLen)

        System.arraycopy(data, 0, p, 48, data.size)

        var udpCk = calculateTransportChecksum6(srcIp, dstIp, p, 40, udpLen, 17)
        if (udpCk == 0) udpCk = 0xFFFF

        p[46] = (udpCk shr 8).toByte()
        p[47] = (udpCk and 0xFF).toByte()

        return p
    }

    /**
     * Hands a UDP datagram to the TUN, fragmenting it when it does not fit the
     * interface MTU.
     *
     * The datagram used to be DROPPED in that case (`if (payload.size >
     * maxPayload) continue`), which silently broke every response bigger than
     * the MTU — large DNS answers, QUIC/gaming traffic, anything a server sends
     * as one big datagram. Fragments carry the checksum of the whole datagram,
     * so the receiving app's kernel reassembles them transparently.
     */
    private fun emitUdp4(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray) {
        // 20 bytes of IP header + 8 of UDP header have to fit in the 16-bit
        // total-length field; a bigger datagram cannot be represented at all.
        if (payload.size > MAX_UDP4_PAYLOAD) return
        val packet = buildUdp4(srcIp, dstIp, srcPort, dstPort, payload)
        if (packet.size <= mtu) {
            enqueueTun(packet)
            return
        }
        // Fragment payloads must be multiples of 8 bytes (the offset field
        // counts 8-byte units); only the last one may be shorter.
        val maxChunk = ((mtu - 20) / 8) * 8
        if (maxChunk <= 0) return
        val body = packet.size - 20
        val id = ipIdSeq.incrementAndGet() and 0xFFFF
        var offset = 0
        while (offset < body) {
            val chunk = minOf(maxChunk, body - offset)
            val more = offset + chunk < body
            val fragment = ByteArray(20 + chunk)
            System.arraycopy(packet, 0, fragment, 0, 20)
            System.arraycopy(packet, 20 + offset, fragment, 20, chunk)
            val total = 20 + chunk
            fragment[2] = (total shr 8).toByte()
            fragment[3] = (total and 0xFF).toByte()
            fragment[4] = (id shr 8).toByte()
            fragment[5] = (id and 0xFF).toByte()
            val flagsField = (if (more) 0x2000 else 0) or (offset / 8)
            fragment[6] = (flagsField shr 8).toByte()
            fragment[7] = (flagsField and 0xFF).toByte()
            fragment[10] = 0
            fragment[11] = 0
            val ck = calculateChecksum(fragment)
            fragment[10] = (ck shr 8).toByte()
            fragment[11] = (ck and 0xFF).toByte()
            // A datagram is only usable once every fragment arrives, so there is
            // no point pushing the rest after one of them could not be queued.
            if (!enqueueTun(fragment)) return
            offset += chunk
        }
    }

    /** IPv6 counterpart of [emitUdp4]; oversized datagrams get a fragment header. */
    private fun emitUdp6(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray) {
        if (payload.size > MAX_UDP6_PAYLOAD) return
        val packet = buildUdp6(srcIp, dstIp, srcPort, dstPort, payload)
        if (packet.size <= mtu) {
            enqueueTun(packet)
            return
        }
        // 40 bytes of base header + 8 bytes of fragment header per fragment.
        val maxChunk = ((mtu - 48) / 8) * 8
        if (maxChunk <= 0) return
        val body = packet.size - 40
        val id = ipIdSeq.incrementAndGet()
        var offset = 0
        while (offset < body) {
            val chunk = minOf(maxChunk, body - offset)
            val more = offset + chunk < body
            val fragment = ByteArray(48 + chunk)
            System.arraycopy(packet, 0, fragment, 0, 40)
            val payloadLength = 8 + chunk
            fragment[4] = (payloadLength shr 8).toByte()
            fragment[5] = (payloadLength and 0xFF).toByte()
            fragment[6] = 44
            fragment[40] = 17
            fragment[41] = 0
            val offsetField = ((offset / 8) shl 3) or (if (more) 1 else 0)
            fragment[42] = (offsetField shr 8).toByte()
            fragment[43] = (offsetField and 0xFF).toByte()
            setInt(fragment, 44, id)
            System.arraycopy(packet, 40 + offset, fragment, 48, chunk)
            if (!enqueueTun(fragment)) return
            offset += chunk
        }
    }

    private fun calculateChecksum(b: ByteArray): Int {
        return foldChecksum(sumBytes(b, 0, 20, 0L))
    }

    /**
     * Transport checksum over a slice of [packet] — no copy. Building the
     * segment into a throwaway array first (`p.copyOfRange(20, total)`) doubled
     * the memory traffic of every packet the bridge produced.
     */
    private fun calculateTransportChecksum4(
        srcIp: Int,
        dstIp: Int,
        packet: ByteArray,
        offset: Int,
        length: Int,
        protocol: Int
    ): Int {
        var sum = 0L
        sum += ((srcIp ushr 16) and 0xFFFF).toLong()
        sum += (srcIp and 0xFFFF).toLong()
        sum += ((dstIp ushr 16) and 0xFFFF).toLong()
        sum += (dstIp and 0xFFFF).toLong()
        sum += protocol.toLong()
        sum += length.toLong()
        sum = sumBytes(packet, offset, offset + length, sum)
        return foldChecksum(sum)
    }

    private fun calculateTransportChecksum6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        offset: Int,
        length: Int,
        protocol: Int
    ): Int {
        var sum = 0L
        sum = sumBytes(srcIp, 0, 16, sum)
        sum = sumBytes(dstIp, 0, 16, sum)
        sum += length.toLong()
        sum += protocol.toLong()
        sum = sumBytes(packet, offset, offset + length, sum)
        return foldChecksum(sum)
    }

    /** One's-complement 16-bit sum of `b[from until to]`. */
    private fun sumBytes(b: ByteArray, from: Int, to: Int, initial: Long): Long {
        var sum = initial
        var i = from
        val last = to - 1

        while (i < last) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < to) {
            sum += (b[i].toInt() and 0xFF) shl 8
        }

        return sum
    }

    private fun foldChecksum(sum: Long): Int {
        var s = sum
        while ((s shr 16) > 0) {
            s = (s and 0xFFFF) + (s shr 16)
        }
        return (s.inv() and 0xFFFF).toInt()
    }

    private fun getInt(b: ByteArray, o: Int): Int {
        return ((b[o].toInt() and 0xFF) shl 24) or
                ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or
                (b[o + 3].toInt() and 0xFF)
    }

    private fun setInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun getShort(p: ByteArray, o: Int): Int {
        return ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
    }

    private fun setShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun getLong(p: ByteArray, o: Int): Long {
        return ((p[o].toLong() and 0xFF) shl 24) or
                ((p[o + 1].toLong() and 0xFF) shl 16) or
                ((p[o + 2].toLong() and 0xFF) shl 8) or
                (p[o + 3].toLong() and 0xFF)
    }

    private fun setLong(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun intToBytes(v: Int): ByteArray {
        return byteArrayOf(
            (v shr 24).toByte(),
            (v shr 16).toByte(),
            (v shr 8).toByte(),
            (v and 0xFF).toByte()
        )
    }

    private fun bytesToInt(b: ByteArray): Int {
        return ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
    }

    companion object {
        /**
         * The bridge that is currently reading the TUN, or null. Published so
         * the traffic meter has a byte source in per-app-blocking mode, where
         * hev-socks5-tunnel — and therefore its stats API — never runs.
         */
        @Volatile
        var active: SocksTunBridge? = null
            private set

        /** How long the TUN reader waits for the engine's SOCKS5 listener. */
        private const val CORE_WAIT_ATTEMPTS = 25
        private const val CORE_WAIT_INTERVAL_MS = 200L
        private const val CORE_PROBE_TIMEOUT_MS = 200

        /** Give up on the TUN fd after this many consecutive read errors. */
        private const val MAX_CONSECUTIVE_TUN_ERRORS = 20
        private const val TUN_ERROR_BACKOFF_MS = 50L

        /** Back-pressure window for non-critical packets (see [enqueueTun]). */
        private const val TUN_QUEUE_WAIT_MS = 250L

        /**
         * Recycle threshold for a session whose client SYN never led to a
         * connection. Above the client's first SYN RTO (~1 s) so a slow
         * upstream dial is not killed mid-flight; see [TcpSession.isStaleUnconnected].
         */
        private const val STALE_UNCONNECTED_MS = 3_000L

        /** Read timeout covering the SOCKS5 handshake phase of a dial. */
        private const val DIAL_READ_TIMEOUT_MS = 10_000

        /**
         * How long a session waits for the client's first segment when it needs
         * the TLS SNI / HTTP Host header to classify the flow. A client that has
         * just been given a SYN/ACK sends its request within one loopback round
         * trip, so this is a safety net, not a budget — and it is only ever
         * reached when domain rules are actually configured.
         */
        private const val SNIFF_WAIT_MS = 400L

        /**
         * Largest UDP payload that still fits the 16-bit length fields of an
         * IPv4 / IPv6 datagram (65535 minus the headers).
         */
        private const val MAX_UDP4_PAYLOAD = 65535 - 20 - 8
        private const val MAX_UDP6_PAYLOAD = 65535 - 8
    }
}
