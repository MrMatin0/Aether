package studio.cluvex.aether.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.core.BridgeLine
import studio.cluvex.aether.core.SessionTracker
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.SplitMode

internal const val HUB_DOT = " \u00B7 "

/** One row of the hub: what it is called, what it is set to, what it does. */
@Immutable
internal data class SettingsEntry(
    val page: SettingsPage,
    val title: String,
    val state: String?,
    val note: String,
) {
    val tags: List<String> get() = page.tags
}

/**
 * The live summary of every destination. Same logic as before the redesign
 * (moved, not rewritten), except the preset count now comes from the caller,
 * which already collects the store for the Recommended strip.
 */
@Composable
internal fun settingsEntries(profile: ConnectionProfile, presetCount: Int): List<SettingsEntry> {
    val context = LocalContext.current
    val prefs by AppPrefs.state.collectAsStateWithLifecycle()
    val sessions by SessionTracker.sessions.collectAsStateWithLifecycle()
    val language = AppLocale.stored(context)
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: BuildConfig.VERSION_NAME
    }
    return listOf(
        entryOf(SettingsPage.CONNECTION, connectionStateLine(profile)),
        entryOf(SettingsPage.CHAIN, chainLabel(profile.chain)),
        entryOf(SettingsPage.BRIDGES, bridgeStateLine(profile)),
        entryOf(SettingsPage.TRANSPORT, transportStateLine(profile)),
        entryOf(SettingsPage.ROUTING, routingStateLine(profile)),
        entryOf(
            SettingsPage.SHARING,
            stringResource(if (profile.lanShare) R.string.hub_state_on else R.string.hub_state_off),
        ),
        entryOf(SettingsPage.SECURITY, securityStateLine(profile)),
        entryOf(SettingsPage.ORGANIZATION, organizationStateLine(profile)),
        entryOf(
            SettingsPage.APPEARANCE,
            listOf(themeModeLabel(prefs.themeMode), appLanguageLabel(language)).joinToString(HUB_DOT),
        ),
        entryOf(SettingsPage.AUTOMATION, automationStateLine(prefs.autoConnectOnLaunch, prefs.autoConnectOnBoot)),
        entryOf(
            SettingsPage.HISTORY,
            if (prefs.keepHistory) stringResource(R.string.history_count, sessions.size)
            else stringResource(R.string.history_off),
        ),
        entryOf(
            SettingsPage.SETUPS,
            if (presetCount == 0) stringResource(R.string.presets_empty)
            else stringResource(R.string.hub_state_presets, presetCount),
        ),
        entryOf(SettingsPage.TUNING, tuningStateLine(profile)),
        entryOf(SettingsPage.RESET, null),
        entryOf(SettingsPage.ABOUT, stringResource(R.string.hub_state_version, version, BuildConfig.CORE_VERSION)),
    )
}

@Composable
private fun entryOf(page: SettingsPage, state: String?): SettingsEntry =
    SettingsEntry(page, settingsPageTitle(page), state, settingsPageSubtitle(page))

@Composable
internal fun connectionStateLine(profile: ConnectionProfile): String = listOf(
    protocolLabel(profile.protocol),
    when (profile.endpointMode) {
        EndpointMode.AUTO -> scanLabel(profile.scanMode)
        EndpointMode.MANUAL_PEER -> stringResource(R.string.hub_state_pinned)
        EndpointMode.MANUAL_RANGE -> endpointLabel(EndpointMode.MANUAL_RANGE)
    },
    ipLabel(profile.ipVersion),
).joinToString(HUB_DOT)

@Composable
private fun bridgeStateLine(profile: ConnectionProfile): String {
    if (!profile.torBridgeMode.isOn) return stringResource(R.string.hub_state_off)
    val count = BridgeLine.parseAll(profile.torBridgeLines).size
    if (count == 0) return stringResource(R.string.bridges_state_none)
    return listOf(
        bridgeTransportLabel(profile.torBridgeTransport),
        stringResource(R.string.bridges_state_count, count),
    ).joinToString(HUB_DOT)
}

@Composable
private fun transportStateLine(profile: ConnectionProfile): String {
    val parts = listOfNotNull(
        if (profile.noize != Noize.OFF) noizeLabel(profile.noize) else null,
        if (profile.fragment) stringResource(R.string.hub_state_fragment) else null,
        if (profile.ech) stringResource(R.string.hub_state_ech) else null,
        if (profile.mtu != ConnectionProfile.DEFAULT_MTU) stringResource(R.string.hub_state_mtu, profile.mtu) else null,
        if (profile.keepalive > 0) stringResource(R.string.hub_state_keepalive, profile.keepalive) else null,
        if (profile.dnsServers.isNotBlank()) stringResource(R.string.hub_state_dns) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_default) else parts.joinToString(HUB_DOT)
}

@Composable
private fun routingStateLine(profile: ConnectionProfile): String {
    val split = profile.splitMode != SplitMode.OFF
    val parts = listOfNotNull(
        if (split) splitLabel(profile.splitMode) else null,
        if (split) stringResource(R.string.hub_state_apps, profile.splitApps.size) else null,
        if (profile.blockedApps.isNotEmpty()) stringResource(R.string.blocked_select_apps, profile.blockedApps.size) else null,
        if (profile.proxyMode) stringResource(R.string.hub_state_proxy) else null,
        if (profile.routeBlock.isNotBlank() || profile.routeDirect.isNotBlank()) stringResource(R.string.hub_state_rules) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_all_traffic) else parts.joinToString(HUB_DOT)
}

@Composable
private fun securityStateLine(profile: ConnectionProfile): String {
    val parts = listOfNotNull(
        when {
            profile.strictKillSwitch -> stringResource(R.string.hub_state_kill_strict)
            profile.killSwitch -> stringResource(R.string.hub_state_kill)
            else -> null
        },
        if (profile.ipv6LeakProtection) stringResource(R.string.hub_state_ipv6) else null,
        if (profile.smartReconnect) stringResource(R.string.hub_state_retry, profile.reconnectRetryLimit) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_unprotected) else parts.joinToString(HUB_DOT)
}

@Composable
private fun organizationStateLine(profile: ConnectionProfile): String = if (!profile.hasTeam) {
    stringResource(R.string.hub_state_off)
} else {
    listOfNotNull(
        stringResource(R.string.hub_state_team, profile.team.trim()),
        teamAuthLabel(profile.teamAuth),
        if (profile.gateway) stringResource(R.string.hub_state_gateway) else null,
    ).joinToString(HUB_DOT)
}

@Composable
private fun tuningStateLine(profile: ConnectionProfile): String {
    val parts = listOfNotNull(
        if (profile.coreLogLevel != CoreLogLevel.WARN) stringResource(R.string.hub_state_log, profile.coreLogLevel.raw) else null,
        profile.tlsGroups.trim().takeIf { it.isNotEmpty() },
        if (profile.validateSecs > 0 || profile.reconnectSecs > 0) stringResource(R.string.hub_state_timers) else null,
        if (profile.noDataCheck) stringResource(R.string.hub_state_no_probe) else null,
        if (profile.noProfileRetry) stringResource(R.string.hub_state_no_retry) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_default) else parts.joinToString(HUB_DOT)
}

@Composable
private fun automationStateLine(onLaunch: Boolean, onBoot: Boolean): String {
    val parts = listOfNotNull(
        if (onLaunch) stringResource(R.string.hub_state_launch) else null,
        if (onBoot) stringResource(R.string.hub_state_boot) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_manual) else parts.joinToString(HUB_DOT)
}
