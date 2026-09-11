package studio.cluvex.aether.core.log

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Sole owner of the on-disk log.
 *
 * WHY THERE IS A FILE AT ALL: the hev tunnel runs IN-PROCESS, so a native fault
 * or an UnsatisfiedLinkError can take the whole app down, and a memory-only log
 * is wiped by that death - "there is no log after a crash". Every line is
 * mirrored to disk as it is written and reloaded on the next launch, so the
 * crashing session is always inspectable.
 *
 * ONE LOCK, ONE OWNER: appends, the size trim, the session reset and the crash
 * handler's synchronous write all go through the same monitor. Without that, a
 * trim racing the crash-line append could destroy exactly the line the log
 * exists to preserve.
 *
 * THREE FIXES IN THIS REVISION, all about the same failure - the process dying
 * mid-write, which for this app is the NORMAL case rather than the exotic one:
 *
 *  1. ATOMIC REPLACEMENT. The trim used to `readLines()` then `writeText()`
 *     over the live file. A SIGKILL between those two calls leaves a truncated
 *     or empty log: the trim destroys the evidence it was supposed to shrink.
 *     Content is now staged in a sibling temp file, fsync'd, and moved into
 *     place with a rename - which is atomic on the filesystem, so a reader ever
 *     sees the old file or the new one and never a half of either.
 *
 *  2. REAL UTF-8 BYTES. Budgets were measured in String length. Persian log
 *     text is two to three bytes per character, so a "512 KB" cap was really a
 *     1 MB+ file on a Farsi device, and the trim it triggered kept the wrong
 *     number of lines. [utf8ByteLength] counts what actually lands on disk,
 *     surrogate pairs included, without allocating a byte array.
 *
 *  3. NO WHOLE-FILE READS ON THE EXPORT PATH. `.prev` can be as large as the
 *     cap, and readText() on it allocates the file twice over. [forEachLine]
 *     and [openStream] hand out a streaming reader instead.
 */
internal class LogFile(private val maxBytes: Long, private val keepLinesOnTrim: Int) {

    @Volatile
    private var file: File? = null

    private val lock = Any()

    /** False until init() hands over a file; the writer waits rather than dropping. */
    val isReady: Boolean get() = file != null

    /**
     * Points the store at [target] and returns the PREVIOUS session's lines
     * (which are also preserved as `<name>.prev` before the file is reused).
     *
     * The read is byte-bounded from the END of the file: a log that somehow
     * grew past its cap (a crash between append and trim) must not be able to
     * OOM the launch that is trying to show it.
     */
    fun attach(target: File): List<String> = synchronized(lock) {
        file = target
        val previous = if (target.exists() && target.length() > 0L) {
            readTailLocked(target, restoreBudget())
        } else {
            emptyList()
        }
        if (previous.isNotEmpty()) rotateLocked(target)
        previous
    }

    /**
     * Appends [lines] as ONE write, and trims the file when it outgrows its
     * budget. Returns false when there was nothing to write to, or the write
     * failed - the caller then re-queues instead of losing the lines.
     *
     * One append per batch replaces the old open/write/close syscall trio PER
     * LINE that the inline flush did on the connect path.
     */
    fun append(lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val target = file ?: return false
        return synchronized(lock) {
            runCatching {
                target.appendText(lines.joinToString("\n", postfix = "\n"))
                if (target.length() > maxBytes) trimLocked(target)
            }.isSuccess
        }
    }

