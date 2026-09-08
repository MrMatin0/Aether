package studio.cluvex.aether.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the diagnostics panel renders: log lines and self-test rows.
 *
 * These live in their own file (and NOT inside DiagnosticsLog) because they are
 * the only part of the logger the UI is allowed to know about. The plumbing -
 * ring buffer, write queue, writer thread, file rotation - is internal to
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

enum class CheckState { PENDING, RUNNING, PASS, FAIL }

data class ComponentCheck(
    val id: String,
    val label: String,
    val state: CheckState = CheckState.PENDING,
    val detail: String = "",
)
