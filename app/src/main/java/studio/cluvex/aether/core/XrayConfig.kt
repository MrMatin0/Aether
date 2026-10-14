package studio.cluvex.aether.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

/**
 * ONE proxy outbound for the Xray hop, read out of whatever the user pasted
 * ([XrayLink.parse]).
 *
 * [outbound] is the Xray outbound object itself (protocol, settings,
 * streamSettings) WITHOUT a tag and without any dialer proxy: how it is chained
 * is decided per session by [XrayConfig.build], never by the pasted text.
 */
data class XrayOutbound(
    /** `vless`, `vmess`, `trojan`, `shadowsocks`, `hysteria`, `wireguard`, `socks`, ... */
    val protocol: String,
    val address: String,
    val port: Int,
    /** Transport: `tcp`, `ws`, `grpc`, `xhttp`, `httpupgrade`, `kcp`, `hysteria`, `udp`. */
    val network: String,
    /** `none`, `tls` or `reality`. */
    val security: String,
    /** The link's `#name` (or a JSON outbound's tag); may be empty. */
    val remark: String,
    val outbound: Map<String, Any?>,
    /**
     * True for a VLESS (without VLESS Encryption) or Trojan outbound with no
     * TLS / REALITY to a PUBLIC address. Xray-core refuses to dial those, so
     * [XrayConfig.build] points the outbound at the on-device relay
     * ([TunnelConfig.XRAY_RELAY_PORT]) and lets the relay reach [address].
     */
    val relayed: Boolean = false,
) {
    /**
     * "VLESS · reality · tcp · 203.0.113.10:443", for the Chain page and the
     * diagnostics log. Carries no secret: no UUID, password or key.
     */
    val summary: String
        get() = buildList {
            add(protocolLabel(protocol))
            if (security.isNotEmpty() && security != "none") add(security)
            if (network.isNotEmpty()) add(network)
            if (address.isNotEmpty()) add(if (address.contains(':')) "[$address]:$port" else "$address:$port")
        }.joinToString(" \u00B7 ")

    private companion object {
        fun protocolLabel(protocol: String): String = when (protocol) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "shadowsocks" -> "Shadowsocks"
            "socks" -> "SOCKS"
            "http" -> "HTTP"
            "wireguard" -> "WireGuard"
            "hysteria" -> "Hysteria2"
            else -> protocol.uppercase(Locale.ROOT)
        }
    }
}

/**
 * Reads the user's Xray config: share links (`vless://`, `vmess://`,
 * `trojan://`, `ss://`, `hy2://`, `wireguard://`, `socks://`), a base64
 * subscription body holding such links, or Xray JSON (comments allowed) - a
 * single outbound object, an array of them, or a full config.
 *
 * THE FIRST ENTRY THIS APP CAN RUN is used: every line of a paste or
 * subscription, and every proxy outbound of a JSON config, is tried in order,
 * so one link this app cannot run does not hide the working one below it.
 *
 * Pure Kotlin, no Android, no network: everything is checked as text, and
 * anything that would only fail later inside Xray (a REALITY link without its
 * public key, an SS link that needs a SIP003 plugin Xray cannot run) is refused
 * here, where the Chain page can say so.
 *
 * ### What the pinned core refuses (XTLS/Xray-core v26.9.30)
 *
 * Xray stopped accepting several things that older share links still carry,
 * and it refuses the WHOLE config over any one of them, so they are handled
 * here instead of surfacing as "config rejected" at connect time:
 *
 *  - `allowInsecure` is a removed feature (a hard error). It is never written;
 *    a link's `pcs` (`pinnedPeerCertSha256`) and `vcn`
 *    (`verifyPeerCertByName`) are what replace it, and a pasted JSON outbound
 *    has the key dropped. A server with a self-signed certificate therefore
 *    needs `pcs` in its link to connect.
 *  - The HTTP/2 (`http` / `h2` / `h3`) and QUIC transports are removed
 *    (migrated to XHTTP), so such configs are refused.
 *  - VLESS without VLESS Encryption, and Trojan, are refused WITHOUT TLS or
 *    REALITY unless the server is a private address. Such an outbound is
 *    marked [XrayOutbound.relayed] and run through the on-device relay (see
 *    [XrayConfig]), whose address IS private.
 */
object XrayLink {

    /** Nobody pastes a 64 KB proxy config by accident; refuse rather than parse it. */
    const val MAX_LENGTH = 64 * 1024

    private val PROXY_PROTOCOLS =
        setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria")

    /** Transports the pinned Xray-core no longer has (see the class KDoc). */
    private val REMOVED_NETWORKS = setOf("http", "h2", "h3", "quic")

    /** The outbound [text] describes, or null when it is not a config this app can run. */
    fun parse(text: String): XrayOutbound? = parse(text, allowSubscription = true)

    private fun parse(text: String, allowSubscription: Boolean): XrayOutbound? {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[") ||
            trimmed.startsWith("//") || trimmed.startsWith("/*")
        ) {
            return fromJson(trimmed)
        }
        val links = trimmed.lineSequence().map { it.trim() }.filter { it.contains("://") }.toList()
        if (links.isEmpty()) {
            // A subscription body is base64 of the links, one per line.
            if (!allowSubscription) return null
            val decoded = base64(trimmed) ?: return null
            return parse(decoded, allowSubscription = false)
        }
        return links.firstNotNullOfOrNull(::link)
    }

