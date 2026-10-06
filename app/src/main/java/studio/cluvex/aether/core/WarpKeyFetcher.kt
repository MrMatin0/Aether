package studio.cluvex.aether.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

/** Where one protocol's manual key request stands. */
enum class WarpKeyPhase { IDLE, RUNNING, DONE, FAILED }

data class WarpKeyState(
    val active: Protocol? = null,
    val results: Map<Protocol, WarpKeyPhase> = emptyMap(),
) {
    val busy: Boolean get() = active != null

    fun phaseOf(protocol: Protocol): WarpKeyPhase =
        if (active == protocol) WarpKeyPhase.RUNNING else results[protocol] ?: WarpKeyPhase.IDLE
}

/**
 * Manual WARP identity (key) registration, one protocol at a time.
 *
 * WHY: the engine registers a WARP identity the first time a protocol is used,
 * and in some regions the registration API itself is filtered, so the very
 * first connect can never succeed. With Aether disconnected and another VPN app
 * active, this process's traffic leaves through that VPN, so the same engine
 * binary can register there instead.
 *
 * HOW: the bundled engine is started with the protocol's own flag on a side
 * SOCKS port ([BIND], so it never fights the session for 127.0.0.1:1819) in the
 * same working directory the session uses (`filesDir`, see NativeStack). The
 * engine provisions and atomically saves the identity before it scans, so the
 * moment every identity file the protocol needs is complete the engine is
 * stopped. File names follow lib.rs: `aether.toml` for WireGuard,
 * `aether-masque.toml` for MASQUE, and a `-secondary` sibling for the second
 * hop of Gool and MASQUE-in-MASQUE.
 *
 * A request always produces a FRESH identity: existing files are set aside
 * first and are only discarded once the new ones are complete. On failure,
 * timeout, cancel, or a session starting, they are put back untouched.
 */
object WarpKeyFetcher {
    val PROTOCOLS: List<Protocol> = listOf(Protocol.MASQUE, Protocol.MIM, Protocol.WIREGUARD, Protocol.GOOL)

    private const val TAG = "warp-key"
    private const val BIND = "127.0.0.1:1839"
    private const val TIMEOUT_MS = 150_000L
    private const val POLL_MS = 400L
    private const val BACKUP_SUFFIX = ".warpkey-bak"

    private const val PRIMARY_WG = "aether.toml"
    private const val SECONDARY_WG = "aether-secondary.toml"
    private const val PRIMARY_MASQUE = "aether-masque.toml"
    private const val SECONDARY_MASQUE = "aether-masque-secondary.toml"
    private val ALL_FILES = listOf(PRIMARY_WG, SECONDARY_WG, PRIMARY_MASQUE, SECONDARY_MASQUE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WarpKeyState())
    val state: StateFlow<WarpKeyState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** Only with no session: the session engine reads and writes the same files. */
    fun canRun(connection: ConnectionState): Boolean =
        connection is ConnectionState.Idle || connection is ConnectionState.Error

