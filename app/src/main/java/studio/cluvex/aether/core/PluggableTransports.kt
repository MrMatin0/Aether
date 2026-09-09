package studio.cluvex.aether.core

import android.content.Context
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.PtPlugin
import java.io.File

/**
 * Which pluggable transports THIS build can actually launch.
 *
 * ### Why it has to be asked at all
 *
 * The PT binaries are optional in exactly the way Psiphon and Tor are optional
 * (see [CoreAvailability]): `scripts/build-pt-transports.sh` produces them, a
 * build without them is a valid build, and the only honest thing to do is to
 * disable the bridge types that need one and say which binary is missing.
 *
 * ### Why getting it wrong is worse than for the cores
 *
 * A missing core makes a chain mode unavailable. A missing PT does something
 * sharper: tor treats a `Bridge obfs4 ...` line with no matching
 * `ClientTransportPlugin` as a FATAL config error and exits during startup. So
 * a build that offers obfs4 without shipping lyrebird does not degrade to a
 * direct connection - it cannot start tor at all, and the user sees a Tor hop
 * that dies instantly with nothing in the log that mentions bridges.
 *
 * Same caching rule as [CoreAvailability], for the same reason: the native
 * library directory is fixed at install time and has no business being stat'ed
 * on every recomposition of a settings page.
 */
object PluggableTransports {

    /** What this build ships, resolved to absolute paths. */
    data class Snapshot(
        /** Plugin -> the executable that provides it. Absent means not bundled. */
        val plugins: Map<PtPlugin, String>,
    ) {
        /** True when tor could run [transport] here. */
        fun has(transport: BridgeTransport): Boolean {
            val plugin = transport.plugin ?: return true
            return plugins.containsKey(plugin)
        }

        /** Transports this build can offer, in picker order. */
        val available: List<BridgeTransport> get() = BridgeTransport.entries.filter(::has)

        /** Transports this build cannot offer. */
        val missing: List<BridgeTransport> get() = BridgeTransport.entries.filterNot(::has)

        /** Every transport TOKEN tor can be given a plugin for. */
        val supportedTorNames: Set<String>
            get() = BridgeTransport.entries
                .filter { it.needsPlugin && has(it) }
                .map { it.torName }
                .toSet()

        /**
         * `transport token -> executable`, for the torrc.
         *
         * Only the transports actually used by [torNames] are wired: a
         * `ClientTransportPlugin` line makes tor spawn that binary as a managed
         * proxy, and spawning snowflake for a session that only uses obfs4 is a
         * process, a WebRTC stack and a broker request nobody asked for.
         */
        fun pluginsFor(torNames: Collection<String>): Map<String, String> {
            val wanted = LinkedHashMap<String, String>()
            for (transport in BridgeTransport.entries) {
                if (!transport.needsPlugin || transport.torName !in torNames) continue
                val path = plugins[transport.plugin] ?: continue
                wanted[transport.torName] = path
            }
            return wanted
        }
    }

    @Volatile
    private var cached: Snapshot? = null

    fun of(context: Context): Snapshot =
        cached ?: read(context.applicationInfo.nativeLibraryDir).also { cached = it }

    /** Re-reads the payload. Only useful in tests and after an in-place update. */
    fun refresh(context: Context): Snapshot =
        read(context.applicationInfo.nativeLibraryDir).also { cached = it }

    private fun read(nativeDir: String): Snapshot {
        val found = LinkedHashMap<PtPlugin, String>()
        for (plugin in PtPlugin.entries) {
            val binary = plugin.binaries.firstNotNullOfOrNull { name ->
                val file = File(nativeDir, name)
                // Same test NativeChild uses: on some devices the extracted file
                // reports as existing before it reports as executable.
                if (runCatching { file.canExecute() || file.exists() }.getOrDefault(false)) {
                    file.absolutePath
                } else {
                    null
                }
            }
            if (binary != null) found[plugin] = binary
        }
        DiagnosticsLog.i(
            TAG,
            "Pluggable transports in this build: " +
                (found.keys.joinToString(", ") { it.id }.ifEmpty { "none" }),
        )
        return Snapshot(found)
    }

    private const val TAG = "bridges"
}
