package studio.cluvex.aether.core.probe

/** One geolocation endpoint the IP badge can ask. */
internal data class GeoProvider(
    val host: String,
    val port: Int,
    val path: String,
    val tls: Boolean,
    /** false = [host] is an IPv4 literal (SOCKS ATYP = 0x01, no DNS involved). */
    val hostIsDomain: Boolean,
) {
    /** True when this provider IS the country database everything else defers to. */
    val isCountryAuthority: Boolean get() = host == GeoProviders.COUNTRY_AUTHORITY_HOST
}

/**
 * The providers, tried in order.
 *
 * ROOT-CAUSE NOTE: relying on a single provider (ip-api.com over plain HTTP:80)
 * was a single point of failure - on many operator networks that host/port
 * simply never answers, so the IP badge silently showed nothing even while
 * fully disconnected. Cloudflare's /cdn-cgi/trace is served from an anycast
 * edge reachable on nearly every network and is used as the fallback: once via
 * TLS with SNI, and once via the bare IP literal 1.1.1.1, which needs no DNS at
 * all.
 */
internal object GeoProviders {

    /**
     * The country database every answer is normalised against.
     *
     * FLAG CORRECTNESS: Cloudflare's `loc=` is the country Cloudflare
     * attributes to the CLIENT, which behind WARP often differs from the
     * country the exit IP is registered in (what ip-api reports). Depending on
     * which provider happened to win, the badge showed a different flag for the
     * very same connection.
     */
    const val COUNTRY_AUTHORITY_HOST = "ip-api.com"
    const val COUNTRY_AUTHORITY_PORT = 80

    val ALL = listOf(
        GeoProvider(
            host = COUNTRY_AUTHORITY_HOST,
            port = COUNTRY_AUTHORITY_PORT,
            path = "/json/?fields=status,query,countryCode",
            tls = false,
            hostIsDomain = true,
        ),
        GeoProvider(
            host = "www.cloudflare.com",
            port = ProbeDefaults.HTTPS_PORT,
            path = "/cdn-cgi/trace",
            tls = true,
            hostIsDomain = true,
        ),
        GeoProvider(
            host = ProbeDefaults.ANYCAST_RESOLVER_IP,
            port = ProbeDefaults.HTTP_PORT,
            path = "/cdn-cgi/trace",
            tls = false,
            hostIsDomain = false,
        ),
    )

    /** Asks the authority about one specific address. */
    fun countryLookupPath(ip: String): String = "/json/$ip?fields=status,countryCode"
}
