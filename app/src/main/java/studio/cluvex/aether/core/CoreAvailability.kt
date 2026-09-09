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
     * UI and the session make from it is then a pure function of these booleans,
     * and testable as such.
     */
    data class Snapshot(
        /** `libpsiphon.so` is in the APK for this device's ABI. */
        val psiphonBinary: Boolean,
        /** `libtor.so` is in the APK for this device's ABI. */
        val torBinary: Boolean,
        /**
         * A Psiphon client config was bundled at build time
         * (`assets/psiphon.config`). NOT required for anything any more:
         * [PsiphonCore] carries its own working config, and this only records
         * whether the build chose to override it. Kept because "which config is
         * this session actually using" is a real diagnostics question.
         */
        val psiphonConfigBundled: Boolean,
        /**
         * tor's country database was bundled. Without it tor cannot map relays
         * to countries and silently ignores `ExitNodes`, so the exit-country
         * setting has to be disabled rather than pretend to work.
         */
        val torGeoipBundled: Boolean,
        /**
         * The Psiphon bootstrap server list was bundled
         * (`assets/psiphon/server_entries.txt`).
         *
         * Appended last, with a default, on purpose: this is a data class other
         * code constructs, and inserting a field in the middle of the parameter
         * list would silently re-bind positional call sites.
         *
         * This is now the ONLY payload fact that can stop Psiphon from working,
         * which is why the Chain page reports it directly instead of leaving the
         * user to discover it from a three-minute connect attempt.
         */
        val psiphonServerList: Boolean = false,
        /**
         * A refreshed built-in bridge list was bundled
         * (`assets/tor/bridges.json`, from `scripts/fetch-tor-bridges.sh`).
         *
         * Not required: [BridgeCatalog] carries a compiled-in copy, for the same
         * reason [PsiphonCore] carries its own config - a bridge feature whose
         * first use needs a successful network request on a censored network is a
         * feature that does not work when it is needed. This only records whether
         * this build shipped something fresher.
         */
        val torBridgeListBundled: Boolean = false,
    ) {
        /**
         * The Aether engine is mandatory, so it is available by construction.
         *
         * Deliberately binary-only for Psiphon: a build with the core but no
         * bootstrap list is a DIFFERENT problem from a build with no core, and
         * reporting it as "no Psiphon core" would send whoever reads it to the
         * wrong build step. [psiphonServerList] carries that case, and ChainStack
         * fails with its own reason.
         */
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
            psiphonServerList = hasAsset(context, PsiphonCore.ASSET_SERVER_LIST),
            torBridgeListBundled = hasAsset(context, BridgeCatalog.ASSET),
        )
        DiagnosticsLog.i(
            TAG,
            "Build payload: psiphon=${snapshot.psiphonBinary} tor=${snapshot.torBinary} " +
                "psiphonServers=${snapshot.psiphonServerList} " +
                "psiphonConfig=${snapshot.psiphonConfigBundled} geoip=${snapshot.torGeoipBundled} " +
                "bridgeList=${snapshot.torBridgeListBundled}",
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
