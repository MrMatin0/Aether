package studio.cluvex.aether.ui.components

import java.util.Locale
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine

/**
 * What the log inspector shows, as pure functions.
 *
 * WHY THIS IS NOT INSIDE THE COMPOSABLE: "which lines does a reader see" is the
 * only logic in the console that can be wrong in a way that matters - a filter
 * that hides the error, a search that matches nothing because the pattern was
 * half-typed - and none of it was testable while it lived inside a LazyColumn.
 */

/**
 * Severity filter. KEPT AS IS on purpose: it is the level predicate the quick
 * filters below delegate to, and its three cases are covered by existing tests.
 */
internal enum class LogFilter { ALL, WARN, ERROR }

internal fun matchesLogFilter(level: LogLevel, filter: LogFilter): Boolean = when (filter) {
    LogFilter.ALL -> true
    LogFilter.WARN -> level == LogLevel.WARN || level == LogLevel.ERROR
    LogFilter.ERROR -> level == LogLevel.ERROR
}

/**
 * The quick-filter pills.
 *
 * Two of them filter by SEVERITY and two by SUBJECT, which is the split a reader
 * actually works in: "show me what broke" and "show me the tunnel" are
 * different questions, and answering the second by reading levels does not work
 * because a tunnel event is usually INFO.
 */
internal enum class ConsoleFilter { ALL, ERRORS, WARNINGS, TUNNEL, DNS }

/**
 * Substrings that mark a line as being about the tunnel itself.
 *
 * Matched against the TAG first and the message second. The tags are written by
 * a dozen different call sites (core/HevTunnel, core/SocksFront, core/TorCore,
 * core/PsiphonCore, the VpnService) and have never been normalised, so an
 * exact-tag allow-list would go stale the first time one of them is renamed.
 */
internal val TUNNEL_MARKERS = listOf(
    "tun", "vpn", "hev", "engine", "socks", "proxy", "bridge", "tor",
    "psiphon", "chain", "handshake", "wireguard", "masque",
)

/** Substrings that mark a line as being about name resolution. */
internal val DNS_MARKERS = listOf("dns", "resolv", "nameserver", "geo", "doh", "hostname")

private fun mentions(line: LogLine, markers: List<String>): Boolean {
    val tag = line.tag.lowercase(Locale.US)
    if (markers.any { tag.contains(it) }) return true
    val message = line.message.lowercase(Locale.US)
    return markers.any { message.contains(it) }
}

internal fun matchesConsoleFilter(line: LogLine, filter: ConsoleFilter): Boolean = when (filter) {
    ConsoleFilter.ALL -> true
    ConsoleFilter.ERRORS -> matchesLogFilter(line.level, LogFilter.ERROR)
    ConsoleFilter.WARNINGS -> matchesLogFilter(line.level, LogFilter.WARN)
    ConsoleFilter.TUNNEL -> mentions(line, TUNNEL_MARKERS)
    ConsoleFilter.DNS -> mentions(line, DNS_MARKERS)
}

/**
 * The search box, compiled.
 *
 * REGEX THAT DOES NOT BLANK THE SCREEN: a pattern is invalid for most of the
 * time it is being typed - `(`, `[a-`, `\` are all half-written and all throw.
 * Treating those as "matches nothing" empties the console under the reader's
 * hands, so an uncompilable pattern falls back to a plain substring match and
 * [malformed] lets the UI say why the results look literal.
 */
internal data class ConsoleQuery(val text: String, val regex: Boolean) {

    val blank: Boolean = text.isBlank()

    private val pattern: Regex? =
        if (regex && text.isNotBlank()) {
            runCatching { Regex(text, RegexOption.IGNORE_CASE) }.getOrNull()
        } else {
            null
        }

    /** Regex mode is on, something is typed, and it does not compile (yet). */
    val malformed: Boolean = regex && text.isNotBlank() && pattern == null

    fun matches(rendered: String): Boolean = when {
        blank -> true
        pattern != null -> pattern.containsMatchIn(rendered)
        else -> rendered.contains(text, ignoreCase = true)
    }
}

/** The visible slice: filter first (cheap), then search (regex). */
internal fun filterConsole(
    lines: List<LogLine>,
    filter: ConsoleFilter,
    query: ConsoleQuery,
): List<LogLine> = lines.filter { line ->
    matchesConsoleFilter(line, filter) && (query.blank || query.matches(line.format()))
}

/**
 * Badge counters, computed in ONE pass over the buffer rather than one pass per
 * pill. The buffer is 800 lines and the pills are re-measured on every publish
 * tick, so five passes was five times the work for the same numbers.
 */
internal fun consoleCounts(lines: List<LogLine>): Map<ConsoleFilter, Int> {
    val counts = HashMap<ConsoleFilter, Int>(ConsoleFilter.entries.size)
    ConsoleFilter.entries.forEach { counts[it] = 0 }
    lines.forEach { line ->
        ConsoleFilter.entries.forEach { filter ->
            if (matchesConsoleFilter(line, filter)) counts[filter] = (counts[filter] ?: 0) + 1
        }
    }
    return counts
}
