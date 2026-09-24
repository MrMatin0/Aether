package studio.cluvex.aether.model

import studio.cluvex.aether.BuildConfig

/**
 * Transport protocol, mapped 1:1 to the engine's CLI flags.
 *
 * [MIM] is MASQUE-in-MASQUE (`--mim`): two MASQUE hops, the second established
 * THROUGH the first, so the address the destination sees is not the edge this
 * device talked to. It is to MASQUE what [GOOL] is to WireGuard, which is why it
 * sits in the same picker as the other transports. It used to be a separate
 * switch that only did anything while MASQUE happened to be selected - a setting
 * that could be on and silently idle is exactly what a protocol choice avoids.
 */
enum class Protocol {
    AUTO, MASQUE, MIM, WIREGUARD, GOOL,
    ;

    /** True for both MASQUE transports: the single hop and MASQUE-in-MASQUE. */
    val isMasque: Boolean
        get() = this == MASQUE || this == MIM

    companion object {
        /**
         * MIGRATION: before MASQUE-in-MASQUE was a protocol it was a boolean
         * (`mim`) that only took effect while MASQUE was selected. A stored
         * MASQUE + mim=true therefore always meant "two MASQUE hops", and is
         * read as [MIM] so nobody's session changes shape on upgrade. With any
         * other protocol the old switch was idle, and stays ignored.
         */
        fun migrateLegacyMim(stored: Protocol, legacyMimSwitch: Boolean?): Protocol =
            if (stored == MASQUE && legacyMimSwitch == true) MIM else stored
    }
}

/**
 * How hard the engine's endpoint scanner tries.
 *
 * WHY SO FEW: this used to be TURBO, BALANCED, THOROUGH, STEALTH and IRONCLAD.
 * Two of those were the same intention with a different amount of patience
 * (balanced vs thorough), one was "balanced, but slower and quieter" (stealth),
 * and the only genuinely different one - every candidate has to carry a REAL
 * request end to end before it is accepted - was called "ironclad", a word that
 * cannot be ranked against "thorough" by anyone who has not read the engine
 * source. Five options, three meanings, no guidance.
 *
 * So the modes are the questions a user actually has:
 *
 *  - [TURBO]    "just get me online"     -> first edge that answers wins
 *  - [PRECISE]  "give me a good one"     -> collect several, keep the fastest
 *  - [VERIFIED] "only proven edges"      -> dial only edges measured to answer
 *                                           connect-ip, never a guessed
 *                                           neighbour; on gool and mim the two
 *                                           hops come from different ranges,
 *                                           which is what moves the exit
 *  - [ULTRA]    "nothing works here"     -> only accept an edge that proves
 *                                           itself with a real request
 *
 * ENGINE FLAGS ARE UPSTREAM'S OWN NAMES. Until 1.5.0 this sent `--precise` and
 * `--ultra`, which only this repo's patched cli.rs understood - so an upstream
 * sync that dropped the patch would have failed every connect on the default
 * mode with "unknown option". The aliases are still carried in cli.rs for
 * shells and scripts, but nothing in the app depends on them any more.
 *
 * [sinceCore] is the first engine version that parses [engineFlag]. The engine
 * treats an unknown option as fatal, so a mode newer than the bundled core is
 * neither offered ([offeredBy]) nor sent ([effectiveFor]).
 *
 * [engineFlag] is the single place a mode becomes an engine argument, and
 * [fromStored] is the single place an older saved name becomes one of these.
 */
enum class ScanMode(val engineFlag: String, val sinceCore: String? = null) {
    TURBO("--turbo"),
    PRECISE("--balanced"),
    VERIFIED("--verified", sinceCore = "2.1.0"),
    ULTRA("--ironclad"),
    ;

    /** True when an engine of [coreVersion] parses this mode's flag. */
    fun isSupportedBy(coreVersion: String): Boolean =
        sinceCore?.let { CoreVersion.atLeast(coreVersion, it) } ?: true

    /**
     * The mode an engine of [coreVersion] is actually run with.
     *
     * A mode the core cannot parse becomes [PRECISE] rather than an argument
     * the engine refuses. That only happens with a profile saved by a newer
     * build (or imported from one) and run against an older core; the picker
     * never offers such a mode in the first place.
     */
    fun effectiveFor(coreVersion: String): ScanMode =
        if (isSupportedBy(coreVersion)) this else PRECISE

    companion object {
        /** The modes the picker offers for an engine of [coreVersion], in order. */
        fun offeredBy(coreVersion: String): List<ScanMode> =
            entries.filter { it.isSupportedBy(coreVersion) }

        /**
         * Reads a mode from a persisted / transported name, or null when the
         * value means nothing (the caller then keeps its own default).
         *
         * MIGRATION: the five pre-1.4.6 names are still understood, because
         * they are sitting in every existing DataStore file, every saved setup,
         * every exported config and every pending Intent extra. Without this a
         * user who had chosen THOROUGH would silently be moved to the default
         * on upgrade - and "my settings reset themselves" is exactly the kind
         * of thing that gets read as a broken update.
         *
         * A stored STEALTH still means PRECISE, not VERIFIED: it was chosen as
         * "quiet and patient", and upstream 2.1.0 reusing the word as an alias
         * of verified does not change what the user asked for.
         */
        fun fromStored(raw: String?): ScanMode? {
            val name = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.name == name }?.let { return it }
            return when (name) {
                "BALANCED", "THOROUGH", "STEALTH" -> PRECISE
                "IRONCLAD" -> ULTRA
                else -> null
            }
        }
    }
}

