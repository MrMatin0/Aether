package studio.cluvex.aether.core

import studio.cluvex.aether.core.probe.GeoLookup
import studio.cluvex.aether.core.probe.Socks5Probe

/** Public IP + country as reported by a geolocation endpoint. */
data class IpInfo(val ip: String, val countryCode: String?)

/** IP shown in the UI, tagged with whether it came through the tunnel. */
data class IpEndpoint(val ip: String, val countryCode: String?, val viaTunnel: Boolean)

/**
 * Low-level, dependency-free network probes: what the rest of the app calls.
 *
 * WHY RAW SOCKETS, still:
 *   1. They are exempt from Android's cleartext-traffic policy, so the plain
 *      HTTP geolocation probe works without extra manifest config.
 *   2. A hand-rolled SOCKS5 client can send the destination as a DOMAIN, so DNS
 *      is resolved REMOTELY by the engine - exactly what a real browser tab
 *      does through the tunnel. That makes the probe a true end-to-end test of
 *      DNS + TCP + the proxy path in one shot.
 *
 * Because the app package is excluded from the VPN
 * (addDisallowedApplication), a direct socket bypasses the tunnel (-> operator
 * IP) while a socket routed through the local SOCKS5 port exits via the
 * connected server (-> server IP).
 *
 * THIS FILE IS DELIBERATELY THIN. It used to be a 23 KB object that also
 * contained the SOCKS5 client, an HTTP/1.1 client, a TLS wrapper, two response
 * parsers, the provider table, a thread pool and three retry policies - none of
 * which could be exercised without a live hostile network. All of that now
 * lives in core/probe, one unit per file:
 *
 *   probe/GeoLookup     which path to ask, in what order, with what patience
 *   probe/GeoProviders  the endpoints and the country authority
 *   probe/Socks5Probe   dialling through the proxy
 *   probe/MiniHttp      one bounded GET
 *   probe/TlsProbe      TLS wrapping + hostname verification
 *   probe/IpInfoParser  the two response shapes
 */
object NetProbe {

    /** Real/operator IP (direct, bypasses the tunnel). */
    fun fetchIpInfoDirect(timeoutMs: Int = 8000): IpInfo? = GeoLookup.direct(timeoutMs)

    /** Exit/server IP, providers tried in order through the local SOCKS5 proxy. */
    fun fetchIpInfoViaSocks(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 9000,
    ): IpInfo? = GeoLookup.viaSocks(socksHost, socksPort, timeoutMs)

    /** Exit/server IP, all providers raced in parallel; first answer wins. */
    fun fetchIpInfoViaSocksRaced(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 6000,
    ): IpInfo? = GeoLookup.racedViaSocks(socksHost, socksPort, timeoutMs)

    /**
     * [fetchIpInfoDirect] with retries for a flaky operator network. BLOCKS
     * between attempts, so call it off the main thread (Dispatchers.IO).
     */
    fun fetchIpInfoDirectWithRetry(
        attempts: Int = 6,
        delayMs: Long = 2000,
        timeoutMs: Int = 8000,
    ): IpInfo? = GeoLookup.retrying(attempts, delayMs) { GeoLookup.direct(timeoutMs) }

    /**
     * Raced lookup with retries to cover the tunnel's cold start: raced
     * providers + 1 s pacing instead of serial providers + 3 s pacing, i.e. the
     * same total patience with a far lower time-to-first-answer.
     */
    fun fetchIpInfoViaSocksWithRetry(
        socksHost: String,
        socksPort: Int,
        attempts: Int = 12,
        delayMs: Long = 1000,
        timeoutMs: Int = 6000,
    ): IpInfo? = GeoLookup.retrying(attempts, delayMs) {
        GeoLookup.racedViaSocks(socksHost, socksPort, timeoutMs)
    }

    /** SOCKS5 greeting only - proves the engine speaks SOCKS5. */
    fun checkSocksHandshake(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 4000,
    ): Boolean = Socks5Probe.handshakeOnly(socksHost, socksPort, timeoutMs)

    /** Full SOCKS5 CONNECT to an IP literal - proves TCP proxying works (no DNS). */
    fun checkTcpViaProxy(
        socksHost: String,
        socksPort: Int,
        destIp: String,
        destPort: Int,
        timeoutMs: Int = 6000,
    ): Boolean = Socks5Probe.canReachIpv4(socksHost, socksPort, destIp, destPort, timeoutMs)

    /** Turns a 2-letter ISO country code into its flag emoji. */
    fun flagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return WHITE_FLAG
        val code = countryCode.uppercase()
        if (!code.all { it in 'A'..'Z' }) return WHITE_FLAG
        val first = REGIONAL_INDICATOR_A + (code[0].code - 'A'.code)
        val second = REGIONAL_INDICATOR_A + (code[1].code - 'A'.code)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private const val WHITE_FLAG = "\uD83C\uDFF3\uFE0F"
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
