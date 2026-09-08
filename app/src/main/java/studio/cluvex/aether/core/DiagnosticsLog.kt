package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.core.log.LogDiskWriter
import studio.cluvex.aether.core.log.LogFile
import studio.cluvex.aether.core.log.LogPublisher
import studio.cluvex.aether.core.log.LogRingBuffer
import studio.cluvex.aether.core.log.PendingWrites
import studio.cluvex.aether.core.log.SelfTestChecks
import java.io.File

/**
 * The in-app log. Every moving part of the tunnel (engine process, in-process
 * hev tunnel, VpnService lifecycle, the connectivity self-tests) writes here, so
 * the panel can show exactly which stage fails when "connected but no site
 * loads" happens.
 *
 * This object is now ONLY the front door. The five things it used to do inline
 * live next to each other in core/log and are individually testable:
 *
 *   [LogRingBuffer] bounded in-memory buffer
 *   [LogPublisher]  one UI snapshot per interval, not one per line
 *   [PendingWrites] bounded queue with an explicit drop policy
 *   [LogDiskWriter] the one thread allowed to touch flash storage
 *   [LogFile]       the file itself: rotation, size cap, crash survival
 *
 * BECAUSE THE LOG IS THE RECORD OF AN INCIDENT, its own failure modes are
 * load-bearing: a logger that dies quietly during a failure destroys the only
 * evidence of it. Both worker threads are written to be unkillable and every
 * queue is bounded, so the logger can never be the thing that exhausts memory.
 */
object DiagnosticsLog {

    private const val MAX_LINES = 800
    private const val UI_PUBLISH_INTERVAL_MS = 200L
    private const val MAX_FILE_BYTES = 512L * 1024L

    /** Far more than one session's worth of lines waiting to reach the disk. */
    private const val MAX_PENDING_WRITES = 8192

    /** Lines swept into a single file append. */
    private const val WRITE_BATCH_LINES = 256

    private val buffer = LogRingBuffer(MAX_LINES)
    private val file = LogFile(MAX_FILE_BYTES, MAX_LINES / 2)
    private val pending = PendingWrites(MAX_PENDING_WRITES)
    private val writer = LogDiskWriter(file, pending, WRITE_BATCH_LINES)

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

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
        if (previous.isEmpty()) return
        // One slot is reserved for the header, so restoring a full log cannot
        // immediately evict the line that explains what is being looked at.
        val restored = previous.takeLast(MAX_LINES - 1)
            .map { LogLine(0L, "prev", LogLevel.DEBUG, it, raw = true) }
        val header = LogLine(
            System.currentTimeMillis(),
            "log",
            LogLevel.INFO,
            "—— previous session restored (${restored.size} lines) ——",
        )
        buffer.replaceAll(restored + header)
        _lines.value = buffer.snapshot()
    }

    fun log(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        buffer.add(line)
        publisher.markDirty()
        // Queued for the writer thread instead of touching the disk inline.
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
     * The normal path hands lines to a writer thread, but that thread is not
     * guaranteed to be scheduled again once the process is dying - which is
     * exactly the case the crash handler cares about, and why the FATAL line
     * could be missing from the very log that was meant to explain the crash.
     *
     * ORDER MATTERS HERE: the file is resolved FIRST. The previous version
     * drained the queue and only then looked for a file, so if init() had not
     * run yet (or the append threw) those lines were already off the queue and
     * gone for good.
     */
    fun logBlocking(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        buffer.add(line)
        publisher.markDirty()

        if (!file.isReady) {
            pending.add(line.format())
            writer.ensureRunning()
            return
        }

        val batch = pending.drainNow()
        batch.add(line.format())
        if (!file.append(batch)) {
            // Bounded re-queue: hand back what could not be written instead of
            // dropping it, and let the writer thread keep trying.
            pending.addAll(batch)
            writer.ensureRunning()
        }
    }

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

    fun updateCheck(id: String, state: CheckState, detail: String? = null) =
        SelfTestChecks.update(id, state, detail)

    fun exportText(): String = buffer.snapshot().joinToString("\n") { it.format() }
}