    /**
     * Starts a fresh session: the current file is rotated aside (never silently
     * destroyed, so a crash log stays recoverable) and emptied.
     */
    fun reset() {
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                if (target.exists() && target.length() > 0L) rotateLocked(target)
                writeAtomicLocked(target, "")
            }
        }
    }

    /**
     * Streams the log a line at a time. Nothing larger than one line is ever
     * held in memory, which is the whole point: `.prev` is allowed to be as big
     * as the cap, and the export path used to read it whole.
     *
     * Returns false when there is no such file.
     */
    fun forEachLine(previous: Boolean = false, action: (String) -> Unit): Boolean {
        val target = resolve(previous) ?: return false
        return runCatching {
            target.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach(action)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * A raw stream over the log, for handing to a share sheet or a file copy.
     * THE CALLER CLOSES IT. Null when the file is not there.
     */
    fun openStream(previous: Boolean = false): InputStream? {
        val target = resolve(previous) ?: return null
        return runCatching { target.inputStream() }.getOrNull()
    }

    /** The current log, or the rotated one. Null when absent or unattached. */
    fun resolve(previous: Boolean = false): File? {
        val target = file ?: return null
        val resolved = if (previous) {
            target.parentFile?.let { File(it, target.name + PREVIOUS_SUFFIX) } ?: return null
        } else {
            target
        }
        return resolved.takeIf { it.exists() }
    }

    /**
     * Keeps the file bounded without giving up crash survivability: the tail is
     * chosen by line count AND by real byte budget, and swapped in atomically.
     */
    private fun trimLocked(target: File) {
        runCatching {
            val kept = boundToByteBudget(
                readTailLocked(target, restoreBudget()).takeLast(keepLinesOnTrim),
                maxBytes,
            )
            rotateLocked(target)
            writeAtomicLocked(target, kept.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Copies the live file aside as `<name>.prev`, staged and renamed so a
     * death mid-copy cannot leave a truncated rotation either.
     */
    private fun rotateLocked(target: File) {
        val parent = target.parentFile ?: return
        runCatching {
            val previous = File(parent, target.name + PREVIOUS_SUFFIX)
            val staging = File(parent, target.name + PREVIOUS_SUFFIX + STAGING_SUFFIX)
            staging.delete()
            target.copyTo(staging, overwrite = true)
            if (!staging.renameTo(previous)) {
                staging.copyTo(previous, overwrite = true)
                staging.delete()
            }
        }
    }

    /**
     * Writes [contents] so that [target] is either entirely the old file or
     * entirely the new one. fsync BEFORE the rename: a rename that lands while
     * the staged bytes are still in the page cache buys nothing.
     */
    private fun writeAtomicLocked(target: File, contents: String) {
        val parent = target.parentFile
        if (parent == null) {
            target.writeText(contents)
            return
        }
        val staging = File(parent, target.name + STAGING_SUFFIX)
        staging.delete()
        FileOutputStream(staging).use { out ->
            out.write(contents.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!staging.renameTo(target)) {
            // Some filesystems refuse a rename onto an existing file. Falling
            // back is still better than leaving the staged copy behind.
            target.writeText(contents)
            staging.delete()
        }
    }

    /**
     * The last [budget] bytes of [target], as whole lines.
     *
     * The first partial line after the seek point is discarded, which also
     * guarantees the read never starts inside a multi-byte UTF-8 sequence: 0x0A
     * cannot appear as a continuation byte, so the byte after a newline is
     * always a character boundary.
     */
    private fun readTailLocked(target: File, budget: Long): List<String> = runCatching {
        RandomAccessFile(target, "r").use { raf ->
            val length = raf.length()
            val from = (length - budget).coerceAtLeast(0L)
            raf.seek(from)
            val bytes = ByteArray((length - from).toInt())
            raf.readFully(bytes)
            var start = 0
            if (from > 0L) {
                while (start < bytes.size && bytes[start] != NEWLINE) start++
                if (start < bytes.size) start++
            }
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
                .split('\n')
                .filter { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    /** Never read back more than this, whatever the cap is set to. */
    private fun restoreBudget(): Long = maxBytes.coerceIn(MIN_READ_BYTES, MAX_READ_BYTES)

    private companion object {
        const val PREVIOUS_SUFFIX = ".prev"
        const val STAGING_SUFFIX = ".tmp"
        const val MIN_READ_BYTES = 64L * 1024L
        const val MAX_READ_BYTES = 4L * 1024L * 1024L
        const val NEWLINE: Byte = 0x0A
    }
}

/**
 * Bytes [text] occupies as UTF-8, without allocating the encoded copy.
 *
 * String.length is a UTF-16 unit count, and this app's logs are routinely
 * Persian - two to three bytes per character, four for an emoji flag. Sizing a
 * file budget by length therefore under-counts by 2-3x exactly on the devices
 * this app is built for.
 */
internal fun utf8ByteLength(text: String): Long {
    var total = 0L
    var index = 0
    while (index < text.length) {
        val c = text[index]
        total += when {
            c.code < 0x80 -> 1L
            c.code < 0x800 -> 2L
            Character.isHighSurrogate(c) && index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1]) -> {
                index++
                4L
            }
            else -> 3L
        }
        index++
    }
    return total
}

/**
 * The longest suffix of [lines] that fits in [budget] UTF-8 bytes, newlines
 * included. The newest line is always kept, even when it alone is over budget:
 * a trim that returns nothing is a trim that deleted the log.
 */
internal fun boundToByteBudget(lines: List<String>, budget: Long): List<String> {
    if (lines.isEmpty()) return lines
    var used = 0L
    var first = lines.size
    for (index in lines.indices.reversed()) {
        val cost = utf8ByteLength(lines[index]) + 1L
        if (first < lines.size && used + cost > budget) break
        used += cost
        first = index
    }
    return lines.subList(first, lines.size)
}
