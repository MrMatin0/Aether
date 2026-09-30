package studio.cluvex.aether.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/*
 * EXPERIMENTAL: the Proteus core (https://github.com/unblockable/proteus).
 *
 * Proteus tunnels traffic with a protocol described in a PSF (protocol
 * specification file). This app runs its standalone client:
 *
 *     proteus client <psf> --connect <server-ip:port> --listen 127.0.0.1:1829
 *                          --mode tunnel|stream --persist <bool>
 *
 * which opens a local SOCKS5 server (TCP CONNECT only, no UDP ASSOCIATE). That
 * listener is fronted by SocksFront exactly like Psiphon's and Tor's, so DNS
 * goes over TCP through the tunnel.
 *
 * Proteus has no upstream-proxy option, so it is always the internet-facing
 * hop of a chain. The PT (pluggable transport) mode is deliberately NOT used:
 * it takes the PSF path from the bridge line and refuses an upstream proxy.
 *
 * Settings live in their own small store ([ProteusSettingsStore]) rather than
 * in ConnectionProfile while the feature is experimental: they are local to
 * this device and are not part of a profile export. See docs/PROTEUS.md.
 */

/** `proteus client --mode`. */
enum class ProteusMode(val wire: String) {
    /** One long-lived tunnel to the server, every app stream inside it (~VPN). */
    TUNNEL("tunnel"),

    /** A new tunnel to the server per app stream (~shadowsocks). */
    STREAM("stream"),
    ;

    companion object {
        /** Unknown or missing values fall back to upstream's default, tunnel. */
        fun fromStored(raw: String?): ProteusMode {
            val value = raw?.trim()?.lowercase() ?: return TUNNEL
            return entries.firstOrNull { it.wire == value || it.name.lowercase() == value } ?: TUNNEL
        }
    }
}

/** What the user configured for Proteus on the Chain page. */
data class ProteusSettings(
    /** `host:port` or `[ipv6]:port` of the user's `proteus server`. */
    val server: String = "",
    /** The PSF text. Must be the one the server runs with. */
    val psf: String = "",
    val mode: ProteusMode = ProteusMode.TUNNEL,
    /** `--persist`. Must match the server's own setting. */
    val persist: Boolean = false,
) {
    /** Enough to attempt a start. Whether the PSF compiles is proteus's call. */
    val isComplete: Boolean
        get() = ProteusArgs.parseServer(server) != null && psf.isNotBlank()
}

/** App-private SharedPreferences store for [ProteusSettings]. Single process. */
object ProteusSettingsStore {
    private const val PREFS = "proteus_settings"
    private const val KEY_SERVER = "server"
    private const val KEY_PSF = "psf"
    private const val KEY_MODE = "mode"
    private const val KEY_PERSIST = "persist"

    fun load(context: Context): ProteusSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ProteusSettings(
            server = prefs.getString(KEY_SERVER, null).orEmpty(),
            psf = prefs.getString(KEY_PSF, null).orEmpty(),
            mode = ProteusMode.fromStored(prefs.getString(KEY_MODE, null)),
            persist = prefs.getBoolean(KEY_PERSIST, false),
        )
    }

    fun save(context: Context, settings: ProteusSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER, settings.server)
            .putString(KEY_PSF, settings.psf)
            .putString(KEY_MODE, settings.mode.wire)
            .putBoolean(KEY_PERSIST, settings.persist)
            .apply()
    }
}

/**
 * The pure half of the Proteus core: input validation and the command line.
 * No Android, no I/O, so it is pinned by ProteusArgsTest on the JVM.
 */
internal object ProteusArgs {

    const val LISTEN_HOST = "127.0.0.1"

    data class Server(val host: String, val port: Int) {
        val isIpv6Literal: Boolean get() = host.contains(':')
        val isIpv4Literal: Boolean get() = Socks5Wire.ipv4Bytes(host) != null
        val isLiteral: Boolean get() = isIpv4Literal || isIpv6Literal
    }

