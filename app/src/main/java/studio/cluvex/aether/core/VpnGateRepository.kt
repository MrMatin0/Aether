package studio.cluvex.aether.core

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.probe.MiniHttp
import studio.cluvex.aether.core.probe.Socks5Probe
import studio.cluvex.aether.model.ProbeState
import studio.cluvex.aether.model.SstpProbe
import studio.cluvex.aether.model.VpnGateServer
import studio.cluvex.aether.model.isConnected

/**
 * Fetches the public SSTP relay list and tests relays for SSTP.
 *
 * ### Source
 *
 * `GET https://raw.githubusercontent.com/Delta-Kronecker/Vpn-Gate/refs/heads/main/sstp_hosts.txt`
 * is a plain text file, one VPN Gate relay per line, kept up to date by its
 * maintainer:
 *
 * ```
 * public-vpn-100.opengw.net
 * vpn110639311.opengw.net:443
 * vpn113368656.opengw.net:1956
 * ```
 *
 * `host[:port]`, nothing else: the port is 443 when it is missing, and a bare
 * name with no domain gets SoftEther's `.opengw.net`. Every relay on it has
 * already been seen answering SSTP, which the old vpngate.net CSV could not
 * say. Whether one still does right now is only known after [probe].
 *
 * ### Country
 *
 * The list carries no metadata, and the only thing the picker still needs is
 * the relay's country. Each host is resolved to its IPv4 address and the
 * addresses are geolocated in batches of [GEO_BATCH_SIZE] through ip-api.com's
 * batch endpoint (the same database the IP badge treats as its country
 * authority, see GeoProviders). A relay whose country cannot be found is kept,
 * with a blank country: an unknown flag is better than a missing relay. If a
 * lookup fails, the country the previous list knew for that host is kept.
 *
 * ### Which path the list is fetched over
 *
 * GitHub's raw host is filtered in several countries, so a direct fetch is
 * exactly what fails for the people who need the relays most. While a session
 * is connected the list is fetched THROUGH it: via the chain's local SOCKS5
 * entry (the app's own package is kept out of a SOCKS session's TUN, so a plain
 * socket would leave directly), with the host name resolved remotely; or, on
 * an SSTP session, over a plain socket, because that TUN carries the app's own
 * traffic ([ChainRuntime.appTrafficInTun]). A failed tunnel fetch falls back
 * to a direct one. Disconnected, the fetch is direct. The country lookup takes
 * the same path as the list it belongs to.
 *
 * ### Caching
 *
 * The last good list (with its countries) is kept in memory and on disk, and
 * [fetchServers] serves it; the network is only asked again when the caller
 * passes `forceRefresh` (the refresh button), or when there is no cache yet.
 *
 * ### Threading
 *
 * Every public suspend function runs on [Dispatchers.IO].
 */
object VpnGateRepository {

    private const val TAG = "VpnGate"
    private const val LIST_URL =
        "https://raw.githubusercontent.com/Delta-Kronecker/Vpn-Gate/refs/heads/main/sstp_hosts.txt"
    private const val SSTP_DOMAIN = "opengw.net"
    private const val DEFAULT_SSTP_PORT = 443

    /** ip-api.com batch: POST a JSON array of up to 100 IPs, plain HTTP only. */
    private const val GEO_HOST = "ip-api.com"
    private const val GEO_PORT = 80
    private const val GEO_BATCH_PATH = "/batch?fields=status,query,country,countryCode"
    private const val GEO_BATCH_SIZE = 100

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val PROBE_TIMEOUT_MS = 5_000

    /** Parallel DNS lookups while resolving the list, and the ceiling on all of them. */
    private const val RESOLVE_PARALLELISM = 16
    private const val RESOLVE_BUDGET_MS = 15_000L

    /** The real list is a few KB; anything this big is not the list. */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /** The on-disk copy of the last good list, in the app's files dir. */
    private const val CACHE_FILE = "sstp_relays.txt"

    /** The vpngate.net CSV cache of older builds. Unreadable now, so it is removed. */
    private const val LEGACY_CACHE_FILE = "vpngate_relays.csv"

    private val HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val COUNTRY_CODE = Regex("^[A-Za-z]{2}$")

    /** One object of the ip-api batch answer (flat, so no nested braces). */
    private val GEO_OBJECT = Regex("\\{[^{}]*\\}")
    private val GEO_STATUS = Regex("\"status\"\\s*:\\s*\"([^\"]*)\"")
    private val GEO_QUERY = Regex("\"query\"\\s*:\\s*\"([^\"]*)\"")
    private val GEO_COUNTRY_CODE = Regex("\"countryCode\"\\s*:\\s*\"([^\"]*)\"")
    private val GEO_COUNTRY_NAME = Regex("\"country\"\\s*:\\s*\"([^\"]*)\"")

