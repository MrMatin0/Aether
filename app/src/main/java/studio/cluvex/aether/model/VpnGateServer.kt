package studio.cluvex.aether.model

import java.util.Locale

/**
 * One public SSTP relay, from the host list VpnGateRepository downloads.
 *
 * The list is `host[:port]` and nothing else, so the only metadata a relay
 * carries is its country, looked up from the address its host resolves to.
 * Whether the relay speaks SSTP right now is what [SstpProbe] answers. The app
 * dials [sstpHostname], not [ip], because that is the name the relay's
 * certificate and SNI routing expect.
 */
data class VpnGateServer(
    /** SSTP endpoint host, e.g. `vpn110639311.opengw.net`. */
    val sstpHostname: String,
    /** SSTP endpoint port, 443 when the list names none. */
    val sstpPort: Int,
    /** ISO 3166-1 alpha-2 code, upper case, blank when unknown. */
    val countryCode: String = "",
    /** Country name in English, blank when unknown. */
    val countryName: String = "",
    /** IPv4 address the host resolved to when the list was fetched, blank when it did not. */
    val ip: String = "",
) {
    /** Stable identity: one host can serve SSTP on more than one port. */
    val id: String get() = "${sstpHostname.lowercase(Locale.ROOT)}:$sstpPort"

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
    /** Grouped by country, relays with an unknown country last. */
    COUNTRY,

    /** Lowest measured SSTP latency first; untested relays after the tested ones. */
    PING,
}

/** Filter for the relay list. */
data class VpnGateFilter(
    /** Free text: country, host or IP. */
    val query: String = "",
    /** ISO country code, blank = all countries. */
    val countryCode: String = "",
    /** Only relays whose SSTP probe succeeded. */
    val verifiedOnly: Boolean = false,
)

/**
 * Applies [criteria] and [sort]. Relays that FAILED a probe sink to the end
 * whatever the sort, ties fall back to the host name. [countryLabel] lets the
 * UI match localized country names too (e.g. a Persian query).
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
        return if (probe != null && probe.state == ProbeState.SSTP_OK && probe.latencyMs >= 0) {
            probe.latencyMs
        } else {
            Int.MAX_VALUE
        }
    }

    val primary: Comparator<VpnGateServer> = when (sort) {
        VpnGateSort.COUNTRY -> compareBy<VpnGateServer> { if (it.countryCode.isEmpty()) 1 else 0 }
            .thenBy { it.countryCode }
        VpnGateSort.PING -> compareBy { latency(it) }
    }
    val failedLast = compareBy<VpnGateServer> { if (probes[it.id]?.state?.failed == true) 1 else 0 }
    return matching.sortedWith(failedLast.then(primary).thenBy { it.sstpHostname })
}

private fun VpnGateServer.matchesQuery(query: String, countryLabel: (String) -> String): Boolean =
    countryName.lowercase(Locale.ROOT).contains(query) ||
        countryCode.lowercase(Locale.ROOT) == query ||
        (countryCode.isNotEmpty() && countryLabel(countryCode).lowercase().contains(query)) ||
        sstpHostname.lowercase(Locale.ROOT).contains(query) ||
        ip.contains(query)
