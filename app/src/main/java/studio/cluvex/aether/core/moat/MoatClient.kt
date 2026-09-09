package studio.cluvex.aether.core.moat

import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.probe.MiniHttp
import studio.cluvex.aether.core.probe.Socks5Probe
import studio.cluvex.aether.core.probe.TlsProbe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * How a moat request leaves the device.
 *
 * ### Why there is a choice at all
 *
 * Tor Browser reaches bridges.torproject.org through DOMAIN FRONTING (a meek
 * request that looks like traffic to a large CDN), precisely because a network
 * that blocks Tor also blocks the site that hands out bridges. This build has no
 * meek binary of its own to front with, so pretending otherwise would ship a
 * button that only works on networks where it is not needed.
 *
 * What this app has instead is a working tunnel of its own. So a fetch goes
 * through the LIVE session's SOCKS5 entry when there is one - Aether, Psiphon,
 * or a chain of them - and directly when there is not. That is the honest
 * version of the same idea: use the circumvention you already have to go and
 * fetch the circumvention you do not.
 */
internal sealed interface MoatRoute {
    /** Straight out. The app package is excluded from its own VPN, so this is direct. */
    object Direct : MoatRoute

    /** Through a local SOCKS5 proxy, with the hostname resolved at the far end. */
    data class ViaSocks(val host: String, val port: Int) : MoatRoute
}

/**
 * The Tor Project's bridge API, over one bounded HTTPS request per call.
 *
 * Deliberately blocking and deliberately not a singleton: every call opens one
 * socket, sends one request, reads a bounded response and closes. Callers run it
 * on [kotlinx.coroutines.Dispatchers.IO] (see
 * [studio.cluvex.aether.core.BridgeService]).
 *
 * Parsing lives in [MoatPayloads] so it can be unit-tested without a socket.
 */
internal class MoatClient(
    private val route: MoatRoute,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    /**
     * The public built-in list, UNPARSED.
     *
     * The raw body is what gets stored (see
     * [studio.cluvex.aether.data.BridgeStore]): the meaning of a transport key
     * belongs to [studio.cluvex.aether.core.BridgeCatalog], and a stored map
     * would have to be migrated every time upstream adds one.
     */
    fun builtinBody(): String = post("$BASE/circumvention/builtin", JSON, "{}")

    /** The built-in list, parsed. */
    fun builtin(): Map<String, List<String>> = MoatPayloads.builtin(builtinBody())

    /**
     * What the Tor Project believes works from [country], or from wherever this
     * request appears to come from when [country] is null.
     *
     * Note whose IP address decides that: with a tunnel up, the request comes
     * from the exit, so the answer is about the exit's country. That is why the
     * UI lets the user name their country explicitly.
     */
    fun settings(country: String?, transports: List<String>): MoatPayloads.Settings =
        MoatPayloads.settings(
            post(
                "$BASE/circumvention/settings",
                JSON,
                MoatPayloads.settingsRequest(country, transports),
            ),
        )

    /** The fallback settings, for a location moat has no specific advice for. */
    fun defaults(transports: List<String>): MoatPayloads.Settings =
        MoatPayloads.settings(
            post(
                "$BASE/circumvention/defaults",
                JSON,
                MoatPayloads.settingsRequest(null, transports),
            ),
        )

    /**
     * Step one of a personal bridge request: a captcha and a token.
     *
     * The captcha endpoints require the JSON:API content type and answer 415 for
     * anything else, which is why [API_JSON] exists.
     */
    fun challenge(transports: List<String>): MoatPayloads.Challenge =
        MoatPayloads.challenge(
            post("$BASE/fetch", API_JSON, MoatPayloads.fetchRequest(transports)),
        )

    /** Step two: the answer, and the bridges it buys. */
    fun bridges(transport: String, challenge: String, solution: String): List<String> =
        MoatPayloads.bridges(
            post(
                "$BASE/check",
                API_JSON,
                MoatPayloads.checkRequest(transport, challenge, solution),
            ),
        )

    // ------------------------------------------------------------- transport

    private fun post(path: String, contentType: String, body: String): String {
        dial().use { socket ->
            val tls = TlsProbe.wrap(socket, HOST, PORT, timeoutMs)
            val response = MiniHttp.post(
                socket = tls,
                host = HOST,
                path = path,
                contentType = contentType,
                body = body,
                limitBytes = MAX_RESPONSE_BYTES,
            )
            DiagnosticsLog.d(TAG, "moat $path -> $response")
            // moat answers 200 for its own errors, so a non-2xx here is the
            // infrastructure in front of it (a CDN block page, a rate limiter,
            // a captive portal) and its body is not a moat document at all.
            if (!response.isSuccess) {
                throw IOException("bridges.torproject.org answered HTTP ${response.status}")
            }
            return response.body
        }
    }

    private fun dial(): Socket = when (route) {
        is MoatRoute.Direct -> Socket().also { socket ->
            try {
                socket.connect(InetSocketAddress(HOST, PORT), timeoutMs)
                socket.soTimeout = timeoutMs
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }
        // useDomain = true: the name is resolved at the far end of the tunnel,
        // so a local resolver that lies about bridges.torproject.org (which is
        // the cheapest way to block it) is not consulted at all.
        is MoatRoute.ViaSocks -> Socks5Probe.connect(
            socksHost = route.host,
            socksPort = route.port,
            destHost = HOST,
            destPort = PORT,
            useDomain = true,
            timeoutMs = timeoutMs,
        )
    }

    companion object {
        /** Host assembled from fragments, same convention as the fetch scripts. */
        private val HOST = "bridges." + "torproject.org"
        private const val PORT = 443
        private const val BASE = "/moat"

        private const val JSON = "application/json"

        /** The captcha endpoints answer 415 for anything else. See doc/moat.md. */
        private const val API_JSON = "application/vnd.api+json"

        /**
         * Enough for a captcha JPEG in base64 plus the JSON around it, and small
         * enough that a hostile endpoint cannot make the app buffer a megabyte.
         */
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        /**
         * Generous on purpose: this request goes over a filtered network, and
         * often through a Tor-less tunnel that is itself scanning for an
         * endpoint. A short timeout here reads as "bridges are unavailable".
         */
        private const val DEFAULT_TIMEOUT_MS = 20_000

        private const val TAG = "bridges"
    }
}
