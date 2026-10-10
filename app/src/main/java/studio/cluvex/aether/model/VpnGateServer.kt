package studio.cluvex.aether.model

/**
 * One VPN server from the VPNGate public relay network.
 *
 * Parsed from the VPNGate CSV API at https://www.vpngate.net/api/iphone/
 * The CSV contains servers volunteered by the community; each row reports
 * the protocols the server supports, its measured throughput, and the
 * number of active sessions. This model carries only the fields the app
 * needs: identity, location, performance, and SSTP support.
 *
 * SSTP (Secure Socket Tunneling Protocol) is the transport this feature
 * adds. A server with [sstpHostname] non-blank can accept SSTP connections
 * on port 443 over TLS/HTTPS. The app connects using the hostname (not
 * the IP) because SSTP requires a valid TLS certificate whose CN or SAN
 * matches the hostname the client presents in its HTTP CONNECT.
 */
data class VpnGateServer(
    /** Server hostname for display and SSTP connection. */
    val hostname: String,
    /** Server IP address (IPv4). */
    val ip: String,
    /** Server operator's self-reported description. */
    val operatorMessage: String,
    /** ISO 3166-1 alpha-2 country code, e.g. "JP", "KR", "US". */
    val countryCode: String,
    /** Full country name as reported by the API. */
    val countryName: String,
    /** Measured throughput in bits per second. */
    val speedBps: Long,
    /** Current number of VPN sessions on this server. */
    val numSessions: Int,
    /** Uptime in milliseconds. */
    val uptimeMs: Long,
    /** Total users who have connected to this server. */
    val totalUsers: Long,
    /** Total traffic handled in bytes. */
    val totalTrafficBytes: Long,
    /** Log policy: "2weeks", "permanent", "no" etc. */
    val logPolicy: String,
    /** True when the server accepts SSTP connections. */
    val supportsSstp: Boolean,
    /** SSTP hostname (may differ from [hostname]). Blank when SSTP is unsupported. */
    val sstpHostname: String,
    /** True when the server provides OpenVPN TCP config. */
    val supportsOpenVpnTcp: Boolean,
    /** True when the server provides OpenVPN UDP config. */
    val supportsOpenVpnUdp: Boolean,
    /** True when the server accepts L2TP/IPsec connections. */
    val supportsL2tp: Boolean,
    /** Server quality score (0..1000+), higher is better. */
    val score: Int,
    /** Measured ping/latency in ms, or -1 when unknown. */
    val pingMs: Int = -1,
    /** Measured download speed in Kbps during ping test, or -1. */
    val downloadSpeedKbps: Int = -1,
) {
    /** Formatted speed for display, e.g. "45.2 Mbps". */
    val formattedSpeed: String
        get() {
            val mbps = speedBps / 1_000_000.0
            return if (mbps >= 1.0) "%.1f Mbps".format(mbps)
            else "%.0f Kbps".format(speedBps / 1_000.0)
        }

    /** Formatted ping for display, or "—" when unknown. */
    val formattedPing: String
        get() = if (pingMs >= 0) "${pingMs}ms" else "—"

    /** Formatted uptime for display. */
    val formattedUptime: String
        get() {
            val hours = uptimeMs / 3_600_000
            val days = hours / 24
            return when {
                days > 0 -> "${days}d ${hours % 24}h"
                hours > 0 -> "${hours}h"
                else -> "${uptimeMs / 60_000}m"
            }
        }
}

/** Sort/filter criteria for the VPNGate server list. */
enum class VpnGateSort {
    /** Best score first (the API's own ranking). */
    SCORE,
    /** Fastest measured throughput first. */
    SPEED,
    /** Lowest ping first. */
    PING,
    /** Fewest active sessions first. */
    SESSIONS,
}

/** Filter predicate for the server list. */
data class VpnGateFilter(
    /** Only show servers that support SSTP. */
    val sstpOnly: Boolean = true,
    /** Country code filter, blank = all countries. */
    val countryCode: String = "",
    /** Minimum speed in bps, 0 = no filter. */
    val minSpeedBps: Long = 0,
    /** Search query for hostname/operator/country. */
    val searchQuery: String = "",
)
