package studio.cluvex.aether.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import studio.cluvex.aether.model.Hop
import java.io.File

/**
 * EXPERIMENTAL: Lantern's Unbounded (getlantern/unbounded, module
 * `github.com/getlantern/broflake`), run as a child process.
 *
 * The binary is upstream's own `cmd` driver built as the DESKTOP consumer in
 * SOCKS5 mode (`-X main.clientType=desktop -X main.proxyMode=socks5`, see
 * `scripts/build-unbounded.sh`), shipped as `libunbounded.so` so it runs
 * through [NativeChild] like every other core.
 *
 * ### What it does on the wire
 *
 * ```
 * app -> SOCKS5 127.0.0.1:1892 -> QUIC over WebRTC -> volunteer peer
 *     -> WebSocket -> Lantern egress -> internet
 * ```
 *
 * The consumer asks Lantern's discovery server ("freddie") for a volunteer,
 * signals, opens WebRTC data channels to it, and runs one QUIC connection
 * across them to the egress. The QUIC connection survives the volunteer going
 * away ("Turbo Tunnel"), which is the whole point of the design.
 *
 * ### Readiness
 *
 * The local SOCKS5 port opens two seconds after launch whether or not a single
 * volunteer was ever found, so, exactly as with tor, an open port means
 * nothing. Ready is the consumer's OWN report that the QUIC connection to the
 * egress is up: `QUIC connection established, ready to proxy!`.
 *
 * ### Configuration
 *
 * Upstream reads everything from the environment: `FREDDIE` (discovery),
 * `EGRESS` and `PORT`. The endpoints below are the production ones from
 * upstream's `ui/.env.production.example`. They belong to Lantern and can
 * change without notice, which is one of the reasons this core is labelled
 * experimental.
 */
class UnboundedCore(
    context: Context,
    filesDir: File,
) {
    private val dataDir = File(filesDir, "unbounded").apply { mkdirs() }

    private val child = NativeChild(
        tag = TAG,
        binaryName = BINARY,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
        workingDir = dataDir,
    )

    /** Set on the log-drain thread once the QUIC connection is up. */
    @Volatile
    private var established = false

    /** Short progress text for the chain row. Written on the log-drain thread. */
    @Volatile
    var phase: String = PHASE_DISCOVERY
        private set

    val isAvailable: Boolean get() = child.isAvailable
    val isAlive: Boolean get() = child.isAlive()
    val isReady: Boolean get() = established

    fun start() {
        established = false
        phase = PHASE_DISCOVERY
        DiagnosticsLog.i(
            TAG,
            "Starting the experimental Unbounded consumer (discovery $DISCOVERY_URL, " +
                "SOCKS5 ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.UNBOUNDED_SOCKS_PORT}).",
        )
        child.start(args = emptyList(), env = environment(), onLine = ::onLine)
    }

    fun stop() = child.stop()

    suspend fun awaitExit(timeoutMs: Long): Boolean = child.awaitExit(timeoutMs)

    /** Waits for the QUIC connection to the egress; false on timeout or if the core dies. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastReported: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isReady) {
                DiagnosticsLog.i(TAG, "Unbounded is up: QUIC connection to the egress established.")
                return true
            }
            if (!isAlive) {
                DiagnosticsLog.e(TAG, "Unbounded exited before it found a volunteer.")
                return false
            }
            val now = phase
            if (now != lastReported) {
                lastReported = now
                ChainRuntime.update(Hop.UNBOUNDED, ChainRuntime.HopState.STARTING, now)
            }
            delay(POLL_MS)
        }
        DiagnosticsLog.e(
            TAG,
            "Unbounded found no working volunteer after ${timeoutMs / 1000}s - WebRTC or " +
                "Lantern's discovery server is probably unreachable from this network.",
        )
        return false
    }

    private fun onLine(line: String) {
        when {
            line.contains(READY_MARKER) -> {
                established = true
                phase = PHASE_READY
            }
            line.contains(ENDED_MARKER) -> {
                // The QUIC connection outlives a single volunteer, so this is a
                // re-pair, not a failure. The session is not torn down for it.
                established = false
                phase = PHASE_REPAIR
            }
        }
    }

    companion object {
        const val TAG = "unbounded"

        /** Upstream's `cmd` desktop consumer, installed by scripts/build-unbounded.sh. */
        const val BINARY = "libunbounded.so"

        /** Lantern's production discovery / signalling server ("freddie"). */
        const val DISCOVERY_URL = "https://freddie.iantem.io"

        /** Lantern's production egress. */
        const val EGRESS_URL = "wss://unbounded.iantem.io"

        private const val READY_MARKER = "QUIC connection established"
        private const val ENDED_MARKER = "QUIC connection ended"

        private const val PHASE_DISCOVERY = "finding a volunteer"
        private const val PHASE_READY = "volunteer peer"
        private const val PHASE_REPAIR = "re-pairing"

        private const val POLL_MS = 500L

        /**
         * The whole configuration surface of the core. Pure, so it is testable
         * without a device.
         */
        internal fun environment(
            port: Int = TunnelConfig.UNBOUNDED_SOCKS_PORT,
        ): Map<String, String> = mapOf(
            "FREDDIE" to DISCOVERY_URL,
            "EGRESS" to EGRESS_URL,
            "PORT" to port.toString(),
        )
    }
}
