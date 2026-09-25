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
 * half-typed, a pattern that never finishes - and none of it is testable
 * inside a LazyColumn.
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
 * The quick-filter pills: two by SEVERITY, two by SUBJECT. "Show me what
 * broke" and "show me the tunnel" are different questions, and the second
 * cannot be answered by level because a tunnel event is usually INFO.
 */
internal enum class ConsoleFilter { ALL, ERRORS, WARNINGS, TUNNEL, DNS }

/**
 * WORDS that mark a line as being about the tunnel itself.
 *
 * WHOLE WORDS, NOT SUBSTRINGS. The previous list was matched with contains(),
 * so "tor" fired on monitor, factory, connector and the "previous session
 * restored" header, and "tun" fired on tune and tuning: the Tunnel pill was
 * mostly noise. A marker followed only by digits still counts (tun0, socks5).
 */
internal val TUNNEL_MARKERS: Set<String> = setOf(
    "tun", "tunnel", "tunnels", "tunneled", "tunneling", "tun2socks",
    "vpn", "vpnservice", "hev", "engine", "socks", "proxy",
    "bridge", "bridges", "tor", "torrc", "psiphon", "chain", "handshake",
    "wireguard", "warp", "masque",
)

/** Words that mark a line as being about name resolution. */
internal val DNS_MARKERS: Set<String> = setOf(
    "dns", "resolv", "resolve", "resolved", "resolver", "resolvers", "resolving",
    "nameserver", "nameservers", "geo", "geoip", "doh", "hostname", "hostnames",
)

/**
 * Calls [predicate] with each lower-cased word of [text] and stops at the first
 * hit. Words are runs of letters and digits, and a camelCase tag is split at
 * its humps, so `core/HevTunnel` reads as core, hev, tunnel and
 * `TrafficMonitor` as traffic, monitor.
 */
private fun anyWord(text: String, predicate: (String) -> Boolean): Boolean {
    val word = StringBuilder()
    var previousLower = false
    fun emit(): Boolean {
        if (word.isEmpty()) return false
        val hit = predicate(word.toString().lowercase(Locale.US))
        word.setLength(0)
        return hit
    }
    for (c in text) {
        if (c.isLetterOrDigit()) {
            if (c.isUpperCase() && previousLower && emit()) return true
            word.append(c)
            previousLower = c.isLowerCase() || c.isDigit()
        } else {
            if (emit()) return true
            previousLower = false
        }
    }
    return emit()
}

private fun isMarker(word: String, markers: Set<String>): Boolean {
    if (word in markers) return true
    val stem = word.trimEnd { it.isDigit() }
    return stem.length < word.length && stem in markers
}

/** Tag first (cheap, usually decisive), message second. */
private fun mentions(line: LogLine, markers: Set<String>): Boolean =
    anyWord(line.tag) { isMarker(it, markers) } || anyWord(line.message) { isMarker(it, markers) }

internal fun matchesConsoleFilter(line: LogLine, filter: ConsoleFilter): Boolean = when (filter) {
    ConsoleFilter.ALL -> true
    ConsoleFilter.ERRORS -> matchesLogFilter(line.level, LogFilter.ERROR)
    ConsoleFilter.WARNINGS -> matchesLogFilter(line.level, LogFilter.WARN)
    ConsoleFilter.TUNNEL -> mentions(line, TUNNEL_MARKERS)
    ConsoleFilter.DNS -> mentions(line, DNS_MARKERS)
}

/** Outcome of matching one line against the search box. */
internal enum class QueryMatch { YES, NO, TIMEOUT }

/**
 * Per-line budget for a regex, in WALL-CLOCK nanoseconds.
 *
 * WHY 20 ms AND NOT 5: the clock starts before the matcher runs, so the budget
 * also pays for whatever the JVM does in the meantime - class loading and JIT
 * on the first search, a GC pause, a busy CPU. 5 ms was shorter than one of
 * those on a low-end phone or a loaded CI runner, and an ordinary pattern like
 * `rep=[0-9]` was reported as too slow (that is what made
 * DiagnosticsRedesignTest flaky). 20 ms is still bounded: after
 * [MAX_REGEX_TIMEOUTS] lines blow it, the rest skip the regex outright, so a
 * catastrophic pattern costs the console roughly 60 ms, once.
 */