    private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    /**
     * `1.2.3.4:443`, `example.com:443` or `[2001:db8::1]:443`, or null.
     *
     * A bare IPv6 address is rejected rather than guessed at: in
     * `2001:db8::1:443` there is no way to tell where the address ends.
     */
    fun parseServer(raw: String?): Server? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val host: String
        val portText: String
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0 || close + 1 >= text.length || text[close + 1] != ':') return null
            host = text.substring(1, close)
            portText = text.substring(close + 2)
            if (!isIpv6(host)) return null
        } else {
            val colon = text.lastIndexOf(':')
            if (colon <= 0) return null
            host = text.substring(0, colon)
            portText = text.substring(colon + 1)
            if (host.contains(':')) return null
            if (!isIpv4OrHostname(host)) return null
        }
        val port = parsePort(portText) ?: return null
        return Server(host, port)
    }

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..65535 }
    }

    private fun isIpv6(host: String): Boolean =
        host.length in 2..45 && host.contains(':') &&
            host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    private fun isIpv4OrHostname(host: String): Boolean {
        // All digits and dots: it is meant as an IPv4 literal, so it must BE one.
        if (host.all { it in '0'..'9' || it == '.' }) return Socks5Wire.ipv4Bytes(host) != null
        if (host.length > 253) return false
        val name = host.removeSuffix(".")
        if (name.isEmpty()) return false
        return name.split('.').all { LABEL.matches(it) }
    }

    /** A Rust `SocketAddr` literal: IPv6 in brackets. */
    fun socketAddress(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /** The PSF as proteus should read it: LF line endings, one trailing newline. */
    fun normalizePsf(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

    /** The full `proteus` argument list (global args first, then the subcommand). */
    fun clientArgs(
        psfPath: String,
        connect: String,
        listenPort: Int,
        mode: ProteusMode,
        persist: Boolean,
    ): List<String> = listOf(
        "--log-level", "info",
        "client", psfPath,
        "--connect", connect,
        "--listen", "$LISTEN_HOST:$listenPort",
        "--mode", mode.wire,
        "--persist", persist.toString(),
    )
}

/**
 * One Proteus client process. Same lifecycle rules as every other core, via
 * [NativeChild]; readiness is proven end-to-end with a SOCKS5 CONNECT through
 * it, because the listener opens long before the server is reachable.
 */
class ProteusCore(
    private val context: Context,
    filesDir: File,
) {
    enum class StartResult {
        STARTED,

        /** No valid server address or no PSF: nothing was spawned. */
        NOT_CONFIGURED,

        /** The server hostname did not resolve: nothing was spawned. */
        UNRESOLVED,
    }

    private val workDir = File(filesDir, "proteus").apply { mkdirs() }

    private val child = NativeChild(
        tag = TAG,
        binaryName = BINARY,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
        workingDir = workDir,
    )

    val isAvailable: Boolean get() = child.isAvailable
    val isAlive: Boolean get() = child.isAlive()

    /** proteus printed an error that points at the PSF (parse / compile). */
    @Volatile
    var configRejected: Boolean = false
        private set

    /** The last error-looking line proteus printed, for the diagnostics row. */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun start(settings: ProteusSettings): StartResult {
        val server = ProteusArgs.parseServer(settings.server) ?: return StartResult.NOT_CONFIGURED
        if (settings.psf.isBlank()) return StartResult.NOT_CONFIGURED
        // `proteus client --connect` wants a literal socket address.
        val address = resolve(server) ?: return StartResult.UNRESOLVED

        val psfFile = File(workDir, PSF_FILE)
        withContext(Dispatchers.IO) { psfFile.writeText(ProteusArgs.normalizePsf(settings.psf)) }

        configRejected = false
        lastError = null
        val args = ProteusArgs.clientArgs(
            psfPath = psfFile.absolutePath,
            connect = ProteusArgs.socketAddress(address, server.port),
            listenPort = SOCKS_PORT,
            mode = settings.mode,
            persist = settings.persist,
        )
        child.start(args, env = mapOf("RUST_BACKTRACE" to "1")) { line -> onLine(line) }
        return StartResult.STARTED
    }

    /**
     * The server as a literal. The app itself is excluded from the TUN, so this
     * lookup goes out directly, as the proteus process's own traffic does.
     */
    private suspend fun resolve(server: ProteusArgs.Server): String? {
        if (server.isLiteral) return server.host
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                val all = InetAddress.getAllByName(server.host)
                (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())
                    ?.hostAddress?.substringBefore('%')
            }.getOrNull()
        }
        if (resolved == null) {
            DiagnosticsLog.w(TAG, "Could not resolve the Proteus server ${server.host}.")
        } else {
            DiagnosticsLog.i(TAG, "Proteus server ${server.host} resolved to $resolved.")
        }
        return resolved
    }

    private fun onLine(line: String) {
        val lower = line.lowercase()
        if ("error" in lower || "caused by" in lower || "panicked" in lower) {
            lastError = line.trim().take(MAX_ERROR_CHARS)
            if (PSF_HINTS.any { it in lower }) configRejected = true
        }
    }

    /**
     * True once a SOCKS5 CONNECT through proteus to a well-known address
     * succeeds, false when the process dies or [timeoutMs] runs out.
     */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var index = 0
        while (true) {
            if (!child.isAlive()) return false
            val (host, port) = PROBE_TARGETS[index % PROBE_TARGETS.size]
            index++
            val ok = withContext(Dispatchers.IO) {
                val socket = Socks5Wire.dial(
                    ProteusArgs.LISTEN_HOST,
                    SOCKS_PORT,
                    host,
                    port,
                    PROBE_CONNECT_MS,
                    PROBE_READ_MS,
                )
                if (socket != null) runCatching { socket.close() }
                socket != null
            }
            if (ok) return true
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            delay(minOf(PROBE_GAP_MS, left))
        }
    }

    suspend fun awaitExit(timeoutMs: Long): Boolean = child.awaitExit(timeoutMs)

    fun stop() = child.stop()

    companion object {
        const val BINARY = "libproteus.so"

        /** proteus's local SOCKS5 listener. Next to Aether's 1819 family. */
        const val SOCKS_PORT = 1829

        private const val TAG = "proteus"
        private const val PSF_FILE = "client.psf"
        private const val MAX_ERROR_CHARS = 300

        private const val PROBE_CONNECT_MS = 3_000
        private const val PROBE_READ_MS = 10_000
        private const val PROBE_GAP_MS = 1_500L

        private val PROBE_TARGETS = listOf(
            "1.1.1.1" to 443,
            "8.8.8.8" to 53,
            "9.9.9.9" to 443,
        )

        private val PSF_HINTS = listOf("psf", "parse", "compile", "specification")
    }
}
