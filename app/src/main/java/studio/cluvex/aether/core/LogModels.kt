package studio.cluvex.aether.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the diagnostics panel renders: log lines and self-test rows.
 *
 * These live in their own file (and NOT inside DiagnosticsLog) because they are
 * the only part of the logger the UI is allowed to know about. The plumbing -
 * ring buffer, write queue, writer coroutine, file rotation - is internal to
 * core/log.
 */

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogLine(
    val timeMs: Long,
    val tag: String,
    val level: LogLevel,
    val message: String,
    /** Restored-from-disk lines are already formatted; print them verbatim. */
    val raw: Boolean = false,
    /**
     * Monotonic id assigned by [DiagnosticsLog]. The console keys its rows on
     * it, so a full ring buffer evicting its head does not shift every row
     * under the reader, and "N new lines" is a comparison instead of a diff.
     */
    val seq: Long = 0L,
) {
    fun format(): String {
        if (raw) return message
        val timestamp = TS_FORMAT.get()?.format(Date(timeMs)) ?: timeMs.toString()
        return "$timestamp ${level.symbol}/$tag: $message"
    }

    private companion object {
        /** SimpleDateFormat is not thread-safe and every thread here logs. */
        val TS_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

/** Single-letter level used by [LogLine.format] and by the on-disk log. */
private val LogLevel.symbol: String
    get() = when (this) {
        LogLevel.DEBUG -> "D"
        LogLevel.INFO -> "I"
        LogLevel.WARN -> "W"
        LogLevel.ERROR -> "E"
    }

/**
 * A line read back from the on-disk log, WITH its severity.
 *
 * Restored lines used to become DEBUG / "prev" wholesale. That painted the
 * error that explains a crash grey and kept it out of the Errors filter, which
 * is the one place a reader looks for it. The file holds exactly what
 * [LogLine.format] wrote, so the level letter and the tag are recoverable.
 * Anything that does not parse (an overflow marker, a torn write) stays DEBUG.
 *
 * The line is kept [LogLine.raw]: it is printed verbatim, timestamp included.
 */
internal fun parseRestoredLine(text: String, seq: Long = 0L): LogLine {
    val match = RESTORED_LINE.find(text)
    val level = when (match?.groupValues?.get(1)) {
        "E" -> LogLevel.ERROR
        "W" -> LogLevel.WARN
        "I" -> LogLevel.INFO
        else -> LogLevel.DEBUG
    }
    val tag = match?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: "prev"
    return LogLine(0L, tag, level, text, raw = true, seq = seq)
}

/** `HH:mm:ss.SSS L/tag: ` - the tag itself may contain a slash (core/HevTunnel). */
private val RESTORED_LINE =
    Regex("^[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3} ([DIWE])/([^:]*): ")

enum class CheckState { PENDING, RUNNING, PASS, FAIL }

/**
 * One drill-down row under a pipeline node: what was actually measured.
 *
 * Values are technical and rendered LTR monospace on purpose (an IP, a port, a
 * resolver, a timeout), so they are NOT translated - a Persian reader pasting
 * `exit ip 104.28.x.x` into a bug report needs the same string an English one
 * would. Only the section headings around them come from string resources.
 */
data class CheckFact(val label: String, val value: String)

data class ComponentCheck(
    val id: String,
    val label: String,
    val state: CheckState = CheckState.PENDING,
    val detail: String = "",
    /** Wall time the probe took, in ms. Null until it has run. */
    val latencyMs: Long? = null,
    val facts: List<CheckFact> = emptyList(),
)
