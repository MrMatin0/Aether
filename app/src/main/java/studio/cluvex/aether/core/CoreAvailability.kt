package studio.cluvex.aether.core

import android.content.Context
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.Hop
import java.io.File

/**
 * What this particular build can actually run.
 *
 * ### Why this exists
 *
 * The Psiphon and Tor cores are OPTIONAL (see `scripts/build-overlay-cores.sh`):
 * a build without them is a valid Aether build that simply cannot offer the
 * chain modes needing them. That was already handled correctly at connect time -
 * and only at connect time, which is the problem. A user on a build without
 * `libtor.so` could pick "Tor over Aether", tap Connect, and learn from an error
 * toast that the feature was never there. The settings page had the answer
 * available the whole time and no way to read it.
 *
 * So the payload is inspected ONCE, here, and both the UI and
 * [studio.cluvex.aether.vpn.session.ChainStack] read the same snapshot. Two
 * independent "is Psiphon available" checks is how a build ends up offering a
 * mode in the picker and refusing it three seconds later.
 *
 * ### Why the result is cached
 *
 * None of these facts can change while the process lives: the native library
 * directory and the APK's assets are fixed at install time. An install-time fact
 * has no business costing a file stat and two asset opens on every recomposition
 * of a settings page.
 */
object CoreAvailability {

    /**
     * The cores and data files this build ships.
     *
     * Deliberately a plain data class with no Context in it: every decision the
     * UI and the session make from it is then a pure function of these four
     * booleans, and testable as such.
     */
    data class Snapshot(
        /** `libpsiphon.so` is in the APK for this device's ABI. */
        val psiphonBinary: Boolean,
        /** `libtor.so` is in the APK for this device's ABI. */
        val torBinary: Boolean,
        /**
         * A Psiphon client config was bundled at build time
         * (`assets/psiphon.config`). Not a credential and not required: the user
         * can paste one in Settings instead. See docs/CHAINING.md.
         */
        val psiphonConfigBundled: Boolean,
        /**
         * tor's country database was bundled. Without it tor cannot map relays
         * to countries and silently ignores `ExitNodes`, so the exit-country
         * setting has to be disabled rather than pretend to work.
         */
        val torGeoipBundled: Boolean,
    ) {
        /** The Aether engine is mandatory, so it is available by construction. */
        fun has(hop: Hop): Boolean = when (hop) {
            Hop.AETHER -> true
            Hop.PSIPHON -> psiphonBinary
            Hop.TOR -> torBinary
        }

        /** Cores [mode] needs that this build does not ship. */
        fun missing(mode: ChainMode): List<Hop> = mode.hops.filterNot(::has)

        /** True when every core [mode] needs is present. */
        fun canRun(mode: ChainMode): Boolean = missing(mode).isEmpty()
    }

    @Volatile
    private var cached: Snapshot? = null

    /** The snapshot for this install, read once and reused. */
    fun of(context: Context): Snapshot = cached ?: read(context).also { cached = it }

    /** Re-reads the payload. Only useful in tests and after an in-place update. */
    fun refresh(context: Context): Snapshot = read(context).also { cached = it }

    private fun read(context: Context): Snapshot {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val snapshot = Snapshot(
            psiphonBinary = hasBinary(nativeDir, PsiphonCore.BINARY),
            torBinary = hasBinary(nativeDir, TorCore.BINARY),
            psiphonConfigBundled = hasAsset(context, PsiphonCore.ASSET_CONFIG),
            torGeoipBundled = hasAsset(context, "${TorCore.ASSET_DIR}/geoip"),
        )
        DiagnosticsLog.i(
            TAG,
            "Build payload: psiphon=${snapshot.psiphonBinary} tor=${snapshot.torBinary} " +
                "psiphonConfig=${snapshot.psiphonConfigBundled} geoip=${snapshot.torGeoipBundled}",
        )
        return snapshot
    }

    /**
     * Same test [NativeChild] uses, for the same reason: these are executables
     * shipped under a `.so` name, and on some devices the extracted file reports
     * as existing before it reports as executable.
     */
    private fun hasBinary(nativeDir: String, name: String): Boolean =
        runCatching { File(nativeDir, name).let { it.canExecute() || it.exists() } }
            .getOrDefault(false)

    /** An asset that is present but empty is not a usable asset. */
    private fun hasAsset(context: Context, path: String): Boolean =
        runCatching { context.assets.open(path).use { it.read() >= 0 } }.getOrDefault(false)

    private const val TAG = "chain"
}
