package studio.cluvex.aether.core

import studio.cluvex.aether.model.BridgeTransport

/**
 * The bridge-line parser. One pure function stands between arbitrary text and
 * the torrc, and this is it.
 *
 * ### Why this is paranoid
 *
 * A bridge line arrives from a text field, a QR code, a Telegram message or an
 * HTTPS response, and it is written verbatim into a file tor then reads as its
 * ENTIRE configuration. A torrc is line-based, so a single embedded newline in
 * a "bridge line" is not a bad bridge - it is a free torrc option, and
 * `ExitNodes`, `Socks5Proxy` or `HTTPTunnelPort` are all things an attacker
 * would love to set on somebody else's tor. So nothing is passed through:
 * every line is TOKENISED, every token is validated, and the line that reaches
 * the torrc is REBUILT from the tokens that survived. A line that cannot be
 * rebuilt is dropped, never repaired.
 *
 * ### The shape being parsed
 *
 * ```
 * [transport] <address>:<port> [fingerprint] [key=value ...]
 * ```
 *
 * All four shapes below are real and all four have to parse:
 *
 * ```
 * obfs4 45.145.95.6:27015 C5B7...7C cert=TD7P...Zw iat-mode=0
 * obfs4 [2a0c:4d80:42:702::1]:27015 C5B7...7C cert=TD7P...Zw iat-mode=0
 * meek_lite 192.0.2.20:80 url=https://x.example front=y.example utls=HelloRandomizedALPN
 * 1.2.3.4:443 4352E58420E68F5E40BF7C74FAEB9D42088F6272
 * ```
 *
 * Note what that means: the fingerprint is OPTIONAL (the built-in meek line has
 * none), and the first token is only a transport when it is not an address -
 * which is how a vanilla bridge is told apart from a transport with a missing
 * address rather than by guessing.
 */
object BridgeLine {

    /**
     * How many bridges are kept.
     *
     * tor tries them in order and a long list mostly means a long first
     * bootstrap; Tor Browser ships single digits per transport. The cap also
     * bounds what a paste can do to the torrc and to the Intent payload.
     */
    const val MAX_LINES = 32

    /** Longest line accepted. Real snowflake lines are ~400 bytes. */
    const val MAX_LENGTH = 512

    /** Most `key=value` parameters kept on one line. */
    private const val MAX_PARAMS = 16

    /**
     * `1.2.3.4:443`, `[2a0c::1]:27015` or `host.example:443`.
     *
     * The port is bounded numerically in [parse] rather than by the pattern: a
     * bare `\d{1,5}` accepts `99999`, and tor would then reject the whole
     * bridge line at startup - i.e. one typo would cost every other bridge too.
     */
    private val ADDRESS =
        Regex("^(?:\\[[0-9A-Fa-f:.]{2,45}]|[0-9A-Za-z](?:[0-9A-Za-z._-]{0,253}[0-9A-Za-z])?):([0-9]{1,5})$")

