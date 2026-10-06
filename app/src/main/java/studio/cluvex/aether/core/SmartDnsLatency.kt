package studio.cluvex.aether.core

import studio.cluvex.aether.model.SmartDnsServer

/**
 * The "Test" button next to a Smart DNS server: how long a REAL question takes
 * to come back from it, straight from this phone.
 *
 * Not an ICMP ping. A resolver that answers ping but refuses DNS (or the other
 * way round) is common, and what the user wants to know is whether the server
 * resolves. So this asks it for [PROBE_NAME] - a name every sanctions DNS
 * provider covers - over the server's own transport (UDP, DoH or DoT), with the
 * same verified TLS and the same answer checks the live session uses
 * ([SmartDnsResolver]).
 *
 * Two questions are asked: the first pays for the TCP + TLS handshake, the
 * second reuses the pooled connection. The smaller of the two is reported, so
 * an encrypted server is not shown as three times slower than it is in use.
 *
 * Always on the DIRECT path (the app's own sockets are outside the VPN), so a
 * test works with the tunnel down. Blocking: call it off the main thread.
 */
object SmartDnsLatency {

    private const val PROBE_NAME = "gemini.google.com"
    private const val BUDGET_MS = 6_000L
    private const val COLD_ID = 0x5A11
    private const val WARM_ID = 0x5A12

    /** Milliseconds for one answered question, or null when [server] did not answer. */
    fun measure(server: SmartDnsServer): Long? {
        val resolver = SmartDnsResolver(
            servers = listOf(server),
            upstreamHost = TunnelConfig.SOCKS_HOST,
            upstreamPort = 1,
            autoFailover = false,
        )
        try {
            val cold = timed(resolver, COLD_ID) ?: return null
            val warm = timed(resolver, WARM_ID)
            return if (warm != null) minOf(cold, warm) else cold
        } finally {
            resolver.close()
        }
    }

    private fun timed(resolver: SmartDnsResolver, id: Int): Long? {
        val query = DnsMessage.buildQuery(PROBE_NAME, DnsMessage.TYPE_A, id) ?: return null
        val started = System.nanoTime()
        resolver.resolveOn(SmartDnsPath.DIRECT, query, BUDGET_MS) ?: return null
        return ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
    }
}
