package studio.cluvex.aether.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val ts = TS_FORMAT.get()?.format(Date(timeMs)) ?: timeMs.toString()
        val lvl = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        return "$ts $lvl/$tag: $message"
    }

    private companion object {
        val TS_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

enum class CheckState { PENDING, RUNNING, PASS, FAIL }

data class ComponentCheck(
    val id: String,
    val label: String,
    val state: CheckState = CheckState.PENDING,
    val detail: String = "",
)

/**
 * In-app, professional-grade log + self-test store. Every moving part of the
 * tunnel (engine process, in-process hev tunnel, VpnService lifecycle and the
 * connectivity self-tests) writes here, so the UI can show exactly which stage
 * fails when "connected but no site loads" happens.
 *
 * CRASH-SURVIVAL (root-cause fix): now that hev runs IN-PROCESS, a native fault
 * or an [Error] such as UnsatisfiedLinkError can take the whole app down. A
 * memory-only log is wiped by that death, so the user "sees no log after a
 * crash". We therefore mirror every line to a file on disk as it is written and
 * reload it on next launch, so the crashing session is always inspectable.
 *
 * BECAUSE OF THAT, this class's OWN failure modes are load-bearing: a logger
 * that dies quietly during an incident destroys the only record of the
 * incident. Both worker threads below are therefore written to be unkillable,
 * and the write queue is bounded so the logger can never be the thing that
 * exhausts memory. See [ensurePublisher], [ensureWriter] and
 * [MAX_PENDING_WRITES].
 */
object DiagnosticsLog {
    private const val MAX_LINES = 800

    /**
     * 1.2.2 PERFORMANCE (memory + CPU).
     *
     * The 1.2.1 implementation did `_lines.value = _lines.value + line` on
     * EVERY log line. With an 800-line cap and a chatty engine that is an
     * O(n) array copy per line plus a `takeLast(800)` copy on top — i.e. two
     * fresh ~800-element lists allocated per log write, several times a
     * second during a scan. That is what made the app feel heavy and kept the
     * GC busy while connecting.
     *
     * The buffer is now a bounded [ArrayDeque] mutated in place: appending is
     * O(1) and eviction is O(1). Exactly ONE immutable snapshot is published
     * to the UI per batch, and batching is time-based
     * ([UI_PUBLISH_INTERVAL_MS]), so a burst of a hundred engine lines costs
     * one recomposition instead of a hundred.
     *
     * Disk I/O moved off the caller's thread as well: lines are queued and
     * flushed by a single dedicated writer thread, so no scanning/UI thread
     * ever blocks on flash storage. The file is also size-capped
     * ([MAX_FILE_BYTES]) so a long session can no longer grow it without
     * bound.
     */
    private const val UI_PUBLISH_INTERVAL_MS = 200L
    private const val MAX_FILE_BYTES = 512L * 1024L

    /**
     * Ceiling on lines waiting to reach the disk.
     *
     * OOM FIX: this queue used to be UNBOUNDED. Two ordinary situations grow it
     * without limit: [init] has not run yet (so the writer thread parks and
     * nothing drains), and the append keeps failing (full storage, revoked
     * directory) — in which case [logBlocking] hands every line it could not
     * write straight BACK to the queue. That turns the logger into the thing
     * that kills the process, during the exact failure it was trying to record.
     *
     * 8192 lines is far more than one session's worth of pending writes.
     */
    private const val MAX_PENDING_WRITES = 8192

    /** Bounded in-memory ring buffer. Guarded by [bufferLock]. */
    private val buffer = ArrayDeque<LogLine>(MAX_LINES)
    private val bufferLock = Any()

    /** Set when the buffer changed but the UI snapshot has not been published. */
    private val dirty = AtomicBoolean(false)

    /** Off-thread disk writer: never blocks a caller on flash I/O. */
    private val pendingWrites = LinkedBlockingQueue<String>(MAX_PENDING_WRITES)

    /** True once an overflow has been recorded, so the marker is only added once. */
    private val overflowed = AtomicBoolean(false)

    @Volatile
    private var writerStarted = false

    @Volatile
    private var publisherStarted = false

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val _checks = MutableStateFlow<List<ComponentCheck>>(emptyList())
    val checks: StateFlow<List<ComponentCheck>> = _checks.asStateFlow()

    @Volatile
    private var logFile: File? = null

    /**
     * Serialises EVERY mutation of [logFile] — the writer thread's appends,
     * [trimFile]'s truncate-and-rewrite, [clear]'s wipe and [logBlocking]'s
     * synchronous crash-line append. Without it, a trim racing the crash-line
     * append could destroy exactly the line the log exists to preserve.
     */
    private val fileLock = Any()

    /**
     * Wires the persistent log file (call once from Application.onCreate). If a
     * file from a previous run exists (e.g. it ended in a crash), its contents
     * are preserved to `<name>.prev` and loaded back into the panel so the
     * crash is visible after relaunch.
     */
    @Synchronized
    fun init(file: File) {
        logFile = file
        runCatching {
            if (file.exists() && file.length() > 0L) {
                val previous = file.readLines()
                runCatching { file.copyTo(File(file.parentFile, file.name + ".prev"), overwrite = true) }
                val restored = previous.takeLast(MAX_LINES).map {
                    LogLine(0L, "prev", LogLevel.DEBUG, it, raw = true)
                }
                val header = LogLine(
                    System.currentTimeMillis(),
                    "log",
                    LogLevel.INFO,
                    "—— previous session restored (${restored.size} lines) ——",
                )
                synchronized(bufferLock) {
                    buffer.clear()
                    buffer.addAll(restored)
                    buffer.addLast(header)
                    while (buffer.size > MAX_LINES) buffer.removeFirst()
                    _lines.value = buffer.toList()
                }
            }
        }
    }

    fun log(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        // O(1) append + O(1) eviction, no list copies.
        synchronized(bufferLock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        dirty.set(true)
        ensurePublisher()
        // Queue for the writer thread instead of touching the disk inline.
        enqueueWrite(line.format())
        ensureWriter()
    }

    /**
     * Queues one formatted line for the disk writer, making room by dropping the
     * OLDEST pending line when the queue is full.
     *
     * Dropping the oldest rather than the newest is deliberate: the reason this
     * queue ever backs up is that something is going wrong, and the lines that
     * explain what are the ones being written right now.
     */
    private fun enqueueWrite(formatted: String) {
        if (pendingWrites.offer(formatted)) return
        // Make room and note the loss exactly once, so the on-disk log cannot
        // silently pretend to be complete.
        pendingWrites.poll()
        if (overflowed.compareAndSet(false, true)) {
            pendingWrites.offer("—— log write queue overflowed; older lines were dropped ——")
            pendingWrites.poll()
        }
        pendingWrites.offer(formatted)
    }

    /** Publishes at most one immutable snapshot per [UI_PUBLISH_INTERVAL_MS]. */
    private fun ensurePublisher() {
        if (publisherStarted) return
        synchronized(bufferLock) {
            if (publisherStarted) return
            publisherStarted = true
        }
        thread(name = "log-publisher", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            // UNKILLABLE BY DESIGN: this loop used to be a bare `while (true)`
            // around Thread.sleep with no catch at all, so ONE
            // InterruptedException (or any unexpected throw) retired the
            // publisher for the rest of the process — and a dead publisher means
            // the diagnostics panel freezes on whatever snapshot it last saw,
            // which is worst exactly when the user is watching it to understand
            // a failure.
            while (true) {
                try {
                    Thread.sleep(UI_PUBLISH_INTERVAL_MS)
                    if (!dirty.compareAndSet(true, false)) continue
                    _lines.value = synchronized(bufferLock) { buffer.toList() }
                } catch (_: InterruptedException) {
                    // Nothing interrupts this thread on purpose; clear the flag
                    // and keep publishing rather than going silent.
                    Thread.interrupted()
                } catch (_: Throwable) {
                    // A snapshot that could not be published is not worth the
                    // panel going dark for the rest of the session.
                }
            }
        }
    }

    /**
     * Single writer thread. Drains the queue in batches so a burst of lines
     * becomes one file append instead of one `appendText` (open + write +
     * close) syscall trio per line, which is what the previous inline flush
     * did on the connect path.
     */
    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(bufferLock) {
            if (writerStarted) return
            writerStarted = true
        }
        thread(name = "log-writer", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val batch = ArrayList<String>(64)
            // Same reasoning as the publisher, with higher stakes: if this
            // thread dies, NOTHING is ever persisted again — including the FATAL
            // line written by the crash handler, which is the whole point of
            // having an on-disk log.
            while (true) {
                try {
                    batch.clear()
                    // Block for the first line, then sweep up whatever queued.
                    batch.add(pendingWrites.take())
                    pendingWrites.drainTo(batch, 256)
                    var file = logFile
                    while (file == null) {
                        // init() has not run yet. Waiting beats the old
                        // behaviour, which dropped the whole drained batch.
                        Thread.sleep(200)
                        file = logFile
                    }
                    val target = file
                    synchronized(fileLock) {
                        runCatching {
                            target.appendText(batch.joinToString("\n", postfix = "\n"))
                            if (target.length() > MAX_FILE_BYTES) trimFile(target)
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.interrupted()
                } catch (_: Throwable) {
                    // Never let one bad batch end the writer. Back off a little
                    // so a persistent failure cannot spin a core.
                    runCatching { Thread.sleep(200) }
                }
            }
        }
    }

    /**
     * Keeps the on-disk log bounded: rotates to `<name>.prev` and restarts the
     * live file with the most recent lines, so crash-survivability is kept
     * without letting storage grow forever.
     */
    private fun trimFile(file: File) {
        runCatching {
            file.copyTo(File(file.parentFile, file.name + ".prev"), overwrite = true)
            val keep = file.readLines().takeLast(MAX_LINES / 2)
            file.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    fun d(tag: String, m: String) = log(tag, LogLevel.DEBUG, m)
    fun i(tag: String, m: String) = log(tag, LogLevel.INFO, m)
    fun w(tag: String, m: String) = log(tag, LogLevel.WARN, m)
    fun e(tag: String, m: String) = log(tag, LogLevel.ERROR, m)

    /**
     * Writes [message] to the log file ON THE CALLING THREAD.
     *
     * The normal path queues lines for a writer thread (so no hot path ever
     * blocks on flash I/O), but that thread is not guaranteed to run again once
     * the process is dying — which is exactly the case the crash handler cares
     * about, and why the FATAL line could be missing from the very log that was
     * meant to explain the crash. This appends synchronously instead, then
     * mirrors the line into the in-memory buffer for the panel.
     */
    fun logBlocking(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        synchronized(bufferLock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        dirty.set(true)

        // LOG-LOSS FIX: the previous version drained [pendingWrites] FIRST and
        // only then looked for the log file. If init() had not run yet, or the
        // append threw, those lines were already off the queue and gone for
        // good — taking the tail of the log the crash report exists to explain
        // with them. Resolve the file first, and hand anything we could not
        // write back to the writer thread instead of dropping it.
        val file = logFile
        if (file == null) {
            enqueueWrite(line.format())
            ensureWriter()
            return
        }

        val pending = ArrayList<String>(64)
        pendingWrites.drainTo(pending)
        pending.add(line.format())
        val written = runCatching {
            synchronized(fileLock) {
                file.appendText(pending.joinToString("\n", postfix = "\n"))
            }
        }.isSuccess
        if (!written) {
            // Bounded re-queue (see MAX_PENDING_WRITES): a store that keeps
            // failing used to grow this queue without limit, one full drain per
            // crash-handler call.
            pending.forEach { enqueueWrite(it) }
            ensureWriter()
        }
    }

    /**
     * Starts a fresh session. The prior on-disk log is rotated to `<name>.prev`
     * (never silently destroyed) so a crash log is always recoverable.
     */
    @Synchronized
    fun clear() {
        synchronized(bufferLock) { buffer.clear() }
        _lines.value = emptyList()
        runCatching {
            logFile?.let { f ->
                synchronized(fileLock) {
                    if (f.exists() && f.length() > 0L) {
                        f.copyTo(File(f.parentFile, f.name + ".prev"), overwrite = true)
                    }
                    f.writeText("")
                }
            }
        }
    }

    @Synchronized
    fun setChecks(checks: List<ComponentCheck>) {
        _checks.value = checks
    }

    @Synchronized
    fun updateCheck(id: String, state: CheckState, detail: String? = null) {
        _checks.value = _checks.value.map {
            if (it.id == id) it.copy(state = state, detail = detail ?: it.detail) else it
        }
    }

    fun exportText(): String =
        synchronized(bufferLock) { buffer.toList() }.joinToString("\n") { it.format() }
}
