package studio.cluvex.aether.model

/**
 * How the Smart DNS resolvers are spoken to.
 *
 *  - [PLAIN] classic DNS: UDP, with a TCP retry when an answer is truncated.
 *  - [DOH]   DNS over HTTPS (RFC 8484): the query is the body of an HTTPS POST,
 *            so on the wire it is indistinguishable from ordinary web traffic.
 *  - [DOT]   DNS over TLS (RFC 7858): length-prefixed DNS inside TLS, port 853.
 *
 * The TUN never advertises the provider itself any more. It advertises the
 * virtual resolver [studio.cluvex.aether.core.TunnelConfig.SMART_DNS_RESOLVER],
 * which the Smart DNS front answers in-process, so the device's own resolver
 * does not have to speak DoH or DoT - and a resolver on any port works, which
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
 */
data class SmartDnsServer(
    val protocol: SmartDnsProtocol,
    val host: String,
    val port: Int,
    val path: String = "",
) {
    /** True when [host] is an address rather than a name. */
    val isIpLiteral: Boolean
        get() = SmartDnsServers.isIpv4(host)

    /** The server as a user would write it: `1.2.3.4`, `tls://dns.example`, `https://dns.example/dns-query`. */
    val label: String
        get() {
            val authority = if (port == protocol.defaultPort) host else "$host:$port"
            return when (protocol) {
                SmartDnsProtocol.PLAIN -> authority
                SmartDnsProtocol.DOT -> "tls://$authority"
                SmartDnsProtocol.DOH -> "https://$authority$path"
            }
        }
}

/**
 * Parses the Smart DNS server list. Pure Kotlin, no Android, no name lookups:
 * every entry is checked as TEXT, and anything that does not fit the selected
 * protocol's grammar is dropped rather than guessed at.
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

    /**
     * The usable servers in [raw] for [protocol], in order, without duplicates,
     * at most [MAX_SERVERS]. Entries are separated by commas, spaces,
     * semicolons or new lines.
     */
    fun parse(raw: String, protocol: SmartDnsProtocol): List<SmartDnsServer> = raw
        .split(',', ' ', ';', '\n', '\t', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { parseOne(it, protocol) }
        .distinct()
        .take(MAX_SERVERS)

    /** One entry, or null when it is not a server of [protocol]. */
    fun parseOne(entry: String, protocol: SmartDnsProtocol): SmartDnsServer? = when (protocol) {
        SmartDnsProtocol.PLAIN -> parsePlain(entry)
        SmartDnsProtocol.DOT -> parseDot(entry)
        SmartDnsProtocol.DOH -> parseDoh(entry)
    }

    /** `1.2.3.4`, `1.2.3.4:5353` or `udp://1.2.3.4`. IPv4 only. */
    private fun parsePlain(entry: String): SmartDnsServer? {
        val text = entry.lowercase().removePrefix("udp://")
        if (text.contains("://") || text.contains('/')) return null
        val (host, port) = splitHostPort(text, SmartDnsProtocol.PLAIN.defaultPort) ?: return null
        if (!isIpv4(host) || !isUsableUnicast(host)) return null
        return SmartDnsServer(SmartDnsProtocol.PLAIN, host, port)
    }

    /** `dns.example.com`, `tls://dns.example.com:853`, `1.1.1.1`. */
    private fun parseDot(entry: String): SmartDnsServer? {
        val text = entry.lowercase().removePrefix("tls://").trimEnd('/')
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
