package studio.cluvex.aether.core

import studio.cluvex.aether.model.PsiphonProtocol

/**
 * Turns the profile's Psiphon protocol choice and CDN settings into the config
 * keys understood by shirokhorshid's psiphon-tunnel-core fork.
 *
 * Ported from shirokhorshid-android's TunnelManager (GPL-3.0), which is where
 * the edge list, the verify names and the protocol lists come from. See
 * docs/CDN_FRONTING.md for what each key does in the core.
 *
 * PURE KOTLIN on purpose: no org.json, no Android. The unit tests run on the
 * JVM without either, and PsiphonCore does the (trivial) JSON mapping.
 *
 * ### What CDN fronting is
 *
 * Fronted meek wraps the Psiphon tunnel in HTTPS to a CDN edge. The TLS SNI and
 * the IP a censor sees belong to the CDN (Akamai, Fastly), while the Host header
 * inside the encrypted stream routes it to a Psiphon meek server behind that
 * CDN. Blocking it means blocking the CDN edge, which also takes out every
 * ordinary site served from it. The fork adds FRONTED-MEEK-CDN-* protocols and
 * lets the client choose WHICH edge addresses to dial and which SNI to present,
 * so users can move to edges that still answer on their network.
 */
object PsiphonCdnFronting {

    /** One entry of `FrontedMeekDialOverrides`. Empty lists are omitted from the JSON. */
    data class DialOverride(
        val id: String,
        val providerRegexes: List<String>,
        val dialAddressRegexes: List<String>,
        val dialAddresses: List<String>,
        val sniServerName: String,
        val verifyServerNames: List<String>,
        val alpnProtocols: List<String>,
        val tlsProfile: String = TLS_PROFILE,
    )

    /** `FrontedMeekCDNScanSpec`: extra edges for the core to probe, and the SNIs to try. */
    data class ScanSpec(
        val ipCandidates: List<String>,
        val sniServerNames: List<String>,
    )

    /**
     * Everything PsiphonCore writes. [limitTunnelProtocols] null means "leave
     * whatever the config says", and [disableTactics] false means the same for
     * tactics - AUTO must not quietly override a network-issued config.
     */
    data class Plan(
        val dialOverrides: List<DialOverride>,
        val dialOverridesProbability: Double,
        val useBuiltInScanSpec: Boolean,
        val scanSpec: ScanSpec?,
        val limitTunnelProtocols: List<String>?,
        val disableTactics: Boolean,
    )

    /**
     * @param edgeIps the user's free-text edge list (IPv4 / CIDR, any separator)
     * @param sni the user's free-text SNI list
     * @param viaUpstream true when Psiphon dials through a local SOCKS5 hop
     *   (Psiphon over Aether). SOCKS5 CONNECT carries no UDP, so the QUIC
     *   protocols are dropped rather than left to fail one by one.
     */
    fun plan(
        protocol: PsiphonProtocol,
        edgeIps: String,
        sni: String,
        viaUpstream: Boolean,
    ): Plan {
        val candidates = parseIpCandidates(edgeIps)
        val snis = parseSniList(sni)
        val limit = when (protocol) {
            PsiphonProtocol.AUTO -> null
            PsiphonProtocol.DIRECT -> DIRECT_PROTOCOLS
            PsiphonProtocol.CDN_FRONTING -> CDN_PROTOCOLS
        }?.let { list -> if (viaUpstream) list.filterNot { it.contains("QUIC") } else list }

        return Plan(
            dialOverrides = dialOverrides(snis.firstOrNull()),
            dialOverridesProbability = 1.0,
            useBuiltInScanSpec = true,
            scanSpec = if (candidates.isEmpty()) null else ScanSpec(candidates, snis),
            limitTunnelProtocols = limit,
            disableTactics = protocol != PsiphonProtocol.AUTO,
        )
    }

    /**
     * Fastly first (matched by provider id OR by a Fastly-looking dial address),
     * then one override per Akamai edge. The order matters: the core takes the
     * first override that matches.
     */
    fun dialOverrides(customSni: String?): List<DialOverride> {
        val fastly = listOf(
            DialOverride(
                id = "fastly-provider",
                providerRegexes = listOf("(?i)fastly"),
                dialAddressRegexes = emptyList(),
                dialAddresses = listOf(FASTLY_DIAL),
                sniServerName = FASTLY_DIAL,
                verifyServerNames = FASTLY_VERIFY_NAMES,
                alpnProtocols = listOf("h2", "http/1.1"),
            ),
            DialOverride(
                id = "fastly-address",
                providerRegexes = emptyList(),
                dialAddressRegexes = listOf("(?i)(fastly|pypi|python|github)"),
                dialAddresses = listOf(FASTLY_DIAL),
                sniServerName = FASTLY_DIAL,
                verifyServerNames = FASTLY_VERIFY_NAMES,
                alpnProtocols = listOf("h2", "http/1.1"),
            ),
        )
        val edges = AKAMAI_EDGES.map { (id, ip) ->
            val edgeSni = customSni ?: ip
            DialOverride(
                id = id,
                providerRegexes = emptyList(),
                dialAddressRegexes = listOf(".*"),
                dialAddresses = listOf(ip),
                sniServerName = edgeSni,
                verifyServerNames = (listOf(edgeSni, ip) + AKAMAI_VERIFY_NAMES).distinct(),
                alpnProtocols = listOf("http/1.1"),
            )
        }
        return fastly + edges
    }

