package studio.cluvex.aether.core.log

import java.io.File

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
     */
    fun attach(target: File): List<String> = synchronized(lock) {
        file = target
        val previous = if (target.exists() && target.length() > 0L) {
            runCatching { target.readLines() }.getOrDefault(emptyList())
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
                target.writeText("")
            }
        }
    }

    /** Keeps the file bounded without giving up crash survivability. */
    private fun trimLocked(target: File) {
        runCatching {
            val keep = target.readLines().takeLast(keepLinesOnTrim)
            rotateLocked(target)
            target.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    private fun rotateLocked(target: File) {
        val parent = target.parentFile ?: return
        runCatching { target.copyTo(File(parent, target.name + PREVIOUS_SUFFIX), overwrite = true) }
    }

    private companion object {
        const val PREVIOUS_SUFFIX = ".prev"
    }
}