    /** One share link, or null when it is not one this app can run. */
    private fun link(line: String): XrayOutbound? {
        val scheme = line.substringBefore("://").lowercase(Locale.ROOT)
        return try {
            when (scheme) {
                "vless" -> vless(line)
                "vmess" -> vmess(line)
                "trojan" -> trojan(line)
                "ss" -> shadowsocks(line)
                "hy2", "hysteria2" -> hysteria2(line)
                "wireguard", "wg" -> wireguard(line)
                "socks", "socks5" -> socks(line)
                else -> null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Whether Xray will run a VLESS / Trojan outbound with this [security] and
     * VLESS [encryption] to [address]: TLS or REALITY, VLESS Encryption, or a
     * private server address (Xray's `validateOutboundTransportSecurity`).
     */
    private fun transportSecured(security: String, encryption: String, address: String): Boolean {
        val sec = security.trim().lowercase(Locale.ROOT)
        if (sec.isNotEmpty() && sec != "none") return true
        val enc = encryption.trim().lowercase(Locale.ROOT)
        if (enc.isNotEmpty() && enc != "none") return true
        return isPrivateAddress(address)
    }

    /**
     * Loopback, RFC 1918, link-local and unique-local literals, and
     * `localhost`. Deliberately NARROWER than Xray's own list: relaying a
     * config Xray would have run directly costs one loopback hop, dialling one
     * Xray refuses costs a connect attempt that can only fail.
     */
    internal fun isPrivateAddress(address: String): Boolean {
        val host = address.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        ipv4(host)?.let { (a, b) ->
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }
        if (':' !in host) return false
        if (host == "::1") return true
        val first = host.substringBefore(':').padStart(4, '0')
        val group = first.toIntOrNull(16) ?: return false
        // fc00::/7 (unique local) and fe80::/10 (link-local).
        return (group and 0xFE00) == 0xFC00 || (group and 0xFFC0) == 0xFE80
    }

    /** The first two octets of a dotted-quad IPv4 literal, or null for anything else. */
    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
        return octets[0] to octets[1]
    }

    // ---------------------------------------------------------------- links

    private fun vless(link: String): XrayOutbound? {
        val parts = LinkParts.of(link) ?: return null
        val id = parts.user.trim()
        if (id.isEmpty()) return null
        val stream = Stream.of(parts.query, defaultSecurity = "none")
        if (!stream.isComplete) return null
        val encryption = parts.query["encryption"].orEmpty().trim().ifEmpty { "none" }
        val relayed = !transportSecured(stream.sec, encryption, parts.host)
        val user = linkedMapOf<String, Any?>(
            "id" to id,
            "encryption" to encryption,
        )
        parts.query["flow"]?.takeIf { it.isNotBlank() }?.let { user["flow"] = it.trim() }
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "vless",
            "settings" to linkedMapOf<String, Any?>(
                "vnext" to listOf(
                    linkedMapOf<String, Any?>("address" to parts.host, "port" to parts.port, "users" to listOf(user)),
                ),
            ),
            "streamSettings" to stream.toMap(),
        )
        return XrayOutbound(
            "vless", parts.host, parts.port, stream.net, stream.sec, parts.fragment, outbound, relayed,
        )
    }

    private fun trojan(link: String): XrayOutbound? {
        val parts = LinkParts.of(link) ?: return null
        val password = parts.user
        if (password.isEmpty()) return null
        val stream = Stream.of(parts.query, defaultSecurity = "tls")
        if (!stream.isComplete) return null
        val relayed = !transportSecured(stream.sec, "none", parts.host)
        val server = linkedMapOf<String, Any?>("address" to parts.host, "port" to parts.port, "password" to password)
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "trojan",
            "settings" to linkedMapOf<String, Any?>("servers" to listOf(server)),
            "streamSettings" to stream.toMap(),
        )
        return XrayOutbound(
            "trojan", parts.host, parts.port, stream.net, stream.sec, parts.fragment, outbound, relayed,
        )
    }

    /** `vmess://` + base64(JSON), the v2rayN spelling; falls back to the URI spelling. */
    private fun vmess(link: String): XrayOutbound? {
        val body = link.substringAfter("://").substringBefore('#').trim()
        val json = base64(body)?.let { XrayJson.parseOrNull(it) } as? Map<*, *>
            ?: return vmessUri(link)

        fun field(key: String): String {
            val raw = when (val v = json[key]) {
                null -> ""
                is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                else -> v.toString()
            }
            return raw.trim()
        }

        val host = field("add")
        val port = field("port").toIntOrNull() ?: return null
        val id = field("id")
        if (host.isEmpty() || id.isEmpty() || port !in 1..65_535) return null
        val net = field("net").ifEmpty { "tcp" }.lowercase(Locale.ROOT)
        val header = field("type").takeUnless { it.equals("none", ignoreCase = true) }.orEmpty()
        val tls = field("tls").lowercase(Locale.ROOT)
        val stream = Stream(
            network = net,
            security = if (tls == "tls" || tls == "reality") tls else "none",
            host = field("host"),
            path = if (net == "kcp" || net == "grpc") "" else field("path"),
            sni = field("sni"),
            fingerprint = field("fp"),
            alpn = field("alpn"),
            publicKey = field("pbk"),
            shortId = field("sid"),
            spiderX = field("spx"),
            serviceName = if (net == "grpc") field("path") else "",
            mode = if (net == "grpc" && header.equals("multi", ignoreCase = true)) "multi" else field("mode"),
            headerType = if (net == "grpc") "" else header,
            seed = if (net == "kcp") field("path") else "",
            pinnedSha256 = field("pcs"),
            verifyName = field("vcn"),
            ech = field("ech"),
        )
        if (!stream.isComplete) return null
        val user = linkedMapOf<String, Any?>("id" to id, "security" to field("scy").ifEmpty { "auto" })
        field("aid").toIntOrNull()?.takeIf { it > 0 }?.let { user["alterId"] = it }
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "vmess",
            "settings" to linkedMapOf<String, Any?>(
                "vnext" to listOf(linkedMapOf<String, Any?>("address" to host, "port" to port, "users" to listOf(user))),
            ),
            "streamSettings" to stream.toMap(),
        )
        return XrayOutbound("vmess", host, port, stream.net, stream.sec, field("ps"), outbound)
    }

