package studio.cluvex.aether.core.tunnel

/**
 * Every timeout, retry budget and buffer size the bridge depends on.
 *
 * These were scattered as raw literals through the old file — `2` seconds here,
 * `300000` there, `120000` somewhere else — with no way to tell which ones were
 * load-bearing. Each one now says WHY it has the value it has.
 */
internal object Tunables {

    /** How long the TUN reader waits for the engine's local SOCKS5 listener. */
    const val CORE_WAIT_ATTEMPTS = 25
    const val CORE_WAIT_INTERVAL_MS = 200L
    const val CORE_PROBE_TIMEOUT_MS = 200

    /** Give up on the TUN fd after this many consecutive read errors. */
    const val MAX_CONSECUTIVE_TUN_ERRORS = 20
    const val TUN_ERROR_BACKOFF_MS = 50L

    /**
     * Recycle threshold for a session whose client SYN never led to a connection.
     * Deliberately ABOVE the client's first SYN RTO (~1 s): the deferred SYN/ACK
     * path can legitimately spend longer than that dialling upstream on high-RTT
     * links, and killing the session mid-dial just restarts the same dial from
     * scratch — repeatedly, until the client gives up entirely.
     */
    const val STALE_UNCONNECTED_MS = 3_000L

    /** Read timeout covering the SOCKS5 handshake phase of a dial. */
    const val DIAL_READ_TIMEOUT_MS = 10_000
    const val DIAL_CONNECT_TIMEOUT_MS = 5_000

    /**
     * How long a session waits for the client's first segment when it needs the
     * TLS SNI / HTTP Host header to classify the flow. A client that has just
     * been given a SYN/ACK sends its request within one loopback round trip, so
     * this is a safety net, not a budget — and it is only ever reached when
     * domain rules are actually configured.
     */
    const val SNIFF_WAIT_MS = 400L

    /** Idle reaper windows. TCP is generous; UDP flows are short-lived. */
    const val TCP_IDLE_TIMEOUT_MS = 300_000L
    const val UDP_IDLE_TIMEOUT_MS = 120_000L

    /** How often a session loop wakes up to check its own liveness. */
    const val SESSION_POLL_SECONDS = 2L

    const val UDP_RECEIVE_TIMEOUT_MS = 10_000

    /** Socket tuning for the upstream leg. */
    const val UPSTREAM_SOCKET_BUFFER = 262_144
    const val UPSTREAM_WRITE_BUFFER = 131_072
    const val UPSTREAM_READ_BUFFER = 131_072

    /** Bounded queues per session, so one stalled flow cannot eat the heap. */
    const val TCP_QUEUE_CAPACITY = 8_192
    const val UDP_QUEUE_CAPACITY = 2_048

    /** Segments coalesced into one upstream write before flushing. */
    const val UPSTREAM_WRITE_BATCH = 64
}