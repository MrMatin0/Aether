package studio.cluvex.aether.core.probe

import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.IpInfo
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** How a probe socket is opened: directly, or through the local SOCKS5 port. */
internal typealias ProbeDialer = (host: String, port: Int, hostIsDomain: Boolean) -> Socket

/**
 * "Which address does the internet see me as?", over a chosen network path.
 *
 * The direct path bypasses the tunnel (the app package is excluded from its own
 * VPN), so it reports the operator's real address; the SOCKS path exits via the
 * connected server. Same providers, same parsing, one strategy per entry point.
 */
internal object GeoLookup {

    private const val TAG = "netprobe"

    /** Headroom over the per-connection timeout for TLS + HTTP I/O. */
    private const val RACE_HEADROOM_MS = 4_000L

    /**
     * Shared worker pool for the race. Threads are daemons and idle ones are
     * reclaimed after 30 s, so the pool costs nothing while disconnected.
     *
     * Before it existed, each call spawned one raw thread per provider (each
     * with its own stack) and threw it away - on connect, on every reconnect
     * and on every IP refresh.
     */
    private val pool = Executors.newCachedThreadPool(
        object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "geo-race-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
        },
    )

    /** Real/operator address (direct, bypasses the tunnel). */
    fun direct(timeoutMs: Int): IpInfo? = firstAnswer("direct", timeoutMs, directDialer(timeoutMs))

    /** Exit/server address, providers tried in order through the proxy. */
    fun viaSocks(socksHost: String, socksPort: Int, timeoutMs: Int): IpInfo? =
        firstAnswer("proxied", timeoutMs, socksDialer(socksHost, socksPort, timeoutMs))

    /**
     * Exit/server address, ALL providers raced through the proxy in parallel.
     *
     * The serial chain burns up to one full timeout per filtered provider, and
     * which provider is filtered or slow varies per operator and region (DPI
     * variance), so some users waited tens of seconds before the provider that
     * actually works on their network was even tried. A race always finishes as
     * fast as the FASTEST provider for the current network.
     */
    fun racedViaSocks(socksHost: String, socksPort: Int, timeoutMs: Int): IpInfo? {
        val dial = socksDialer(socksHost, socksPort, timeoutMs)
        val winner = AtomicReference<IpInfo?>(null)
        val remaining = AtomicInteger(GeoProviders.ALL.size)
        val finished = CountDownLatch(1)
        for (provider in GeoProviders.ALL) {
            pool.execute {
                try {
                    // WASTED-WORK FIX: a loser used to run all the way through
                    // its own country-refinement request before discarding the
                    // result, i.e. one extra round trip per provider per refresh.
                    if (winner.get() == null) {
                        attempt(provider, timeoutMs, "raced", dial)?.let { info ->
                            val refined = runCatching {
                                withAuthoritativeCountry(info, provider, timeoutMs, dial)
                            }.getOrDefault(info)
                            if (winner.compareAndSet(null, refined)) finished.countDown()
                        }
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) finished.countDown()
                }
            }
        }
        finished.await(timeoutMs.toLong() + RACE_HEADROOM_MS, TimeUnit.MILLISECONDS)
        return winner.get()
    }

    /**
     * Repeats [lookup] to ride out the tunnel's cold start.
     *
     * Right after connect the warp-in-warp tunnel is still building its INNER
     * tunnel, so the first request loses and the badge used to be stuck on
     * "unavailable" forever, because a single attempt was all there was. This
     * BLOCKS between attempts, so call it off the main thread.
     */
    fun retrying(attempts: Int, delayMs: Long, lookup: () -> IpInfo?): IpInfo? {
        repeat(attempts) { index ->
            lookup()?.let { return it }
            if (index < attempts - 1 && !pause(delayMs)) return null
        }
        return null
    }

    private fun firstAnswer(tag: String, timeoutMs: Int, dial: ProbeDialer): IpInfo? {
        for (provider in GeoProviders.ALL) {
            val info = attempt(provider, timeoutMs, tag, dial) ?: continue
            return withAuthoritativeCountry(info, provider, timeoutMs, dial)
        }
        return null
    }

    /** One provider attempt: dial, TLS-wrap when asked, fetch, parse. */
    private fun attempt(
        provider: GeoProvider,
        timeoutMs: Int,
        tag: String,
        dial: ProbeDialer,
    ): IpInfo? = runCatching {
        dial(provider.host, provider.port, provider.hostIsDomain).use { socket ->
            val io = if (provider.tls) {
                TlsProbe.wrap(socket, provider.host, provider.port, timeoutMs)
            } else {
                socket
            }
            val response = MiniHttp.get(io, provider.host, provider.path)
            // SILENT-SKIP FIX: an unusable answer (captive portal, error page,
            // rate limit) used to return null from a SUCCESSFUL runCatching, so
            // the provider was dropped without a single line in the log and the
            // panel showed no reason at all for a missing badge.
            IpInfoParser.parse(response.body)
                ?: throw IOException("unusable response ($response)")
        }
    }.onFailure {
        DiagnosticsLog.d(TAG, "$tag geo ${provider.host} failed: ${it.message}")
    }.getOrNull()

    /**
     * Re-asks the authority about the address we just learned, over the SAME
     * network path, so the flag always comes from one geo database. Falls back
     * to the provider's own country code when the authority is unreachable.
     */
    private fun withAuthoritativeCountry(
        info: IpInfo,
        provider: GeoProvider,
        timeoutMs: Int,
        dial: ProbeDialer,
    ): IpInfo {
        if (provider.isCountryAuthority) return info
        val country = runCatching {
            dial(GeoProviders.COUNTRY_AUTHORITY_HOST, GeoProviders.COUNTRY_AUTHORITY_PORT, true)
                .use { socket ->
                    val response = MiniHttp.get(
                        socket,
                        GeoProviders.COUNTRY_AUTHORITY_HOST,
                        GeoProviders.countryLookupPath(info.ip),
                    )
                    IpInfoParser.countryCode(response.body)
                }
        }.getOrNull()
        return if (country != null) info.copy(countryCode = country) else info
    }

    /**
     * Direct dialer, forcing IPv4.
     *
     * ROOT-CAUSE FIX (Iranian cellular): on dual-stack mobile data the default
     * connect prefers IPv6, so the badge surfaced a scoped/temporary IPv6
     * address instead of the operator's real public IPv4 (on Wi-Fi, often
     * IPv4-only, it already looked correct). Resolve and connect to the IPv4
     * address explicitly, so the endpoint always sees - and reports - that.
     */
    private fun directDialer(timeoutMs: Int): ProbeDialer = { host, port, _ ->
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(resolveIpv4(host), port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun socksDialer(socksHost: String, socksPort: Int, timeoutMs: Int): ProbeDialer =
        { host, port, hostIsDomain ->
            Socks5Probe.connect(
                socksHost = socksHost,
                socksPort = socksPort,
                destHost = host,
                destPort = port,
                useDomain = hostIsDomain,
                timeoutMs = timeoutMs,
            )
        }

    private fun resolveIpv4(host: String): InetAddress {
        val all = InetAddress.getAllByName(host)
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    /**
     * Sleeps between retries. Returns false when the wait was interrupted, and
     * re-arms the interrupt flag - the old version swallowed it, so a caller
     * whose coroutine had been cancelled could not tell why the retry stopped.
     */
    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
