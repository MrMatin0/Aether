package studio.cluvex.aether.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONObject
import studio.cluvex.aether.model.ConnectionProfile
import java.io.File

/**
 * psiphon-tunnel-core's console client, run as a child process.
 *
 * ### Why the console client and not the gomobile library
 *
 * Psiphon also ships `ca.psiphon:psiphontunnel`, an in-process Go library. This
 * app already runs its main core as an exec'd binary out of `jniLibs`, has a
 * process supervisor built for exactly that ([NativeChild]), and gets crash and
 * teardown isolation for free: a Go runtime panic kills a child, not the
 * VpnService that is holding the user's TUN. The library would also pull a
 * ~6 MB AAR from a raw.githubusercontent Maven repo into a circumvention app's
 * dependency graph, which is a supply-chain decision this repo has consistently
 * refused to make (see `scripts/fetch-natives.sh`: everything is built from
 * source, nothing is vendored as a blob).
 *
 * ### Configuration
 *
 * Psiphon needs a real client config: `PropagationChannelId` and `SponsorId`
 * are issued by the Psiphon network and the server-discovery URLs and signing
 * keys travel with them. Those values are NOT in this repository (they are not
 * ours to ship), so the config comes from, in order:
 *
 *  1. what the user pasted into Settings ([ConnectionProfile.psiphonConfig]);
 *  2. `assets/psiphon.config`, which `scripts/fetch-natives.sh` can place there
 *     at build time and which is gitignored like every other fetched artifact.
 *
 * With neither, the Psiphon chain modes report a clear "no Psiphon config"
 * error instead of failing somewhere deep in a Go stack trace. See
 * `docs/CHAINING.md`.
 */
