package studio.cluvex.aether.core

import android.util.Log
import studio.cluvex.aether.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A native executable shipped in jniLibs and run as a child process.
 *
 * On Android an executable packaged under `lib/<abi>/` is extracted to
 * `nativeLibraryDir` with the exec bit set, and that directory is one of the
 * very few places the platform still allows exec from (W^X killed the app's
 * data dir in API 29). All three cores in a chain — the Rust engine, Psiphon's
 * console client and tor — are ordinary executables, so all three are launched
 * exactly this way.
 *
 * ### Why this class exists rather than a second copy of [AetherProcess]
 *
 * Getting a child process right on Android is not one line, and every rule
 * below was learned the hard way by the engine's supervisor:
 *
 *  - the handle MUST be @Volatile, or a teardown on another thread can read a
 *    stale null, return without killing anything, and leave an ORPHAN holding
 *    the local SOCKS5 port — which then looks exactly like "the port is still
 *    busy" on the next connect;
 *  - start and stop MUST be serialised, or two connects can both pass the
 *    liveness check and both spawn;
 *  - the output pipe MUST be drained, or a chatty core blocks on a full pipe
 *    and simply stops working;
 *  - the wait MUST be interruptible, or a disconnect sits on "Disconnecting…"
 *    for as long as the wait had left to run;
 *  - stop MUST NOT return before the OS has reaped the process, or the next
 *    core to bind that port fails.
 *
 * Duplicating that per core is how three cores end up with three different
 * sets of bugs, so it lives here once and [AetherProcess], [PsiphonCore] and
 * [TorCore] all wear it.
 */
class NativeChild(
    /** For logs: "engine", "psiphon", "tor". */
    private val tag: String,
    /** File name inside nativeLibraryDir, e.g. `libtor.so`. */
    private val binaryName: String,
    private val nativeLibDir: String,
    private val workingDir: File,
) {
    /** See the class KDoc: this @Volatile is load-bearing, not decoration. */
    @Volatile
    private var process: Process? = null

    /**
     * Serialises [start] against [stop]. Deliberately NOT held across
     * [awaitExit]: that parks for up to the whole watchdog interval, and
     * blocking a disconnect behind it is a latency bug, not a safety property.
     */
    private val lifecycleLock = Any()

    /** Absolute path of the binary, whether or not it exists. */
    val binary: File get() = File(nativeLibDir, binaryName)

    /**
     * True when this core is actually bundled in the running APK.
     *
     * Psiphon's and tor's binaries are fetched and built by
     * `scripts/fetch-natives.sh` / `scripts/build-natives.sh`; a build made
     * without them is a perfectly valid Aether build that simply cannot offer
     * the chain modes that need them. The UI has to be able to SAY that instead
     * of failing with a stack trace at connect time.
     */
    val isAvailable: Boolean get() = binary.canExecute() || binary.exists()

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Spawns the binary with [args] and [env] added to the inherited
     * environment. Every output line is handed to [onLine] on the drain thread.
     *
     * @throws IllegalStateException when the binary is missing, or when this
     *   child is already running (spawning over a live process would orphan it).
     */
    fun start(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
    ) = synchronized(lifecycleLock) {
        check(process?.isAlive != true) { "$tag is already running" }
        val bin = binary
        if (!bin.exists()) {
            throw IllegalStateException("$tag binary missing: ${bin.absolutePath}")
        }

        val builder = ProcessBuilder(listOf(bin.absolutePath) + args)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            putAll(env)
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", workingDir.absolutePath)
        }

        val proc = builder.start()
        process = proc
        DiagnosticsLog.i(tag, "Spawned ${bin.name} ${args.joinToString(" ")}")

        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // SECURITY: a core's stdout carries endpoints, exit IPs
                        // and config echoes. Logcat is world-readable via adb and
                        // ends up in bug reports, so release builds keep this in
                        // the app-private diagnostics file only.
                        if (BuildConfig.DEBUG) Log.i("aether-$tag", line)
                        DiagnosticsLog.d(tag, line)
                        runCatching { onLine(line) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w(tag, "$tag output stream closed.")
            }
        }, "aether-$tag-log").apply { isDaemon = true }.start()
    }

    /**
     * Blocks (interruptibly) until the process exits or [timeoutMs] elapses;
     * returns true if it exited (or was never running).
     *
     * `Process.waitFor` is a BLOCKING java call and coroutine cancellation
     * cannot interrupt one, which is why this goes through `runInterruptible`:
     * cancellation becomes a real thread interrupt, the wait throws immediately,
     * and teardown continues within milliseconds while a healthy core still
     * costs zero polling.
     *
     * CancellationException is rethrown, not mapped to "timeout": a cancelled
     * caller must unwind, and it must stay distinguishable from "the core
     * outlived the window".
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean =
        try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                process?.waitFor(timeoutMs, TimeUnit.MILLISECONDS) ?: true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /**
     * Stops the process and does not return until the OS has really reaped it.
     *
     * `destroy()` only ASKS a process to exit. A fire-and-forget destroy leaves
     * the old core alive — and still holding its local port — while the next
     * connect is already spawning its replacement, which is why switching
     * configuration used to look like it hung and then retried. Short grace
     * period, then SIGKILL, then confirm the reaping.
     */
    fun stop() = synchronized(lifecycleLock) {
        val proc = process ?: return@synchronized
        process = null
        runCatching {
            proc.destroy()
            if (!proc.waitFor(GRACEFUL_EXIT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(FORCE_EXIT_MS, TimeUnit.MILLISECONDS)
            }
        }
        Unit
    }

    private companion object {
        /**
         * How long a polite SIGTERM gets before we escalate to SIGKILL.
         *
         * Deliberately short: the user is waiting for the button to turn grey,
         * and the next connect independently waits for the local ports to be
         * released, so nothing depends on a long wait here.
         */
        const val GRACEFUL_EXIT_MS = 250L

        /** How long a SIGKILL gets to be reaped before we give up waiting. */
        const val FORCE_EXIT_MS = 1_000L
    }
}