/**
 * Dotted engine versions as written to native/aether/CORE_VERSION (and from
 * there to BuildConfig.CORE_VERSION): `2.1.0`, optionally `v`-prefixed or with
 * a `-suffix`. Anything unparseable - `unknown` on a build without a vendored
 * core - is treated as older than every requirement, because a flag the engine
 * does not know is fatal and "not offered" is the safe side of that.
 */
object CoreVersion {

    fun parse(raw: String?): List<Int>? {
        val text = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        val numbers = text.split('.', '-', '+').take(3).map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.isNotEmpty() }
    }

    fun atLeast(actual: String?, required: String): Boolean {
        val have = parse(actual) ?: return false
        val need = parse(required) ?: return true
        for (i in 0 until maxOf(have.size, need.size)) {
            val a = have.getOrElse(i) { 0 }
            val b = need.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }
}

/** IP family preference. */
enum class IpVersion { V4, V6, BOTH }

/**
 * Anti-DPI obfuscation profile ("Amnezia"-style). Maps to the engine's
 * `--noize <profile>` option (see aethernoize.rs / noize.rs in the engine).
 * The engine injects junk packets + fake handshake signatures so WireGuard /
 * MASQUE traffic no longer looks like a fixed fingerprint to DPI boxes.
 */
enum class Noize { OFF, LIGHT, FIREWALL, BALANCED, GFW, AGGRESSIVE }

/**
 * Where the engine gets its endpoint from:
 *  - AUTO         : engine scans the clean (non-Iranian) WARP edge ranges.
 *  - MANUAL_PEER  : user pins one endpoint `ip:port`; the engine skips scanning.
 *  - MANUAL_RANGE : user types their own IP range(s); the engine scans ONLY those.
 *
 * Whatever is chosen here, the exit is still verified end-to-end before the
 * session is accepted.
 */
enum class EndpointMode { AUTO, MANUAL_PEER, MANUAL_RANGE }

/** Per-app tunneling policy (split tunneling). */
enum class SplitMode { OFF, INCLUDE, EXCLUDE }

/**
 * How the device enrols into a Cloudflare Zero Trust ("WARP for teams")
 * organization. New in engine v1.5.0 (see zerotrust.rs).
 *  - OFF            : consumer WARP, no organization (default).
 *  - SERVICE_TOKEN  : headless enrolment with an Access service token id+secret.
 *  - EMAIL          : one-time code sent to a work e-mail address.
 *  - TOKEN          : an enrolment JWT the user already obtained in a browser
 *                     at https://<team>.cloudflareaccess.com/warp.
 */
enum class TeamAuth { OFF, SERVICE_TOKEN, EMAIL, TOKEN }

/** Engine core log verbosity (1.2.4). Mapped to the engine's AETHER_LOG_LEVEL. */
enum class CoreLogLevel(val raw: String) { OFF("off"), ERROR("error"), WARN("warn"), INFO("info"), DEBUG("debug") }

/**
 * User-tunable connection profile. Knows how to turn itself into the engine's
 * CLI arguments and environment variables.
 *
 * ONE TOR. The app runs exactly one Tor implementation: the bundled tor binary
 * behind the Tor chain modes (see core/TorCore.kt, [chain] and the bridge
 * fields below). Engine v2.0.0 can also embed its own Arti-based tor, but that
 * was a second, independent Tor with its own bridges and bootstrap competing
 * with the first - so the engine is built without it and this profile has no way
 * to ask for it.
 *
 * ONE PSIPHON. Same rule, same reason. Engine v2.1.0 can spawn its own
 * psiphon-tunnel-core ConsoleClient (`--psiphon*`), built from a second fork
 * and branch. The app already has a Psiphon - core/PsiphonCore.kt, built from
 * source to close audit finding F-8 - so this profile never emits `--psiphon*`
 * or `AETHER_PSIPHON*`. EngineOverlayGuardTest holds both rules.
 */
