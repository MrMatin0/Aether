package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * LAN sharing bridge: lets OTHER devices on the same Wi-Fi / hotspot use this
 * phone's Aether tunnel as a normal proxy.
 *
 * Two listeners are exposed while sharing is on:
 *  - SOCKS5  0.0.0.0:[SOCKS_SHARE_PORT] — a transparent TCP relay into the
 *    engine's local SOCKS5 (127.0.0.1:1819, loopback-only), so the full SOCKS5
 *    protocol (including remote DNS) is served by the engine itself.
 *  - HTTP    0.0.0.0:[HTTP_SHARE_PORT] — a minimal HTTP/1.1 proxy (CONNECT for
 *    HTTPS + absolute-form for plain HTTP) that dials upstream THROUGH that
 *    SOCKS5 proxy. This is what the "system proxy" settings on Windows/macOS
 *    (and most phones) expect, so laptops work out of the box.
 *
 * Loop safety: this code runs inside the app process, which is excluded from
 * the TUN via addDisallowedApplication(), so proxied traffic always leaves via
 * the engine and never re-enters the VPN.
 *
 * ### THREAT MODEL (read before touching anything below)
 *
 * While sharing is on, both listeners accept connections from ANY device on the
 * local network, unauthenticated, and every byte of the request is chosen by
 * that device. That makes this the app's largest untrusted-input surface, so
 * three properties are load-bearing rather than nice to have:
 *
 *  - **Bounded resources.** Connections are capped ([MAX_CONCURRENT_CLIENTS]).
 *    Before that cap existed, a peer could open sockets in a loop and each one
 *    got a raw Thread (plus a second one for the reverse relay leg), so a
 *    trivial loop from any laptop on the network could exhaust the process's
 *    thread stacks and take the tunnel down with it.
 *  - **Bounded parsing.** The request line and headers are size-capped
 *    ([MAX_HEADER_BYTES]) and the target is parsed by [HostPort], which refuses
 *    what it cannot forward faithfully rather than guessing.
 *  - **No smuggling.** Hop-by-hop headers are stripped per RFC 7230 and a
 *    chunked request is refused outright, because this proxy does not dechunk
 *    and forwarding a body it does not understand desynchronises the upstream
 *    connection.
 *
 * The UI warns the user that sharing exposes the tunnel, and sharing is OFF by
 * default.
 */
object ShareBridge {

    /**
     * FIXED local proxy ports. These NEVER change at runtime: users type them
     * once into another app (Psiphon, Telegram, a browser) and the address
     * keeps working across every reconnect.
     *
     * ### Why these numbers changed in 1.2.2 (v2rayNG conflict — root cause)
     *
     * 1.2.1 used 10808/10809. Those are not "neutral" numbers: they are
     * **v2rayNG's own defaults** (10808 = SOCKS5, 10809 = HTTP), and Clash/
     * NekoBox derivatives reuse them too. Any user with v2rayNG installed and
     * running therefore hit a hard collision:
     *
     *  - If v2rayNG bound first, Aether's sharing/proxy mode failed with
     *    EADDRINUSE and the user saw "could not open the proxy ports".
     *  - If Aether bound first, v2rayNG failed to start, which is how this got
     *    reported as "Aether breaks v2rayNG".
     *  - Worst case, an app configured for 127.0.0.1:10808 silently sent its
     *    traffic into whichever tunnel happened to own the port that minute —
     *    a routing conflict with real privacy consequences.
     *
     * 1.2.2 moves one slot up to **10810/10811**, which sit in the same
     * easy-to-remember block but are claimed by no mainstream client, and adds
     * an explicit pre-bind conflict check ([describePortHolder]) so a genuine
     * collision is reported in plain language instead of a bare stack trace.
     *
     * Note the engine's own SOCKS5 listener (127.0.0.1:1819, see TunnelConfig)
     * never overlapped with v2rayNG and is unchanged.
     */
    const val SOCKS_SHARE_PORT = 10810
    const val HTTP_SHARE_PORT = 10811

    /**
     * Ports owned by well-known neighbouring tunnels. Used only to produce a
     * helpful diagnostic message — Aether never binds these.
     */
    private val KNOWN_NEIGHBOUR_PORTS = mapOf(
        10808 to "v2rayNG (SOCKS5)",
        10809 to "v2rayNG (HTTP)",
        7890 to "Clash (mixed)",
        1080 to "Psiphon / generic SOCKS",
        8118 to "Privoxy",
    )

