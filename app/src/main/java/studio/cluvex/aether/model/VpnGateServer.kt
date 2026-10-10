package studio.cluvex.aether.model

import java.util.Locale

/**
 * One relay from the VPN Gate public network, parsed from
 * https://www.vpngate.net/api/iphone/ (see VpnGateRepository).
 *
 * The API does not say which relays speak SSTP. [sstpHostname] and [sstpPort]
 * are where SoftEther serves it if it does; [SstpProbe] is the actual answer.
 * The app dials [sstpHostname], not [ip], because that is the name the
 * relay's certificate and SNI routing expect.
 */
data class VpnGateServer(
    /** Host name as the API reports it, e.g. "public-vpn-227" (no domain). */
    val hostname: String,
    /** IPv4 address. */
    val ip: String,
    /** VPN Gate's own quality score. Unbounded: values in the millions are normal. */
    val score: Long,
    /** Ping VPN Gate measured from Japan, in ms, or -1. Not the user's ping. */
    val apiPingMs: Int,
    /** Measured throughput, bits per second. */
    val speedBps: Long,
    /** Country name as reported by the API (English). */
    val countryName: String,
    /** ISO 3166-1 alpha-2 code, upper case. */
    val countryCode: String,
    /** Current VPN sessions on the relay. */
    val numSessions: Int,
    /** Uptime in milliseconds. */
    val uptimeMs: Long,
    /** Users who have ever connected. */
    val totalUsers: Long,
    /** Traffic ever relayed, bytes. */
    val totalTrafficBytes: Long,
    /** Log policy, e.g. "2weeks". */
    val logPolicy: String,
    /** Operator's self-reported name. */
    val operatorName: String,
    /** Operator's self-reported message. */
    val operatorMessage: String,
    /** SSTP endpoint host, `<hostname>.opengw.net`. */
    val sstpHostname: String,
    /** SSTP endpoint port: the relay's TCP listener when known, else 443. */
    val sstpPort: Int,
    /** TCP port from the OpenVPN config, when the config is TCP. */
    val openVpnTcpPort: Int? = null,
    /** UDP port from the OpenVPN config, when the config is UDP. */
    val openVpnUdpPort: Int? = null,
) {
    /** Stable identity: the same IP can host several relays. */
    val id: String get() = "$ip|$hostname"

    val formattedSpeed: String get() = formatGateBitrate(speedBps)
    val formattedUptime: String get() = formatGateUptime(uptimeMs)
    val formattedTraffic: String get() = formatGateBytes(totalTrafficBytes)
    val formattedUsers: String get() = String.format(Locale.US, "%,d", totalUsers)

    /** An [SstpConfig] for this relay, keeping the MRU/MTU the user tuned in [base]. */
    fun toSstpConfig(base: SstpConfig = SstpConfig()): SstpConfig = base.copy(
        hostname = sstpHostname,
        port = sstpPort,
        username = SstpConfig.VPNGATE_USERNAME,
        password = SstpConfig.VPNGATE_PASSWORD,
        verifyCert = false,
        customSni = "",
        source = SstpSource.VPNGATE,
        countryCode = countryCode,
        countryName = countryName,
    )
}

/** Result of testing one relay. */
enum class ProbeState {
    RUNNING,

    /** TLS + SSTP_DUPLEX_POST answered 200. */
    SSTP_OK,

    /** TCP connected, but SSTP was refused. */
    REACHABLE_NO_SSTP,

    /** TCP connect failed or timed out. */
    UNREACHABLE,
    ;

    val failed: Boolean get() = this == REACHABLE_NO_SSTP || this == UNREACHABLE
}

data class SstpProbe(
    val state: ProbeState,
    /** TCP connect time in ms, or -1. */
    val latencyMs: Int = -1,
)

/** Sort criteria for the relay list. */
enum class VpnGateSort {
    /** Best score first (VPN Gate's own ranking). */
    SCORE,

    /** Fastest measured throughput first. */
    SPEED,

    /** Lowest ping first: the user's own probe when there is one. */
    PING,

    /** Fewest active sessions first. */
    SESSIONS,
}

/** Filter for the relay list. */
data class VpnGateFilter(
    /** Free text: country, host, IP or operator. */
    val query: String = "",
    /** ISO country code, blank = all countries. */
    val countryCode: String = "",
    /** Only relays whose SSTP probe succeeded. */
    val verifiedOnly: Boolean = false,
)

/**
 * Applies [criteria] and [sort]. Relays that FAILED a probe sink to the end
 * whatever the sort, ties fall back to score. [countryLabel] lets the UI match
 * localized country names too (e.g. a Persian query).
 */
fun List<VpnGateServer>.filteredAndSorted(
    criteria: VpnGateFilter,
    sort: VpnGateSort,
    probes: Map<String, SstpProbe> = emptyMap(),
    countryLabel: (String) -> String = { "" },
): List<VpnGateServer> {
    val query = criteria.query.trim().lowercase(Locale.ROOT)
    val country = criteria.countryCode.trim().uppercase(Locale.ROOT)
    val matching = this.filter { server ->
        (country.isEmpty() || server.countryCode == country) &&
            (!criteria.verifiedOnly || probes[server.id]?.state == ProbeState.SSTP_OK) &&
            (query.isEmpty() || server.matchesQuery(query, countryLabel))
    }

    fun latency(server: VpnGateServer): Int {
        val probe = probes[server.id]
        if (probe != null && probe.state == ProbeState.SSTP_OK && probe.latencyMs >= 0) return probe.latencyMs
        return if (server.apiPingMs > 0) server.apiPingMs else Int.MAX_VALUE
    }

    val primary: Comparator<VpnGateServer> = when (sort) {
        VpnGateSort.SCORE -> compareByDescending { it.score }
        VpnGateSort.SPEED -> compareByDescending { it.speedBps }
        VpnGateSort.PING -> compareBy { latency(it) }
        VpnGateSort.SESSIONS -> compareBy { it.numSessions }
    }
    val failedLast = compareBy<VpnGateServer> { if (probes[it.id]?.state?.failed == true) 1 else 0 }
    return matching.sortedWith(failedLast.then(primary).thenByDescending { it.score })
}

private fun VpnGateServer.matchesQuery(query: String, countryLabel: (String) -> String): Boolean =
    countryName.lowercase(Locale.ROOT).contains(query) ||
        countryCode.lowercase(Locale.ROOT) == query ||
        countryLabel(countryCode).lowercase().contains(query) ||
        sstpHostname.lowercase(Locale.ROOT).contains(query) ||
        ip.contains(query) ||
        operatorName.lowercase(Locale.ROOT).contains(query)

// Locale.US on purpose: these are technical figures, shown LTR in a mono face.
private fun formatGateBitrate(bps: Long): String = when {
    bps >= 1_000_000_000L -> String.format(Locale.US, "%.1f Gbps", bps / 1e9)
    bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1e6)
    bps > 0L -> String.format(Locale.US, "%.0f Kbps", bps / 1e3)
    else -> "\u2014"
}

private fun formatGateUptime(ms: Long): String {
    if (ms <= 0L) return "\u2014"
    val minutes = ms / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0L -> "${days}d ${hours % 24L}h"
        hours > 0L -> "${hours}h ${minutes % 60L}m"
        else -> "${minutes}m"
    }
}

private fun formatGateBytes(bytes: Long): String {
    if (bytes <= 0L) return "\u2014"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