    /** The other vmess spelling: `vmess://uuid@host:port?type=ws&security=tls#name`. */
    private fun vmessUri(link: String): XrayOutbound? {
        val parts = LinkParts.of(link) ?: return null
        val id = parts.user.trim()
        if (id.isEmpty()) return null
        val stream = Stream.of(parts.query, defaultSecurity = "none")
        if (!stream.isComplete) return null
        val user = linkedMapOf<String, Any?>(
            "id" to id,
            "security" to parts.query["encryption"].orEmpty().ifEmpty { "auto" },
        )
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "vmess",
            "settings" to linkedMapOf<String, Any?>(
                "vnext" to listOf(
                    linkedMapOf<String, Any?>("address" to parts.host, "port" to parts.port, "users" to listOf(user)),
                ),
            ),
            "streamSettings" to stream.toMap(),
        )
        return XrayOutbound("vmess", parts.host, parts.port, stream.net, stream.sec, parts.fragment, outbound)
    }

    /**
     * `ss://`, in all three spellings: SIP002 with base64url(method:password),
     * SIP002 with a percent-encoded method:password (2022 ciphers), and the
     * legacy base64(method:password@host:port).
     */
    private fun shadowsocks(link: String): XrayOutbound? {
        val body = link.substringAfter("://")
        val remark = pct(body.substringAfter('#', ""))
        val main = body.substringBefore('#')
        val query = main.substringAfter('?', "")
        // SIP003 plugins (obfs-local, v2ray-plugin) are separate programs Xray cannot run.
        val needsPlugin = query.split('&').any { pair ->
            pair.substringBefore('=').equals("plugin", ignoreCase = true) && pair.substringAfter('=', "").isNotBlank()
        }
        if (needsPlugin) return null
        val head = main.substringBefore('?')
        val method: String
        val password: String
        val endpoint: String
        val at = head.lastIndexOf('@')
        if (at > 0) {
            val info = head.substring(0, at)
            endpoint = head.substring(at + 1).trimEnd('/')
            val plain = pct(info)
            val credentials = if (':' in plain) plain else base64(info) ?: return null
            method = credentials.substringBefore(':')
            password = credentials.substringAfter(':', "")
        } else {
            val decoded = base64(head) ?: base64(head.trimEnd('/')) ?: return null
            val split = decoded.lastIndexOf('@')
            if (split <= 0) return null
            val credentials = decoded.substring(0, split)
            method = credentials.substringBefore(':')
            password = credentials.substringAfter(':', "")
            endpoint = decoded.substring(split + 1).trim()
        }
        val (host, port) = hostPort(endpoint) ?: return null
        if (method.isBlank() || password.isEmpty()) return null
        val server = linkedMapOf<String, Any?>(
            "address" to host,
            "port" to port,
            "method" to method.trim().lowercase(Locale.ROOT),
            "password" to password,
        )
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "shadowsocks",
            "settings" to linkedMapOf<String, Any?>("servers" to listOf(server)),
        )
        return XrayOutbound("shadowsocks", host, port, "tcp", "none", remark, outbound)
    }

    /**
     * `hy2://auth@host:port/?sni=...&pinSHA256=...#name` (also `hysteria2://`),
     * as Xray's Hysteria2 outbound: protocol `hysteria` version 2, transport
     * `hysteria`, always TLS with ALPN h3.
     *
     * Refused: Salamander `obfs` (not carried by this outbound) and a port
     * range (`mport` / `443,20000-30000` port hopping). `insecure=1` is NOT
     * honoured - the pinned core has no allowInsecure - so a self-signed
     * server needs `pinSHA256` (or `pcs`).
     */
    private fun hysteria2(link: String): XrayOutbound? {
        val parts = LinkParts.of(link) ?: return null
        val auth = parts.user
        if (auth.isEmpty()) return null
        val q = parts.query
        val obfs = q["obfs"].orEmpty().trim()
        if (obfs.isNotEmpty() && !obfs.equals("none", ignoreCase = true)) return null
        val sni = q["sni"].orEmpty().trim().ifEmpty { q["peer"].orEmpty().trim() }
        val pin = q["pinSHA256"].orEmpty().ifEmpty { q["pcs"].orEmpty() }
            .replace(":", "").trim().lowercase(Locale.ROOT)
        val tls = linkedMapOf<String, Any?>()
        if (sni.isNotEmpty()) tls["serverName"] = sni
        tls["alpn"] = listOf("h3")
        if (pin.isNotEmpty()) tls["pinnedPeerCertSha256"] = pin
        q["vcn"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tls["verifyPeerCertByName"] = it }
        q["ech"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tls["echConfigList"] = it }
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "hysteria",
            "settings" to linkedMapOf<String, Any?>(
                "version" to 2,
                "address" to parts.host,
                "port" to parts.port,
            ),
            "streamSettings" to linkedMapOf<String, Any?>(
                "network" to "hysteria",
                "security" to "tls",
                "tlsSettings" to tls,
                "hysteriaSettings" to linkedMapOf<String, Any?>("version" to 2, "auth" to auth),
            ),
        )
        return XrayOutbound("hysteria", parts.host, parts.port, "hysteria", "tls", parts.fragment, outbound)
    }

    /**
     * `wireguard://<private key>@host:port?publickey=...&address=...&reserved=a,b,c&mtu=...#name`
     * (also `wg://`), as Xray's WireGuard outbound. The private key may carry a
     * raw '/' (see [LinkParts.of]); `address` is optional (Xray has a default).
     */
    private fun wireguard(link: String): XrayOutbound? {
        val parts = LinkParts.of(link) ?: return null
        val secret = parts.user.trim()
        val q = parts.query.mapKeys { it.key.lowercase(Locale.ROOT) }
        val publicKey = q["publickey"].orEmpty().trim().ifEmpty { q["peerpublickey"].orEmpty().trim() }
        if (secret.isEmpty() || publicKey.isEmpty()) return null
        val endpoint = if (':' in parts.host) "[${parts.host}]:${parts.port}" else "${parts.host}:${parts.port}"
        val peer = linkedMapOf<String, Any?>("publicKey" to publicKey, "endpoint" to endpoint)
        q["presharedkey"]?.trim()?.takeIf { it.isNotEmpty() }?.let { peer["preSharedKey"] = it }
        val settings = linkedMapOf<String, Any?>("secretKey" to secret)
        val addresses = (q["address"] ?: q["ip"]).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (addresses.isNotEmpty()) settings["address"] = addresses
        settings["peers"] = listOf(peer)
        q["mtu"]?.trim()?.toIntOrNull()?.takeIf { it in 576..65_535 }?.let { settings["mtu"] = it }
        val reserved = q["reserved"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { byte -> byte.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
        if (reserved.isNotEmpty()) {
            if (reserved.size != 3) return null
            settings["reserved"] = reserved
        }
        val outbound = linkedMapOf<String, Any?>("protocol" to "wireguard", "settings" to settings)
        return XrayOutbound("wireguard", parts.host, parts.port, "udp", "none", parts.fragment, outbound)
    }

    /**
     * `socks://base64(user:pass)@host:port#name` (v2rayN), `socks5://user:pass@host:port`,
     * or `socks://host:port` with no credentials.
     */
    private fun socks(link: String): XrayOutbound? {
        val rest = link.substringAfter("://")
        val remark = pct(rest.substringAfter('#', ""))
        val authority = rest.substringBefore('#').substringBefore('?').trimEnd('/')
        val at = authority.lastIndexOf('@')
        val (host, port) = hostPort(if (at >= 0) authority.substring(at + 1) else authority) ?: return null
        val server = linkedMapOf<String, Any?>("address" to host, "port" to port)
        if (at > 0) {
            val info = authority.substring(0, at)
            val plain = pct(info)
            val credentials = if (':' in plain) plain else base64(info) ?: return null
            if (':' !in credentials) return null
            val user = credentials.substringBefore(':')
            val pass = credentials.substringAfter(':', "")
            if (user.isNotEmpty()) {
                server["users"] = listOf(linkedMapOf<String, Any?>("user" to user, "pass" to pass))
            }
        }
        val outbound = linkedMapOf<String, Any?>(
            "protocol" to "socks",
            "settings" to linkedMapOf<String, Any?>("servers" to listOf(server)),
        )
        return XrayOutbound("socks", host, port, "tcp", "none", remark, outbound)
    }

    // ----------------------------------------------------------------- JSON

    /** The first proxy outbound in [text] that this app can run. */
    private fun fromJson(text: String): XrayOutbound? {
        val roots: List<Map<*, *>> = when (val root = XrayJson.parseOrNull(text)) {
            is List<*> -> root.mapNotNull { it as? Map<*, *> }
            is Map<*, *> -> listOf(root)
            else -> return null
        }
        return roots.asSequence().flatMap(::proxyOutbounds).firstNotNullOfOrNull(::describe)
    }

    /** [obj] itself when it IS a proxy outbound, else every proxy outbound of a full config. */
    private fun proxyOutbounds(obj: Map<*, *>): Sequence<Map<*, *>> {
        val protocol = obj["protocol"]
        if (protocol is String) {
            return if (protocol.lowercase(Locale.ROOT) in PROXY_PROTOCOLS) sequenceOf(obj) else emptySequence()
        }
        val outbounds = obj["outbounds"] as? List<*> ?: return emptySequence()
        return outbounds.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .filter { ((it["protocol"] as? String)?.lowercase(Locale.ROOT)) in PROXY_PROTOCOLS }
    }

    private fun describe(source: Map<*, *>): XrayOutbound? {
        val outbound = XrayJson.copyObject(source)
        // How this outbound is chained is decided per session, never by the paste.
        outbound.remove("tag")
        outbound.remove("proxySettings")
        val protocol = (outbound["protocol"] as? String)?.lowercase(Locale.ROOT) ?: return null
        outbound["protocol"] = protocol
        val settings = outbound["settings"] as? Map<*, *>
        val first = ((settings?.get("vnext") ?: settings?.get("servers") ?: settings?.get("peers")) as? List<*>)
            ?.firstOrNull() as? Map<*, *>
        var address = (first?.get("address") ?: settings?.get("address"))?.toString().orEmpty()
        var port = portOf(first?.get("port") ?: settings?.get("port"))
        if (address.isEmpty()) {
            (first?.get("endpoint") as? String)?.let(::hostPort)?.let { (host, endpointPort) ->
                address = host
                port = endpointPort
            }
        }
        val stream = (outbound["streamSettings"] as? Map<*, *>)?.let(XrayJson::copyObject)
        if (stream != null) {
            // A removed feature is a hard error for the WHOLE config; dropping
            // the key lets the rest run (see the class KDoc).
            (stream["tlsSettings"] as? Map<*, *>)?.let { tls ->
                stream["tlsSettings"] = XrayJson.copyObject(tls).apply { remove("allowInsecure") }
            }
            outbound["streamSettings"] = stream
        }
        val network = (stream?.get("network") as? String)?.lowercase(Locale.ROOT) ?: "tcp"
        if (network in REMOVED_NETWORKS) return null
        val security = (stream?.get("security") as? String)?.lowercase(Locale.ROOT) ?: "none"
        val relayed = when (protocol) {
            "vless" -> {
                val user = (first?.get("users") as? List<*>)?.firstOrNull() as? Map<*, *>
                val encryption = (user?.get("encryption") ?: settings?.get("encryption"))?.toString().orEmpty()
                !transportSecured(security, encryption, address)
            }
            "trojan" -> !transportSecured(security, "none", address)
            else -> false
        }
        // The relay needs somewhere to forward to.
        if (relayed && (address.isEmpty() || port !in 1..65_535)) return null
        val remark = (source["tag"] as? String).orEmpty()
        return XrayOutbound(protocol, address, port, network, security, remark, outbound, relayed)
    }

    private fun portOf(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: 0
        else -> 0
    }

    // -------------------------------------------------------------- pieces

    /** `user@host:port?query#fragment`, split without java.net.URI's strictness. */
    private class LinkParts(
        val user: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    ) {
        companion object {
            fun of(link: String): LinkParts? {
                val rest = link.substringAfter("://")
                val fragment = pct(rest.substringAfter('#', ""))
                val noFragment = rest.substringBefore('#')
                val query = parseQuery(noFragment.substringAfter('?', ""))
                val beforeQuery = noFragment.substringBefore('?')
                // `user@host:port[/path]`. A raw '/' in the user part (a
                // WireGuard key, a Shadowsocks password) cuts the first try
                // short, so the whole span before the query is tried next.
                return split(beforeQuery.substringBefore('/'), query, fragment)
                    ?: split(beforeQuery, query, fragment)
            }

            private fun split(span: String, query: Map<String, String>, fragment: String): LinkParts? {
                val at = span.lastIndexOf('@')
                if (at <= 0) return null
                val (host, port) = hostPort(span.substring(at + 1).substringBefore('/')) ?: return null
                return LinkParts(pct(span.substring(0, at)), host, port, query, fragment)
            }
        }
    }

    private fun parseQuery(text: String): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in text.split('&')) {
            if (pair.isEmpty()) continue
            val key = pct(pair.substringBefore('='))
            if (key.isEmpty()) continue
            out[key] = pct(pair.substringAfter('=', ""))
        }
        return out
    }

    /** `host:port` or `[v6]:port`; null for a missing / bad port or an unbracketed IPv6. */
    private fun hostPort(text: String): Pair<String, Int>? {
        val value = text.trim()
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return null
            host = value.substring(1, end)
            portText = value.substring(end + 1).removePrefix(":")
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0) return null
            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
            if (host.contains(':')) return null
        }
        val port = portText.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65_535) return null
        if (host.any { it.isWhitespace() || it == '/' || it == '@' || it == '?' || it == '#' }) return null
        return host to port
    }

    /** Percent-decoding as UTF-8. Unlike URLDecoder, a '+' stays a '+' (passwords contain them). */
    private fun pct(text: String): String {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            if (c.isHighSurrogate() && i + 1 < text.length) {
                out.write(text.substring(i, i + 2).toByteArray(Charsets.UTF_8))
                i += 2
                continue
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Standard or URL-safe base64, padded or not; null when it is not base64 at all. */
    private fun base64(text: String): String? {
        val clean = text.trim().filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (clean.isEmpty()) return null
        val bare = clean.trimEnd('=').replace('+', '-').replace('/', '_')
        val padded = bare + "=".repeat((4 - bare.length % 4) % 4)
        val bytes = runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull() ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /** A link's transport + security, as Xray `streamSettings`. */
    private data class Stream(
        val network: String = "",
        val security: String = "",
        val host: String = "",
        val path: String = "",
        val sni: String = "",
        val fingerprint: String = "",
        val alpn: String = "",
        val publicKey: String = "",
        val shortId: String = "",
        val spiderX: String = "",
        val serviceName: String = "",
        val mode: String = "",
        val headerType: String = "",
        val seed: String = "",
        val authority: String = "",
        val extra: String = "",
        /** `pcs`: hex SHA-256 of the server's certificate(s), comma-separated. Replaces allowInsecure. */
        val pinnedSha256: String = "",
        /** `vcn`: the name(s) the certificate is verified against instead of the SNI. */
        val verifyName: String = "",
        /** `ech`: the ECHConfigList (or where Xray should look it up). */
        val ech: String = "",
        /** `pqv`: REALITY's ML-DSA-65 verification key. */
        val mldsa65Verify: String = "",
    ) {
        val net: String
            get() = when (val n = network.trim().lowercase(Locale.ROOT)) {
                "", "raw" -> "tcp"
                "splithttp" -> "xhttp"
                "h2" -> "http"
                "websocket" -> "ws"
                "gun" -> "grpc"
                "mkcp" -> "kcp"
                else -> n
            }

        val sec: String
            get() = security.trim().lowercase(Locale.ROOT).let {
                if (it.isEmpty() || it == "0" || it == "false") "none" else it
            }

        /**
         * REALITY cannot handshake without the server's public key, and a
         * transport the pinned Xray removed cannot run at all.
         */
        val isComplete: Boolean
            get() = (sec != "reality" || publicKey.isNotBlank()) && net !in REMOVED_NETWORKS

        fun toMap(): Map<String, Any?> {
            val out = linkedMapOf<String, Any?>("network" to net, "security" to sec)
            val hosts = host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val firstHost = hosts.firstOrNull().orEmpty()
            // Like every other client: a TLS server name defaults to the Host header.
            val serverName = sni.trim().ifEmpty { if (sec == "tls") firstHost else "" }
            when (sec) {
                "tls" -> {
                    val tls = linkedMapOf<String, Any?>()
                    if (serverName.isNotEmpty()) tls["serverName"] = serverName
                    if (fingerprint.isNotBlank()) tls["fingerprint"] = fingerprint.trim()
                    val alpnList = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (alpnList.isNotEmpty()) tls["alpn"] = alpnList
                    // Never allowInsecure: the pinned core refuses the config over it.
                    if (pinnedSha256.isNotBlank()) tls["pinnedPeerCertSha256"] = pinnedSha256.trim()
                    if (verifyName.isNotBlank()) tls["verifyPeerCertByName"] = verifyName.trim()
                    if (ech.isNotBlank()) tls["echConfigList"] = ech.trim()
                    out["tlsSettings"] = tls
                }
                "reality" -> {
                    val reality = linkedMapOf<String, Any?>()
                    if (serverName.isNotEmpty()) reality["serverName"] = serverName
                    reality["fingerprint"] = fingerprint.trim().ifEmpty { "chrome" }
                    reality["publicKey"] = publicKey.trim()
                    if (shortId.isNotBlank()) reality["shortId"] = shortId.trim()
                    if (spiderX.isNotBlank()) reality["spiderX"] = spiderX
                    if (mldsa65Verify.isNotBlank()) reality["mldsa65Verify"] = mldsa65Verify.trim()
                    out["realitySettings"] = reality
                }
            }
            when (net) {
                "ws" -> out["wsSettings"] = pathAndHost(firstHost)
                "httpupgrade" -> out["httpupgradeSettings"] = pathAndHost(firstHost)
                "xhttp" -> {
                    val xhttp = pathAndHost(firstHost)
                    xhttp["mode"] = mode.trim().ifEmpty { "auto" }
                    (XrayJson.parseOrNull(extra) as? Map<*, *>)?.let { xhttp["extra"] = XrayJson.copyObject(it) }
                    out["xhttpSettings"] = xhttp
                }
                "grpc" -> {
                    val grpc = linkedMapOf<String, Any?>("serviceName" to serviceName.ifEmpty { path }.trim())
                    if (mode.trim().equals("multi", ignoreCase = true)) grpc["multiMode"] = true
                    if (authority.isNotBlank()) grpc["authority"] = authority.trim()
                    out["grpcSettings"] = grpc
                }
                "kcp" -> {
                    val kcp = linkedMapOf<String, Any?>()
                    if (headerType.isNotBlank()) kcp["header"] = linkedMapOf<String, Any?>("type" to headerType.trim())
                    if (seed.isNotBlank()) kcp["seed"] = seed
                    out["kcpSettings"] = kcp
                }
                "tcp" -> if (headerType.trim().equals("http", ignoreCase = true)) {
                    out["tcpSettings"] = linkedMapOf<String, Any?>(
                        "header" to linkedMapOf<String, Any?>(
                            "type" to "http",
                            "request" to linkedMapOf<String, Any?>(
                                "path" to listOf(path.ifEmpty { "/" }),
                                "headers" to linkedMapOf<String, Any?>("Host" to hosts),
                            ),
                        ),
                    )
                }
            }
            return out
        }

        private fun pathAndHost(firstHost: String): LinkedHashMap<String, Any?> {
            val settings = linkedMapOf<String, Any?>("path" to path.ifEmpty { "/" })
            if (firstHost.isNotEmpty()) settings["host"] = firstHost
            return settings
        }

        companion object {
            fun of(q: Map<String, String>, defaultSecurity: String): Stream = Stream(
                network = q["type"].orEmpty().ifEmpty { q["net"].orEmpty() },
                security = q["security"].orEmpty().ifEmpty { defaultSecurity },
                host = q["host"].orEmpty(),
                path = q["path"].orEmpty(),
                sni = q["sni"].orEmpty().ifEmpty { q["peer"].orEmpty() },
                fingerprint = q["fp"].orEmpty(),
                alpn = q["alpn"].orEmpty(),
                publicKey = q["pbk"].orEmpty(),
                shortId = q["sid"].orEmpty(),
                spiderX = q["spx"].orEmpty(),
                serviceName = q["serviceName"].orEmpty().ifEmpty { q["servicename"].orEmpty() },
                mode = q["mode"].orEmpty(),
                headerType = q["headerType"].orEmpty().ifEmpty { q["headertype"].orEmpty() },
                seed = q["seed"].orEmpty(),
                authority = q["authority"].orEmpty(),
                extra = q["extra"].orEmpty(),
                pinnedSha256 = q["pcs"].orEmpty(),
                verifyName = q["vcn"].orEmpty(),
                ech = q["ech"].orEmpty(),
                mldsa65Verify = q["pqv"].orEmpty(),
            )
        }
    }
}

/**
 * The Xray config a session runs: ONE local SOCKS inbound, the user's outbound,
 * and the plumbing around it.
 *
 * ### Where Xray sits
 *
 * Xray is the INTERNET-FACING hop of every Xray mode (see
 * studio.cluvex.aether.model.ChainMode): it dials the user's server itself,
 * and Aether / Psiphon / Tor dial out through its inbound. So in practice
 * [build] is called with no `upstreamPort`. The parameter is kept, and still
 * pinned by a test: with one, whatever reaches the user's server gets
 * `streamSettings.sockopt.dialerProxy = "chain-up"`, a SOCKS outbound on that
 * hop's port.
 *
 * ### The relay ([XrayOutbound.relayed])
 *
 * Xray-core refuses to dial VLESS (without VLESS Encryption) or Trojan without
 * TLS / REALITY to a PUBLIC address, and accepts it to a private one. Such an
 * outbound is pointed at a `dokodemo-door` inbound on
 * 127.0.0.1:[TunnelConfig.XRAY_RELAY_PORT], whose own `freedom` outbound
 * forwards the bytes, unchanged, to the real server. What goes on the wire is
 * exactly what the link asked for; only the check is satisfied. With a hop
 * underneath, it is the relay's outbound that dials through it (the proxy then
 * only ever dials loopback). Nothing is bound on that port otherwise.
 *
 * ### DNS
 *
 * The device's DNS reaches Xray as SOCKS5 UDP to port 53. A routing rule hands
 * exactly that to a `dns` outbound, and Xray's own resolver answers it over TCP
 * through the proxy outbound. That works whatever the outbound's protocol,
 * which is why an Xray entry needs no [SocksFront].
 */
object XrayConfig {
    const val INBOUND_TAG = "socks-in"
    const val PROXY_TAG = "proxy"
    const val UPSTREAM_TAG = "chain-up"
    const val DNS_TAG = "dns-out"

    /** The relay's inbound (dokodemo-door) and outbound (freedom). */
    const val RELAY_TAG = "relay-in"
    const val RELAY_OUT_TAG = "relay-out"

    /** Loopback, i.e. a private address: what makes Xray accept a relayed outbound. */
    private const val RELAY_HOST = "127.0.0.1"

    fun build(
        outbound: XrayOutbound,
        socksPort: Int,
        upstreamPort: Int?,
        upstreamHost: String = "127.0.0.1",
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        relayPort: Int = TunnelConfig.XRAY_RELAY_PORT,
    ): String {
        val relayed = outbound.relayed
        val proxy = XrayJson.copyObject(outbound.outbound)
        proxy["tag"] = PROXY_TAG
        if (relayed) pointAt(proxy, RELAY_HOST, relayPort)
        // Whatever reaches the user's server is what dials through the hop
        // underneath: the proxy itself, or for a relayed config the relay's
        // outbound. Any dialerProxy in the paste is dropped either way.
        setDialer(proxy, if (upstreamPort != null && !relayed) UPSTREAM_TAG else null)

        val outbounds = mutableListOf<Any?>(proxy)
        if (relayed) {
            val direct = linkedMapOf<String, Any?>("tag" to RELAY_OUT_TAG, "protocol" to "freedom")
            setDialer(direct, if (upstreamPort != null) UPSTREAM_TAG else null)
            outbounds += direct
        }
        if (upstreamPort != null) {
            outbounds += linkedMapOf<String, Any?>(
                "tag" to UPSTREAM_TAG,
                "protocol" to "socks",
                "settings" to linkedMapOf<String, Any?>(
                    "servers" to listOf(linkedMapOf<String, Any?>("address" to upstreamHost, "port" to upstreamPort)),
                ),
            )
        }
        outbounds += linkedMapOf<String, Any?>("tag" to DNS_TAG, "protocol" to "dns")

        val inbounds = mutableListOf<Any?>(
            linkedMapOf<String, Any?>(
                "tag" to INBOUND_TAG,
                "listen" to "127.0.0.1",
                "port" to socksPort,
                "protocol" to "socks",
                "settings" to linkedMapOf<String, Any?>("auth" to "noauth", "udp" to true, "ip" to "127.0.0.1"),
                "sniffing" to linkedMapOf<String, Any?>(
                    "enabled" to true,
                    "destOverride" to listOf("http", "tls", "quic"),
                    "routeOnly" to true,
                ),
            ),
        )
        val rules = mutableListOf<Any?>(
            linkedMapOf<String, Any?>(
                "type" to "field",
                "inboundTag" to listOf(INBOUND_TAG),
                "network" to "udp",
                "port" to "53",
                "outboundTag" to DNS_TAG,
            ),
        )
        if (relayed) {
            inbounds += linkedMapOf<String, Any?>(
                "tag" to RELAY_TAG,
                "listen" to RELAY_HOST,
                "port" to relayPort,
                "protocol" to "dokodemo-door",
                "settings" to linkedMapOf<String, Any?>(
                    "address" to outbound.address,
                    "port" to outbound.port,
                    // mKCP is the one plaintext VLESS / Trojan transport over UDP.
                    "network" to if (outbound.network == "kcp") "udp" else "tcp",
                ),
            )
            rules += linkedMapOf<String, Any?>(
                "type" to "field",
                "inboundTag" to listOf(RELAY_TAG),
                "outboundTag" to RELAY_OUT_TAG,
            )
        }

        val config = linkedMapOf<String, Any?>(
            "log" to linkedMapOf<String, Any?>("loglevel" to "warning", "access" to "none"),
            "dns" to linkedMapOf<String, Any?>("servers" to dnsServers.map { "tcp://$it:53" }),
            "inbounds" to inbounds,
            "outbounds" to outbounds,
            "routing" to linkedMapOf<String, Any?>(
                "domainStrategy" to "AsIs",
                "rules" to rules,
            ),
        )
        return XrayJson.write(config)
    }

    /** Sets (or, with null, removes) `streamSettings.sockopt.dialerProxy` on [target]. */
    private fun setDialer(target: MutableMap<String, Any?>, dialer: String?) {
        val stream = (target["streamSettings"] as? Map<*, *>)?.let(XrayJson::copyObject) ?: LinkedHashMap()
        val sockopt = (stream["sockopt"] as? Map<*, *>)?.let(XrayJson::copyObject) ?: LinkedHashMap()
        sockopt.remove("dialerProxy")
        if (dialer != null) sockopt["dialerProxy"] = dialer
        if (sockopt.isNotEmpty()) stream["sockopt"] = sockopt else stream.remove("sockopt")
        if (stream.isNotEmpty()) target["streamSettings"] = stream else target.remove("streamSettings")
    }

    /**
     * Points the proxy outbound's (first) server at [host]:[port]: `vnext` /
     * `servers`, or the flat `settings.address` form. Only the relayed server
     * is kept: any other entry would be plaintext to a public address as well.
     */
    private fun pointAt(proxy: MutableMap<String, Any?>, host: String, port: Int) {
        val settings = (proxy["settings"] as? Map<*, *>)?.let(XrayJson::copyObject) ?: LinkedHashMap()
        val key = listOf("vnext", "servers").firstOrNull { settings[it] is List<*> }
        val first = key?.let { (settings[it] as? List<*>)?.firstOrNull() as? Map<*, *> }?.let(XrayJson::copyObject)
        if (key != null && first != null) {
            first["address"] = host
            first["port"] = port
            settings[key] = listOf(first)
        } else {
            settings["address"] = host
            settings["port"] = port
        }
        proxy["settings"] = settings
    }
}
