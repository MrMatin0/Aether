package studio.cluvex.aether.core

import android.util.Log
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.data.WgExperimentPrefs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the native `aether` engine (shipped as libaether.so) as a child
 * process. On Android an executable packaged in jniLibs is extracted to
 * nativeLibraryDir with the exec bit set, which is exactly what we run.
 *
 * THREAD SAFETY (this is not decoration — see below): [start] runs on the
 * connect coroutine, [isAlive] and [awaitExit] on the supervisor coroutine,
 * [stop] on the teardown coroutine or straight off the watchdog, and the
 * log-drain thread holds its own reference. All of those are different threads.
 */
class AetherProcess(
    private val nativeLibDir: String,
    private val workingDir: File,
) {
    /**
     * The live child process, or null.
     *
     * MUST stay @Volatile. Without it there is no happens-before edge between
     * the connect coroutine's write and the supervisor / teardown threads'
     * reads, so the JMM permits [stop] to observe a STALE null and return
     * without killing anything. The engine then keeps running — orphaned,
     * holding the local SOCKS5 listener on 127.0.0.1:1819, and invisible to
     * both isAlive() and a later stop(). That is precisely the "local port is
     * still busy" state the protocol-switch path downstream has to work
     * around, so the fix belongs here rather than in another retry loop.
     */
    @Volatile
    private var process: Process? = null

    /**
     * Serialises [start] against [stop]. Deliberately NOT held across
     * [awaitExit]: that parks for up to the whole watchdog interval, and
     * blocking a disconnect behind it is the latency bug this class already
     * fixed once.
     */
    private val lifecycleLock = Any()

    fun start(profile: ConnectionProfile) = synchronized(lifecycleLock) {
        // Reentrancy guard: spawning over a live engine would orphan it — the
        // old process keeps running (holding port 1819) but is invisible to
        // isAlive()/stop(). Checked under the lock so two concurrent connects
        // cannot both pass the check and both spawn.
        check(process?.isAlive != true) { "Engine is already running" }
        val bin = File(nativeLibDir, "libaether.so")
        if (!bin.exists()) {
            throw IllegalStateException("Engine binary missing: ${bin.absolutePath}")
        }

        val command = mutableListOf(bin.absolutePath).apply { addAll(profile.toArgs()) }
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        // Snapshot once per launch. Changing a lab switch never mutates a live
        // child, and boot/restart paths read the same persisted device settings.
        val experiments = WgExperimentPrefs.state.value.toEnv(profile.protocol)
        builder.environment().apply {
            putAll(profile.toEnv())
            putAll(experiments)
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", workingDir.absolutePath)
        }

        val proc = builder.start()
        process = proc

        DiagnosticsLog.i("engine", "WireGuard experiments: hop=${experiments["AETHER_WG_PORT_HOP"]}, padding=${experiments["AETHER_WG_DATA_PADDING"]}")
        DiagnosticsLog.i("engine", "Spawned ${bin.name} ${profile.toArgs().joinToString(" ")}")
        // Drain stdout/stderr so a full pipe never blocks the engine, mirroring
        // every line into both logcat and the in-app diagnostics panel.
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        // SECURITY: the engine's stdout (endpoints, exit IPs,
                        // config echo) must not be mirrored to Logcat in release
                        // builds — Logcat is world-readable via adb and ends up
                        // in bug reports. The in-app diagnostics panel
                        // (app-private file) still receives every line below.
                        if (BuildConfig.DEBUG) Log.i("aether-engine", it)
                        DiagnosticsLog.d("engine", it)
                        // Desktop-parity info row: pick out the endpoint the
                        // engine selected (no-op for every other line).
                        EngineMeta.ingest(it)
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w("engine", "Engine output stream closed.")
            }
        }, "aether-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Blocks until the engine exits or [timeoutMs] elapses; returns true if it
     * exited.
     *
     * 1.2.2 CPU FIX: the VpnService supervisor used to poll `isAlive()` every
     * two seconds for the whole life of the tunnel. That is thousands of
     * pointless wake-ups per session on a connection that is perfectly
     * healthy, and it keeps the CPU out of deep idle. `Process.waitFor` parks
     * the supervisor coroutine on the OS instead: it costs nothing while the
     * engine is running and returns the moment the engine actually dies, so
     * crash detection is FASTER than the old poll while using less power.
     *
     * The bounded overload is used so the supervisor still re-checks its own
     * cancellation state periodically.
     *
     * DISCONNECT-LATENCY ROOT CAUSE (fixed): this used to be a BLOCKING
     * java call. Coroutine cancellation cannot interrupt a blocking call, so
     * when the user tapped disconnect the service sat inside this wait until
     * the whole window expired. `runInterruptible` maps cancellation onto a
     * real thread interrupt, so teardown continues within milliseconds.
     * CancellationException is rethrown, not mapped to a timeout.
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
     * Stops the engine and does not return until the OS has really reaped it.
     *
     * 1.2.2 PROTOCOL-SWITCH FIX: a fire-and-forget destroy leaves the engine
     * holding its port while the next connect is starting. Wait for exit and
     * escalate to SIGKILL if necessary.
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
        /** How long a polite SIGTERM gets before we escalate to SIGKILL. */
        const val GRACEFUL_EXIT_MS = 250L
        /** How long a SIGKILL gets to be reaped before we give up waiting. */
        const val FORCE_EXIT_MS = 1_000L
    }
}
