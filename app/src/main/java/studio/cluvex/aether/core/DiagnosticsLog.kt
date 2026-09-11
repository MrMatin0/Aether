package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.core.log.LogDiskWriter
import studio.cluvex.aether.core.log.LogFile
import studio.cluvex.aether.core.log.LogPublisher
import studio.cluvex.aether.core.log.LogRingBuffer
import studio.cluvex.aether.core.log.LogSanitizer
import studio.cluvex.aether.core.log.PendingWrites
import studio.cluvex.aether.core.log.SelfTestChecks
import java.io.File

/**
 * The in-app log. Every moving part of the tunnel (engine process, in-process
 * hev tunnel, VpnService lifecycle, the connectivity self-tests) writes here, so
 * the panel can show exactly which stage fails when "connected but no site
 * loads" happens.
 *
 * This object is ONLY the front door. The pieces it used to do inline live next
 * to each other in core/log and are individually testable:
 *
 *   [LogRingBuffer] bounded in-memory buffer, snapshot cached by revision
 *   [LogPublisher]  one UI snapshot per interval, via sample() not a thread
 *   [PendingWrites] bounded queue, batch drop policy, suspending take
 *   [LogDiskWriter] the one coroutine allowed to touch flash storage
 *   [LogFile]       the file itself: atomic rotation, byte cap, crash survival
 *   [LogSanitizer]  what has to come out before a log leaves the device
 *
 * BECAUSE THE LOG IS THE RECORD OF AN INCIDENT, its own failure modes are
 * load-bearing: a logger that dies quietly during a failure destroys the only
 * evidence of it. Both workers are supervised and restart themselves, every
 * queue is bounded, and no path drops a line it has already taken - so the
 * logger can neither exhaust memory nor lose the tail that explains a crash.
 */
object DiagnosticsLog {

    private const val MAX_LINES = 800
    private const val UI_PUBLISH_INTERVAL_MS = 200L
    private const val MAX_FILE_BYTES = 512L * 1024L

    /** Far more than one session's worth of lines waiting to reach the disk. */
    private const val MAX_PENDING_WRITES = 8192

    /** Lines swept into a single file append. */
    private const val WRITE_BATCH_LINES = 256

    /**
     * Ceiling on a streamed export, in lines. A rotated log is bounded in BYTES,
     * and a caller that asks for it as one String needs a bound in lines too.
     */
    private const val MAX_STREAMED_LINES = 20_000

    private val buffer = LogRingBuffer(MAX_LINES)
    private val file = LogFile(MAX_FILE_BYTES, MAX_LINES / 2)
    private val pending = PendingWrites(MAX_PENDING_WRITES)
    private val writer = LogDiskWriter(file, pending, WRITE_BATCH_LINES)

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    // The snapshot is cached by revision, so an unchanged buffer assigns the
    // SAME instance here and StateFlow drops it: no recomposition for a tick
    // in which nothing was logged.
    private val publisher = LogPublisher(UI_PUBLISH_INTERVAL_MS) { _lines.value = buffer.snapshot() }

    /** The self-test board, owned by [SelfTestChecks]. */
    val checks: StateFlow<List<ComponentCheck>> get() = SelfTestChecks.checks

    /**
     * Wires the persistent log file (call once from Application.onCreate). A
     * file left over from a previous run - e.g. one that ended in a crash - is
     * preserved as `<name>.prev` and loaded back into the panel, so the crash is
     * visible after relaunch.
     */
    @Synchronized
    fun init(target: File) {
        val previous = file.attach(target)
        // Attaching is what makes the store ready, so anything the writer parked
        // on can move now.
        writer.ensureRunning()
        if (previous.isEmpty()) return
        // One slot is reserved for the header, so restoring a full log cannot
        // immediately evict the line that explains what is being looked at.
        val restored = previous.takeLast(MAX_LINES - 1)
            .map { LogLine(0L, "prev", LogLevel.DEBUG, it, raw = true) }
        val header = LogLine(
            System.currentTimeMillis(),
            "log",
            LogLevel.INFO,
            "\u2014\u2014 previous session restored (${restored.size} lines) \u2014\u2014",
        )
        buffer.replaceAll(restored + header)
        _lines.value = buffer.snapshot()
    }