    /** One downloaded list, and when it was fetched (wall clock). */
    internal class Snapshot(val servers: List<VpnGateServer>, val fetchedAtMs: Long)

    /** A country as ip-api names it: ISO code (upper case) and English name. */
    internal data class GeoCountry(val code: String, val name: String)

    /** The last good list of this process. */
    @Volatile
    private var memory: Snapshot? = null

    /**
     * The list already in memory, or null. No I/O, so a screen can draw it on
     * its very first frame instead of flashing a skeleton.
     */
    fun cachedServers(): List<VpnGateServer>? = memory?.servers

    /**
     * The relay list: the cached one (memory, then the disk copy under
     * [context]'s files dir) unless [forceRefresh], otherwise - or when nothing
     * is cached yet - a fresh download, which then replaces the cache.
     *
     * A failure (network, HTTP status, or a body with no usable hosts, e.g. a
     * captive portal page) is returned as [Result.failure] so the UI can offer
     * a retry; the cache is left untouched by it. A failed COUNTRY lookup is
     * not a failure: the relays are still returned, without a country.
     */
    suspend fun fetchServers(
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): Result<List<VpnGateServer>> = withContext(Dispatchers.IO) {
        val filesDir = context?.applicationContext?.filesDir
        val cacheFile = filesDir?.let { File(it, CACHE_FILE) }
        filesDir?.let { File(it, LEGACY_CACHE_FILE).delete() }
        if (!forceRefresh) {
            val cached = memory ?: cacheFile?.let { readCache(it) }?.also { memory = it }
            if (cached != null) return@withContext Result.success(cached.servers)
        }
        try {
            val servers = download()
            memory = Snapshot(servers, System.currentTimeMillis())
            if (cacheFile != null) writeCache(cacheFile, formatCache(servers))
            Result.success(servers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLog.w(TAG, "SSTP relay list failed: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /** One way out: an HTTP proxy for the list, and a raw dialer for the geo batch. */
    private class Route(val label: String, val proxy: Proxy, val dial: (String, Int) -> Socket)

    /** The paths to try, best first (see the class doc). */
    private fun routes(): List<Route> {
        val direct = Route("directly", Proxy.NO_PROXY, ::directSocket)
        if (!AetherController.state.value.isConnected) return listOf(direct)
        if (ChainRuntime.appTrafficInTun) {
            return listOf(Route("through the SSTP tunnel", Proxy.NO_PROXY, ::directSocket))
        }
        val host = TunnelConfig.SOCKS_HOST
        val port = TunnelConfig.SOCKS_PORT
        val socks = Route(
            "through the tunnel (SOCKS5 $host:$port)",
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)),
        ) { destHost, destPort ->
            Socks5Probe.connect(
                socksHost = host,
                socksPort = port,
                destHost = destHost,
                destPort = destPort,
                useDomain = true,
                timeoutMs = CONNECT_TIMEOUT_MS,
            ).apply { soTimeout = READ_TIMEOUT_MS }
        }
        return listOf(socks, direct)
    }

    /** The relays, with their countries, from the first route that works. */
    private fun download(): List<VpnGateServer> {
        var firstFailure: Exception? = null
        for (route in routes()) {
            try {
                val body = downloadVia(route.proxy)
                val hosts = parseHostList(body.lineSequence())
                if (hosts.isEmpty()) throw IOException("the SSTP relay list has no usable hosts")
                val servers = withCountries(hosts, route)
                val located = servers.count { it.countryCode.isNotEmpty() }
                DiagnosticsLog.i(
                    TAG,
                    "SSTP relay list: ${servers.size} relays, $located located, fetched ${route.label}",
                )
                return servers
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticsLog.w(
                    TAG,
                    "SSTP relay list ${route.label} failed: ${e.message ?: e.javaClass.simpleName}",
                )
                if (firstFailure == null) firstFailure = e
            }
        }
        throw firstFailure ?: IOException("the SSTP relay list is unreachable")
    }

    /**
     * One GET of the list over [proxy]. With a SOCKS proxy the platform's HTTP
     * stack hands the proxy the host NAME, so DNS is resolved by the tunnel
     * too, not by a resolver that may be poisoned for GitHub.
     */
    private fun downloadVia(proxy: Proxy): String {
        val connection = URI(LIST_URL).toURL().openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Aether+/1.0")
        connection.setRequestProperty("Accept", "text/plain, */*")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("the relay list answered HTTP $code")
            return connection.inputStream.use { readBounded(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BODY_BYTES) throw IOException("the relay list is oversized")
        }
        return out.toString("UTF-8")
    }

    // ------------------------------------------------------------ countries

    /**
     * [hosts] with their IPv4 address and country filled in where they could
     * be found. Never throws for a lookup problem: a relay without a country
     * is still a usable relay.
     */
    private fun withCountries(hosts: List<VpnGateServer>, route: Route): List<VpnGateServer> {
        val ips = resolveAll(hosts.map { it.sstpHostname })
        val countries = try {
            lookupCountries(ips.values, route.dial)
        } catch (e: Exception) {
            DiagnosticsLog.w(TAG, "Relay country lookup failed: ${e.message ?: e.javaClass.simpleName}")
            emptyMap()
        }
        val previous = memory?.servers?.associateBy { it.id }.orEmpty()
        return hosts.map { server ->
            val ip = ips[server.sstpHostname].orEmpty()
            val country = countries[ip]
            val known = previous[server.id]
            when {
                country != null -> server.copy(ip = ip, countryCode = country.code, countryName = country.name)
                known != null && known.countryCode.isNotEmpty() -> server.copy(
                    ip = ip.ifEmpty { known.ip },
                    countryCode = known.countryCode,
                    countryName = known.countryName,
                )
                else -> server.copy(ip = ip)
            }
        }
    }

    /**
     * host -> IPv4 for every host that resolved within [RESOLVE_BUDGET_MS]
     * (all of them together, not each). Lookups run in parallel because the
     * list is a hundred-odd names and a serial pass would take minutes on a
     * slow resolver.
     */
    private fun resolveAll(hosts: List<String>): Map<String, String> {
        val distinct = hosts.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(RESOLVE_PARALLELISM, distinct.size), resolverThreads)
        try {
            val futures = distinct.associateWith { host -> pool.submit(Callable { resolveIpv4(host) }) }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESOLVE_BUDGET_MS)
            val out = HashMap<String, String>(distinct.size)
            for ((host, future) in futures) {
                val left = deadline - System.nanoTime()
                val ip = if (left <= 0L) {
                    null
                } else {
                    try {
                        future.get(left, TimeUnit.NANOSECONDS)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (ip != null) out[host] = ip
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private val resolverThreads = object : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "sstp-resolve-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private fun resolveIpv4(host: String): String? {
        if (IPV4.matches(host)) return host
        return try {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * IPv4 -> country for [ips], one ip-api batch request per
     * [GEO_BATCH_SIZE] addresses (well inside its 15 requests a minute). A
     * failed batch only loses the countries of that batch.
     *
     * A raw socket + [MiniHttp] rather than HttpURLConnection: ip-api's free
     * tier is plain HTTP only, which the platform client refuses under the
     * app's network security config, and the probe layer already talks to the
     * very same host this way.
     */
    private fun lookupCountries(ips: Collection<String>, dial: (String, Int) -> Socket): Map<String, GeoCountry> {
        val out = HashMap<String, GeoCountry>()
        var lastFailure: Exception? = null
        for (batch in ips.distinct().chunked(GEO_BATCH_SIZE)) {
            try {
                val payload = batch.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
                val response = dial(GEO_HOST, GEO_PORT).use { socket ->
                    MiniHttp.post(socket, GEO_HOST, GEO_BATCH_PATH, "application/json", payload)
                }
                if (!response.isSuccess) throw IOException("$GEO_HOST answered $response")
                out += parseGeoBatch(response.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        if (out.isEmpty() && lastFailure != null) throw lastFailure
        return out
    }

    private fun directSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Reads ip-api's batch answer: a JSON array of flat objects such as
     * `{"status":"success","country":"Japan","countryCode":"JP","query":"1.2.3.4"}`.
     * Failed entries (private or reserved ranges) and malformed codes are
     * skipped.
     */
    internal fun parseGeoBatch(body: String): Map<String, GeoCountry> {
        val out = HashMap<String, GeoCountry>()
        for (match in GEO_OBJECT.findAll(body)) {
            val entry = match.value
            if (GEO_STATUS.find(entry)?.groupValues?.get(1) != "success") continue
            val ip = GEO_QUERY.find(entry)?.groupValues?.get(1)?.trim() ?: continue
            val code = GEO_COUNTRY_CODE.find(entry)?.groupValues?.get(1)?.trim()
                ?.takeIf { COUNTRY_CODE.matches(it) } ?: continue
            val name = GEO_COUNTRY_NAME.find(entry)?.groupValues?.get(1)?.trim().orEmpty()
            out[ip] = GeoCountry(code.uppercase(Locale.ROOT), name)
        }
        return out
    }

    // ---------------------------------------------------------- list format

    /**
     * Parses the host list, or the disk cache (the same lines with
     * `,ip,CC,Country name` appended). Skips blank and `#` lines, malformed
     * hosts and duplicates, keeping the order of the source.
     */
    internal fun parseHostList(lines: Sequence<String>): List<VpnGateServer> {
        val seen = HashSet<String>()
        val servers = ArrayList<VpnGateServer>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val server = parseHostLine(line) ?: continue
            if (seen.add(server.id)) servers += server
        }
        return servers
    }

    /**
     * One `host[:port]` line, optionally followed by the cache's
     * `,ip,CC,Country name`, or null when it is malformed. A host the list
     * names without a domain gets SoftEther's `.opengw.net`.
     */
    internal fun parseHostLine(line: String): VpnGateServer? {
        val parts = line.split(',', limit = 4)
        val endpoint = parts[0].trim()
        val colon = endpoint.lastIndexOf(':')
        val hostPart = if (colon >= 0) endpoint.substring(0, colon) else endpoint
        val port = if (colon >= 0) {
            endpoint.substring(colon + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            DEFAULT_SSTP_PORT
        }
        val host = hostPart.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty() || !HOST.matches(host)) return null
        val sstpHost = if (host.contains('.')) host else "$host.$SSTP_DOMAIN"

        val ip = parts.getOrNull(1)?.trim().orEmpty().takeIf { IPV4.matches(it) }.orEmpty()
        val code = parts.getOrNull(2)?.trim().orEmpty()
            .takeIf { COUNTRY_CODE.matches(it) }?.uppercase(Locale.ROOT).orEmpty()
        val name = if (code.isEmpty()) "" else parts.getOrNull(3)?.trim().orEmpty()
        return VpnGateServer(
            sstpHostname = sstpHost,
            sstpPort = port,
            countryCode = code,
            countryName = name,
            ip = ip,
        )
    }

    /** [servers] as cache lines, readable back by [parseHostList]. */
    internal fun formatCache(servers: List<VpnGateServer>): String = buildString {
        for (server in servers) {
            append(server.sstpHostname).append(':').append(server.sstpPort)
            append(',').append(server.ip)
            append(',').append(server.countryCode)
            append(',').append(server.countryName.replace('\n', ' ').replace('\r', ' '))
            append('\n')
        }
    }

    /** The disk copy of the list, or null when there is none or it is unusable. */
    internal fun readCache(file: File): Snapshot? {
        if (!file.isFile) return null
        return try {
            val servers = file.bufferedReader(Charsets.UTF_8).useLines { parseHostList(it) }
            if (servers.isEmpty()) null else Snapshot(servers, file.lastModified())
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Replaces the disk copy with [body]. Written next to it and renamed into
     * place, so a crash mid-write can never leave a half list behind.
     */
    internal fun writeCache(file: File, body: String) {
        val target = file.absoluteFile
        val partial = File(target.parentFile, target.name + ".partial")
        try {
            partial.writeText(body, Charsets.UTF_8)
            if (!partial.renameTo(target)) {
                target.delete()
                if (!partial.renameTo(target)) throw IOException("could not replace ${target.name}")
            }
        } catch (e: IOException) {
            partial.delete()
            DiagnosticsLog.w(TAG, "Could not cache the SSTP relay list: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- probe

    /**
     * Tests [server] for real: TCP connect (timed, that is the latency), then
     * TLS and the SSTP_DUPLEX_POST request. A `200` means the relay speaks
     * SSTP. No credentials are sent, so the unverified TLS here leaks nothing.
     * Dials the address resolved with the list when there is one, so the
     * latency is not a DNS lookup; the host name otherwise.
     */
    suspend fun probe(server: VpnGateServer): SstpProbe = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            val started = System.nanoTime()
            try {
                val target = if (server.ip.isNotEmpty()) {
                    InetSocketAddress(server.ip, server.sstpPort)
                } else {
                    InetSocketAddress(server.sstpHostname, server.sstpPort)
                }
                if (target.isUnresolved) return@withContext SstpProbe(ProbeState.UNREACHABLE)
                socket.connect(target, PROBE_TIMEOUT_MS)
            } catch (_: Exception) {
                return@withContext SstpProbe(ProbeState.UNREACHABLE)
            }
            val latency = ((System.nanoTime() - started) / 1_000_000L).toInt()
            val speaksSstp = try {
                socket.soTimeout = PROBE_TIMEOUT_MS
                val tls = SstpCore.insecureSocketFactory()
                    .createSocket(socket, server.sstpHostname, server.sstpPort, true) as SSLSocket
                tls.startHandshake()
                tls.outputStream.write(SstpCore.duplexPostRequest(server.sstpHostname))
                tls.outputStream.flush()
                SstpCore.isHttpOk(SstpCore.readHttpHeader(tls.inputStream))
            } catch (_: Exception) {
                false
            }
            SstpProbe(if (speaksSstp) ProbeState.SSTP_OK else ProbeState.REACHABLE_NO_SSTP, latency)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
