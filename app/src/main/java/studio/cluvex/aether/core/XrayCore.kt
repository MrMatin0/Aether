package studio.cluvex.aether.core

import android.content.Context
import java.io.File

/**
 * XTLS/Xray-core, run as a child process.
 *
 * The binary is Xray's own `./main`, cross-compiled with the NDK by
 * `scripts/build-xray-core.sh` and packaged as `libxray.so` for the same reason
 * every other core is (see [NativeChild]).
 *
 * ### What it runs
 *
 * The USER'S config, read by [XrayLink], wrapped by [XrayConfig] into a config
 * with one SOCKS inbound on [TunnelConfig.XRAY_SOCKS_PORT] and - when the chain
 * puts a hop underneath - a dialer proxy that sends Xray's own handshake
 * through that hop. Nothing in the pasted text decides the chaining.
 *
 * ### Readiness
 *
 * Not this class's job, deliberately: "Xray started" only means the config
 * loaded and the inbound is bound. Whether the user's server actually answers
 * through the chain is proven by a real CONNECT through Xray's port
 * (studio.cluvex.aether.vpn.session.ChainStack), which is what a handshake
 * that works looks like from the outside.
 *
 * ### Secrets
 *
 * The written config carries the UUID / password, so it lives in app-private
 * storage, is never echoed into the diagnostics log (only [XrayOutbound.summary]
 * is), and is deleted again when the core stops.
 */
class XrayCore(
    private val context: Context,
    filesDir: File,
) {
    private val dataDir = File(filesDir, "xray").apply { mkdirs() }

    private val child = NativeChild(
        tag = TAG,
        binaryName = BINARY,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
        workingDir = dataDir,
    )

    /** Xray printed its "started" line: the config loaded and the inbound is bound. */
    @Volatile
    var started = false
        private set

    /** The first line in which Xray said why it could not start, if any. */
    @Volatile
    var failure: String? = null
        private set

    val isAvailable: Boolean get() = child.isAvailable
    val isAlive: Boolean get() = child.isAlive()

    /**
     * @param upstreamPort local SOCKS5 port of the hop underneath, which Xray's
     *   own connection to the server must be dialled through, or null to dial
     *   it directly.
     */
    fun start(outbound: XrayOutbound, upstreamPort: Int?) {
        started = false
        failure = null
        val config = XrayConfig.build(
            outbound = outbound,
            socksPort = TunnelConfig.XRAY_SOCKS_PORT,
            upstreamPort = upstreamPort,
            upstreamHost = TunnelConfig.SOCKS_HOST,
            dnsServers = TunnelConfig.DNS_SERVERS,
        )
        val file = File(dataDir, CONFIG_FILE)
        file.writeText(config)
        DiagnosticsLog.i(
            TAG,
            "Xray outbound: ${outbound.summary}" + if (upstreamPort != null) {
                " - its handshake is dialled through ${TunnelConfig.SOCKS_HOST}:$upstreamPort"
            } else {
                " - dialled directly"
            },
        )
        child.start(
            args = listOf("run", "-c", file.absolutePath),
            env = mapOf("XRAY_LOCATION_ASSET" to dataDir.absolutePath),
            onLine = ::onLine,
        )
    }

    fun stop() {
        child.stop()
        runCatching { File(dataDir, CONFIG_FILE).delete() }
    }

    suspend fun awaitExit(timeoutMs: Long): Boolean = child.awaitExit(timeoutMs)

    private fun onLine(line: String) {
        if (STARTED.containsMatchIn(line)) started = true
        if (failure == null && FAILED.containsMatchIn(line)) failure = line.take(300)
    }

    companion object {
        const val TAG = "xray"

        /** Xray-core's ./main, built by scripts/build-xray-core.sh. */
        const val BINARY = "libxray.so"

        private const val CONFIG_FILE = "config.json"

        /** "Xray 26.7.28 started" */
        private val STARTED = Regex("Xray \\S+ started")

        private val FAILED = Regex("Failed to start|failed to load|infra/conf", RegexOption.IGNORE_CASE)
    }
}