    fun log(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        buffer.add(line)
        publisher.markDirty()
        // Queued for the writer coroutine instead of touching the disk inline.
        pending.add(line.format())
        writer.ensureRunning()
    }

    fun d(tag: String, m: String) = log(tag, LogLevel.DEBUG, m)
    fun i(tag: String, m: String) = log(tag, LogLevel.INFO, m)
    fun w(tag: String, m: String) = log(tag, LogLevel.WARN, m)
    fun e(tag: String, m: String) = log(tag, LogLevel.ERROR, m)

    /**
     * Writes [message] to the log file ON THE CALLING THREAD.
     *
     * The normal path hands lines to a writer coroutine, but no dispatcher is
     * guaranteed to be scheduled again once the process is dying - which is
     * exactly the case the crash handler cares about, and why the FATAL line
     * could be missing from the very log that was meant to explain the crash.
     *
     * ORDER MATTERS HERE, and it is the opposite of what it used to be: the line
     * is QUEUED FIRST and written second. Earlier revisions resolved the file
     * first and drained the queue after, so a store that was momentarily not
     * ready - init() still running, a rotation in flight - took the batch off
     * the queue and had nowhere to put it. Queue-then-write means the worst case
     * is a late line, never a lost one.
     */
    fun logBlocking(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        buffer.add(line)
        publisher.markDirty()
        pending.add(line.format())
        writer.ensureRunning()
        writer.flushBlocking()
    }

    /**
     * Pushes everything queued onto disk synchronously. For teardown paths -
     * VpnService.onDestroy, a deliberate process exit - where the writer
     * coroutine may not be scheduled again. Returns false when lines are still
     * queued, which never means they were dropped.
     */
    fun flushToDisk(): Boolean = writer.flushBlocking()

    /**
     * Starts a fresh session. The prior on-disk log is rotated to `<name>.prev`
     * rather than destroyed, so a crash log is always recoverable.
     */
    @Synchronized
    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
        // The queued lines belong to the session being cleared. Letting them
        // reach the writer is how a "cleared" log used to come back half full.
        pending.clear()
        file.reset()
    }

    fun setChecks(checks: List<ComponentCheck>) = SelfTestChecks.replace(checks)

    /**
     * Patches one self-test row. Null arguments mean "keep what is there", so a
     * caller that only knows the new state does not erase a measurement taken
     * by whoever set it.
     */
    fun updateCheck(
        id: String,
        state: CheckState,
        detail: String? = null,
        latencyMs: Long? = null,
        facts: List<CheckFact>? = null,
    ) = SelfTestChecks.update(id, state, detail, latencyMs, facts)

    /** Puts one row back to PENDING and forgets its measurements. */
    fun resetCheck(id: String) = SelfTestChecks.reset(id)

    /** The in-memory buffer, verbatim. What the console shows. */
    fun exportText(): String = buffer.snapshot().joinToString("\n") { it.format() }

    /**
     * The in-memory buffer with credentials removed. THIS is what the export and
     * copy buttons use: the log exists to be pasted into a bug report, and this
     * app's own settings include a Zero Trust client secret and an enrolment
     * token that anything echoing a command line or an HTTP header can carry.
     */
    fun exportSanitizedText(): String =
        buffer.snapshot().joinToString("\n") { LogSanitizer.sanitize(it.format()) }

    /**
     * The ON-DISK log, streamed and sanitized, for the previous session (or this
     * one). Bounded in lines as well as bytes, and never holds more than one
     * line plus the result in memory - `.prev` is allowed to be half a megabyte
     * and reading it whole is how an export OOMs a phone that is already in
     * trouble.
     */
    fun exportSanitizedDiskLog(previous: Boolean = false): String {
        val out = StringBuilder()
        var kept = 0
        file.forEachLine(previous) { line ->
            if (kept < MAX_STREAMED_LINES) {
                out.append(LogSanitizer.sanitize(line)).append('\n')
                kept++
            }
        }
        return out.toString()
    }

    /** Line-at-a-time access to the on-disk log. False when there is no file. */
    fun streamDiskLog(previous: Boolean = false, onLine: (String) -> Unit): Boolean =
        file.forEachLine(previous, onLine)

    /** True when a rotated log from an earlier session is on disk. */
    fun hasPreviousSessionOnDisk(): Boolean = file.resolve(previous = true) != null
}
