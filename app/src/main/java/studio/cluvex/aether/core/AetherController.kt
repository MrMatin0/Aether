package studio.cluvex.aether.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorBridgeMode
import studio.cluvex.aether.vpn.AetherVpnService

/**
 * App-wide singleton that (a) publishes the live [ConnectionState] to the UI and
 * (b) sends connect/disconnect intents to [AetherVpnService].
 */
object AetherController {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Epoch millis of when the current session became Connected, or null. */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /** IP + country shown in the UI (exit server when connected, operator when not). */
    private val _ipInfo = MutableStateFlow<IpEndpoint?>(null)
    val ipInfo: StateFlow<IpEndpoint?> = _ipInfo.asStateFlow()

    /** True while an IP lookup is in flight (drives the "..." placeholder). */
    private val _ipLoading = MutableStateFlow(false)
    val ipLoading: StateFlow<Boolean> = _ipLoading.asStateFlow()

    /** Called by the service to broadcast state changes. */
    fun setState(newState: ConnectionState) {
        _state.value = newState
        when (newState) {
            is ConnectionState.Connected ->
                if (_connectedSince.value == null) _connectedSince.value = System.currentTimeMillis()
            is ConnectionState.Reconnecting -> {
                // Keep the running timer during a transient reconnect.
            }
            else -> _connectedSince.value = null
        }
    }

    fun setIpInfo(info: IpEndpoint?) {
        _ipInfo.value = info
    }

    /**
     * Sets the tunnel exit IP only when the badge doesn't already show a
     * tunnel IP for this session.
     */
    fun offerTunnelIpInfo(info: IpEndpoint) {
        if (_ipInfo.value?.viaTunnel == true) return
        _ipInfo.value = info
    }

    fun setIpLoading(loading: Boolean) {
        _ipLoading.value = loading
    }

    /**
     * Returns a consent Intent if the user must still grant VPN permission,
     * or null if permission was already granted.
     */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun connect(context: Context, profile: ConnectionProfile) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_CONNECT
            putExtra(AetherVpnService.EXTRA_PROFILE, ProfileCodec.encode(profile))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_DISCONNECT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

/**
 * Serialises a [ConnectionProfile] into a compact `key=value` list (one pair per
 * line) for Intent transport. This format is forward/backward tolerant: unknown
 * keys are ignored and missing keys fall back to the model defaults, so old and
 * new builds can decode each other's payloads without crashing.
 *
 * DROPPED-SETTINGS FIX: the codec is the ONLY channel between the UI and
 * [AetherVpnService], so anything it forgets is silently thrown away - the user
 * configures it in Settings, sees it persisted, and the cores never learn about
 * it. The 1.2.3 feature block (in-tunnel DNS, Zero Trust organization,
 * `--route-block` / `--route-direct`) was never added here, so those fields
 * reached the DataStore but never the engine's argv/env. Every non-secret field
 * of [ConnectionProfile] is transported now.
 *
 * The two Zero Trust SECRETS ([ConnectionProfile.accessClientSecret],
 * [ConnectionProfile.accessToken]) are deliberately NOT part of the payload:
 * Intent extras are visible in system service dumps, so the service reads them
 * straight from the Keystore-sealed store instead (see the VpnService's
 * `hydrate`).
 */
object ProfileCodec {
    fun encode(p: ConnectionProfile): String = buildList {
        add("protocol=${p.protocol.name}")
        add("scan=${p.scanMode.name}")
        add("ip=${p.ipVersion.name}")
        add("quick=${p.quickReconnect}")
        add("h2=${p.masqueHttp2}")
        add("share=${p.lanShare}")
        add("noize=${p.noize.name}")
        add("endpoint=${p.endpointMode.name}")
        add("peer=${flatten(p.manualPeer)}")
        add("range=${flatten(p.manualRange)}")
        add("keepalive=${p.keepalive}")
        add("fragment=${p.fragment}")
        add("ech=${p.ech}")
        add("mtu=${p.mtu}")
        add("proxy=${p.proxyMode}")
        add("split=${p.splitMode.name}")
        add("splitApps=${p.splitApps.joinToString(",")}")
        // Added in 1.2.3 (engine v1.5.0) - these were missing from the codec,
        // so the engine never received them (see the class doc).
        add("dns=${flatten(p.dnsServers)}")
        add("team=${flatten(p.team)}")
        add("teamAuth=${p.teamAuth.name}")
        add("accessId=${flatten(p.accessClientId)}")
        add("accessEmail=${flatten(p.accessEmail)}")
        add("gateway=${p.gateway}")
        add("routeBlock=${flatten(p.routeBlock)}")
        add("routeDirect=${flatten(p.routeDirect)}")
        // Added in 1.2.4 (feature parity)
        add("kill=${p.killSwitch}")
        add("strictKill=${p.strictKillSwitch}")
        add("v6leak=${p.ipv6LeakProtection}")
        add("smartRe=${p.smartReconnect}")
        add("reLimit=${p.reconnectRetryLimit}")
        add("fSize=${flatten(p.fragmentSize)}")
        add("fDelay=${flatten(p.fragmentDelay)}")
        add("noDataCheck=${p.noDataCheck}")
        add("tlsGroups=${flatten(p.tlsGroups)}")
        add("valSecs=${p.validateSecs}")
        add("recSecs=${p.reconnectSecs}")
        add("noProfRetry=${p.noProfileRetry}")
        add("coreLog=${p.coreLogLevel.name}")
        add("blockedApps=${p.blockedApps.joinToString(",")}")
        // Added in 1.5.0 (chained cores: Psiphon / Tor)
        add("chain=${p.chain.name}")
        add("psiphonRegion=${flatten(p.psiphonRegion)}")
        add("psiphonConfig=${flattenJson(p.psiphonConfig)}")
        add("torExit=${flatten(p.torExitCountry)}")
        add("torStrict=${p.torStrictNodes}")
        // Added in 1.4.7 (Tor bridges)
        add("torBridgeMode=${p.torBridgeMode.name}")
        add("torBridgeTransport=${p.torBridgeTransport.name}")
        add("torBridges=${flattenBridges(p.torBridgeLines)}")
    }.joinToString("\n")

