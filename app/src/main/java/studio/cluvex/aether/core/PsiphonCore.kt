package studio.cluvex.aether.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONArray
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
 * VpnService that is holding the user's TUN. The library also arrives as a
 * ~44 MB prebuilt AAR, which is a supply-chain decision this repo has
 * consistently refused to make (see `scripts/build-overlay-cores.sh`:
 * psiphon-tunnel-core is cross-compiled from upstream source, nothing is
 * vendored as a blob).
 *
 * ### Why this used to not work at all, and what changed
 *
 * ROOT CAUSE of every "Psiphon does nothing" report: this class refused to
 * start without a client config, and no build ever had one. It looked for a
 * config the user had pasted into Settings, then for `assets/psiphon.config`
 * written from a CI secret, and with neither it threw before the core was even
 * exec'd. So the feature shipped in a state where the ONLY way to use it was to
 * obtain a `PropagationChannelId` / `SponsorId` pair out of band - which is not
 * something a user of a circumvention app can do, and not something the Chain
 * page could honestly ask for.
 *
 * A Psiphon client needs exactly two things, and neither is a user secret:
 *
 *  1. **A client identity.** `PropagationChannelId` / `SponsorId` are
 *     statistics and campaign labels, not credentials, and
 *     psiphon-tunnel-core's own published values are used here ([BUILT_IN_*]).
 *     The signature keys next to them are PUBLIC keys: they are what lets the
 *     client verify the server lists it later downloads.
 *  2. **Somewhere to start dialling.** The bootstrap server list, passed to the
 *     console client as `-serverList`. It is a build-time artifact like the
 *     cores themselves (`scripts/fetch-psiphon-serverlist.sh`, gitignored), and
 *     after the first successful tunnel the core maintains its own list in its
 *     datastore and stops depending on it.
 *
 * A pasted or bundled config still WINS if one exists, so a build with its own
 * network-issued config behaves exactly as before. There is simply no longer a
 * path on which the absence of one is fatal - see [baseConfig].
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

    /**
     * True when this build carries the bootstrap server list.
     *
     * This is the ONE payload question left that can stop Psiphon: with no
     * server entries and an empty datastore the core has nothing to dial and
     * would spend its whole establish budget saying so in Go. The Chain page and
     * [studio.cluvex.aether.vpn.session.ChainStack] both read this, so a build
     * without it says so up front instead of at the end of a three-minute
     * connect attempt.
     */
    fun hasServerList(): Boolean =
        runCatching { context.assets.open(ASSET_SERVER_LIST).use { it.read() >= 0 } }
            .getOrDefault(false) || File(dataDir, SERVER_LIST_FILE).length() > 0

    /**
     * Kept, and now always true: the core ships its own working config.
     *
     * Retained rather than deleted because "can Psiphon run" is asked from more
     * than one place and the honest answer to "is a config available" is now
     * unconditionally yes. [hasServerList] is the question that still has two
     * answers.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hasConfig(profile: ConnectionProfile): Boolean = true

    /**
     * Starts the core.
     *
     * @param upstreamPort local SOCKS5 port Psiphon must dial its own upstream
     *   through (the previous hop in the chain), or null to dial directly.
     * @param egress ISO 3166-1 alpha-2 exit country, or blank for no region
     *   filter. Defaults to the profile's choice; the caller passes it
     *   explicitly when it is retrying with an automatic exit, because
     *   `EgressRegion` is a HARD filter and a region with no reachable server
     *   never establishes (see ChainStack).
     */
    fun start(
        profile: ConnectionProfile,
        upstreamPort: Int?,
        egress: String = profile.psiphonRegion,
    ) {
        val config = buildConfig(baseConfig(profile), upstreamPort, egress)
        val configFile = File(dataDir, CONFIG_FILE)
        configFile.writeText(config)
        tunnelCount = 0
        listeningPort = 0
        egressRegion = null

        val args = mutableListOf("-config", configFile.absolutePath)
        val serverList = extractServerList()
        if (serverList != null) {
            args += "-serverList"
            args += serverList.absolutePath
        } else {
            // Not fatal HERE: an existing datastore from a previous session may
            // well hold enough servers to connect with. It is fatal in
            // ChainStack, which checks hasServerList() before it gets this far.
            DiagnosticsLog.w(
                TAG,
                "No Psiphon bootstrap server list in this build - the core can only use " +
                    "servers already in its datastore.",
            )
        }

        DiagnosticsLog.i(
            TAG,
            "Starting Psiphon on 127.0.0.1:${TunnelConfig.PSIPHON_SOCKS_PORT}" +
                (upstreamPort?.let { " through 127.0.0.1:$it" } ?: " (direct)") +
                " exit=${PsiphonRegions.name(PsiphonRegions.sanitize(egress))}",
        )
        child.start(args = args, onLine = ::onNotice)
    }

    fun stop() = child.stop()

    suspend fun awaitExit(timeoutMs: Long): Boolean = child.awaitExit(timeoutMs)

    /**
     * Throws away everything Psiphon has learned.
     *
     * A wedged or lock-held BoltDB datastore is a classic cause of a Psiphon
     * that never establishes and cannot say why, so the retry path wipes it
     * rather than handing the same broken state to the next attempt. The
     * bootstrap list is re-extracted from assets on the next [start], so this is
     * a true cold start.
     */
    fun resetDataStore() {
        runCatching { dataDir.listFiles()?.forEach { it.deleteRecursively() } }
        dataDir.mkdirs()
        DiagnosticsLog.i(TAG, "Psiphon datastore reset.")
    }

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
     * The config to overlay the app's own fields onto, in priority order:
     * whatever the user supplied, then whatever the build bundled, then the
     * built-in one.
     *
     * NEVER null any more, which is the whole point of this change. The first
     * two sources are validated only as far as "it parses as a JSON object": the
     * core itself is the authority on what a valid config is, and duplicating
     * its rules here would just reject configs it accepts.
     */
    private fun baseConfig(profile: ConnectionProfile): String {
        val supplied = profile.psiphonConfig.trim()
        if (supplied.isNotEmpty()) {
            if (runCatching { JSONObject(supplied) }.isSuccess) return supplied
            DiagnosticsLog.w(TAG, "The supplied Psiphon config is not valid JSON - ignoring it.")
        }
        val bundled = runCatching {
            context.assets.open(ASSET_CONFIG).bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { text -> runCatching { JSONObject(text) }.isSuccess }
        if (bundled != null) return bundled
        return builtInConfig()
    }

    /**
     * The config this app ships with, and the reason Psiphon works out of the
     * box.
     *
     * Every value here is public: the two ids are upstream's own published
     * client identity (they label statistics, they do not authenticate anyone),
     * and the three keys are PUBLIC signature-verification keys - they let the
     * client check that a downloaded server list really came from Psiphon.
     * Nothing in here is a credential, and nothing in here is per-user.
     */
    private fun builtInConfig(): String = JSONObject().apply {
        put("PropagationChannelId", BUILT_IN_PROPAGATION_CHANNEL_ID)
        put("SponsorId", BUILT_IN_SPONSOR_ID)
        put("ClientVersion", BUILT_IN_CLIENT_VERSION)
        put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_PUBLIC_KEY)
        put("ServerEntrySignaturePublicKey", SERVER_ENTRY_SIGNATURE_PUBLIC_KEY)
        put("ExchangeObfuscationKey", EXCHANGE_OBFUSCATION_KEY)

        // The Go side gives up at this point and says so through a notice, which
        // is a much better failure than the app's own timeout firing first.
        put("EstablishTunnelTimeoutSeconds", ESTABLISH_TIMEOUT_SECONDS)

        // Twelve concurrent establishment workers rather than the default
        // handful: on a filtered network most candidates are dead ends, and the
        // cost of racing more of them is bandwidth we are already spending.
        put("ConnectionWorkerPoolSize", CONNECTION_WORKER_POOL_SIZE)

        // Where the users of this app are. It steers Psiphon's own tactics; it
        // is not a location the app reports about the device.
        put("DeviceRegion", DEVICE_REGION)

        put("EmitBytesTransferred", true)

        // The local resolver on a filtered mobile network is frequently the
        // thing doing the filtering, so Psiphon's own lookups go to public
        // resolvers instead. Probability 1.0 = always prefer them.
        put(
            "DNSResolverPreferredAlternateServers",
            JSONArray(listOf("1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53")),
        )
        put("DNSResolverPreferAlternateServerProbability", 1.0)
    }.toString()

    /**
     * Overlays the fields the app owns onto [base]. Every `put` here is
     * deliberate; anything else the config carries (server list URLs, signing
     * keys, tactics) is left exactly as issued.
     */
    private fun buildConfig(
        base: String,
        upstreamPort: Int?,
        egress: String,
    ): String {
        val json = JSONObject(base)

        // Ports and paths are ours: the config's own values would collide with
        // a separately installed Psiphon app (its default is 1080).
        json.put("DataRootDirectory", dataDir.absolutePath)
        json.put("LocalSocksProxyPort", TunnelConfig.PSIPHON_SOCKS_PORT)

        // DO NOT SET ListenInterface HERE, AND DO NOT "FIX" IT TO AN IP.
        //
        // This field is an INTERFACE NAME, not an address (psiphon/config.go):
        // unset means listen on 127.0.0.1, "any" means 0.0.0.0, and anything
        // else is resolved with net.InterfaceByName. This code used to write
        // "127.0.0.1" into it, so the core looked for an interface with that
        // name, found none, and gave up BEFORE binding the local SOCKS5
        // listener. Nothing in the log said "bad config" - the port simply never
        // opened, so every Psiphon chain mode spent its full readiness budget
        // waiting for a listener that was never going to exist and then reported
        // a timeout. The default is precisely what this app wants, so the
        // override is REMOVED rather than corrected: a network-issued config
        // that carries its own value must not be able to move the listener off
        // loopback either, which is the same rule every other listener in this
        // app follows.
        json.remove("ListenInterface")

        // One listener is enough: everything in this app speaks SOCKS5, and an
        // extra unauthenticated HTTP proxy is one more thing bound on loopback.
        json.put("DisableLocalHTTPProxy", true)
        json.put("DisableLocalSocksProxy", false)

        // Reported to the network for stats; also what tells Psiphon it is on a
        // phone rather than a server.
        json.put("ClientPlatform", "Android_${Build.VERSION.RELEASE}_${context.packageName}")
        json.put("EmitDiagnosticNotices", true)

        // CHAINING: this single field is what "Psiphon over Aether" is. With it
        // set, psiphon-tunnel-core routes EVERY connection it makes through the
        // upstream - including its own server-list fetches - so the chain cannot
        // leak a direct dial.
        if (upstreamPort != null) {
            json.put("UpstreamProxyUrl", "socks5://${TunnelConfig.SOCKS_HOST}:$upstreamPort")
        } else {
            json.remove("UpstreamProxyUrl")
        }

        // OMITTED rather than sent as "" for Automatic: an empty string is not
        // the documented way to express "no region filter", removing the key is.
        val region = PsiphonRegions.sanitize(egress)
        if (region.isNotEmpty()) json.put("EgressRegion", region) else json.remove("EgressRegion")

        if (!json.has("PropagationChannelId") || !json.has("SponsorId")) {
            DiagnosticsLog.w(
                TAG,
                "Psiphon config has no PropagationChannelId/SponsorId - the core will refuse it.",
            )
        }
        return json.toString()
    }

    /**
     * Copies the bundled bootstrap server list somewhere the core can open it.
     *
     * The console client takes a PATH, and an APK asset is not a file, so it has
     * to land on disk once. Re-written only when the size differs, so a normal
     * connect does not rewrite a megabyte for nothing; returns null when this
     * build has no list and nothing was extracted earlier.
     */
    private fun extractServerList(): File? {
        val target = File(dataDir, SERVER_LIST_FILE)
        val bytes = runCatching {
            context.assets.open(ASSET_SERVER_LIST).use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return target.takeIf { it.length() > 0 }
        }
        if (target.length() != bytes.size.toLong()) {
            runCatching { target.writeBytes(bytes) }
                .onFailure { DiagnosticsLog.w(TAG, "Could not stage the Psiphon server list: ${it.message}") }
        }
        return target.takeIf { it.length() > 0 }
    }

    /**
     * psiphon-tunnel-core emits one JSON notice per line. Only a handful of them
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
            // Logged because it is the evidence for the automatic-exit retry in
            // ChainStack: when the user's country is not in this list, that
            // retry is the reason the session comes up at all.
            "AvailableEgressRegions" -> {
                val regions = data?.optJSONArray("regions") ?: return
                val list = (0 until regions.length()).mapNotNull {
                    regions.optString(it).takeIf { code -> code.isNotEmpty() }
                }
                if (list.isNotEmpty()) {
                    DiagnosticsLog.i(TAG, "Psiphon reachable exits: ${list.sorted().joinToString(", ")}")
                }
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

        /**
         * OPTIONAL build-time config override, placed by CI from
         * PSIPHON_CONFIG_B64. A build without it uses [builtInConfig].
         */
        const val ASSET_CONFIG = "psiphon.config"

        /**
         * The bootstrap server list, provided by
         * `scripts/fetch-psiphon-serverlist.sh` at build time (gitignored, like
         * the cores and the fonts).
         */
        const val ASSET_SERVER_LIST = "psiphon/server_entries.txt"

        private const val SERVER_LIST_FILE = "server_entries.txt"
        private const val CONFIG_FILE = "psiphon.config"

        private const val POLL_MS = 500L

        // ---- the built-in client identity (all public, none per-user) ------

        private const val BUILT_IN_PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
        private const val BUILT_IN_SPONSOR_ID = "1111111111111111"
        private const val BUILT_IN_CLIENT_VERSION = "127"
        private const val ESTABLISH_TIMEOUT_SECONDS = 180
        private const val CONNECTION_WORKER_POOL_SIZE = 12
        private const val DEVICE_REGION = "IR"

        private const val SERVER_ENTRY_SIGNATURE_PUBLIC_KEY =
            "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
        private const val EXCHANGE_OBFUSCATION_KEY =
            "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU="
        private const val REMOTE_SERVER_LIST_PUBLIC_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    }
}
