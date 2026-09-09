package studio.cluvex.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorBridgeMode

private val Context.dataStore by preferencesDataStore(name = "aether_profile")

/** Persists the last-used [ConnectionProfile] with Jetpack DataStore. */
class ProfileStore(private val context: Context) {
    private object Keys {
        val protocol = stringPreferencesKey("protocol")
        val scan = stringPreferencesKey("scan")
        val ip = stringPreferencesKey("ip")
        val quick = booleanPreferencesKey("quick")
        val h2 = booleanPreferencesKey("h2")
        val share = booleanPreferencesKey("share")
        // Added in 1.2.0
        val noize = stringPreferencesKey("noize")
        /**
         * True once this profile has been written by a build whose engine
         * honours an explicit OFF. See the obfuscation migration in [profile]:
         * without it, an OFF stored by an older build cannot be told apart
         * from a deliberate "run without obfuscation".
         */
        val noizeChosen = booleanPreferencesKey("noizeChosen")
        val endpoint = stringPreferencesKey("endpoint")
        val peer = stringPreferencesKey("peer")
        val range = stringPreferencesKey("range")
        val keepalive = intPreferencesKey("keepalive")
        val fragment = booleanPreferencesKey("fragment")
        val ech = booleanPreferencesKey("ech")
        val mtu = intPreferencesKey("mtu")
        val proxy = booleanPreferencesKey("proxy")
        val split = stringPreferencesKey("split")
        val splitApps = stringPreferencesKey("splitApps")
        // Added in 1.2.3 (engine v1.5.0)
        val dns = stringPreferencesKey("dns")
        val team = stringPreferencesKey("team")
        val teamAuth = stringPreferencesKey("teamAuth")
        val accessId = stringPreferencesKey("accessId")
        val accessEmail = stringPreferencesKey("accessEmail")
        val gateway = booleanPreferencesKey("gateway")
        val routeBlock = stringPreferencesKey("routeBlock")
        val routeDirect = stringPreferencesKey("routeDirect")
        // Added in 1.2.4 (feature parity)
        val killSwitch = booleanPreferencesKey("killSwitch")
        val strictKillSwitch = booleanPreferencesKey("strictKillSwitch")
        val ipv6Leak = booleanPreferencesKey("ipv6Leak")
        val smartReconnect = booleanPreferencesKey("smartReconnect")
        val reconnectRetryLimit = intPreferencesKey("reconnectRetryLimit")
        val fragmentSize = stringPreferencesKey("fragmentSize")
        val fragmentDelay = stringPreferencesKey("fragmentDelay")
        val noDataCheck = booleanPreferencesKey("noDataCheck")
        val tlsGroups = stringPreferencesKey("tlsGroups")
        val validateSecs = intPreferencesKey("validateSecs")
        val reconnectSecs = intPreferencesKey("reconnectSecs")
        val noProfileRetry = booleanPreferencesKey("noProfileRetry")
        val coreLogLevel = stringPreferencesKey("coreLogLevel")
        val blockedApps = stringPreferencesKey("blockedApps")
        // Added in 1.5.0 (chained cores: Psiphon / Tor)
        val chain = stringPreferencesKey("chain")
        val psiphonRegion = stringPreferencesKey("psiphonRegion")
        val psiphonConfig = stringPreferencesKey("psiphonConfig")
        val torExitCountry = stringPreferencesKey("torExitCountry")
        val torStrictNodes = booleanPreferencesKey("torStrictNodes")
        // Added in 1.4.7 (Tor bridges)
        val torBridgeMode = stringPreferencesKey("torBridgeMode")
        val torBridgeTransport = stringPreferencesKey("torBridgeTransport")
        /**
         * Newline separated, exactly as tor will receive them. A preferences
         * file has no line framing of its own, so unlike the Intent payload
         * (see ProfileCodec) nothing has to be folded here.
         */
        val torBridgeLines = stringPreferencesKey("torBridgeLines")
    }

    /**
     * Zero Trust secrets (service-token secret + enrolment JWT) are NOT kept in
     * the DataStore preferences file. That file is plain protobuf inside the app
     * sandbox, so a device backup or an adb dump on a rooted phone would expose
     * a long-lived organization credential. They live in [SecretStore] instead,
     * sealed with a hardware-backed AES-GCM key from the Android Keystore.
     *
     * The Psiphon client config deliberately stays HERE: it is a public,
     * network-issued client identity (propagation channel + sponsor + server
     * list URLs), not a user credential, and every Psiphon client ships one.
     *
     * Bridge lines stay here too, and that is a deliberate call rather than an
     * oversight: a bridge address is not a credential (it authenticates nobody
     * and is shared by everyone using that bridge), and the alternative -
     * sealing them - would make them unreadable to a user trying to copy their
     * working bridges to another device, which is a thing people on filtered
     * networks actually do. What they ARE is sensitive in a different sense: a
     * personal bridge in a screenshot is a bridge that can be reported. The UI
     * says so where it matters.
     */
    private val secrets = SecretStore(context)