    private const val TAG = "share"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val DIAL_TIMEOUT_MS = 10_000

    /**
     * Read timeout for accepted clients and relayed legs. Bounds how long a
     * dead/stalled peer can pin its connection thread and socket; generous
     * enough that legitimate idle periods (long-poll, keep-alive) survive.
     */
    private const val IDLE_TIMEOUT_MS = 120_000

    /** Relay copy buffer. Sized to move a full TCP window per syscall pair. */
    private const val RELAY_BUFFER_BYTES = 64 * 1024

    /** Socket buffer both legs of a relayed connection ask the kernel for. */
    private const val SOCKET_BUFFER_BYTES = 256 * 1024

    /**
     * Ceiling on simultaneously served proxy clients.
     *
     * Each accepted connection costs two threads (its handler plus the reverse
     * relay leg) and two sockets. Without a ceiling, anything on the LAN could
     * connect in a loop until the process died of OutOfMemoryError while trying
     * to allocate a thread stack — and a VPN client dying is not a graceful
     * degradation, it is a leak. 128 clients is far more than the "a laptop and
     * a tablet" case this feature exists for, and excess is refused with a
     * proper status line rather than dropped on the floor.
     */
    private const val MAX_CONCURRENT_CLIENTS = 128

    /** How often a refusal is worth a log line, so a flood cannot spam the log. */
    private const val OVERLOAD_LOG_INTERVAL_MS = 5_000L