class PsiphonCore(
    private val context: Context,
    filesDir: File,
) {
    /**
     * Psiphon assumes FULL control of its data root (it deletes, moves and
     * overwrites files there), so it gets its own directory and not filesDir.
     */
    private val dataDir = File(filesDir, "psiphon").apply { mkdirs() }

    private val child = NativeChild(
        tag = TAG,
        binaryName = BINARY,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
        workingDir = dataDir,
    )

    /** How many tunnels the core reports. Written on the log-drain thread. */
    @Volatile
    private var tunnelCount = 0

    /** The port the core says it is listening on, from its own notice. */
    @Volatile
    private var listeningPort = 0

    /** Last server region the core reported connecting to, for the info row. */
    @Volatile
    var egressRegion: String? = null
        private set

    val isAvailable: Boolean get() = child.isAvailable
    val isAlive: Boolean get() = child.isAlive()

    /** Ground truth: the core has a working tunnel, not merely an open port. */
    val isReady: Boolean get() = tunnelCount > 0

    /** True when a usable config exists (pasted by the user, or bundled). */
    fun hasConfig(profile: ConnectionProfile): Boolean = baseConfig(profile) != null

    /**
     * Starts the core.
     *
     * @param upstreamPort local SOCKS5 port Psiphon must dial its own upstream
     *   through (the previous hop in the chain), or null to dial directly.
     */
    fun start(profile: ConnectionProfile, upstreamPort: Int?) {
        val base = baseConfig(profile)
            ?: throw IllegalStateException("No Psiphon config available")
        val config = buildConfig(base, profile, upstreamPort)
        val configFile = File(dataDir, "psiphon.config")
        configFile.writeText(config)
        tunnelCount = 0
        listeningPort = 0
        egressRegion = null
        DiagnosticsLog.i(
            TAG,
            "Starting Psiphon on 127.0.0.1:${TunnelConfig.PSIPHON_SOCKS_PORT}" +
                (upstreamPort?.let { " through 127.0.0.1:$it" } ?: " (direct)") +
                (profile.psiphonRegion.takeIf { it.isNotBlank() }?.let { " region=$it" } ?: ""),
        )
        child.start(args = listOf("-config", configFile.absolutePath), onLine = ::onNotice)
    }

    fun stop() = child.stop()

    suspend fun awaitExit(timeoutMs: Long): Boolean = child.awaitExit(timeoutMs)

    /**
     * Waits for a real tunnel, not just for the port to answer.
     *
     * The local SOCKS5 listener opens almost immediately, long before the core
     * has found a server; gating on the port would report "connected" and then
     * refuse every CONNECT with a SOCKS failure. Returns false on timeout or if
     * the core dies, and the caller decides what to tell the user.
     */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isReady) {
                DiagnosticsLog.i(
                    TAG,
                    "Psiphon tunnel established (port ${listeningPort.takeIf { it > 0 } ?: TunnelConfig.PSIPHON_SOCKS_PORT})",
                )
                return true
            }
            if (!isAlive) {
                DiagnosticsLog.e(TAG, "Psiphon exited before it established a tunnel.")
                return false
            }
            delay(POLL_MS)
        }
        DiagnosticsLog.e(TAG, "Psiphon did not establish a tunnel within ${timeoutMs / 1000}s.")
        return false
    }

    // ------------------------------------------------------------- internals

    /**
     * The user's pasted config, else the bundled asset, else null. Trimmed and
     * validated only as far as "it parses as a JSON object": the core itself is
     * the authority on what a valid config is, and duplicating its rules here
     * would just reject configs it accepts.
     */
    private fun baseConfig(profile: ConnectionProfile): String? {
        val pasted = profile.psiphonConfig.trim()
        if (pasted.isNotEmpty() && runCatching { JSONObject(pasted) }.isSuccess) return pasted
        if (pasted.isNotEmpty()) {
            DiagnosticsLog.w(TAG, "The Psiphon config in Settings is not valid JSON - ignoring it.")
        }
        return runCatching {
            context.assets.open(ASSET_CONFIG).bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { text -> runCatching { JSONObject(text) }.isSuccess }
    }

    /**
     * Overlays the fields the app owns onto the network-issued config. Every
     * `put` here is deliberate; anything else the config carries (server list
     * URLs, signing keys, tactics) is left exactly as issued.
     */
    private fun buildConfig(
        base: String,
        profile: ConnectionProfile,
        upstreamPort: Int?,
    ): String {
        val json = JSONObject(base)

        // Ports and paths are ours: the config's own values would collide with
        // a separately installed Psiphon app (its default is 1080).
        json.put("DataRootDirectory", dataDir.absolutePath)
        json.put("LocalSocksProxyPort", TunnelConfig.PSIPHON_SOCKS_PORT)
        json.put("ListenInterface", TunnelConfig.SOCKS_HOST)
        // One listener is enough: everything in this app speaks SOCKS5, and an
        // extra unauthenticated HTTP proxy is one more thing bound on loopback.
        json.put("DisableLocalHTTPProxy", true)
        json.put("DisableLocalSocksProxy", false)

        // Reported to the network for stats; also what tells Psiphon it is on a
        // phone rather than a server.
        json.put("ClientPlatform", "Android_${Build.VERSION.RELEASE}_${context.packageName}")
        json.put("EmitDiagnosticNotices", true)

        // CHAINING: this single field is what "Psiphon over Aether" is.
        if (upstreamPort != null) {
            json.put("UpstreamProxyUrl", "socks5://${TunnelConfig.SOCKS_HOST}:$upstreamPort")
        } else {
            json.remove("UpstreamProxyUrl")
        }

        val region = sanitizedRegion(profile.psiphonRegion)
        if (region != null) json.put("EgressRegion", region) else json.remove("EgressRegion")

        if (!json.has("PropagationChannelId") || !json.has("SponsorId")) {
            DiagnosticsLog.w(
                TAG,
                "Psiphon config has no PropagationChannelId/SponsorId - the core will refuse it.",
            )
        }
        return json.toString()
    }

    /** ISO 3166-1 alpha-2 or nothing; a typo must not become a config field. */
    private fun sanitizedRegion(raw: String): String? =
        raw.trim().uppercase().takeIf { it.matches(Regex("^[A-Z]{2}$")) }

    /**
     * psiphon-tunnel-core emits one JSON notice per line. Only four of them
     * matter here; the rest are already in the diagnostics log verbatim because
     * [NativeChild] mirrors every line.
     */
    private fun onNotice(line: String) {
        val text = line.trim()
        if (!text.startsWith("{")) return
        val notice = runCatching { JSONObject(text) }.getOrNull() ?: return
        val data = notice.optJSONObject("data")
        when (notice.optString("noticeType")) {
            "ListeningSocksProxyPort" -> {
                listeningPort = data?.optInt("port") ?: 0
                DiagnosticsLog.i(TAG, "Psiphon SOCKS5 listening on port $listeningPort")
            }
            "Tunnels" -> {
                tunnelCount = data?.optInt("count") ?: 0
                DiagnosticsLog.i(TAG, "Psiphon tunnels: $tunnelCount")
            }
            "ConnectedServerRegion" -> {
                egressRegion = data?.optString("serverRegion")?.takeIf { it.isNotEmpty() }
                egressRegion?.let { DiagnosticsLog.i(TAG, "Psiphon egress region: $it") }
            }
            "Alert", "Error", "Warning" ->
                DiagnosticsLog.w(TAG, "Psiphon: ${data?.optString("message").orEmpty()}")
        }
    }

    companion object {
        /** Log tag AND the diagnostics section name. */
        const val TAG = "psiphon"

        /**
         * psiphon-tunnel-core's ConsoleClient, cross-compiled for Android and
         * installed under a `.so` name so the platform extracts it into
         * nativeLibraryDir with the exec bit set (same trick as libaether.so).
         */
        const val BINARY = "libpsiphon.so"

        /** Optional build-time config, placed by scripts/fetch-natives.sh. */
        const val ASSET_CONFIG = "psiphon.config"

        private const val POLL_MS = 500L
    }
}