data class ConnectionProfile(
    val protocol: Protocol = Protocol.AUTO,
    val scanMode: ScanMode = ScanMode.PRECISE,
    val ipVersion: IpVersion = IpVersion.V4,
    val quickReconnect: Boolean = true,
    val masqueHttp2: Boolean = false,
    /**
     * Share the tunnel with other devices on the same Wi-Fi / hotspot via the
     * in-app proxy bridge (see [studio.cluvex.aether.core.ShareBridge]).
     * UI-side option only - it never reaches the engine's CLI args.
     */
    val lanShare: Boolean = false,

    // ---- Added in 1.2.0 (engine v1.3.0 feature parity) ----

    /**
     * Anti-DPI obfuscation ("Amnezia").
     *
     * THE DEFAULT IS ON, and deliberately so. Until the engine was told to
     * honour an explicit OFF, an OFF profile emitted no `--noize` at all and
     * the engine fell back to its own defaults - firewall for MASQUE, balanced
     * for WireGuard. So a default install has always connected WITH junk
     * packets and fake handshake signatures, which is what carries a MASQUE
     * scan through a filtered network. Now that OFF really means "no
     * obfuscation", shipping OFF as the default would quietly take that away
     * from every user who never opened this setting. FIREWALL maps to exactly
     * those two native defaults, so the out-of-the-box behaviour is unchanged.
     *
     * A user who picks OFF still gets OFF, on every rung of every ladder.
     */
    val noize: Noize = Noize.FIREWALL,
    /** Endpoint selection strategy. */
    val endpointMode: EndpointMode = EndpointMode.AUTO,
    /** `ip:port` used when [endpointMode] is MANUAL_PEER. */
    val manualPeer: String = "",
    /**
     * Comma-separated IP range(s) used when [endpointMode] is MANUAL_RANGE,
     * e.g. "8.6.112.x" or "188.114.96.0/24, 162.159.192.0/24", handed to the
     * engine as AETHER_SCAN_CIDRS / AETHER_MASQUE_CIDRS / AETHER_WG_CIDRS (see
     * [toEnv]).
     *
     * KNOWN GAP: prober/scan.rs, which read them, was deleted by the core 2.0.0
     * sync, and neither upstream prober.rs nor wg_prober.rs reads them, so the
     * vendored engine currently scans its built-in ranges whatever is set here
     * (scripts/sync-core.sh, NOTE 1.5.0; docs/MASQUE_SCAN.md).
     */
    val manualRange: String = "",
    /** WireGuard persistent keepalive, seconds. 0 = engine default (5). */
    val keepalive: Int = 0,
    /** Fragment the TLS ClientHello on the HTTP/2 transport (anti-DPI). */
    val fragment: Boolean = false,
    /**
     * Enable Encrypted Client Hello (hides the real SNI). Never sent to the
     * MASQUE transports, whose endpoint does not accept it; see [sendsEch].
     */
    val ech: Boolean = false,

    // ---- App-side only (never reach the engine CLI) ----

    /** TUN interface MTU. 1280 is the safe default for Iranian mobile/DPI. */
    val mtu: Int = DEFAULT_MTU,
    /**
     * Proxy mode: run the cores + local SOCKS5/HTTP proxy WITHOUT capturing
     * the whole device through a system VPN/TUN. Lets apps that support SOCKS5
     * natively (e.g. Telegram) use the tunnel selectively.
     */
    val proxyMode: Boolean = false,
    /** Split-tunneling policy. */
    val splitMode: SplitMode = SplitMode.OFF,
    /** Package names the split policy applies to. */
    val splitApps: List<String> = emptyList(),

    // ---- Added in 1.2.3 (engine v1.5.0 feature parity) ----

    /**
     * Resolvers used INSIDE the tunnel (engine `--dns`). Blank = engine default
     * (1.1.1.1, 1.0.0.1). Comma separated; a bare IP implies port 53.
     *
     * Also used by the chained-core path: with a Psiphon entry these are the
     * resolvers the DNS-over-TCP shim queries through the tunnel (see
     * [studio.cluvex.aether.core.SocksFront]).
     */
    val dnsServers: String = "",

    /**
     * Zero Trust organization ("team") name, e.g. "acme" for
     * acme.cloudflareaccess.com. Blank = consumer WARP.
     */
    val team: String = "",
    /** Which Zero Trust enrolment method to use. */
    val teamAuth: TeamAuth = TeamAuth.OFF,
    /** Access service-token client id (used when [teamAuth] is SERVICE_TOKEN). */
    val accessClientId: String = "",
    /**
     * Access service-token client secret. SECURITY: never emitted as a CLI
     * argument (argv is world-readable via /proc on rooted devices) - it is
     * handed to the engine through its environment instead.
     */
    val accessClientSecret: String = "",
    /** Work e-mail for the one-time-code flow (used when [teamAuth] is EMAIL). */
    val accessEmail: String = "",
    /** Pre-obtained enrolment JWT (used when [teamAuth] is TOKEN). Env-only. */
    val accessToken: String = "",
    /**
     * Route http/https through the organization's Gateway proxy so its
     * filtering and logging apply. Off by default: it adds a hop inside the
     * tunnel AND makes the organization able to log browsing.
     */
    val gateway: Boolean = false,

    /** Destinations that must never reach the network at all (engine `--route-block`). */
    val routeBlock: String = "",
    /** Destinations sent straight out, bypassing the tunnel (engine `--route-direct`). */
    val routeDirect: String = "",

    // ---- Added in 1.2.4 (feature parity) ----

    /** Kill switch: if the tunnel drops, keep a blocking blackhole TUN up so nothing leaks direct. */
    val killSwitch: Boolean = false,
    /** Strict kill switch: stay in lockdown even after a MANUAL disconnect until the user lifts it. */
    val strictKillSwitch: Boolean = false,
    /** Route IPv6 through the tunnel as well (prevents IPv6 leaks). On by default. */
    val ipv6LeakProtection: Boolean = true,
    /** Stop and report an error after [reconnectRetryLimit] failed core restarts. */
    val smartReconnect: Boolean = true,
    /** Max automatic core restarts when [smartReconnect] is on. */
    val reconnectRetryLimit: Int = 5,
    /** TLS ClientHello fragment chunk-size range, e.g. "16-32" (engine `--fragment-size`). */
    val fragmentSize: String = "",
    /** Inter-fragment delay range in ms, e.g. "2-10" (engine `--fragment-delay`). */
    val fragmentDelay: String = "",
    /** Skip the engine's end-to-end data check after connect (engine env). */
    val noDataCheck: Boolean = false,
    /** Restrict TLS curve groups, e.g. "X25519:P-256" (engine `--tls-groups`). */
    val tlsGroups: String = "",
    /** Endpoint validation window, seconds; 0 = engine default. */
    val validateSecs: Int = 0,
    /** Delay between engine-level reconnects, seconds; 0 = engine default. */
    val reconnectSecs: Int = 0,
    /** Do not fall back to alternate WireGuard profiles (engine `--no-profile-retry`). */
    val noProfileRetry: Boolean = false,
    /** Engine core log verbosity. */
    val coreLogLevel: CoreLogLevel = CoreLogLevel.WARN,
    /** Apps that get NO internet at all while the VPN is on (UID-filtering bridge). */
    val blockedApps: List<String> = emptyList(),

    // ---- Added in 1.5.0: chained cores (Psiphon / Tor) ----

    /**
     * Which cores carry the session, and in which order. [ChainMode.AETHER] is
     * the behaviour of every build before chaining existed, and stays the
     * default: the extra hops cost latency and are only worth it when the
     * simple path does not work.
     *
     * None of the fields below reach the engine's CLI - they configure the
     * Psiphon and Tor children instead (see PsiphonCore / TorCore). TorCore is
     * the app's ONLY Tor, and PsiphonCore its ONLY Psiphon.
     */
    val chain: ChainMode = ChainMode.AETHER,

    /**
     * Psiphon `EgressRegion`: a two-letter country code for the exit, or blank
     * for "the best performing server anywhere", which is what Psiphon is good
     * at and what a user should normally leave it doing.
     */
    val psiphonRegion: String = "",

    /**
     * A Psiphon client config (JSON), pasted by the user.
     *
     * `PropagationChannelId` and `SponsorId` are issued by the Psiphon network
     * and are not ours to ship, so this app cannot embed a working config. Blank
     * means "use the one bundled at build time" (assets/psiphon.config), and
     * with neither the Psiphon chain modes report that instead of failing deep
     * inside the core. See docs/CHAINING.md.
     */
    val psiphonConfig: String = "",

    /**
     * Tor `ExitNodes {cc}`: two-letter country code for the exit relay, or
     * blank for Tor's own choice. Requires the geoip database to be bundled;
     * without it tor cannot map relays to countries and ignores the request.
     */
    val torExitCountry: String = "",

    /**
     * Tor `StrictNodes 1`: never fall back to another country when the
     * requested one has no usable exit. Off by default, because "strict" here
     * means "rather no connection at all".
     */
    val torStrictNodes: Boolean = false,

    // ---- Added in 1.4.7: Tor bridges ----

    /**
     * WHERE the bridges in [torBridgeLines] came from, and therefore what the
     * Bridges page shows when it is reopened.
     *
     * ### Why the default is BUILTIN and not OFF
     *
     * Because of WHEN this setting is read. It only ever applies to a session
     * whose chain contains Tor - and the reason somebody in Iran selects a Tor
     * chain mode is that the ordinary internet is not working for them. Tor's
     * public relay list is public, so on those exact networks a bridgeless tor
     * sits at 5% and reports the network as blocked. Shipping OFF meant the
     * feature that makes the Tor hop work at all was one the user had to go and
     * find first, in a settings page they had no reason to suspect existed.
     *
     * So a Tor chain mode now comes with bridges already on, the built-in list
     * already selected (see [studio.cluvex.aether.core.BridgePlan]) and obfs4 as
     * the transport. Turning them OFF is still one tap, and it is honoured
     * verbatim from then on - see ProfileStore for how a stored OFF from a build
     * that had no other choice is told apart from a chosen one.
     *
     * This is provenance and UI state, NOT the thing tor is configured with -
     * see [activeBridgeLines].
     */
    val torBridgeMode: TorBridgeMode = TorBridgeMode.BUILTIN,

    /**
     * The bridge type the page is working with: which built-in list is shown,
     * and which transport a personal bridge is requested for.
     *
     * obfs4 is the default because it is what the Tor Project hands out first,
     * it is the cheapest of the obfuscated transports, and it is the one most
     * bridges actually run. When a build cannot launch it - no lyrebird - the
     * ladder in [studio.cluvex.aether.core.BridgePlan] moves on to a transport
     * it can, so this default can never strand a session.
     */
    val torBridgeTransport: BridgeTransport = BridgeTransport.OBFS4,

    /**
     * The bridge lines tor will be given, one per line.
     *
     * THE SINGLE SOURCE OF TRUTH for the torrc. Built-in bridges are COPIED in
     * here when the user picks them rather than resolved at connect time, so
     * what is configured is exactly what will run - a catalogue that refreshes
     * itself between the choice and the connection is a setting that changes
     * behind the user's back. A fresh profile is seeded with the built-in list
     * for the same reason, by ProfileStore, once.
     *
     * Never trusted: every line is re-validated by
     * [studio.cluvex.aether.core.BridgeLine] before it reaches the torrc,
     * because this field can hold anything that was pasted into it.
     */
    val torBridgeLines: String = "",

    // ---- Added in 2.0.0 (engine v2.0.0 feature parity) ----

    /**
     * Outer hop `ip:port` for [Protocol.MIM]. Blank = the engine scans for it.
     * A pinned [manualPeer] takes precedence, because pinning an endpoint with
     * two hops in play can only mean the one this device dials.
     */
    val mimOuterPeer: String = "",

    /** Inner hop `ip:port` for [Protocol.MIM]. Blank = the engine scans for it. */
    val mimInnerPeer: String = "",

    /**
     * QUIC v2 version negotiation on the opening packet, which gets HTTP/3
     * through boxes that recognise and drop QUIC v1 but pass v2.
     *
     * ON by default because that is the engine's own default; the only reason
     * to turn it off is a network that drops QUIC v2 specifically, where
     * negotiating costs a round trip for nothing.
     */
    val quicV2Opener: Boolean = true,

    /**
     * `SO_MARK` for the engine's own sockets (engine `--mark`, hex or decimal).
     *
     * Lets a tun front end on the same Linux host route around the engine's
     * traffic instead of feeding it back into the tunnel it came out of.
     * Requires root / CAP_NET_ADMIN, so on a normal Android device this stays
     * blank and the UI says why.
     */
    val socketMark: String = "",

    /**
     * Cap on concurrent proxy clients (engine `AETHER_MAX_CLIENTS`). 0 = the
     * engine's own sizing, which scales with the process fd limit it raised at
     * startup. Only worth setting on a device where something is holding
     * hundreds of sockets open.
     */
    val maxClients: Int = 0,

    /** Half-closed connection timeout, seconds (engine env). 0 = engine default (30). */
    val halfCloseSecs: Int = 0,

    /** TCP keep-alive on relayed sockets, seconds (engine env). 0 = engine default (60). */
    val tcpKeepaliveSecs: Int = 0,

    /** Upstream TCP connect timeout, seconds (engine env). 0 = engine default (30). */
    val tcpConnectSecs: Int = 0,

) {
    /** True when a Zero Trust organization is configured and usable. */
    val hasTeam: Boolean
        get() = teamAuth != TeamAuth.OFF && team.isNotBlank()

    /** True when the user pinned one specific gateway by hand. */
    val hasManualPeer: Boolean
        get() = endpointMode == EndpointMode.MANUAL_PEER && manualPeer.isNotBlank()

    /**
     * True when this profile asks tor to enter the network through bridges.
     *
     * The MODE alone decides it. This used to also require a non-empty
     * [torBridgeLines], which was right when an empty list meant tor got
     * `UseBridges` and nothing to use it with - but it no longer does: with
     * bridges on and no lines of its own,
     * [studio.cluvex.aether.core.BridgePlan] falls back to the built-in list for
     * a transport this build can launch. So "on" really does mean bridges, and a
     * user who cleared the list has not silently switched the feature off.
     */
    val usesBridges: Boolean
        get() = torBridgeMode.isOn

    /** True when the session is built as two MASQUE hops ([Protocol.MIM]). */
    val usesMim: Boolean
        get() = protocol == Protocol.MIM

    /**
     * True when [ech] actually reaches the engine as `--ech auto`.
     *
     * Never for the MASQUE transports (fix/masque-scan). The WARP MASQUE
     * endpoint does not accept ECH - upstream's own resolve_ech() says so - yet
     * with `--ech auto` the engine first spends up to ~18 s of DNS fetching an
     * ECHConfigList, before it scans anything, and then injects it into the
     * HTTP/3 tunnel handshake. The scan probes never carry ECH, so an edge that
     * passed the scan was dialled with a handshake the scan had never tested.
     * Over HTTP/2 the engine ignores the value, so there it was only the delay.
     * WireGuard and Gool are unchanged.
     */
    val sendsEch: Boolean
        get() = ech && !protocol.isMasque

    /**
     * The bridge lines to configure tor with, unvalidated.
     *
     * Returns nothing when bridges are off, so switching them off never depends
     * on also clearing the list - the lines are kept so turning bridges back on
     * does not mean fetching them again.
     *
     * Blank lines are dropped, and that is load-bearing rather than tidy:
     * `"".lines()` is a list of ONE empty string, so without this a profile with
     * bridges on and nothing in the field would hand the parser a line to reject
     * on every single connect, and "the list is empty" and "the list has one
     * unparseable entry" would look identical in the log.
     */
    fun activeBridgeLines(): List<String> =
        if (!torBridgeMode.isOn) {
            emptyList()
        } else {
            torBridgeLines.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

    /**
     * Command-line arguments passed to the `aether` engine binary.
     *
     * [coreVersion] is the engine these arguments are for. It defaults to the
     * core bundled in this APK and only exists as a parameter so tests can pin
     * it: a flag the engine does not know is fatal, so what is emitted has to
     * depend on which engine will read it.
     */
    fun toArgs(coreVersion: String = BuildConfig.CORE_VERSION): List<String> {
        val args = mutableListOf<String>()

        when (protocol) {
            // AUTO no longer reaches the engine: Smart Auto (core/SmartAuto.kt)
            // fingerprints the network's DPI and resolves AUTO to a concrete,
            // tuned protocol BEFORE launch. Kept only for exhaustiveness.
            Protocol.AUTO -> { /* resolved by SmartAuto before launch */ }
            Protocol.MASQUE -> args += "--masque"
            // --mim is MASQUE with a second hop through the first, so it
            // REPLACES --masque rather than joining it.
            Protocol.MIM -> args += "--mim"
            Protocol.WIREGUARD -> args += "--wg"
            Protocol.GOOL -> args += "--gool"
        }

        // A pinned peer makes scan mode irrelevant, so only emit it otherwise.
        // The flag lives on the mode itself, so there is exactly one place
        // where a mode becomes an engine argument - and effectiveFor() makes
        // sure it is one this engine parses.
        if (!hasManualPeer) args += scanMode.effectiveFor(coreVersion).engineFlag

        when (ipVersion) {
            IpVersion.V4 -> args += "-4"
            IpVersion.V6 -> args += "-6"
            IpVersion.BOTH -> args += "--dual"
        }

        args += if (quickReconnect) "--quick-reconnect" else "--no-quick-reconnect"

        // OFF is an explicit choice, not the absence of an option: the native
        // defaults are firewall (MASQUE) and balanced (WireGuard). Always send
        // the value so scans, verification and tunnels see the same setting.
        // Which is also why [noize] defaults to FIREWALL rather than OFF: those
        // native defaults are what a default install has always connected with.
        args += "--noize"
        args += noize.name.lowercase()

        // Manual endpoint pins one gateway and skips scanning entirely.
        if (hasManualPeer) {
            // With two MASQUE hops the pinned address is the OUTER one; --peer
            // would describe a single-hop tunnel the engine is not building.
            args += if (usesMim) "--mim-outer" else "--peer"
            args += manualPeer.trim()
        }

        if (fragment) args += "--fragment"
        // Never to a MASQUE transport, whose endpoint does not accept ECH; see sendsEch.
        if (sendsEch) { args += "--ech"; args += "auto" }
        if (keepalive > 0) { args += "--keepalive"; args += keepalive.toString() }

        // ---- engine v1.5.0 ----

        // In-tunnel resolvers. Sanitised so a malformed entry can never inject
        // a second CLI token (the engine itself also re-validates each entry).
        sanitizedDns().takeIf { it.isNotEmpty() }?.let {
            args += "--dns"
            args += it.joinToString(",")
        }

        // Zero Trust: only the non-secret team name travels via argv. The id,
        // secret, token and e-mail go through the environment (see toEnv).
        if (hasTeam) {
            args += "--team"
            args += team.trim()
            if (gateway) args += "--gateway"
        }

        // Split routing rules. Block is evaluated before direct by the engine.
        sanitizedRules(routeBlock).takeIf { it.isNotEmpty() }?.let {
            args += "--route-block"
            args += it.joinToString(",")
        }
        sanitizedRules(routeDirect).takeIf { it.isNotEmpty() }?.let {
            args += "--route-direct"
            args += it.joinToString(",")
        }

        // ---- 1.2.4 engine tuning ----
        if (fragment) {
            sanitizedRange(fragmentSize)?.let { args += "--fragment-size"; args += it }
            sanitizedRange(fragmentDelay)?.let { args += "--fragment-delay"; args += it }
        }
        sanitizedTlsGroups()?.let { args += "--tls-groups"; args += it }
        if (validateSecs > 0) { args += "--validate-secs"; args += validateSecs.coerceIn(1, 3600).toString() }
        if (reconnectSecs > 0) { args += "--reconnect-secs"; args += reconnectSecs.coerceIn(1, 600).toString() }
        if (noProfileRetry) args += "--no-profile-retry"

        // ---- engine v2.0.0 ----

        // The pinned peer, if there is one, already went out as the OUTER hop
        // above, so only the halves that are still unset are filled in here.
        // Blank means "scan for it", which is the normal case for both.
        if (usesMim) {
            if (!hasManualPeer) {
                sanitizedEndpoint(mimOuterPeer)?.let { args += "--mim-outer"; args += it }
            }
            sanitizedEndpoint(mimInnerPeer)?.let { args += "--mim-inner"; args += it }
        }

        // The QUIC v2 opener is ON in the engine, so this only ever speaks up
        // to switch it OFF.
        if (!quicV2Opener) args += "--no-quic-v2"

        sanitizedMark()?.let { args += "--mark"; args += it }

        // Deliberately NO --tor / --tor-reverse / --tor-only and NO --psiphon /
        // --psiphon-reverse / --psiphon-only here. Tor is the app's TorCore and
        // Psiphon its PsiphonCore, both reached through the chain modes; see
        // the class doc.

        return args
    }

    /** Environment variables for the engine process. */
    fun toEnv(): Map<String, String> = buildMap {
        put("AETHER_MASQUE_HTTP2", if (masqueHttp2) "1" else "0")

        // Which addresses the engine's scanner may consider.
        //
        // Only what the user pinned in Settings. With nothing pinned the
        // engine uses its own built-in WARP ranges and picks an endpoint
        // itself, which is the natural behaviour of the core.
        val userRange = manualRange.trim()
        if (endpointMode == EndpointMode.MANUAL_RANGE && userRange.isNotBlank()) {
            // The manual-range patch read AETHER_MASQUE_CIDRS (MASQUE) and
            // AETHER_WG_CIDRS (WireGuard), then AETHER_SCAN_CIDRS. It did not
            // survive the core 2.0.0 sync, so today's engine ignores all three
            // (see [manualRange]); they stay set so a re-applied patch works
            // without an app change.
            put("AETHER_SCAN_CIDRS", userRange)
            put("AETHER_MASQUE_CIDRS", userRange)
            put("AETHER_WG_CIDRS", userRange)
        }

        // ---- Zero Trust credentials (engine v1.5.0) ----
        //
        // SECURITY: these are passed as environment variables, NOT as CLI
        // arguments. On Android every local app can read /proc/<pid>/cmdline
        // of a process it can see, but the environment block is only readable
        // by the process owner. The engine reads exactly these names in
        // zerotrust.rs::TeamSettings::from_env().
        if (hasTeam) {
            when (teamAuth) {
                TeamAuth.SERVICE_TOKEN -> {
                    accessClientId.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_CLIENT_ID", it) }
                    accessClientSecret.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_CLIENT_SECRET", it) }
                }
                TeamAuth.EMAIL ->
                    accessEmail.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_EMAIL", it) }
                TeamAuth.TOKEN ->
                    accessToken.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_TOKEN", it) }
                TeamAuth.OFF -> Unit
            }
        }

        // ---- 1.2.4 engine tuning ----
        if (noDataCheck) {
            put("AETHER_MASQUE_NO_DATA_CHECK", "1")
            put("AETHER_WG_NO_DATA_CHECK", "1")
        }
        if (validateSecs > 0) {
            put("AETHER_MASQUE_VALIDATE_SECS", validateSecs.coerceIn(1, 3600).toString())
        }
        if (reconnectSecs > 0) {
            put("AETHER_MASQUE_RECONNECT_SECS", reconnectSecs.coerceIn(1, 600).toString())
            put("AETHER_WG_RECONNECT_SECS", reconnectSecs.coerceIn(1, 600).toString())
        }
        if (noProfileRetry) put("AETHER_WG_NO_PROFILE_RETRY", "1")
        sanitizedTlsGroups()?.let { put("AETHER_TLS_GROUPS", it) }
        if (coreLogLevel != CoreLogLevel.WARN) put("AETHER_LOG_LEVEL", coreLogLevel.raw)

        // ---- engine v2.0.0 ----
        //
        // The flags in toArgs already set most of v2.0.0's new behaviour. What
        // is here either has no flag at all (the resource caps) or is worth
        // setting twice so a profile reproduced in a shell behaves identically.
        if (!quicV2Opener) put("AETHER_QUIC_V2", "0")
        // Zero means "the engine's own sizing", which it derives from the fd
        // limit it raises at startup - a better answer than any constant this
        // app could pick, so these only appear when a user overrides them.
        if (maxClients > 0) put("AETHER_MAX_CLIENTS", maxClients.coerceIn(16, 8192).toString())
        if (halfCloseSecs > 0) put("AETHER_HALF_CLOSE_SECS", halfCloseSecs.coerceIn(1, 3600).toString())
        if (tcpKeepaliveSecs > 0) put("AETHER_TCP_KEEPALIVE_SECS", tcpKeepaliveSecs.coerceIn(1, 3600).toString())
        if (tcpConnectSecs > 0) put("AETHER_TCP_CONNECT_SECS", tcpConnectSecs.coerceIn(1, 300).toString())
    }

    /**
     * Validated resolver list for `--dns`. Accepts `1.1.1.1` or `1.1.1.1:53`
     * and drops anything else, so a stray space or shell metacharacter in the
     * settings field can never become a separate engine argument.
     */
    fun sanitizedDns(): List<String> = dnsServers
        .split(',', ' ', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && DNS_ENTRY.matches(it) }
        .distinct()
        .take(MAX_DNS_SERVERS)

    /**
     * Validated routing-rule list. Mirrors the grammar documented by the
     * engine (`example.com`, `full:`, `keyword:`, `regexp:`, CIDR, `port:`,
     * `private`) and rejects entries containing a comma, whitespace or a shell
     * metacharacter, which would otherwise split into extra arguments.
     */
    fun sanitizedRules(raw: String): List<String> = raw
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && RULE_ENTRY.matches(it) }
        .distinct()
        .take(MAX_ROUTE_RULES)

    /**
     * How long to wait for the engine to open the local SOCKS5 port before
     * giving up.
     *
     * This MUST comfortably exceed the engine's OWN scan budget for the chosen
     * mode (prober.rs strategy(): 45 s turbo, 120-150 s balanced, 60 s verified,
     * 180-300 s ironclad on MASQUE, depending on the core), otherwise the app
     * aborts an attempt while the engine is still legitimately scanning - which
     * looks exactly like a failure and is not one. A pinned peer connects almost
     * immediately.
     *
     * Verified gets twice its engine budget because on gool and mim it scans for
     * two hops in two different ranges.
     *
     * The MASQUE transports with quick reconnect get
     * [MASQUE_QUICK_RECONNECT_ALLOWANCE_MS] on top (fix/masque-scan): from core
     * 2.1.0 the engine re-verifies up to eight remembered gateways, one at a
     * time and 5 s each, BEFORE its scan starts. On a network that has just
     * blocked them that is 40 s no scan budget above accounts for - enough to
     * end a 60 s Turbo attempt (Smart Auto's mode on every rung) before its
     * sweep had really begun.
     *
     * The budget is the one for the mode the engine will actually RUN
     * ([ScanMode.effectiveFor]), so a Verified profile on a pre-2.1.0 core is
     * waited on like the Precise scan it becomes.
     *
     * Chained cores are NOT included here: each of them has its own budget in
     * VpnTunables and its own readiness signal, and folding them into one
     * number would mean a slow Tor bootstrap looked like a slow endpoint scan.
     */
    fun connectTimeoutMs(coreVersion: String = BuildConfig.CORE_VERSION): Long {
        if (hasManualPeer) return 45_000L
        val scan = when (scanMode.effectiveFor(coreVersion)) {
            ScanMode.TURBO -> 60_000L
            ScanMode.PRECISE -> 190_000L
            ScanMode.VERIFIED -> 120_000L
            ScanMode.ULTRA -> 340_000L
        }
        val ring = if (protocol.isMasque && quickReconnect) {
            MASQUE_QUICK_RECONNECT_ALLOWANCE_MS
        } else {
            0L
        }
        return scan + ring
    }

    /** Accepts `500` or `16-32` style ranges; anything else is dropped. */
    private fun sanitizedRange(raw: String): String? {
        val text = raw.trim().takeIf { it.matches(RANGE_ENTRY) } ?: return null
        val numbers = text.split("-").map { it.toInt() }
        // Out-of-bounds or reversed values are typos, not tuning data - the
        // engine would receive garbage flags and fail late instead of never.
        if (numbers.any { it < 1 || it > 65_535 }) return null
        return if (numbers.size == 2 && numbers[0] > numbers[1]) null else text
    }

    private fun sanitizedTlsGroups(): String? =
        tlsGroups.trim().takeIf { it.matches(Regex("^[A-Za-z0-9:_-]{1,64}$")) }

    /**
     * `ip:port` for the MASQUE-in-MASQUE hops. The port is mandatory here,
     * unlike [sanitizedDns] where a bare resolver implies 53 - an endpoint
     * without a port is not something the engine can dial.
     */
    private fun sanitizedEndpoint(raw: String): String? =
        raw.trim().takeIf { it.matches(ENDPOINT_ENTRY) }

    /**
     * `SO_MARK` as the engine expects it: decimal, or `0x`-prefixed hex, and
     * inside the 32-bit range. Zero is rejected rather than passed through,
     * because `--mark 0` asks the engine to mark every socket with "no mark".
     */
    private fun sanitizedMark(): String? {
        val text = socketMark.trim().takeIf { it.matches(MARK_ENTRY) } ?: return null
        val value = if (text.length > 2 && (text[1] == 'x' || text[1] == 'X')) {
            text.substring(2).toLongOrNull(16)
        } else {
            text.toLongOrNull()
        }
        return if (value == null || value <= 0L || value > 0xFFFF_FFFFL) null else text
    }

    companion object {
        /** Safe default TUN MTU for Iranian mobile networks / aggressive DPI. */
        const val DEFAULT_MTU = 1280
        /** Presets offered in the UI. */
        val MTU_PRESETS = listOf(1280, 1380, 1420, 1500, 8500)
        /** Keepalive presets offered in the UI (0 = engine default). */
        val KEEPALIVE_PRESETS = listOf(0, 10, 25, 45)

        /** Hard caps so a pasted blob can't build a gigantic argv. */
        const val MAX_DNS_SERVERS = 8
        const val MAX_ROUTE_RULES = 256

        /**
         * What a MASQUE attempt with quick reconnect waits on top of its scan
         * budget (see [connectTimeoutMs]): engine 2.1.0 re-verifies up to
         * lastconn::RECENT_CAP = 8 remembered gateways with a 5 s timeout each,
         * sequentially, in lib.rs run_masque before it scans. 40 s, plus slack.
         */
        const val MASQUE_QUICK_RECONNECT_ALLOWANCE_MS = 45_000L

        /**
         * `1.1.1.1` or `1.1.1.1:53` (IPv4, or bracketed IPv6 with a port).
         * Octets and ports are numerically bounded - `\d{1,3}` alone would
         * accept `999.999.999.999:99999` and defer the failure to the engine.
         */
        private val OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
        private val PORT = "(?:6553[0-5]|655[0-2]\\d|65[0-4]\\d{2}|6[0-4]\\d{3}|[1-9]\\d{0,3})"
        private val DNS_ENTRY =
            Regex("^(?:$OCTET(?:\\.$OCTET){3}|\\[[0-9A-Fa-f:]+])(?::$PORT)?$")

        /** The same address, but the port is required (see [sanitizedEndpoint]). */
        private val ENDPOINT_ENTRY =
            Regex("^(?:$OCTET(?:\\.$OCTET){3}|\\[[0-9A-Fa-f:]+]):$PORT$")

        /** `500` or `16-32`: digits only, bounds and ordering checked in [sanitizedRange]. */
        private val RANGE_ENTRY = Regex("^\\d{1,5}(-\\d{1,5})?$")

        /** One routing-rule token: no comma, no whitespace, no shell metacharacters. */
        private val RULE_ENTRY = Regex("^[A-Za-z0-9_.:/*\\-\\[\\]^$+?()|{}\\\\]{1,200}$")

        /** Decimal or `0x` hex; range checked in [sanitizedMark]. */
        private val MARK_ENTRY = Regex("^(?:0[xX][0-9A-Fa-f]{1,8}|\\d{1,10})$")
    }
}
