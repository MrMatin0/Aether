package studio.cluvex.aether.model

/**
 * How ONE Smart DNS server is spoken to.
 *
 *  - [PLAIN] classic DNS: UDP, with a TCP retry when an answer is truncated.
 *  - [DOH]   DNS over HTTPS (RFC 8484): the query is the body of an HTTPS POST,
 *            so on the wire it is indistinguishable from ordinary web traffic.
 *  - [DOT]   DNS over TLS (RFC 7858): length-prefixed DNS inside TLS, port 853.
 *
 * ### Not a setting any more
 *
 * The protocol used to be one choice for the whole list, which meant a user
 * had to know which of three transports a provider spoke before typing its
 * address, and could not mix a DoH resolver with a plain fallback. It is now
 * DETECTED per entry by [SmartDnsServers.parse]: `1.2.3.4` is plain DNS,
 * `https://...` is DoH, `tls://...` (or `:853`) is DoT, and a bare host name is
 * DoH. One list can mix all three; they are tried in order.
 *
 * [studio.cluvex.aether.model.ConnectionProfile.smartDnsProtocol] survives only
 * as a HINT for bare entries saved while it was a setting, so a profile that
 * said "DoT" next to `dns.example.com` keeps meaning DoT.
 *
 * The TUN never advertises the provider itself. It advertises the virtual
 * resolver [studio.cluvex.aether.core.TunnelConfig.SMART_DNS_RESOLVER], which
 * the Smart DNS front answers in-process, so the device's own resolver does not
 * have to speak DoH or DoT - and a resolver on any port works, which
 * `VpnService.Builder.addDnsServer` never allowed.
 */
enum class SmartDnsProtocol(val defaultPort: Int) {
    PLAIN(53),
    DOH(443),
    DOT(853),
    ;

    companion object {
        /**
         * Reads a stored / transported name, or null when it means nothing (the
         * caller keeps its own default). Accepts the spellings a hand-written
         * or imported config is likely to use.
         */
        fun fromStored(raw: String?): SmartDnsProtocol? {
            val name = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.name.lowercase() == name }?.let { return it }
            return when (name) {
                "udp", "dns", "do53", "normal" -> PLAIN
                "https", "dns-over-https", "dns_over_https" -> DOH
                "tls", "dns-over-tls", "dns_over_tls" -> DOT
                else -> null
            }
        }
    }
}

/**
 * One validated Smart DNS server.
 *
 * [host] is a unicast IPv4 literal for [SmartDnsProtocol.PLAIN], and an IPv4
 * literal or a host name for the encrypted protocols (the name is what the
 * certificate is verified against). [path] is only used by DoH.
 *
 * [alias] is set for a built-in preset ([SmartDnsPresets]): its token, e.g.
 * `preset:geohide-eu`. The UI and the diagnostics log show that instead of the
 * address, so the sealed preset list is not spelled out on screen or in a
 * shared log.
 */
data class SmartDnsServer(
    val protocol: SmartDnsProtocol,
    val host: String,
    val port: Int,
    val path: String = "",
    val alias: String = "",
) {
    /** True when [host] is an address rather than a name. */
    val isIpLiteral: Boolean
        get() = SmartDnsServers.isIpv4(host)

    /** True for a built-in preset. */
    val isPreset: Boolean
        get() = alias.isNotEmpty()

    /**
     * The server as a user would write it, scheme included so it parses back to
     * the same transport: `1.2.3.4`, `tls://dns.example`, `https://dns.example/dns-query`.
     */
    val endpoint: String
        get() {
            val authority = if (port == protocol.defaultPort) host else "$host:$port"
            return when (protocol) {
                SmartDnsProtocol.PLAIN -> authority
                SmartDnsProtocol.DOT -> "tls://$authority"
                SmartDnsProtocol.DOH -> "https://$authority$path"
            }
        }

    /** What the UI and the log show: the preset token for a preset, otherwise [endpoint]. */
    val label: String
        get() = alias.ifEmpty { endpoint }
}

/**
 * Parses the Smart DNS server list. Pure Kotlin, no Android, no name lookups:
 * every entry is checked as TEXT, and anything that does not fit a transport's
 * grammar is dropped rather than guessed at.
 */