    fun decode(raw: String?): ConnectionProfile {
        if (raw.isNullOrBlank()) return ConnectionProfile()
        // Backward compatibility: the 1.0/1.1 codec used a single pipe-delimited
        // line with no keys. Detect and decode that legacy shape.
        if (!raw.contains('=') && raw.contains('|')) return decodeLegacy(raw)

        val map = raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        val d = ConnectionProfile()
        return runCatching {
            ConnectionProfile(
                protocol = map["protocol"]?.let { enumOr<Protocol>(it) } ?: d.protocol,
                // Not enumOr(): a payload written before 1.4.6 carries one of
                // the five retired names, and ScanMode knows how to map those
                // onto the three that exist now.
                scanMode = ScanMode.fromStored(map["scan"]) ?: d.scanMode,
                ipVersion = map["ip"]?.let { enumOr<IpVersion>(it) } ?: d.ipVersion,
                quickReconnect = map["quick"]?.toBooleanStrictOrNull() ?: d.quickReconnect,
                masqueHttp2 = map["h2"]?.toBooleanStrictOrNull() ?: d.masqueHttp2,
                lanShare = map["share"]?.toBooleanStrictOrNull() ?: d.lanShare,
                noize = map["noize"]?.let { enumOr<Noize>(it) } ?: d.noize,
                endpointMode = map["endpoint"]?.let { enumOr<EndpointMode>(it) } ?: d.endpointMode,
                manualPeer = map["peer"] ?: d.manualPeer,
                manualRange = map["range"] ?: d.manualRange,
                keepalive = map["keepalive"]?.toIntOrNull() ?: d.keepalive,
                fragment = map["fragment"]?.toBooleanStrictOrNull() ?: d.fragment,
                ech = map["ech"]?.toBooleanStrictOrNull() ?: d.ech,
                mtu = map["mtu"]?.toIntOrNull() ?: d.mtu,
                proxyMode = map["proxy"]?.toBooleanStrictOrNull() ?: d.proxyMode,
                splitMode = map["split"]?.let { enumOr<SplitMode>(it) } ?: d.splitMode,
                splitApps = map["splitApps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.splitApps,
                dnsServers = map["dns"] ?: d.dnsServers,
                team = map["team"] ?: d.team,
                teamAuth = map["teamAuth"]?.let { enumOr<TeamAuth>(it) } ?: d.teamAuth,
                accessClientId = map["accessId"] ?: d.accessClientId,
                accessEmail = map["accessEmail"] ?: d.accessEmail,
                gateway = map["gateway"]?.toBooleanStrictOrNull() ?: d.gateway,
                routeBlock = map["routeBlock"] ?: d.routeBlock,
                routeDirect = map["routeDirect"] ?: d.routeDirect,
                killSwitch = map["kill"]?.toBooleanStrictOrNull() ?: d.killSwitch,
                strictKillSwitch = map["strictKill"]?.toBooleanStrictOrNull() ?: d.strictKillSwitch,
                ipv6LeakProtection = map["v6leak"]?.toBooleanStrictOrNull() ?: d.ipv6LeakProtection,
                smartReconnect = map["smartRe"]?.toBooleanStrictOrNull() ?: d.smartReconnect,
                reconnectRetryLimit = map["reLimit"]?.toIntOrNull() ?: d.reconnectRetryLimit,
                fragmentSize = map["fSize"] ?: d.fragmentSize,
                fragmentDelay = map["fDelay"] ?: d.fragmentDelay,
                noDataCheck = map["noDataCheck"]?.toBooleanStrictOrNull() ?: d.noDataCheck,
                tlsGroups = map["tlsGroups"] ?: d.tlsGroups,
                validateSecs = map["valSecs"]?.toIntOrNull() ?: d.validateSecs,
                reconnectSecs = map["recSecs"]?.toIntOrNull() ?: d.reconnectSecs,
                noProfileRetry = map["noProfRetry"]?.toBooleanStrictOrNull() ?: d.noProfileRetry,
                coreLogLevel = map["coreLog"]?.let { enumOr<CoreLogLevel>(it) } ?: d.coreLogLevel,
                blockedApps = map["blockedApps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.blockedApps,
                // Not enumOr(): ChainMode also understands the shapes a human
                // types into an exported config by hand ("aether+psiphon").
                chain = ChainMode.fromStored(map["chain"]) ?: d.chain,
                psiphonRegion = map["psiphonRegion"] ?: d.psiphonRegion,
                psiphonConfig = map["psiphonConfig"] ?: d.psiphonConfig,
                torExitCountry = map["torExit"] ?: d.torExitCountry,
                torStrictNodes = map["torStrict"]?.toBooleanStrictOrNull() ?: d.torStrictNodes,
                // Same reasoning as the two above: an imported config can spell
                // these the way a human would.
                torBridgeMode = TorBridgeMode.fromStored(map["torBridgeMode"]) ?: d.torBridgeMode,
                torBridgeTransport = BridgeTransport.fromStored(map["torBridgeTransport"])
                    ?: d.torBridgeTransport,
                torBridgeLines = map["torBridges"]?.let { unflattenBridges(it) } ?: d.torBridgeLines,
            )
        }.getOrDefault(d)
    }

    private fun decodeLegacy(raw: String): ConnectionProfile {
        val parts = raw.split("|")
        if (parts.size < 5) return ConnectionProfile()
        val d = ConnectionProfile()
        return runCatching {
            ConnectionProfile(
                protocol = Protocol.valueOf(parts[0]),
                // Field two is a scan mode from the five-mode era by
                // definition: this format predates 1.2.
                scanMode = ScanMode.fromStored(parts[1]) ?: d.scanMode,
                ipVersion = IpVersion.valueOf(parts[2]),
                quickReconnect = parts[3].toBoolean(),
                masqueHttp2 = parts[4].toBoolean(),
                lanShare = parts.getOrNull(5)?.toBoolean() ?: false,
            )
        }.getOrDefault(ConnectionProfile())
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()

    /**
     * The payload is line-framed, so an embedded newline would truncate the
     * value and turn the rest into a bogus key (one containing '=' could even
     * shadow another field). Free-text fields are comma-separated for the
     * engine anyway, so newlines fold into commas.
     */
    private fun flatten(value: String): String = value.lineSequence().joinToString(",")

    /**
     * Same framing problem, opposite fold: a pasted Psiphon config is JSON, and
     * [flatten] would splice a comma into it and hand the core a document that
     * no longer parses. In JSON a newline is nothing but whitespace, so it folds
     * to a space and the document survives the round trip byte-for-byte as far
     * as any parser is concerned.
     */
    private fun flattenJson(value: String): String = value.lineSequence().joinToString(" ")

    /**
     * Bridge lines: a THIRD fold, and it has to be.
     *
     * [flatten] would join them with commas, and a bridge line contains commas
     * of its own - a built-in snowflake line carries
     * `fronts=a.example,b.example` and eight comma-separated `ice=` servers. So
     * splitting on a comma cannot reconstruct the lines, and the transport most
     * likely to be reached for when obfs4 is blocked would be the one that
     * arrives corrupted. [flattenJson] is no better: folding to a space merges
     * two bridges into one unparseable line.
     *
     * '|' is safe: it appears in no bridge-line grammar (a URL must
     * percent-encode it) and [BridgeLine] rejects any line containing one, so a
     * round trip is exact. It also cannot be confused with the 1.0/1.1 legacy
     * payload, which is only tried when the whole document has no '=' in it.
     */
    private fun flattenBridges(value: String): String =
        value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|")

    private fun unflattenBridges(value: String): String =
        value.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