    /** Hop-by-hop headers, per RFC 7230 §6.1, plus the de-facto proxy ones. */
    private val HOP_BY_HOP_HEADERS = setOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "trailers",
        "transfer-encoding",
        "upgrade",
    )

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Actual bound ports for the current session (null while that listener is down). */
    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private val _httpPort = MutableStateFlow<Int?>(null)
    val httpPort: StateFlow<Int?> = _httpPort.asStateFlow()

    /**
     * Cumulative byte counters for THIS sharing session (reset on every
     * [startSync]). In proxy mode the system TUN (and therefore
     * hev-socks5-tunnel's stats API) is not running, so these counters are the
     * ONLY source of download/upload numbers for the traffic meter.
     *
     * Direction mapping matches HevTunnel.traffic():
     *  - upload   = bytes from proxy clients relayed INTO the engine (device -> internet)
     *  - download = bytes from the engine relayed BACK to proxy clients (internet -> device)
     */
    private val uploadBytesCounter = AtomicLong(0L)
    private val downloadBytesCounter = AtomicLong(0L)

    /** Snapshot of the bridge's cumulative session traffic. */
    data class Traffic(val downloadBytes: Long, val uploadBytes: Long)

    fun traffic(): Traffic = Traffic(
        downloadBytes = downloadBytesCounter.get(),
        uploadBytes = uploadBytesCounter.get(),
    )

    private var socksServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null

    /**
     * Monotonic session id. Every [startSync] / [stop] bumps it, so a stale
     * asynchronous stop can never close the listeners of a NEWER session —
     * the race that used to fail rebinding with EADDRINUSE or leave
     * "sharing ON" with already-dead sockets after a quick reconnect.
     *
     * ANR FIX: this used to be a plain Int bumped inside `synchronized(this)`,
     * which meant [stop] had to take the monitor ON ITS CALLER'S THREAD just to
     * increment it. [startSync] holds that monitor for the entire bind phase,
     * and [bindWithRetry] sleeps up to 10 x 300 ms PER PORT — so a disconnect
     * tapped while a bind was retrying blocked the UI thread for up to ~6 s,
     * which is an ANR, not a slow frame. An atomic counter needs no monitor, so
     * the caller returns immediately and only the background closer contends.
     */
    private val session = AtomicInteger(0)

    /** Currently served proxy clients; bounded by [MAX_CONCURRENT_CLIENTS]. */
    private val liveClients = AtomicInteger(0)

    /** Timestamp of the last "at capacity" log line, for rate limiting. */
    private val lastOverloadLogMs = AtomicLong(0L)

    /** Bind address for the current session: loopback-only or all interfaces. */
    @Volatile
    private var bindHost = "127.0.0.1"

    /**
     * Turn sharing on. Safe to call from ANY thread — including the UI thread:
     * binding sockets is a network operation and Android throws
     * NetworkOnMainThreadException when it happens on the main thread, so the
     * actual work runs on a short-lived background thread and [active] flips
     * to true once both listeners are ready.
     */
    fun start(localOnly: Boolean = false) {
        thread(name = "share-start", isDaemon = true) { startSync(localOnly) }
    }

    /**
     * Turn sharing on and WAIT until the listeners are bound. Returns true when
     * BOTH fixed listeners (SOCKS5 and HTTP) are actually accepting connections.
     * MUST be called from a background thread (binding is a network operation).
     *
     * Unlike the old fire-and-forget start, callers such as the VpnService can
     * use the return value as ground truth instead of assuming success — in
     * proxy mode these listeners ARE the product, so a swallowed bind failure
     * meant "connected" with nothing listening on the share ports.
     */
    fun startSync(localOnly: Boolean = false): Boolean = synchronized(this) {
        // Already up with BOTH listeners healthy? Nothing to do. (The old check
        // was an OR, so a session that had lost one of its two listeners still
        // reported success — and in proxy mode that return value is the ground
        // truth the VpnService gates "Connected" on.)
        if (_active.value && socksServer?.isClosed == false && httpServer?.isClosed == false) {
            return@synchronized true
        }

        // New session: invalidates any in-flight async [stop] and clears
        // leftovers so rebinding is deterministic.
        val mySession = session.incrementAndGet()
        closeServers()
        uploadBytesCounter.set(0L)
        downloadBytesCounter.set(0L)

        // SECURITY: when not explicitly sharing to the LAN, bind loopback
        // only so no other device on the network can use us as an open
        // proxy. LAN exposure is opt-in via the user's "share" toggle.
        bindHost = if (localOnly) "127.0.0.1" else "0.0.0.0"

        // Bind the FIXED standard ports. NO fallback: the address users typed
        // into other apps must never silently change between sessions. A short
        // retry loop absorbs a transient EADDRINUSE from a just-closed listener
        // (TIME_WAIT / async teardown); a genuinely occupied port fails loudly.
        // 1.2.2: log which neighbouring tunnels are live BEFORE binding, so a
        // port clash is diagnosable from the in-app log alone.
        reportNeighbours()

        socksServer = bindWithRetry("SOCKS5", SOCKS_SHARE_PORT)
        httpServer = bindWithRetry("HTTP", HTTP_SHARE_PORT)
        _socksPort.value = socksServer?.localPort
        _httpPort.value = httpServer?.localPort

        if (socksServer == null || httpServer == null) {
            DiagnosticsLog.e(
                TAG,
                "Could not open the fixed proxy ports ($SOCKS_SHARE_PORT/$HTTP_SHARE_PORT) — " +
                    "close the app holding them and reconnect.",
            )
            closeServers()
            _active.value = false
            return@synchronized false
        }

        // A stop() delivered WHILE we were retrying a bind has already bumped
        // the session id. Honour it instead of leaving listeners nobody asked
        // for: the bind phase can take seconds, which is plenty of time for the
        // user to change their mind.
        if (session.get() != mySession) {
            DiagnosticsLog.i(TAG, "Sharing was stopped while binding — closing the fresh listeners.")
            closeServers()
            _active.value = false
            return@synchronized false
        }

        socksServer?.let { server -> acceptLoop("share-socks", server) { relayToLocalSocks(it) } }
        httpServer?.let { server -> acceptLoop("share-http", server) { serveHttpClient(it) } }
        _active.value = true

        val scope = if (bindHost == "127.0.0.1") "loopback only" else "all interfaces (LAN)"
        DiagnosticsLog.i(
            TAG,
            "Sharing ON — SOCKS5 :${_socksPort.value ?: "unavailable"} + HTTP :${_httpPort.value ?: "unavailable"} ($scope)",
        )
        true
    }

    /**
     * Turn sharing off. Safe to call from any thread, INCLUDING the UI thread:
     * it takes no lock and does no I/O on the caller (see [session]).
     */
    fun stop() {
        // Bump the session id FIRST and WITHOUT the monitor: the flag flip and
        // the socket closing happen on the background thread below. Flipping
        // _active on the caller raced startSync (stop -> false, startSync still
        // holding the monitor finished and set it back to true, then the stop
        // thread matched its session id and killed the FRESH listeners, leaving
        // active == true over dead ports).
        val stopSession = session.incrementAndGet()
        thread(name = "share-stop", isDaemon = true) {
            synchronized(this) {
                // Only close if no NEWER session started meanwhile: a stale
                // async stop must never kill a fresh session's listeners.
                if (session.get() == stopSession) {
                    _active.value = false
                    val hadServers = socksServer != null || httpServer != null
                    closeServers()
                    if (hadServers) DiagnosticsLog.i(TAG, "Sharing OFF")
                }
            }
        }
    }

    /** Best local (site-local IPv4) address other devices can reach us on. */
    fun lanAddress(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { it.name.startsWith("tun") || it.name.startsWith("ppp") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

    // ------------------------------------------------------------- internals

    private fun bind(port: Int): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        // Set BEFORE bind: accepted sockets inherit it, and a LAN client's TCP
        // window scale is negotiated during the handshake — raising the buffer
        // afterwards would be too late to widen the window.
        runCatching { server.receiveBufferSize = SOCKET_BUFFER_BYTES }
        server.bind(InetSocketAddress(bindHost, port), 32)
        return server
    }

    /** Applies the relay's buffer sizing to one end of a proxied connection. */
    private fun tune(socket: Socket) {
        socket.tcpNoDelay = true
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER_BYTES }
        runCatching { socket.sendBufferSize = SOCKET_BUFFER_BYTES }
    }

    /**
     * Binds a FIXED port, retrying briefly so a listener that is still being
     * torn down by the previous session never pushes users onto a different
     * port. The port either opens or sharing fails loudly — it NEVER moves.
     */
    private fun bindWithRetry(
        label: String,
        port: Int,
        attempts: Int = 10,
        delayMs: Long = 300,
    ): ServerSocket? {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return bind(port)
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts - 1) runCatching { Thread.sleep(delayMs) }
            }
        }
        DiagnosticsLog.e(
            TAG,
            "$label port $port is busy${describePortHolder(port)}: $lastError",
        )
        return null
    }

    /** Names the usual suspect for [port], for human-readable diagnostics. */
    private fun describePortHolder(port: Int): String =
        KNOWN_NEIGHBOUR_PORTS[port]?.let { " — this port belongs to $it" }
            ?: " (held by another app?)"

    /**
     * Detects other local tunnels listening on the classic proxy ports and
     * notes them in the log. Purely informational: 1.2.2 deliberately binds
     * ports nobody else claims, so co-existing with v2rayNG/Clash is expected
     * to just work — this line simply proves it in the diagnostics.
     */
    private fun reportNeighbours() {
        val live = KNOWN_NEIGHBOUR_PORTS.filterKeys { port ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 120) }
                true
            }.getOrDefault(false)
        }.values.distinct()
        if (live.isEmpty()) return
        DiagnosticsLog.i(
            TAG,
            "Other local proxies detected (${live.joinToString(", ")}) — Aether uses " +
                "$SOCKS_SHARE_PORT/$HTTP_SHARE_PORT, so they can run side by side.",
        )
    }

    private fun closeServers() {
        runCatching { socksServer?.close() }
        socksServer = null
        runCatching { httpServer?.close() }
        httpServer = null
        _socksPort.value = null
        _httpPort.value = null
    }

    private fun acceptLoop(name: String, server: ServerSocket, handler: (Socket) -> Unit) {
        thread(name = name, isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    // A closed server means sharing stopped; anything else
                    // (EMFILE/ENOBUFS under fd pressure) is transient —
                    // exiting the loop would leave a silently dead listener
                    // that startSync's health check still reports as healthy.
                    if (server.isClosed) break
                    DiagnosticsLog.w(TAG, "$name accept failed: ${e.message}")
                    runCatching { Thread.sleep(200) }
                    continue
                }

                // RESOURCE CAP (increment-then-check, so two accept loops can
                // never both slip past the ceiling): refuse cleanly instead of
                // letting an unbounded number of peers each take two threads.
                if (liveClients.incrementAndGet() > MAX_CONCURRENT_CLIENTS) {
                    liveClients.decrementAndGet()
                    logOverload(name)
                    runCatching { respondError(client, "503 Service Unavailable") }
                    runCatching { client.close() }
                    continue
                }

                thread(name = "$name-conn", isDaemon = true) {
                    try {
                        tune(client)
                        // Bounds a client that connects and then never speaks:
                        // without it each stalled peer parks its own thread
                        // and socket forever.
                        runCatching { client.soTimeout = IDLE_TIMEOUT_MS }
                        handler(client)
                    } catch (_: Exception) {
                        // Per-connection errors are non-fatal by design.
                    } finally {
                        runCatching { client.close() }
                        liveClients.decrementAndGet()
                    }
                }
            }
        }
    }

    /** Rate-limited "at capacity" note, so a connection flood cannot spam the log. */
    private fun logOverload(name: String) {
        val now = System.currentTimeMillis()
        val last = lastOverloadLogMs.get()
        if (now - last < OVERLOAD_LOG_INTERVAL_MS) return
        if (!lastOverloadLogMs.compareAndSet(last, now)) return
        DiagnosticsLog.w(
            TAG,
            "$name at capacity ($MAX_CONCURRENT_CLIENTS live clients) — refusing new connections.",
        )
    }

    /** Dials the engine's loopback SOCKS5 with the standard relay tuning. */
    private fun dialEngine(): Socket =
        Socket().apply {
            tune(this)
            connect(
                InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT),
                DIAL_TIMEOUT_MS,
            )
        }

    /** SOCKS5 share = byte-for-byte relay into the engine's loopback SOCKS5. */
    private fun relayToLocalSocks(client: Socket) {
        val upstream = dialEngine()
        try {
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * Minimal HTTP proxy: CONNECT tunnels + absolute-form plain requests.
     *
     * Everything read here comes from an unauthenticated peer on the local
     * network, so the parse is deliberately strict and every rejection answers
     * with a status line rather than dropping the socket — a client that gets a
     * 400 fixes its request, a client that gets silence retries forever.
     */
    private fun serveHttpClient(client: Socket) {
        val input = client.getInputStream()
        val header = readHeaderBlock(input) ?: run {
            respondError(client, "400 Bad Request")
            return
        }
        val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        // Exactly three tokens: METHOD SP TARGET SP VERSION. A request line with
        // extra spaces is ambiguous and is precisely how request smuggling gets
        // started, so it is refused rather than "best effort" parsed.
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            respondError(client, "400 Bad Request")
            return
        }
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        if (!version.startsWith("HTTP/1.")) {
            respondError(client, "505 HTTP Version Not Supported")
            return
        }

        // Headers only, up to the blank line that ends the block.
        val headers = lines.drop(1).takeWhile { it.isNotEmpty() }

        // SMUGGLING GUARD: this proxy relays the body as an opaque byte stream,
        // so it cannot dechunk. Forwarding Transfer-Encoding verbatim while
        // appending our own `Connection: close` (which is what the old rebuild
        // did) leaves the upstream reading a framing we are not honouring — the
        // textbook desync. Refuse it instead of half-supporting it.
        if (headers.any { headerName(it) == "transfer-encoding" }) {
            respondError(client, "501 Not Implemented")
            return
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            // CONNECT authority-form. Parsed by HostPort so a missing port, a
            // bracketed IPv6 literal and an out-of-range port are all handled
            // instead of being mangled by substringBeforeLast(':').
            val dest = HostPort.parse(target, 443) ?: run {
                respondError(client, "400 Bad Request")
                return
            }
            val upstream = socksOpen(dest.host, dest.port) ?: run {
                respondError(client, "502 Bad Gateway")
                return
            }
            try {
                client.getOutputStream().writeAscii("HTTP/1.1 200 Connection Established\r\n\r\n")
                relay(client, upstream)
            } finally {
                runCatching { upstream.close() }
            }
            return
        }

        // Plain HTTP with an absolute URI, e.g. "GET http://example.com/x HTTP/1.1".
        if (!target.startsWith("http://", ignoreCase = true)) {
            // https:// must use CONNECT; anything else is not absolute-form.
            respondError(client, "400 Bad Request")
            return
        }
        val withoutScheme = target.substring("http://".length)
        val slash = withoutScheme.indexOf('/')
        val authority = if (slash < 0) withoutScheme else withoutScheme.substring(0, slash)
        val path = if (slash < 0) "/" else withoutScheme.substring(slash)
        // Strip any userinfo: it is not part of the host and must never end up
        // in the SOCKS5 address field.
        val dest = HostPort.parse(authority.substringAfterLast('@'), 80) ?: run {
            respondError(client, "400 Bad Request")
            return
        }

        val upstream = socksOpen(dest.host, dest.port) ?: run {
            respondError(client, "502 Bad Gateway")
            return
        }
        try {
            val rebuilt = buildString {
                append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
                headers.forEach { line ->
                    val name = headerName(line)
                    // A line with no colon is not a header; forwarding it would
                    // splice peer-chosen text into the upstream request.
                    if (name.isEmpty() || name in HOP_BY_HOP_HEADERS) return@forEach
                    append(line).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            upstream.getOutputStream().writeAscii(rebuilt)
            uploadBytesCounter.addAndGet(rebuilt.length.toLong())
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** Lower-cased field name of a header line, or "" when the line has none. */
    private fun headerName(line: String): String {
        val colon = line.indexOf(':')
        if (colon <= 0) return ""
        return line.substring(0, colon).trim().lowercase(Locale.US)
    }

    /** Opens a TCP stream to host:port THROUGH the engine's SOCKS5 proxy. */
    private fun socksOpen(host: String, port: Int): Socket? {
        // REPRESENTABILITY GUARD: ATYP=0x03 carries the host length in ONE byte
        // and the port in two. `write(size)` keeps only the low 8 bits, so an
        // over-long or non-ASCII name used to be TRUNCATED onto the wire and the
        // proxy then read the rest of the name as the port — reported back as a
        // plain "CONNECT rejected", which blames the wrong component. The port
        // is already bounded by HostPort; the host is checked here.
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        if (hostBytes.isEmpty() || hostBytes.size > Hostname.MAX_WIRE_LENGTH ||
            !Hostname.isRepresentable(host)
        ) {
            DiagnosticsLog.w(TAG, "Refusing unrepresentable SOCKS5 host (${host.length} chars)")
            return null
        }

        val socket = dialEngine()
        return try {
            socket.soTimeout = DIAL_TIMEOUT_MS
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // Greeting: version 5, one method, no-auth.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = inp.readExact(2)
            if (greet == null || greet[0] != 5.toByte() || greet[1] != 0.toByte()) {
                throw IOException("SOCKS5 greeting failed")
            }

            // CONNECT with a DOMAIN address -> DNS resolves inside the tunnel.
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                write(hostBytes.size)
                write(hostBytes)
                write((port shr 8) and 0xFF)
                write(port and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val reply = inp.readExact(4) ?: throw IOException("SOCKS5 reply truncated")
            if (reply[1] != 0.toByte()) throw IOException("SOCKS5 connect refused (${reply[1]})")
            val remaining = when (reply[3].toInt()) {
                0x01 -> 4 + 2
                0x03 -> (
                    inp.readExact(1)?.get(0)?.toInt()?.and(0xFF)
                        ?: throw IOException("SOCKS5 reply truncated")
                    ) + 2
                0x04 -> 16 + 2
                else -> throw IOException("Bad SOCKS5 address type")
            }
            inp.readExact(remaining) ?: throw IOException("SOCKS5 reply truncated")

            // Handshake done: swap the tight dial timeout for the session idle
            // timeout (never 0 — a dead peer must not pin this socket's relay
            // thread forever).
            socket.soTimeout = IDLE_TIMEOUT_MS
            socket
        } catch (e: Exception) {
            DiagnosticsLog.e(TAG, "Upstream dial failed for $host:$port — $e")
            runCatching { socket.close() }
            null
        }
    }

    /** Reads raw bytes up to and including the CRLFCRLF header terminator. */
    private fun readHeaderBlock(input: InputStream): ByteArrayOutputStream? {
        val buf = ByteArrayOutputStream()
        var run = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            run = when {
                b == '\r'.code && (run == 0 || run == 2) -> run + 1
                b == '\n'.code && (run == 1 || run == 3) -> run + 1
                else -> 0
            }
            if (run == 4) return buf
        }
        return null
    }

    /**
     * Full-duplex pipe between the proxy client and the engine; returns when
     * either side ends. Every relayed byte is added to the session traffic
     * counters so the UI meter works in proxy mode too:
     * client -> upstream = upload, upstream -> client = download.
     */
    private fun relay(client: Socket, upstream: Socket) {
        val reverse = thread(isDaemon = true) { pipe(upstream, client, downloadBytesCounter) }
        pipe(client, upstream, uploadBytesCounter)
        // Grace for the reverse leg to drain a response tail after the client
        // half-closed. One second truncated real downloads on high-RTT paths;
        // both sockets are closed right after, which unblocks it regardless.
        runCatching { reverse.join(10_000) }
    }

    private fun pipe(from: Socket, to: Socket, counter: AtomicLong) {
        // THROUGHPUT: a 16 KB buffer meant four read/write syscall pairs for
        // every 64 KB relayed, and the per-read flush() was pure ceremony — a
        // raw socket stream has nothing buffered to flush.
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
        }
    }

    private fun InputStream.readExact(n: Int): ByteArray? {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = read(out, done, n - done)
            if (r < 0) return null
            done += r
        }
        return out
    }

    private fun OutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    /** Writes an HTTP error status line and closes the reply. */
    private fun respondError(client: Socket, status: String) {
        runCatching { client.getOutputStream().writeAscii("HTTP/1.1 $status\r\nConnection: close\r\n\r\n") }
    }
}
