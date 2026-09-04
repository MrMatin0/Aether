package studio.cluvex.aether.core

import android.os.SystemClock
import java.util.Collections

/**
 * Short-lived IP -> domain cache filled from DNS answers observed on the TUN.
 * Merged into Aether; used by the userspace tunnel bridge so a flow to an
 * IP can be attributed to the domain that resolved to it.
 *
 * Both the keys and the values come off the wire, so [put] validates the domain
 * through [Hostname] before caching it: the cached value is later encoded into a
 * SOCKS5 ATYP=0x03 request whose length is a single byte, and a DNS answer is
 * not a trustworthy source of a well-formed name.
 */
object DnsMap {
    private data class Entry(
        val domain: String,
        val expiresAt: Long
    )

    private const val MAX_ENTRIES = 4096
    private const val MAX_DOMAINS_PER_IP = 8
    private const val DEFAULT_TTL_MILLIS = 300_000L
    private val ipToDomains = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableList<Entry>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Entry>>?): Boolean {
                return size > MAX_ENTRIES
            }
        }
    )

    fun put(ip: String, domain: String, ttlMillis: Long = DEFAULT_TTL_MILLIS) {
        val normalizedIp = ip.trim()
        // Hostname.sanitize does the trim / root-dot / lower-case normalisation
        // AND rejects anything that cannot be put back on the wire, so an
        // over-long or non-ASCII name from a hostile DNS answer never reaches a
        // SOCKS5 request as a truncated length-prefixed field.
        val normalizedDomain = Hostname.sanitize(domain) ?: return
        if (normalizedIp.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val expiry = now + ttlMillis.coerceIn(1_000L, 86_400_000L)
        synchronized(ipToDomains) {
            val entries = ipToDomains.getOrPut(normalizedIp) { mutableListOf() }
            entries.removeAll { it.expiresAt <= now || it.domain == normalizedDomain }
            entries.add(Entry(normalizedDomain, expiry))
            while (entries.size > MAX_DOMAINS_PER_IP) entries.removeAt(0)
        }
    }

    fun get(ip: String): String? {
        val now = SystemClock.elapsedRealtime()
        synchronized(ipToDomains) {
            val entries = ipToDomains[ip] ?: return null
            entries.removeAll { it.expiresAt <= now }
            if (entries.isEmpty()) {
                ipToDomains.remove(ip)
                return null
            }
            return entries.last().domain
        }
    }

    fun clear() {
        synchronized(ipToDomains) { ipToDomains.clear() }
    }
}
