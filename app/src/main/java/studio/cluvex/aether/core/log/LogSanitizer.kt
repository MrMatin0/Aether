package studio.cluvex.aether.core.log

/**
 * Strips credentials out of a log line before it leaves the device.
 *
 * WHY THE EXPORT BUTTON NEEDS THIS: the log is written to be pasted into a bug
 * report, and this app's own settings include a Zero Trust client secret, an
 * enrolment token, a work e-mail and a pre-shared key. Anything the engine
 * echoes back - a command line, an HTTP header, a rejected request - can carry
 * one of those, and "copy logs" then quietly publishes it.
 *
 * DELIBERATELY BLUNT IN ONE DIRECTION ONLY. A match wins, because a redaction
 * that is sometimes skipped is worse than one that occasionally eats a harmless
 * value. But the diagnosis has to survive: timestamps, levels, tags, IPv4
 * addresses, ports, MTUs and file paths are the entire value of an export, so
 * none of the patterns can reach them. Both directions are asserted in
 * LogSanitizerTest.
 *
 * IDEMPOTENT BY CONSTRUCTION: [REDACTED] contains no character the value
 * pattern stops at, so sanitizing an already-sanitized line reproduces it
 * exactly. That matters because the in-memory export and the streamed disk log
 * are separate paths and a line can pass through both.
 */
object LogSanitizer {

    const val REDACTED = "<redacted>"

    /**
     * Keys whose value is never safe to publish. Matched case-insensitively on
     * a word boundary, so `session` does not fire on `sessions` and the longer
     * keys are listed BEFORE the shorter ones they contain.
     */
    private val SENSITIVE_KEYS = listOf(
        "authorization", "auth", "access_token", "refresh_token", "id_token",
        "token", "client_secret", "clientsecret", "client_id", "clientid",
        "secret", "password", "passwd", "apikey", "api_key", "privatekey",
        "private_key", "presharedkey", "preshared_key", "psk", "credential",
        "cookie", "session", "signature",
    )

    /**
     * `key=value`, `key: value`, `"key":"value"`.
     *
     * The value stops at whitespace so one leaked token cannot blank the rest of
     * the line - EXCEPT when it opens with a scheme word, because
     * `Authorization: Bearer <jwt>` is two tokens and redacting only the first
     * publishes the one that matters.
     */
    private val keyedValue = Regex(
        "(?i)\\b(" + SENSITIVE_KEYS.joinToString("|") { Regex.escape(it) } + ")\\b" +
            "[\"']?\\s*[:=]\\s*[\"']?" +
            "((?:bearer|basic|token)\\s+[^\\s,;\"']+|[^\\s,;&\"'}\\])]+)",
    )

    /** A bare `Bearer <blob>` with no key in front of it. */
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=\\-]{8,}")

    /** The Zero Trust enrolment flow logs the address it sent a code to. */
    private val email = Regex("(?i)\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b")

    /**
     * A long mixed-case-plus-digits run: what a JWT, a base64 key or a hex
     * secret looks like, and what ordinary log prose never does. All three
     * lookaheads must hold, and the class excludes `.` and `/`, so IPv4
     * addresses, hostnames and file paths are split into short runs and left
     * alone.
     */
    private val opaqueBlob = Regex(
        "\\b(?=[A-Za-z0-9=_\\-]*[a-z])(?=[A-Za-z0-9=_\\-]*[A-Z])(?=[A-Za-z0-9=_\\-]*[0-9])" +
            "[A-Za-z0-9=_\\-]{24,}\\b",
    )

    /** One line, safe to paste in public. Never throws; never returns null. */
    fun sanitize(line: String): String {
        if (line.isEmpty()) return line
        return line
            .replace(keyedValue) { match -> match.groupValues[1] + "=" + REDACTED }
            .replace(bearer) { "Bearer $REDACTED" }
            .replace(email) { REDACTED }
            .replace(opaqueBlob) { REDACTED }
    }

    fun sanitize(lines: List<String>): List<String> = lines.map(::sanitize)

    /** True when [line] carries something [sanitize] would remove. */
    fun isSensitive(line: String): Boolean = sanitize(line) != line
}
