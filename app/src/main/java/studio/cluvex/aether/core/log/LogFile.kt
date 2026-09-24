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
 * THREE FILES, THREE MEANINGS:
 *
 *   `<name>`       the live session
 *   `<name>.prev`  whatever was last rotated aside: the pre-trim file, the
 *                  session before a clear, or the previous launch at attach
 *   `<name>.last`  the PREVIOUS LAUNCH, written once at attach and never again
 *
 * `.last` exists because `.prev` alone could not hold a crash log: the first
 * trim or clear of the NEW session rotated over it, so the evidence of a crash
 * survived exactly until the log grew past its cap or the reader tapped Clear.
 *
 * Durability rules, all about the process dying mid-write (the NORMAL case for
 * this app): content is staged in a sibling temp file, fsync'd and renamed into
 * place; budgets are counted in real UTF-8 bytes; export paths stream instead
 * of reading whole files.
 */
internal class LogFile(private val maxBytes: Long, private val keepLinesOnTrim: Int) {

    @Volatile
    private var file: File? = null

    private val lock = Any()

    /** False until init() hands over a file; the writer waits rather than dropping. */
    val isReady: Boolean get() = file != null

    /**
     * Points the store at [target] and returns the PREVIOUS session's lines
     * (which are also preserved as `<name>.prev` and `<name>.last` before the
     * file is reused).
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
        if (previous.isNotEmpty()) {
            rotateLocked(target)
            copyAsideLocked(target, LAST_LAUNCH_SUFFIX)
        } else {
            // A launch with nothing before it must not present a log from two
            // launches ago as "the previous launch".
            target.parentFile?.let { runCatching { File(it, target.name + LAST_LAUNCH_SUFFIX).delete() } }
        }
        previous
    }

    /**
     * Appends [lines] as ONE write, and trims the file when it outgrows its
     * budget. Returns false when there was nothing to write to, or the write
     * failed - the caller then re-queues instead of losing the lines.
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
     * destroyed) and emptied. `.last` is not touched.
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
     * held in memory. Returns false when there is no such file.
     */
    fun forEachLine(previous: Boolean = false, action: (String) -> Unit): Boolean =
        streamLines(resolve(previous), action)

    /** Streams the previous launch's log. False when there is none. */
    fun forEachLastLaunchLine(action: (String) -> Unit): Boolean =
        streamLines(resolveLastLaunch(), action)

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

    /** The previous launch's log. Null when absent or unattached. */
    fun resolveLastLaunch(): File? {
        val target = file ?: return null
        val parent = target.parentFile ?: return null
        return File(parent, target.name + LAST_LAUNCH_SUFFIX).takeIf { it.exists() }
    }

    private fun streamLines(target: File?, action: (String) -> Unit): Boolean {
        if (target == null) return false
        return runCatching {
            target.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach(action)
            }
            true
        }.getOrDefault(false)
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

    private fun rotateLocked(target: File) = copyAsideLocked(target, PREVIOUS_SUFFIX)

    /**
     * Copies the live file aside as `<name><suffix>`, staged and renamed so a
     * death mid-copy cannot leave a truncated copy either.
     */
    private fun copyAsideLocked(target: File, suffix: String) {
        val parent = target.parentFile ?: return
        runCatching {
            val aside = File(parent, target.name + suffix)
            val staging = File(parent, target.name + suffix + STAGING_SUFFIX)
            staging.delete()
            target.copyTo(staging, overwrite = true)
            if (!staging.renameTo(aside)) {
                staging.copyTo(aside, overwrite = true)
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
        const val LAST_LAUNCH_SUFFIX = ".last"
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
