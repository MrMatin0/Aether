package studio.cluvex.aether.core.probe

import studio.cluvex.aether.core.IpInfo

/**
 * Reads an address + country out of the two response shapes the providers
 * answer with. Pure string work, so it is unit-testable - which matters,
 * because a parser that silently returns null is indistinguishable from a
 * filtered network.
 *
 * The patterns are compiled ONCE. They used to be built inline on every call,
 * on a path that runs per provider, per retry, per reconnect.
 */
internal object IpInfoParser {

    /** ip-api.com JSON: {"query":"1.2.2.4","countryCode":"DE"} */
    private val JSON_IP = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"")
    private val JSON_COUNTRY = Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"")

    /** Cloudflare /cdn-cgi/trace: key=value lines. */
    private val TRACE_IP = Regex("(?m)^ip=(\\S+)")
    private val TRACE_LOC = Regex("(?m)^loc=([A-Za-z]{2})")

    fun parse(body: String): IpInfo? {
        JSON_IP.find(body)?.groupValues?.get(1)?.let { ip ->
            return IpInfo(ip, countryCode(body))
        }
        TRACE_IP.find(body)?.groupValues?.get(1)?.let { ip ->
            return IpInfo(ip, TRACE_LOC.find(body)?.groupValues?.get(1)?.uppercase())
        }
        return null
    }

    fun countryCode(body: String): String? = JSON_COUNTRY.find(body)?.groupValues?.get(1)
}
