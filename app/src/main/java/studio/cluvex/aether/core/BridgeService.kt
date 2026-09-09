package studio.cluvex.aether.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.moat.JsonLite
import studio.cluvex.aether.core.moat.MoatClient
import studio.cluvex.aether.core.moat.MoatPayloads
import studio.cluvex.aether.core.moat.MoatRoute
import studio.cluvex.aether.data.BridgeStore
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ConnectionState
import java.io.IOException

/**
 * Why a bridge request did not work, in terms the user can act on.
 *
 * The exception is NOT passed to the UI. "java.net.SocketTimeoutException" and
 * "moat error 404" are the same event to somebody trying to get online, and
 * neither says what to do next - which for this feature is nearly always "bring
 * up Aether first, then come back".
 */
enum class BridgeFailure {
    /** Nothing reached bridges.torproject.org: it is blocked, or the tunnel is down. */
    UNREACHABLE,

    /** The captcha answer was refused (moat 419). */
    CAPTCHA_REFUSED,

    /** moat has no bridges of the requested type for that country (moat 404). */
    NO_TRANSPORT,

    /** moat answered, with an error of its own. */
    SERVER,

    /** Something answered, but not with a moat document. Usually a portal page. */
    MALFORMED,
}

/** The outcome of one bridge request. */
sealed interface BridgeResult<out T> {
    /** [viaTunnel] is worth showing: it is the difference between two very different networks. */
    data class Ok<T>(val value: T, val viaTunnel: Boolean) : BridgeResult<T>

    data class Failed(
        val failure: BridgeFailure,
        /** moat's own code when it had one, for the log and for a bug report. */
        val code: Int? = null,
        val detail: String? = null,
    ) : BridgeResult<Nothing>
}

/**
 * Everything the Bridges settings page can ask for, off the main thread.
 *
 * ### The route policy, in one place
 *
 * A moat request is tried through the LIVE tunnel first when there is one, and
 * directly otherwise - and a tunnelled attempt that fails falls back to direct
 * before giving up. Both halves matter: on a network that blocks Tor, direct is
 * usually blocked too (that is the whole point of a bridge), and a tunnel whose
 * exit is somewhere moat rate-limits can fail where direct succeeds. Trying both
 * costs one extra round trip on a path that is already user-initiated.
 *
 * Which path produced the answer is reported back, because "this only worked
 * through Aether" is exactly what the user needs to know before they disconnect
 * and switch to Tor.
 */
object BridgeService {

    private const val TAG = "bridges"

    /** Refreshes the built-in list and stores it. Returns what the picker will show. */
    suspend fun refreshBuiltin(
        context: Context,
    ): BridgeResult<Map<BridgeTransport, List<String>>> = attempt { client ->
        val body = client.builtinBody()
        val parsed = BridgeCatalog.parse(body)
        if (parsed.isEmpty()) throw JsonLite.JsonException("no built-in bridges in the reply")
        BridgeStore(context).saveCatalog(body)
        parsed
    }

    /**
     * What the Tor Project recommends for [country] (or for wherever the request
     * appears to come from), including any bridges it hands over on the spot.
     *
     * `/circumvention/settings` can legitimately answer "nothing needed here",
     * which is why an empty list is a SUCCESS with no bundles rather than a
     * failure - and why the caller says so in words instead of showing an empty
     * list.
     */
    suspend fun recommended(
        country: String?,
        transports: List<BridgeTransport>,
    ): BridgeResult<MoatPayloads.Settings> = attempt { client ->
        val names = transports.map { it.moatName }
        val answer = client.settings(country, names)
        // A country moat has no specific advice for gets the defaults rather
        // than an empty page: the user asked a question and there is an answer.
        if (answer.bundles.isEmpty()) {
            runCatching { client.defaults(names) }.getOrDefault(answer)
        } else {
            answer
        }
    }

    /** Step one of a personal request: the captcha. */
    suspend fun challenge(
        transport: BridgeTransport,
    ): BridgeResult<MoatPayloads.Challenge> = attempt { client ->
        client.challenge(listOf(transport.moatName))
    }

    /** Step two: the answer, the bridges, and storing them before anything else. */
    suspend fun solve(
        context: Context,
        transport: BridgeTransport,
        challenge: String,
        solution: String,
    ): BridgeResult<List<String>> = attempt { client ->
        val lines = client.bridges(transport.moatName, challenge, solution)
        val usable = BridgeLine.parseAll(lines).map { it.line }
        if (usable.isEmpty()) throw JsonLite.JsonException("no usable bridge lines in the reply")
        // Stored FIRST, before the caller can decide what to do with them: this
        // pool is rate-limited per subnet, so a bridge that arrives and is then
        // dropped on a recomposition is a bridge the user may not get again.
        BridgeStore(context).addPersonal(usable)
        usable
    }

    // ------------------------------------------------------------- internals

    /**
     * Runs [block] against the tunnel first, then directly, and maps whatever
     * went wrong onto a [BridgeFailure].
     */
    private suspend fun <T> attempt(
        block: suspend (MoatClient) -> T,
    ): BridgeResult<T> = withContext<BridgeResult<T>>(Dispatchers.IO) {
        val routes = buildList<Pair<MoatRoute, Boolean>> {
            tunnelRoute()?.let { add(it to true) }
            add(MoatRoute.Direct to false)
        }
        var last: BridgeResult<T> = BridgeResult.Failed(BridgeFailure.UNREACHABLE)
        for ((route, viaTunnel) in routes) {
            try {
                val value = block(MoatClient(route))
                DiagnosticsLog.i(TAG, "Bridge request succeeded (viaTunnel=$viaTunnel).")
                return@withContext BridgeResult.Ok(value, viaTunnel)
            } catch (e: MoatPayloads.MoatError) {
                DiagnosticsLog.w(TAG, "moat refused the request: ${e.message}")
                // A server that ANSWERED is reachable, so there is nothing another
                // route can fix. Stop here, with its own reason.
                return@withContext BridgeResult.Failed(
                    failure = when (e.code) {
                        419 -> BridgeFailure.CAPTCHA_REFUSED
                        404 -> BridgeFailure.NO_TRANSPORT
                        else -> BridgeFailure.SERVER
                    },
                    code = e.code,
                    detail = e.detail,
                )
            } catch (e: JsonLite.JsonException) {
                DiagnosticsLog.w(TAG, "Unusable reply from the bridge server: ${e.message}")
                last = BridgeResult.Failed(BridgeFailure.MALFORMED, detail = e.message)
            } catch (e: IOException) {
                DiagnosticsLog.w(
                    TAG,
                    "Bridge server unreachable (viaTunnel=$viaTunnel): ${e.message}",
                )
                last = BridgeResult.Failed(BridgeFailure.UNREACHABLE, detail = e.message)
            } catch (e: Exception) {
                DiagnosticsLog.w(TAG, "Bridge request failed: ${e.message}")
                last = BridgeResult.Failed(BridgeFailure.UNREACHABLE, detail = e.message)
            }
        }
        last
    }

    /**
     * The live chain entry, or null when nothing is connected.
     *
     * Reads the same [ChainRuntime] port everything else in the app probes
     * through, so a chained session is followed for free.
     */
    private fun tunnelRoute(): MoatRoute? =
        if (AetherController.state.value is ConnectionState.Connected) {
            MoatRoute.ViaSocks(TunnelConfig.SOCKS_HOST, ChainRuntime.entryPort)
        } else {
            null
        }
}