object SmartDnsServers {

    /** Most servers kept; they are tried in order. */
    const val MAX_SERVERS = 8

    /** Path used for a DoH entry that names no path of its own. */
    const val DEFAULT_DOH_PATH = "/dns-query"

    private val OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
    private val IPV4 = Regex("^$OCTET(?:\\.$OCTET){3}$")
    private val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    private val HOSTNAME = Regex("^$LABEL(?:\\.$LABEL)+$")

    /** A DoH path: no query string, no fragment, no list separators. */
    private val DOH_PATH = Regex("^/[A-Za-z0-9._~!&'()*+=:@%/-]*$")

    /** `1.2.3.0/24`: a range somebody pasted, not a server. */
    private val CIDR_SUFFIX = Regex("^/\\d{1,2}$")

    /** The raw entries of a stored list: separated by commas, spaces, semicolons or new lines. */
    fun entries(raw: String): List<String> = raw
        .split(',', ' ', ';', '\n', '\t', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /**
     * The usable servers in [raw], in order, without duplicates, at most
     * [MAX_SERVERS]. Each entry's transport is detected on its own (see
     * [parseAuto]); [hint] only decides what a BARE entry meant in a profile
     * saved while the protocol was still a setting.
     */
    fun parse(raw: String, hint: SmartDnsProtocol = SmartDnsProtocol.PLAIN): List<SmartDnsServer> =
        entries(raw)
            .mapNotNull { parseAuto(it, hint) }
            .distinct()
            .take(MAX_SERVERS)

    /**
     * One entry, its transport detected from the text:
     *
     *  - `preset:<id>`                  a built-in preset ([SmartDnsPresets])
     *  - `https://...`                  DoH
     *  - `tls://...`                    DoT
     *  - `udp://...`, `dns://...`       plain DNS
     *  - `host/path`                    DoH (only DoH has a path)
     *  - bare `host[:port]`             by [hint] when it is DoH or DoT; otherwise
     *                                   port 853 is DoT, port 443 is DoH, an IPv4
     *                                   address is plain DNS and a name is DoH
     *
     * Any other scheme (`http://`, ...) is dropped, never downgraded.
     */
    fun parseAuto(entry: String, hint: SmartDnsProtocol = SmartDnsProtocol.PLAIN): SmartDnsServer? {
        val text = entry.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (lower.startsWith(SmartDnsPresets.TOKEN_PREFIX)) return SmartDnsPresets.fromToken(text)?.server
        return when {
            lower.startsWith("https://") -> parseDoh(text)
            lower.startsWith("tls://") -> parseDot(text)
            lower.startsWith("udp://") || lower.startsWith("dns://") -> parsePlain(text)
            text.contains("://") -> null
            text.contains('/') -> parseBareWithPath(text)
            else -> parseBare(text, hint)
        }
    }

    /**
     * One entry, read STRICTLY as [protocol]: the pre-auto grammar, kept for
     * callers that already know what they hold (a preset's sealed address).
     */
    fun parseOne(entry: String, protocol: SmartDnsProtocol): SmartDnsServer? = when (protocol) {
        SmartDnsProtocol.PLAIN -> parsePlain(entry)
        SmartDnsProtocol.DOT -> parseDot(entry)
        SmartDnsProtocol.DOH -> parseDoh(entry)
    }

    private fun parseBareWithPath(text: String): SmartDnsServer? {
        val slash = text.indexOf('/')
        if (CIDR_SUFFIX.matches(text.substring(slash))) return null
        return parseDoh(text)
    }

    private fun parseBare(text: String, hint: SmartDnsProtocol): SmartDnsServer? {
        when (hint) {
            SmartDnsProtocol.DOT -> return parseDot(text)
            SmartDnsProtocol.DOH -> return parseDoh(text)
            SmartDnsProtocol.PLAIN -> Unit
        }
        val (host, port) = splitHostPort(text.lowercase(), SmartDnsProtocol.PLAIN.defaultPort) ?: return null
        return when {
            port == SmartDnsProtocol.DOT.defaultPort -> parseDot(text)
            port == SmartDnsProtocol.DOH.defaultPort -> parseDoh(text)
            isIpv4(host) -> parsePlain(text)
            else -> parseDoh(text)
        }
    }

    /** `1.2.3.4`, `1.2.3.4:5353`, `udp://1.2.3.4` or `dns://1.2.3.4`. IPv4 only. */
    private fun parsePlain(entry: String): SmartDnsServer? {
        val text = entry.trim().lowercase().removePrefix("udp://").removePrefix("dns://")
        if (text.contains("://") || text.contains('/')) return null
        val (host, port) = splitHostPort(text, SmartDnsProtocol.PLAIN.defaultPort) ?: return null
        if (!isIpv4(host) || !isUsableUnicast(host)) return null
        return SmartDnsServer(SmartDnsProtocol.PLAIN, host, port)
    }

    /** `dns.example.com`, `tls://dns.example.com:853`, `1.1.1.1`. */
    private fun parseDot(entry: String): SmartDnsServer? {
        val text = entry.trim().lowercase().removePrefix("tls://").trimEnd('/')
        if (text.contains("://") || text.contains('/')) return null
        val (host, port) = splitHostPort(text, SmartDnsProtocol.DOT.defaultPort) ?: return null
        if (!isUsableHost(host)) return null
        return SmartDnsServer(SmartDnsProtocol.DOT, host, port)
    }

    /**
     * `https://dns.example.com/dns-query`, a bare `dns.example.com` (which
     * means the default path), or an RFC 8484 template ending in `{?dns}`.
     * Anything with credentials, a query string or another scheme is dropped.
     */
    private fun parseDoh(entry: String): SmartDnsServer? {
        var text = entry.trim()
        if (text.lowercase().startsWith("https://")) {
            text = text.substring("https://".length)
        } else if (text.contains("://")) {
            return null
        }
        text = text.replace("{?dns}", "")
        if (text.contains('@')) return null
        val slash = text.indexOf('/')
        val authority = (if (slash >= 0) text.substring(0, slash) else text).lowercase()
        var path = if (slash >= 0) text.substring(slash) else ""
        if (path.isEmpty() || path == "/") path = DEFAULT_DOH_PATH
        if (!DOH_PATH.matches(path)) return null
        val (host, port) = splitHostPort(authority, SmartDnsProtocol.DOH.defaultPort) ?: return null
        if (!isUsableHost(host)) return null
        return SmartDnsServer(SmartDnsProtocol.DOH, host, port, path)
    }

    /** `host` or `host:port`; null for an empty host, an IPv6 literal or a bad port. */
    private fun splitHostPort(text: String, defaultPort: Int): Pair<String, Int>? {
        if (text.isEmpty()) return null
        val colon = text.lastIndexOf(':')
        if (colon < 0) return text to defaultPort
        val host = text.substring(0, colon)
        val portText = text.substring(colon + 1)
        if (host.isEmpty() || host.contains(':')) return null
        if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
        val port = portText.toInt()
        if (port !in 1..65_535) return null
        return host to port
    }

    private fun isUsableHost(host: String): Boolean =
        if (isIpv4(host)) isUsableUnicast(host) else isHostname(host)

    /** LDH labels, at least two, and a top-level label that is not all digits. */
    private fun isHostname(host: String): Boolean {
        if (host.length > 253 || !HOSTNAME.matches(host)) return false
        return host.substringAfterLast('.').any { it in 'a'..'z' }
    }

    /** True for a dotted-quad IPv4 literal with every octet in range. */
    fun isIpv4(text: String): Boolean = IPV4.matches(text)

    /**
     * True for an IPv4 address that can be a unicast host on the internet or a
     * LAN: not `0.0.0.0/8`, loopback `127/8`, link-local `169.254/16`, or
     * anything from `224` up - multicast, reserved and the limited broadcast,
     * none of which a resolver can be.
     */
    fun isUsableUnicast(address: String): Boolean {
        if (!isIpv4(address)) return false
        val octets = address.split('.').map { it.toInt() }
        val first = octets[0]
        return when {
            first == 0 -> false
            first == 127 -> false
            first == 169 && octets[1] == 254 -> false
            first >= 224 -> false
            else -> true
        }
    }
}