    /**
     * IPv4 addresses and IPv4 CIDRs (/8 to /32), in order, de-duplicated,
     * capped at [MAX_IP_CANDIDATES]. Anything else is dropped silently: the
     * field is free text and the UI shows how many entries were accepted.
     */
    fun parseIpCandidates(raw: String): List<String> =
        raw.split(SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() && isIpv4OrCidr(it) }
            .distinct()
            .take(MAX_IP_CANDIDATES)

    /**
     * Host names only: lower-cased, trailing dot removed, ASCII labels, at
     * least one dot, and never a bare IP (that is what the edge field is for).
     */
    fun parseSniList(raw: String): List<String> =
        raw.split(SEPARATORS)
            .map { it.trim().lowercase().removeSuffix(".") }
            .filter { it.isNotEmpty() && isHostname(it) }
            .distinct()
            .take(MAX_SNI)

    fun isIpv4OrCidr(text: String): Boolean {
        val parts = text.split('/')
        if (parts.size > 2) return false
        if (!isIpv4(parts[0])) return false
        if (parts.size == 2) {
            val prefix = parts[1].takeIf { it.length in 1..2 && it.all(Char::isDigit) }
                ?.toIntOrNull() ?: return false
            if (prefix !in MIN_PREFIX..32) return false
        }
        return true
    }

    private fun isIpv4(text: String): Boolean {
        val octets = text.split('.')
        if (octets.size != 4) return false
        return octets.all { o ->
            o.length in 1..3 && o.all(Char::isDigit) &&
                (o.length == 1 || o[0] != '0') && o.toInt() <= 255
        }
    }

    private fun isHostname(text: String): Boolean {
        if (text.length > 253 || !text.contains('.')) return false
        if (isIpv4(text)) return false
        val labels = text.split('.')
        if (labels.last().all(Char::isDigit)) return false
        return labels.all { HOST_LABEL.matches(it) }
    }

    // ------------------------------------------------------------ constants

    const val TLS_PROFILE = "Chrome-83"
    const val MAX_IP_CANDIDATES = 64
    const val MAX_SNI = 16
    private const val MIN_PREFIX = 8

    private val SEPARATORS = Regex("[\\s,;]+")
    private val HOST_LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    private const val FASTLY_DIAL = "pypi.org"

    val FASTLY_VERIFY_NAMES = listOf(
        "www.python.org", "pypi.org", "fastly.com", "www.fastly.com",
        "developer.fastly.com", "githubassets.com", "github.com", "github.io",
        "githubusercontent.com",
    )

    val AKAMAI_VERIFY_NAMES = listOf(
        "a248.e.akamai.net", "a.akamaized.net", "a.akamaized-staging.net",
        "a.akamaihd.net", "a.akamaihd-staging.net", "www.akamai.com",
    )

    /** Akamai edges shirokhorshid ships, id to address. Order is preference. */
    val AKAMAI_EDGES = listOf(
        "edge-a-1" to "23.215.0.206",
        "edge-a-2" to "23.215.0.203",
        "edge-b-1" to "23.212.250.91",
        "edge-b-2" to "23.212.250.78",
        "edge-c-1" to "23.12.147.13",
        "edge-c-2" to "23.12.147.29",
        "edge-d-1" to "23.73.207.8",
        "edge-d-2" to "23.73.207.15",
        "edge-original" to "92.123.102.43",
    )

    /** CDN fronting only: every byte through a CDN edge. */
    val CDN_PROTOCOLS = listOf(
        "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH",
        "FRONTED-MEEK-CDN-QUIC-OSSH",
    )

    /** The ordinary protocol set, CDN-fronted ones included. */
    val DIRECT_PROTOCOLS = listOf(
        "SSH", "OSSH", "TLS-OSSH",
        "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH", "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
        "QUIC-OSSH", "SHADOWSOCKS-OSSH",
        "FRONTED-MEEK-OSSH", "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH",
        "FRONTED-MEEK-QUIC-OSSH", "FRONTED-MEEK-CDN-QUIC-OSSH",
    )
}
