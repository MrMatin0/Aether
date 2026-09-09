package studio.cluvex.aether.core.moat

/**
 * The moat wire format, as pure functions.
 *
 * Split from [MoatClient] deliberately: every interesting failure of this
 * feature is a PARSING failure on a network nobody can reproduce - a captive
 * portal answering HTML with a 200, an error object where bridges were expected,
 * a transport key that changed name upstream. Those belong in unit tests, and
 * they can only be unit-tested if no socket is involved.
 *
 * Reference: `doc/moat.md` in tpo/anti-censorship/rdsys.
 */
object MoatPayloads {

    /** An error object moat answered with. Always HTTP 200, per the spec. */
    class MoatError(val code: Int, val detail: String) :
        Exception("moat error $code: $detail")

    /** One `bridges` entry of a `/circumvention/*` response. */
    data class Bundle(
        /** moat's transport name (`obfs4`, `snowflake`, `meek`, `vanilla`). */
        val type: String,
        /** `builtin` for the public list, `bridgedb` for bridges just for us. */
        val source: String,
        val lines: List<String>,
    ) {
        /** True when these bridges were handed to this client rather than published. */
        val isPrivate: Boolean get() = !source.equals("builtin", ignoreCase = true)
    }

    /** A `/circumvention/settings` (or `/defaults`) response. */
    data class Settings(val country: String?, val bundles: List<Bundle>)

    /** A `/fetch` response: the captcha and the token that goes back with it. */
    data class Challenge(
        val id: String,
        val transports: List<String>,
        /** base64 of a JPEG. Not decoded here: this file stays testable. */
        val image: String,
        val challenge: String,
    )

    /**
     * `/circumvention/builtin`: a flat `transport -> lines` object.
     *
     * Returned with moat's own keys, not this app's enum, so the caller decides
     * what to do with a transport it does not know yet - dropping unknown keys
     * here would mean an upstream addition is invisible until the app is
     * updated.
     */
    fun builtin(body: String): Map<String, List<String>> {
        val root = JsonLite.parseObject(body)
        throwIfError(root)
        val result = LinkedHashMap<String, List<String>>()
        for ((key, value) in root) {
            val lines = JsonLite.strings(value)
            if (lines.isNotEmpty()) result[key.lowercase()] = lines
        }
        return result
    }

    /**
     * `/circumvention/settings`: the mechanisms that work where the client is,
     * best first.
     *
     * An empty `settings` list is a real, useful answer and NOT an error - it
     * means the Tor Project believes plain Tor works from that country, which
     * the UI says out loud instead of showing an empty list.
     */
    fun settings(body: String): Settings {
        val root = JsonLite.parseObject(body)
        throwIfError(root)
        val bundles = JsonLite.list(root["settings"]).orEmpty().mapNotNull { entry ->
            val bridges = JsonLite.map(JsonLite.map(entry)?.get("bridges")) ?: return@mapNotNull null
            val type = JsonLite.string(bridges["type"])?.lowercase() ?: return@mapNotNull null
            Bundle(
                type = type,
                source = JsonLite.string(bridges["source"])?.lowercase() ?: "",
                lines = JsonLite.strings(bridges["bridge_strings"]),
            )
        }
        return Settings(JsonLite.string(root["country"])?.lowercase(), bundles)
    }

    /** `/fetch`: the captcha challenge. */
    fun challenge(body: String): Challenge {
        val root = JsonLite.parseObject(body)
        throwIfError(root)
        val data = JsonLite.map(JsonLite.list(root["data"])?.firstOrNull())
            ?: throw JsonLite.JsonException("no data in the moat challenge")
        val challenge = JsonLite.string(data["challenge"])
            ?: throw JsonLite.JsonException("moat sent no challenge token")
        return Challenge(
            // The spec pins the solution id at 2 regardless of what /fetch says,
            // so the id is carried for the log and never used as a protocol value.
            id = JsonLite.string(data["id"]) ?: "1",
            transports = JsonLite.stringOrStrings(data["transport"]),
            image = JsonLite.string(data["image"]).orEmpty(),
            challenge = challenge,
        )
    }

    /** `/check`: the bridges, or the 419 that says the captcha was refused. */
    fun bridges(body: String): List<String> {
        val root = JsonLite.parseObject(body)
        throwIfError(root)
        val data = JsonLite.map(JsonLite.list(root["data"])?.firstOrNull())
            ?: throw JsonLite.JsonException("no data in the moat response")
        return JsonLite.strings(data["bridges"])
    }

    /**
     * moat answers HTTP 200 for everything, including failure, so the error
     * object is the ONLY signal. Checked before every read: without this a 404
     * "no transport works for this country" would surface as "no bridges found",
     * which sends the user to retry the one thing that cannot work.
     */
    private fun throwIfError(root: Map<String, Any?>) {
        val first = JsonLite.map(JsonLite.list(root["errors"])?.firstOrNull()) ?: return
        val code = JsonLite.int(first["code"]) ?: 0
        val detail = JsonLite.string(first["detail"])
            ?: JsonLite.string(first["status"])
            ?: "unknown error"
        throw MoatError(code, detail)
    }

    // ------------------------------------------------------------- requests
    //
    // Built by hand rather than by a serialiser: they are three fixed documents,
    // and the only values that vary are validated below.

    fun settingsRequest(country: String?, transports: List<String>): String {
        val fields = mutableListOf<String>()
        val code = country?.trim()?.lowercase()?.takeIf { it.matches(COUNTRY) }
        if (code != null) fields += "\"country\": \"$code\""
        val names = transports.mapNotNull { safeName(it) }
        if (names.isNotEmpty()) {
            fields += "\"transports\": [" + names.joinToString(", ") { "\"$it\"" } + "]"
        }
        return "{" + fields.joinToString(", ") + "}"
    }

    fun fetchRequest(transports: List<String>): String {
        val names = transports.mapNotNull { safeName(it) }.ifEmpty { listOf("obfs4") }
        return "{\"data\": [{\"type\": \"client-transports\", \"version\": \"$VERSION\", " +
            "\"supported\": [" + names.joinToString(", ") { "\"$it\"" } + "]}]}"
    }

    fun checkRequest(transport: String, challenge: String, solution: String): String {
        val name = safeName(transport) ?: "obfs4"
        return "{\"data\": [{\"type\": \"moat-solution\", \"id\": \"2\", " +
            "\"version\": \"$VERSION\", \"transport\": \"$name\", " +
            "\"challenge\": \"${escape(challenge)}\", \"qrcode\": \"false\", " +
            "\"solution\": \"${escape(solution)}\"}]}"
    }

    /**
     * A transport name we are willing to put in a request body. Anything else is
     * dropped rather than escaped: the set of valid names is tiny and known, and
     * an unexpected one is a bug here, not user input.
     */
    private fun safeName(raw: String): String? =
        raw.trim().lowercase().takeIf { it.matches(TRANSPORT) }

    /**
     * Minimal JSON string escaping for the two values that come from outside:
     * the challenge token (base64 from the server) and the solution (typed by
     * the user, so it can contain anything at all).
     */
    private fun escape(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    /** The moat protocol version every payload in doc/moat.md carries. */
    private const val VERSION = "0.1.0"

    private val COUNTRY = Regex("^[a-z]{2}$")
    private val TRANSPORT = Regex("^[a-z][a-z0-9_-]{0,31}$")
}
