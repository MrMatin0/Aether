package studio.cluvex.aether.core

import java.util.Locale

/**
 * Psiphon alerts that LOOK like failures but are not, on Android or behind an
 * upstream proxy (a chain hop such as Xray).
 *
 * psiphon-tunnel-core reports all of these as Alert / Warning notices, so they
 * used to land in the diagnostics log as `W/psiphon`, right next to the lines
 * that actually explain a failed connect (`meek round trip failed: EOF`, which
 * is the upstream dropping the connection). Users and bug reports then chased
 * the noise. Each entry below says why it is harmless, and [PsiphonCore] logs
 * it as info with that reason attached.
 *
 * Pure Kotlin, no Android: unit-tested on the JVM against real log lines.
 */
internal object PsiphonNotices {

    /** Why [message] is harmless, or null when it is a real warning. */
    fun harmless(message: String): String? {
        val text = message.lowercase(Locale.ROOT)
        return when {
            // Android forbids non-system apps from reading the routing table
            // over netlink (RTM_GETROUTE). Psiphon only wanted to know whether
            // an IPv6 route exists; without the answer it simply prefers IPv4.
            "unable to determine ipv6 route" in text && "permission denied" in text ->
                "Android does not let apps read the routing table; Psiphon prefers IPv4"

            // Something opened the local SOCKS port and closed it without a
            // request. The app's own port check (PortProbe.awaitOpen) does
            // exactly that while it waits for the listener.
            "socks proxy accept error" in text && "eof" in text ->
                "a local port check connected and closed without a SOCKS request"

            // The untunneled discovery-server-list fetch goes through the
            // in-proxy broker, which psiphon-tunnel-core cannot dial through an
            // upstream proxy. It is optional: the tunnel does not depend on it.
            "dsl fetch" in text && "upstream proxy unsupported" in text ->
                "the optional untunneled server-list fetch cannot run through an upstream proxy"

            else -> null
        }
    }
}
