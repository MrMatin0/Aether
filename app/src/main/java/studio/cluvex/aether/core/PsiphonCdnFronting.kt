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
 *
 * ### Performance
 *
 * Everything that does not depend on user input (the Fastly overrides, the
 * default Akamai overrides, the UDP-free protocol lists) is built ONCE, and the
 * two free-text parsers are single-pass and regex-free. [parseIpCandidates] runs
 * on every keystroke of the Chain page's edge field, so it matters.
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
            PsiphonProtocol.DIRECT -> if (viaUpstream) DIRECT_PROTOCOLS_TCP else DIRECT_PROTOCOLS
            PsiphonProtocol.CDN_FRONTING -> if (viaUpstream) CDN_PROTOCOLS_TCP else CDN_PROTOCOLS
        }

        return Plan(
            dialOverrides = dialOverrides(snis.firstOrNull()),
            dialOverridesProbability = DIAL_OVERRIDES_PROBABILITY,
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
     *
     * Without a custom SNI the result is always the same, so it is the cached
     * [DEFAULT_DIAL_OVERRIDES]; a blank SNI counts as none.
     */
    fun dialOverrides(customSni: String?): List<DialOverride> {
        val sni = customSni?.takeIf { it.isNotBlank() } ?: return DEFAULT_DIAL_OVERRIDES
        return FASTLY_OVERRIDES + akamaiOverrides(sni)
    }

    /**
     * IPv4 addresses and IPv4 CIDRs (/8 to /32), in order, de-duplicated,
     * capped at [MAX_IP_CANDIDATES]. Anything else is dropped silently: the
     * field is free text and the UI shows how many entries were accepted.
     *
     * Persian / Arabic-Indic digits are folded to ASCII first: a Persian
     * keyboard types `۲۳.۲۱۵.۰.۲۰۶`, and the core only parses ASCII.
     */
    fun parseIpCandidates(raw: String): List<String> =
        collectTokens(raw, MAX_IP_CANDIDATES) { token -> token.takeIf { isIpv4OrCidr(it) } }

    /**
     * Host names only: lower-cased, trailing dot removed, ASCII labels, at
     * least one dot, and never a bare IP (that is what the edge field is for).
     */
    fun parseSniList(raw: String): List<String> =
        collectTokens(raw, MAX_SNI) { token ->
            token.removeSuffix(".").takeIf { isHostname(it) }
        }

    /**
     * Strict ASCII dotted-quad, optionally `/prefix` with prefix in
     * [MIN_PREFIX]..32. No leading zeros in an octet OR in the prefix (Go's
     * netip rejects both). Allocation-free: no split, no regex.
     */
    fun isIpv4OrCidr(text: String): Boolean {
        val slash = text.indexOf('/')
        if (slash < 0) return isIpv4(text, 0, text.length)
        if (!isIpv4(text, 0, slash)) return false

        val digits = text.length - slash - 1
        if (digits !in 1..2) return false
        if (digits == 2 && text[slash + 1] == '0') return false
        var prefix = 0
        for (i in slash + 1 until text.length) {
            val c = text[i]
            if (c !in '0'..'9') return false
            prefix = prefix * 10 + (c - '0')
        }
        return prefix in MIN_PREFIX..32
    }

    // ------------------------------------------------------------ internals

    /**
     * One pass over [raw]: split on [isSeparator], fold digits / lower-case
     * ASCII, hand each token to [accept] (which may rewrite it, or reject it
     * with null), de-duplicate in order, and stop as soon as [max] is reached.
     */
    private inline fun collectTokens(
        raw: String,
        max: Int,
        accept: (String) -> String?,
    ): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val token = StringBuilder()
        val n = raw.length
        var i = 0
        while (i <= n) {
            val c = if (i < n) raw[i] else ' '
            if (isSeparator(c)) {
                if (token.isNotEmpty()) {
                    val accepted = accept(token.toString())
                    token.setLength(0)
                    if (accepted != null && out.add(accepted) && out.size >= max) break
                }
            } else {
                token.append(normalize(c))
            }
            i++
        }
        return out.toList()
    }

    /**
     * Whitespace (Unicode, not just ASCII), `,` `;` and their Persian forms
     * `،` `؛`, and invisible format characters. The last group matters: text
     * pasted from a Persian chat carries RLM / LRM / ZWNJ marks that would
     * otherwise glue themselves to an IP and get it dropped.
     */
    private fun isSeparator(c: Char): Boolean =
        c == ',' || c == ';' || c == '\u060C' || c == '\u061B' ||
            c.isWhitespace() || c.category == CharCategory.FORMAT

    /** Persian / Arabic-Indic digits and decimal mark to ASCII, A-Z to a-z. */
    private fun normalize(c: Char): Char = when (c) {
        in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
        in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
        '\u066B' -> '.'
        in 'A'..'Z' -> c + ('a' - 'A')
        else -> c
    }

    /** `text[start, end)` is four ASCII octets, 0-255, no leading zeros. */
    private fun isIpv4(text: String, start: Int, end: Int): Boolean {
        var i = start
        for (octet in 0 until 4) {
            if (octet > 0) {
                if (i >= end || text[i] != '.') return false
                i++
            }
            val from = i
            var value = 0
            while (i < end && i - from < 3) {
                val c = text[i]
                if (c !in '0'..'9') break
                value = value * 10 + (c - '0')
                i++
            }
            val len = i - from
            if (len == 0 || value > 255 || (len > 1 && text[from] == '0')) return false
        }
        return i == end
    }

    /**
     * RFC 1123 host name over already lower-cased input: 1-63 char labels of
     * `[a-z0-9-]` not starting or ending with `-`, at least one dot, at most
     * 253 chars, and a non-numeric last label (which also rules out an IPv4).
     */
    private fun isHostname(text: String): Boolean {
        val n = text.length
        if (n == 0 || n > 253) return false
        var labelStart = 0
        var dots = 0
        var numeric = true
        for (i in 0..n) {
            if (i == n || text[i] == '.') {
                val len = i - labelStart
                if (len == 0 || len > 63) return false
                if (text[labelStart] == '-' || text[i - 1] == '-') return false
                if (i == n) return dots > 0 && !numeric
                dots++
                labelStart = i + 1
                numeric = true
            } else {
                when (text[i]) {
                    in '0'..'9' -> Unit
                    in 'a'..'z', '-' -> numeric = false
                    else -> return false
                }
            }
        }
        return false
    }

    private fun akamaiOverrides(customSni: String?): List<DialOverride> =
        AKAMAI_EDGES.map { (id, ip) ->
            val edgeSni = customSni ?: ip
            DialOverride(
                id = id,
                providerRegexes = emptyList(),
                dialAddressRegexes = MATCH_ANY_ADDRESS,
                dialAddresses = listOf(ip),
                sniServerName = edgeSni,
                verifyServerNames = (listOf(edgeSni, ip) + AKAMAI_VERIFY_NAMES).distinct(),
                alpnProtocols = ALPN_HTTP1,
            )
        }

    private fun List<String>.withoutQuic(): List<String> = filterNot { "QUIC" in it }

    // ------------------------------------------------------------ constants
    //
    // ORDER MATTERS below: an object initialises its properties top to bottom,
    // so every derived list sits after the constants it is built from.

    const val TLS_PROFILE = "Chrome-83"
    const val MAX_IP_CANDIDATES = 64
    const val MAX_SNI = 16
    private const val MIN_PREFIX = 8
    private const val DIAL_OVERRIDES_PROBABILITY = 1.0

    private const val FASTLY_DIAL = "pypi.org"

    private val ALPN_H2_HTTP1 = listOf("h2", "http/1.1")
    private val ALPN_HTTP1 = listOf("http/1.1")
    private val MATCH_ANY_ADDRESS = listOf(".*")

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

    // ---- derived once, after everything they read --------------------------

    /** Through a SOCKS5 upstream: no UDP, so no QUIC. */
    private val CDN_PROTOCOLS_TCP = CDN_PROTOCOLS.withoutQuic()
    private val DIRECT_PROTOCOLS_TCP = DIRECT_PROTOCOLS.withoutQuic()

    private val FASTLY_OVERRIDES = listOf(
        DialOverride(
            id = "fastly-provider",
            providerRegexes = listOf("(?i)fastly"),
            dialAddressRegexes = emptyList(),
            dialAddresses = listOf(FASTLY_DIAL),
            sniServerName = FASTLY_DIAL,
            verifyServerNames = FASTLY_VERIFY_NAMES,
            alpnProtocols = ALPN_H2_HTTP1,
        ),
        DialOverride(
            id = "fastly-address",
            providerRegexes = emptyList(),
            dialAddressRegexes = listOf("(?i)(fastly|pypi|python|github)"),
            dialAddresses = listOf(FASTLY_DIAL),
            sniServerName = FASTLY_DIAL,
            verifyServerNames = FASTLY_VERIFY_NAMES,
            alpnProtocols = ALPN_H2_HTTP1,
        ),
    )

    /** What every connect without a custom SNI gets: built once, shared. */
    private val DEFAULT_DIAL_OVERRIDES = FASTLY_OVERRIDES + akamaiOverrides(null)
}
