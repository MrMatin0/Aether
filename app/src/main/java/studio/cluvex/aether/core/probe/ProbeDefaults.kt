package studio.cluvex.aether.core.probe

/**
 * Every value the probe layer shares, in one place.
 *
 * These addresses and timeouts used to be inline literals spread over
 * NetProbe, PingMonitor, PortProbe, SmartAuto and Diagnostics, which is how
 * "the latency probe" (1.1.1.1:53), "the TCP self-test" (1.1.1.1:80) and "the
 * TLS fingerprint" (1.1.1.1:443) ended up looking like three unrelated
 * decisions instead of three ports on the same deliberately chosen anycast
 * host.
 */
internal object ProbeDefaults {

    /**
     * Cloudflare's anycast resolver: reachable on effectively every network
     * this app runs on, and the same host for the UDP, TCP, TLS and latency
     * probes so a difference between them means something.
     */
    const val ANYCAST_RESOLVER_IP = "1.1.1.1"

    /** Second opinion for the UDP probe, so one filtered resolver is not a verdict. */
    const val SECONDARY_RESOLVER_IP = "8.8.8.8"

    const val DNS_PORT = 53
    const val HTTP_PORT = 80
    const val HTTPS_PORT = 443

    /** SNI presented by the TLS fingerprint probe (see [TlsProbe]). */
    const val TLS_SNI_PROBE_HOST = "www.cloudflare.com"

    /** One latency measurement is one TCP handshake with a hard ceiling. */
    const val LATENCY_TIMEOUT_MS = 5_000

    /**
     * Timeout for a LOCALHOST connect. Polling the engine's own SOCKS5 port is
     * either instant or it is not listening, so the generous 800 ms default
     * only stretched the documented poll cadence.
     */
    const val LOCAL_PORT_TIMEOUT_MS = 250

    /**
     * Hard ceiling on a geolocation response.
     *
     * Two of the three providers are probed over PLAIN HTTP, on networks whose
     * hostility is the entire reason this app exists, so "the endpoint sends a
     * well-behaved 200 bytes of JSON" is an assumption an on-path attacker gets
     * to break for free. The real payloads are a few hundred bytes; 64 KB is
     * already absurdly generous, and anything past it is discarded rather than
     * buffered.
     */
    const val MAX_HTTP_RESPONSE_BYTES = 64 * 1024
}