    val profile: Flow<ConnectionProfile> = context.dataStore.data.map { prefs ->
        val d = ConnectionProfile()
        // MIGRATION (obfuscation): an OFF stored by an older build is NOT a
        // choice to run bare. Back then OFF meant "emit no --noize", and the
        // engine then applied its own defaults (firewall for MASQUE, balanced
        // for WireGuard) - so that profile has always connected WITH
        // obfuscation. Now that the engine honours OFF verbatim, reading such a
        // value literally would silently strip the junk packets a filtered
        // network needs, and a MASQUE scan on it simply finds nothing.
        //
        // So a stored OFF is read as the default until this profile is written
        // by a build that honours OFF ([Keys.noizeChosen], set by [save]).
        // After that, OFF is respected exactly as chosen.
        val storedNoize = prefs[Keys.noize]?.let { runCatching { Noize.valueOf(it) }.getOrNull() }
        val noizeChosen = prefs[Keys.noizeChosen] ?: false
        val noize = when {
            storedNoize == null -> d.noize
            storedNoize == Noize.OFF && !noizeChosen -> d.noize
            else -> storedNoize
        }
        ConnectionProfile(
            protocol = prefs[Keys.protocol]
                ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() } ?: Protocol.AUTO,
            // Not valueOf(): the five-mode set was retired in 1.4.6 and this
            // file still holds whatever the user picked before, so the stored
            // name is MIGRATED rather than dropped on the floor.
            scanMode = ScanMode.fromStored(prefs[Keys.scan]) ?: d.scanMode,
            ipVersion = prefs[Keys.ip]
                ?.let { runCatching { IpVersion.valueOf(it) }.getOrNull() } ?: IpVersion.V4,
            quickReconnect = prefs[Keys.quick] ?: true,
            masqueHttp2 = prefs[Keys.h2] ?: false,
            lanShare = prefs[Keys.share] ?: false,
            noize = noize,
            endpointMode = prefs[Keys.endpoint]
                ?.let { runCatching { EndpointMode.valueOf(it) }.getOrNull() } ?: EndpointMode.AUTO,
            manualPeer = prefs[Keys.peer] ?: "",
            manualRange = prefs[Keys.range] ?: "",
            keepalive = prefs[Keys.keepalive] ?: 0,
            fragment = prefs[Keys.fragment] ?: false,
            ech = prefs[Keys.ech] ?: false,
            mtu = prefs[Keys.mtu] ?: d.mtu,
            proxyMode = prefs[Keys.proxy] ?: false,
            splitMode = prefs[Keys.split]
                ?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() } ?: SplitMode.OFF,
            splitApps = prefs[Keys.splitApps]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            dnsServers = prefs[Keys.dns] ?: "",
            team = prefs[Keys.team] ?: "",
            teamAuth = prefs[Keys.teamAuth]
                ?.let { runCatching { TeamAuth.valueOf(it) }.getOrNull() } ?: TeamAuth.OFF,
            accessClientId = prefs[Keys.accessId] ?: "",
            accessClientSecret = secrets.read(SecretStore.ACCESS_SECRET),
            accessEmail = prefs[Keys.accessEmail] ?: "",
            accessToken = secrets.read(SecretStore.ACCESS_TOKEN),
            gateway = prefs[Keys.gateway] ?: false,
            routeBlock = prefs[Keys.routeBlock] ?: "",
            routeDirect = prefs[Keys.routeDirect] ?: "",
            killSwitch = prefs[Keys.killSwitch] ?: false,
            strictKillSwitch = prefs[Keys.strictKillSwitch] ?: false,
            ipv6LeakProtection = prefs[Keys.ipv6Leak] ?: true,
            smartReconnect = prefs[Keys.smartReconnect] ?: true,
            reconnectRetryLimit = prefs[Keys.reconnectRetryLimit] ?: 5,
            fragmentSize = prefs[Keys.fragmentSize] ?: "",
            fragmentDelay = prefs[Keys.fragmentDelay] ?: "",
            noDataCheck = prefs[Keys.noDataCheck] ?: false,
            tlsGroups = prefs[Keys.tlsGroups] ?: "",
            validateSecs = prefs[Keys.validateSecs] ?: 0,
            reconnectSecs = prefs[Keys.reconnectSecs] ?: 0,
            noProfileRetry = prefs[Keys.noProfileRetry] ?: false,
            coreLogLevel = prefs[Keys.coreLogLevel]
                ?.let { runCatching { CoreLogLevel.valueOf(it) }.getOrNull() } ?: CoreLogLevel.WARN,
            blockedApps = prefs[Keys.blockedApps]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            chain = ChainMode.fromStored(prefs[Keys.chain]) ?: d.chain,
            psiphonRegion = prefs[Keys.psiphonRegion] ?: "",
            psiphonConfig = prefs[Keys.psiphonConfig] ?: "",
            torExitCountry = prefs[Keys.torExitCountry] ?: "",
            torStrictNodes = prefs[Keys.torStrictNodes] ?: false,
            // Not valueOf(): both enums accept the aliases a hand-written or
            // imported config can carry ("manual", "meek-azure", ...), and a
            // value neither understands keeps the default instead of throwing
            // the whole profile away.
            torBridgeMode = TorBridgeMode.fromStored(prefs[Keys.torBridgeMode]) ?: d.torBridgeMode,
            torBridgeTransport = BridgeTransport.fromStored(prefs[Keys.torBridgeTransport])
                ?: d.torBridgeTransport,
            torBridgeLines = prefs[Keys.torBridgeLines] ?: "",
        )
    }

    suspend fun save(profile: ConnectionProfile) {
        context.dataStore.edit { prefs ->
            prefs[Keys.protocol] = profile.protocol.name
            prefs[Keys.scan] = profile.scanMode.name
            prefs[Keys.ip] = profile.ipVersion.name
            prefs[Keys.quick] = profile.quickReconnect
            prefs[Keys.h2] = profile.masqueHttp2
            prefs[Keys.share] = profile.lanShare
            prefs[Keys.noize] = profile.noize.name
            // From here on this profile's obfuscation value is taken literally,
            // OFF included: it was written by a build that honours it.
            prefs[Keys.noizeChosen] = true
            prefs[Keys.endpoint] = profile.endpointMode.name
            prefs[Keys.peer] = profile.manualPeer
            prefs[Keys.range] = profile.manualRange
            prefs[Keys.keepalive] = profile.keepalive
            prefs[Keys.fragment] = profile.fragment
            prefs[Keys.ech] = profile.ech
            prefs[Keys.mtu] = profile.mtu
            prefs[Keys.proxy] = profile.proxyMode
            prefs[Keys.split] = profile.splitMode.name
            prefs[Keys.splitApps] = profile.splitApps.joinToString(",")
            prefs[Keys.dns] = profile.dnsServers
            prefs[Keys.team] = profile.team
            prefs[Keys.teamAuth] = profile.teamAuth.name
            prefs[Keys.accessId] = profile.accessClientId
            prefs[Keys.accessEmail] = profile.accessEmail
            prefs[Keys.gateway] = profile.gateway
            prefs[Keys.routeBlock] = profile.routeBlock
            prefs[Keys.routeDirect] = profile.routeDirect
            prefs[Keys.killSwitch] = profile.killSwitch
            prefs[Keys.strictKillSwitch] = profile.strictKillSwitch
            prefs[Keys.ipv6Leak] = profile.ipv6LeakProtection
            prefs[Keys.smartReconnect] = profile.smartReconnect
            prefs[Keys.reconnectRetryLimit] = profile.reconnectRetryLimit
            prefs[Keys.fragmentSize] = profile.fragmentSize
            prefs[Keys.fragmentDelay] = profile.fragmentDelay
            prefs[Keys.noDataCheck] = profile.noDataCheck
            prefs[Keys.tlsGroups] = profile.tlsGroups
            prefs[Keys.validateSecs] = profile.validateSecs
            prefs[Keys.reconnectSecs] = profile.reconnectSecs
            prefs[Keys.noProfileRetry] = profile.noProfileRetry
            prefs[Keys.coreLogLevel] = profile.coreLogLevel.name
            prefs[Keys.blockedApps] = profile.blockedApps.joinToString(",")
            prefs[Keys.chain] = profile.chain.name
            prefs[Keys.psiphonRegion] = profile.psiphonRegion
            prefs[Keys.psiphonConfig] = profile.psiphonConfig
            prefs[Keys.torExitCountry] = profile.torExitCountry
            prefs[Keys.torStrictNodes] = profile.torStrictNodes
            prefs[Keys.torBridgeMode] = profile.torBridgeMode.name
            prefs[Keys.torBridgeTransport] = profile.torBridgeTransport.name
            prefs[Keys.torBridgeLines] = profile.torBridgeLines
        }
        // Secrets go to the Keystore-sealed store, never to the prefs file.
        // Writing a blank value clears the entry, so "Reset settings" (which
        // saves a default profile) wipes them through this same path.
        secrets.write(SecretStore.ACCESS_SECRET, profile.accessClientSecret)
        secrets.write(SecretStore.ACCESS_TOKEN, profile.accessToken)
    }
}