    /** A transport name: `obfs4`, `meek_lite`, `snowflake`, `webtunnel`. */
    private val TRANSPORT = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}$")

    /** A relay identity fingerprint: 40 hex digits, no spaces. */
    private val FINGERPRINT = Regex("^[0-9A-Fa-f]{40}$")

    /**
     * One `key=value` parameter. The value is printable ASCII with no space,
     * which is what keeps a URL, a certificate and a comma-separated ICE list
     * intact while making a newline, a tab and any control character impossible.
     */
    private val PARAM = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,31}=[\\x21-\\x7E]*$")

    /**
     * Characters that are never allowed anywhere in a line, whatever else the
     * patterns above would accept:
     *
     *  - `\` is a torrc LINE CONTINUATION, so a value ending in one silently
     *    swallows the next line;
     *  - `"` starts a torrc quoted value, with its own escaping rules;
     *  - `#` starts a comment, so it can delete the rest of a line.
     */
    private const val FORBIDDEN = "\\\"#"

    /** A validated bridge line, and the pieces the UI wants to show. */
    data class Parsed(
        /** Recognised transport, or null when the name means nothing to us. */
        val transport: BridgeTransport?,
        /** The raw transport token; empty for a vanilla bridge. */
        val transportName: String,
        val address: String,
        val port: Int,
        val fingerprint: String?,
        val params: List<String>,
    ) {
        /** The canonical line: single-spaced, lower-case transport, no surprises. */
        val line: String
            get() = buildList {
                if (transportName.isNotEmpty()) add(transportName)
                add("$address:$port")
                fingerprint?.let { add(it) }
                addAll(params)
            }.joinToString(" ")

        /** `obfs4 45.145.95.6:27015` - enough to tell two bridges apart in a list. */
        val shortLabel: String
            get() = if (transportName.isEmpty()) "$address:$port" else "$transportName $address:$port"

        override fun toString(): String = line
    }

    /**
     * Parses ONE line, or returns null when it is not a bridge line.
     *
     * Null is the only failure mode on purpose: there is nothing useful a caller
     * could do with a partially understood bridge, and "drop it and say how many
     * were dropped" is a UI the user can act on.
     */
    fun parse(raw: String?): Parsed? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        if (text.any { it in FORBIDDEN || it.isISOControl() }) return null

        // A pasted line often still carries the keyword from a torrc or from a
        // Tor Browser text box. Both are noise here, not part of the bridge.
        val body = text.removePrefix("Bridge ").removePrefix("bridge ").trim()
        val tokens = body.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        var index = 0
        var transportName = ""
        var match = ADDRESS.find(tokens[0])
        if (match == null) {
            if (!TRANSPORT.matches(tokens[0])) return null
            transportName = tokens[0].lowercase()
            index = 1
            match = ADDRESS.find(tokens.getOrNull(index) ?: return null) ?: return null
        }

        val port = match.groupValues[1].toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        val address = tokens[index].substringBeforeLast(':')
        index++

        var fingerprint: String? = null
        if (index < tokens.size && FINGERPRINT.matches(tokens[index])) {
            fingerprint = tokens[index].uppercase()
            index++
        }

        val params = tokens.drop(index)
        if (params.size > MAX_PARAMS) return null
        if (params.any { !PARAM.matches(it) }) return null

        return Parsed(
            transport = BridgeTransport.fromTorName(transportName),
            transportName = transportName,
            address = address,
            port = port,
            fingerprint = fingerprint,
            params = params,
        )
    }

    /**
     * Every line of [text] that is a bridge, de-duplicated, capped at
     * [MAX_LINES].
     *
     * De-duplication is by canonical line rather than by address: the same
     * bridge with a different `iat-mode` is a different configuration, and two
     * copies of the identical line only make tor try it twice.
     */
    fun parseAll(text: String?): List<Parsed> = parseAll((text ?: "").lines())

    fun parseAll(lines: Iterable<String>): List<Parsed> {
        val seen = LinkedHashMap<String, Parsed>()
        for (line in lines) {
            val parsed = parse(line) ?: continue
            if (seen.size >= MAX_LINES) break
            seen.putIfAbsent(parsed.line, parsed)
        }
        return seen.values.toList()
    }

    /** The canonical text form of a set of lines, ready for the profile field. */
    fun canonicalText(lines: Iterable<String>): String =
        parseAll(lines).joinToString("\n") { it.line }

    /**
     * The lines tor can ACTUALLY run, given the transports this build can
     * launch.
     *
     * This is not a nicety. A `Bridge obfs4 ...` line with no matching
     * `ClientTransportPlugin` is a fatal configuration error for tor: it exits
     * during startup instead of ignoring the line, so ONE unusable bridge in
     * the list costs the whole session. The UI and [Torrc] both filter through
     * here so they cannot disagree about what is runnable.
     *
     * @param supported transport tokens this build has a plugin for. Vanilla
     *   bridges need no entry - tor speaks them itself.
     */
    fun usable(parsed: List<Parsed>, supported: Set<String>): List<Parsed> =
        parsed.filter { it.transportName.isEmpty() || it.transportName in supported }

    /** Transport tokens used by [parsed], in first-seen order. */
    fun transportsOf(parsed: List<Parsed>): List<String> =
        parsed.mapNotNull { it.transportName.takeIf { name -> name.isNotEmpty() } }.distinct()
}
