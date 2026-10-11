package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
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
 * EXCEPT on an SSTP session: SSTP has no SOCKS5 port, so its TUN carries the
 * app's own traffic instead ([ChainRuntime.appTrafficInTun]). The exit lookup
 * then binds its sockets to Aether's own VPN network rather than trusting the
 * app's default network to have moved onto it already (see
 * [fetchIpInfoViaTunnelWithRetry] and GeoLookup.boundTo).
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

    private const val TAG = "netprobe"

    /**
     * The operator address last seen with NO tunnel up (the idle badge), or
     * null when none was learned yet. See [noteOperatorIp].
     */
    @Volatile
    private var operatorIp: String? = null

    /**
     * Remembers the address the device has WITHOUT a tunnel.
     *
     * A tunnel exit can never be that address. On a packet tunnel whose TUN
     * carries this app's own sockets (SSTP), an "exit" lookup that answers
     * with it went out before the route was in place, and publishing it is
     * exactly the "the badge shows my IP from before I connected" report.
     */
    fun noteOperatorIp(ip: String) {
        if (ip.isNotBlank()) operatorIp = ip
    }

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

    /**
     * The LIVE session's exit IP, over whichever path reaches its tunnel: the
     * local SOCKS5 entry for a SOCKS chain, or Aether's own VPN network on an
     * SSTP session, whose TUN carries this app's own sockets
     * ([ChainRuntime.appTrafficInTun]). Same patience as
     * [fetchIpInfoViaSocksWithRetry]. BLOCKS; call it off the main thread.
     *
     * [context] is what finds that VPN network. Without one the SSTP path
     * falls back to a plain socket, still guarded against the operator
     * address (see [noteOperatorIp]).
     */
    fun fetchIpInfoViaTunnelWithRetry(context: Context? = null): IpInfo? =
        if (ChainRuntime.appTrafficInTun) {
            fetchIpInfoThroughOwnVpnWithRetry(context)
        } else {
            fetchIpInfoViaSocksWithRetry(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT)
        }

    /**
     * The SSTP exit, asked over Aether's OWN VPN network only.
     *
     * ROOT CAUSE of the wrong SSTP badge: this used to be a plain
     * [fetchIpInfoDirectWithRetry], i.e. "whatever the default network is
     * right now". The probe starts the moment the TUN is established, and the
     * platform moves the app's default network onto a new VPN asynchronously,
     * so the first lookup regularly went out over the operator's network and
     * came back with the pre-connection address and the user's own country -
     * which was then published as the tunnel exit and never corrected.
     *
     * Now an attempt only runs once the VPN network exists, its sockets and its
     * DNS are bound to that network, and an answer equal to the operator
     * address is discarded and retried rather than trusted.
     */
    private fun fetchIpInfoThroughOwnVpnWithRetry(context: Context?): IpInfo? {
        val cm = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
        return GeoLookup.retrying(attempts = 12, delayMs = 1000) {
            val info = if (cm != null) {
                val vpn = ownVpnNetwork(cm)
                if (vpn == null) {
                    DiagnosticsLog.d(TAG, "exit lookup: the VPN network is not visible yet")
                    null
                } else {
                    GeoLookup.boundTo(vpn, 6000)
                }
            } else {
                GeoLookup.direct(6000)
            }
            val operator = operatorIp
            if (info != null && operator != null && info.ip == operator) {
                DiagnosticsLog.w(
                    TAG,
                    "Exit lookup answered with the pre-connection address ${info.ip} - " +
                        "the tunnel route was not in place yet; asking again.",
                )
                null
            } else {
                info
            }
        }
    }

    /**
     * The VPN network this app established, or null while the platform has not
     * published it yet. Only one VPN can hold the device at a time, and on Q+
     * the owner UID is visible to the owner itself, which is what tells a
     * leftover network of another VPN app apart from ours.
     */
    private fun ownVpnNetwork(cm: ConnectivityManager): Network? = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && ownedByUs(caps)
        }
    }.getOrNull()

    private fun ownedByUs(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val owner = caps.ownerUid
        return owner == Process.myUid() || owner == Process.INVALID_UID
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