    @Synchronized
    fun start(context: Context, protocol: Protocol) {
        if (protocol !in PROTOCOLS || job?.isActive == true) return
        if (!canRun(AetherController.state.value)) return
        val app = context.applicationContext
        _state.update { it.copy(active = protocol) }
        job = scope.launch {
            val phase = try {
                if (fetch(app, protocol)) WarpKeyPhase.DONE else WarpKeyPhase.FAILED
            } catch (e: CancellationException) {
                WarpKeyPhase.IDLE
            } catch (e: Exception) {
                DiagnosticsLog.e(TAG, "key request crashed: ${e::class.java.simpleName}: ${e.message}")
                WarpKeyPhase.FAILED
            }
            _state.update { it.copy(active = null, results = it.results + (protocol to phase)) }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun fetch(context: Context, protocol: Protocol): Boolean {
        val dir = context.filesDir
        val bin = File(context.applicationInfo.nativeLibraryDir, "libaether.so")
        if (!bin.exists()) {
            DiagnosticsLog.e(TAG, "engine binary missing: ${bin.absolutePath}")
            return false
        }

        recover(dir)
        val masque = protocol.isMasque
        val targets = identityFiles(dir, protocol)
        val moved = mutableListOf<File>()
        for (file in targets) {
            if (!file.exists()) continue
            val backup = backupOf(file)
            backup.delete()
            if (!file.renameTo(backup)) {
                DiagnosticsLog.e(TAG, "could not set ${file.name} aside")
                restore(moved)
                return false
            }
            moved += file
        }

        var process: Process? = null
        var ok = false
        try {
            val profile = ConnectionProfile(
                protocol = protocol,
                scanMode = ScanMode.TURBO,
                quickReconnect = false,
            )
            val command = buildList {
                add(bin.absolutePath)
                addAll(profile.toArgs())
                add("--bind")
                add(BIND)
            }
            val builder = ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
            builder.environment().apply {
                putAll(profile.toEnv())
                put("AETHER_LOG_LEVEL", "info")
                put("HOME", dir.absolutePath)
                put("TMPDIR", dir.absolutePath)
            }

            DiagnosticsLog.i(TAG, "requesting a fresh ${protocol.name} identity")
            val proc = builder.start()
            process = proc
            drain(proc)

            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (true) {
                if (targets.all { complete(it, masque) }) {
                    ok = true
                    break
                }
                if (!proc.isAlive) {
                    ok = targets.all { complete(it, masque) }
                    break
                }
                if (!canRun(AetherController.state.value)) {
                    DiagnosticsLog.w(TAG, "a session is starting; key request stopped")
                    break
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    DiagnosticsLog.w(TAG, "key request timed out")
                    break
                }
                delay(POLL_MS)
            }
        } finally {
            process?.let { stop(it) }
            if (ok) moved.forEach { backupOf(it).delete() } else restore(moved)
            if (ok) {
                DiagnosticsLog.i(TAG, "new ${protocol.name} identity saved")
            } else {
                DiagnosticsLog.e(TAG, "${protocol.name} identity request failed; previous identity kept")
            }
        }
        return ok
    }

    private fun identityFiles(dir: File, protocol: Protocol): List<File> = when (protocol) {
        Protocol.WIREGUARD -> listOf(PRIMARY_WG)
        Protocol.GOOL -> listOf(PRIMARY_WG, SECONDARY_WG)
        Protocol.MASQUE -> listOf(PRIMARY_MASQUE)
        Protocol.MIM -> listOf(PRIMARY_MASQUE, SECONDARY_MASQUE)
        Protocol.AUTO -> emptyList()
    }.map { File(dir, it) }

    private fun backupOf(file: File): File = File(file.parentFile, file.name + BACKUP_SUFFIX)

    /** Puts every set-aside identity back, replacing whatever the engine left. */
    private fun restore(moved: List<File>) {
        for (file in moved) {
            val backup = backupOf(file)
            if (!backup.exists()) continue
            file.delete()
            if (!backup.renameTo(file)) DiagnosticsLog.e(TAG, "could not restore ${file.name}")
        }
    }

    /** A previous request killed mid-way (app process death) left backups behind. */
    private fun recover(dir: File) {
        for (name in ALL_FILES) {
            val file = File(dir, name)
            val backup = backupOf(file)
            if (!backup.exists()) continue
            if (complete(file, name.contains("masque"))) {
                backup.delete()
            } else {
                file.delete()
                backup.renameTo(file)
            }
        }
    }

    /** True once the engine has saved a usable identity (config.rs PersistedIdentity). */
    private fun complete(file: File, masque: Boolean): Boolean {
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        if (!hasValue(text, "device_id")) return false
        if (!hasValue(text, "wg_private_key") || !hasValue(text, "wg_peer_public_key")) return false
        return !masque || (hasValue(text, "cert_pem") && hasValue(text, "key_pem"))
    }

    private fun hasValue(text: String, key: String): Boolean {
        val match = Regex("(?m)^\\s*" + key + "\\s*=\\s*(.+)$").find(text) ?: return false
        val value = match.groupValues[1].trim()
        return value.isNotEmpty() && value != "\"\"" && value != "''"
    }

    private fun drain(process: Process) {
        Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { DiagnosticsLog.d(TAG, it) }
                }
            }
        }, "aether-warp-key").apply { isDaemon = true }.start()
    }

    private fun stop(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1_000, TimeUnit.MILLISECONDS)
            }
        }
    }
}
