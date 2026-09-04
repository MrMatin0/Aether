package studio.cluvex.aether.core

/**
 * A parsed `host:port` authority.
 *
 * WHY THIS EXISTS
 * ---------------
 * The HTTP side of [ShareBridge] used to pull a CONNECT target apart with
 * `target.substringBeforeLast(':')` / `target.substringAfterLast(':')`. That
 * mis-handles every interesting shape:
 *
 *  - `example.com` (no port at all): both halves return the WHOLE string, so
 *    the host was right by accident and the port silently became the default.
 *  - `[::1]:8443` (bracketed IPv6): the last colon is inside the literal, so
 *    the host came out as `[::1]` with a garbage port, or vice versa.
 *  - `example.com:99999`: `toIntOrNull()` happily returns 99999, which is then
 *    written into the SOCKS5 request as two bytes - i.e. the request is sent
 *    to port 34463 instead, and the failure is reported as the upstream's
 *    fault.
 *
 * So the authority is parsed ONCE, here, and a value that cannot be put on the
 * wire is rejected instead of being quietly rewritten.
 */
data class HostPort(val host: String, val port: Int) {

    /** `host:port`, with an IPv6 literal re-bracketed so it round-trips. */
    fun authority(): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535

        /**
         * Parses [authority], falling back to [defaultPort] when no port is
         * present. Returns null when the value is not something this proxy can
         * faithfully forward.
         */
        fun parse(authority: String, defaultPort: Int): HostPort? {
            val text = authority.trim()
            if (text.isEmpty()) return null

            // Bracketed IPv6 literal: `[::1]` or `[::1]:8443`.
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                // `close <= 1` covers both a missing bracket and an empty one.
                if (close <= 1) return null
                val host = text.substring(1, close)
                if (!host.contains(':')) return null
                val rest = text.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                    else -> return null
                }
                return build(host, port)
            }

            val colon = text.lastIndexOf(':')
            // An UNBRACKETED IPv6 literal is ambiguous by construction - there
            // is no way to tell `::1` (host, no port) from `a:1` (host + port).
            // Refusing it beats guessing and dialling the wrong thing.
            if (colon >= 0 && text.indexOf(':') != colon) return null
            if (colon < 0) return build(text, defaultPort)

            val host = text.substring(0, colon)
            val portText = text.substring(colon + 1)
            if (portText.isEmpty()) return build(host, defaultPort)
            val port = portText.toIntOrNull() ?: return null
            return build(host, port)
        }

        private fun build(host: String, port: Int): HostPort? {
            if (port < MIN_PORT || port > MAX_PORT) return null
            if (host.isEmpty()) return null
            // A host carrying whitespace, a path separator or userinfo is not a
            // host; forwarding it would splice attacker text into the request
            // we build for the upstream proxy.
            if (host.any { it.isWhitespace() || it == '/' || it == '@' || it == '\\' }) return null
            return HostPort(host, port)
        }
    }
}