internal const val REGEX_LINE_BUDGET_NANOS = 20_000_000L

/** After this many lines blow their budget, the rest are skipped outright. */
private const val MAX_REGEX_TIMEOUTS = 3

/**
 * The search box, compiled.
 *
 * REGEX THAT DOES NOT BLANK THE SCREEN: a pattern is invalid for most of the
 * time it is being typed, so an uncompilable pattern falls back to a plain
 * substring match and [malformed] lets the UI say why.
 *
 * REGEX THAT CANNOT HANG THE APP: java.util.regex backtracks, and a pattern
 * like `(a+)+$` is exponential on an ordinary log line. The input is handed to
 * the matcher through a CharSequence that checks a deadline as it is read, so
 * a runaway match is abandoned instead of freezing the console.
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

    fun matches(rendered: String): Boolean = match(rendered) == QueryMatch.YES

    /**
     * [allowRegex] false skips an expensive pattern once it has proven slow.
     * [budgetNanos] is the per-line time box for a regex.
     */
    fun match(
        rendered: String,
        allowRegex: Boolean = true,
        budgetNanos: Long = REGEX_LINE_BUDGET_NANOS,
    ): QueryMatch = when {
        blank -> QueryMatch.YES
        pattern != null ->
            if (allowRegex) guardedFind(pattern, rendered, budgetNanos) else QueryMatch.TIMEOUT
        rendered.contains(text, ignoreCase = true) -> QueryMatch.YES
        else -> QueryMatch.NO
    }
}

private class RegexBudgetExceeded : RuntimeException("regex budget exceeded", null, false, false)

/** Reads through to [inner] and aborts the match once [deadlineNanos] passes. */
private class DeadlineCharSequence(
    private val inner: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    private var reads = 0

    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        reads++
        if ((reads and 0x3F) == 0 && System.nanoTime() > deadlineNanos) throw RegexBudgetExceeded()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = inner.toString()
}

private fun guardedFind(pattern: Regex, rendered: String, budgetNanos: Long): QueryMatch = try {
    val input = DeadlineCharSequence(rendered, System.nanoTime() + budgetNanos)
    if (pattern.containsMatchIn(input)) QueryMatch.YES else QueryMatch.NO
} catch (e: RegexBudgetExceeded) {
    QueryMatch.TIMEOUT
}

/** The visible slice, and whether a slow pattern made it incomplete. */
internal data class ConsoleSlice(val lines: List<LogLine>, val regexTimedOut: Boolean)

/** Filter first (cheap), then search (possibly regex, always time-boxed). */
internal fun filterConsoleGuarded(
    lines: List<LogLine>,
    filter: ConsoleFilter,
    query: ConsoleQuery,
    budgetNanos: Long = REGEX_LINE_BUDGET_NANOS,
): ConsoleSlice {
    var timeouts = 0
    val out = ArrayList<LogLine>(lines.size)
    for (line in lines) {
        if (!matchesConsoleFilter(line, filter)) continue
        if (query.blank) {
            out.add(line)
            continue
        }
        when (
            query.match(
                line.format(),
                allowRegex = timeouts < MAX_REGEX_TIMEOUTS,
                budgetNanos = budgetNanos,
            )
        ) {
            QueryMatch.YES -> out.add(line)
            QueryMatch.NO -> Unit
            QueryMatch.TIMEOUT -> timeouts++
        }
    }
    return ConsoleSlice(out, timeouts > 0)
}

/** The visible slice, without the diagnostics. Kept for existing callers and tests. */
internal fun filterConsole(
    lines: List<LogLine>,
    filter: ConsoleFilter,
    query: ConsoleQuery,
): List<LogLine> = filterConsoleGuarded(lines, filter, query).lines

/**
 * Badge counters, computed in ONE pass over the buffer rather than one pass per
 * pill.
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

/**
 * Lines in [shown] newer than [anchorSeq], counted from the tail. Drives the
 * "N new lines" pill while the reader is scrolled up.
 */
internal fun newerThan(shown: List<LogLine>, anchorSeq: Long): Int {
    var count = 0
    for (index in shown.indices.reversed()) {
        if (shown[index].seq <= anchorSeq) break
        count++
    }
    return count
}
