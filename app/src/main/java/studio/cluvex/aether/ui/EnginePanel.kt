package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.engine.*

private enum class AppPicker { SPLIT, BLOCKED }

/**
 * Renders ONE engine category, because each of them is now its own
 * destination.
 *
 * This replaces AdvancedPanel, which owned a row of seven filter chips and
 * therefore owned navigation as well as editing. Two consequences of that are
 * gone with it: the chip row wrapped onto three lines at normal font scale, and
 * every category shared one scroll position, so returning to "Connection"
 * landed you wherever "Tuning" had been left.
 *
 * The [ProfileEdit] closure still rebases each change on the LATEST profile
 * rather than the one captured at composition, and still refuses to write while
 * the tunnel is up.
 */
@Composable
internal fun EngineDestination(
    page: SettingsPage,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<AppPicker?>(null) }
    val latest by rememberUpdatedState(profile)
    val latestEnabled by rememberUpdatedState(enabled)
    val focus = LocalFocusManager.current
    val edit: ProfileEdit = remember(onProfileChange) {
        { change -> if (latestEnabled) onProfileChange(latest.change()) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            picker = null
            focus.clearFocus(force = true)
        }
    }
    Column(modifier.fillMaxWidth()) {
        when (page) {
            SettingsPage.CONNECTION -> ConnectionSection(
                protocol = profile.protocol, scanMode = profile.scanMode,
                endpointMode = profile.endpointMode, manualPeer = profile.manualPeer,
                manualRange = profile.manualRange, ipVersion = profile.ipVersion,
                enabled = enabled, edit = edit,
            )
            // psiphonConfig is deliberately NOT passed: the Psiphon core ships
            // its own client config, so there is no field to fill.
            SettingsPage.CHAIN -> ChainSection(
                chain = profile.chain, psiphonRegion = profile.psiphonRegion,
                torExitCountry = profile.torExitCountry, torStrictNodes = profile.torStrictNodes,
                enabled = enabled, edit = edit,
            )
            // The chain is passed so the page can say when bridges are
            // configured but nothing in the current chain will use them.
            SettingsPage.BRIDGES -> BridgeSection(
                bridgeMode = profile.torBridgeMode, transport = profile.torBridgeTransport,
                bridgeLines = profile.torBridgeLines, chain = profile.chain,
                enabled = enabled, edit = edit,
            )
            SettingsPage.TRANSPORT -> {
                TransportSection(
                    noize = profile.noize, keepalive = profile.keepalive, mtu = profile.mtu,
                    fragment = profile.fragment, fragmentSize = profile.fragmentSize,
                    fragmentDelay = profile.fragmentDelay, ech = profile.ech,
                    masqueHttp2 = profile.masqueHttp2, dnsServers = profile.dnsServers,
                    enabled = enabled, edit = edit,
                )
                Hint(stringResource(R.string.settings_expert_note))
            }
            SettingsPage.ROUTING -> {
                RoutingSection(
                    proxyMode = profile.proxyMode, splitMode = profile.splitMode,
                    splitAppCount = profile.splitApps.size, blockedAppCount = profile.blockedApps.size,
                    enabled = enabled, edit = edit,
                    onPickSplitApps = { picker = AppPicker.SPLIT },
                    onPickBlockedApps = { picker = AppPicker.BLOCKED },
                )
                Spacer(Modifier.height(32.dp))
                RoutesSection(
                    routeBlock = profile.routeBlock, routeDirect = profile.routeDirect,
                    enabled = enabled, edit = edit,
                )
            }
            SettingsPage.SECURITY -> {
                SafetySection(
                    quickReconnect = profile.quickReconnect, killSwitch = profile.killSwitch,
                    strictKillSwitch = profile.strictKillSwitch, enabled = enabled, edit = edit,
                )
                Spacer(Modifier.height(32.dp))
                ResilienceSection(
                    ipv6LeakProtection = profile.ipv6LeakProtection, smartReconnect = profile.smartReconnect,
                    reconnectRetryLimit = profile.reconnectRetryLimit, enabled = enabled, edit = edit,
                )
            }
            SettingsPage.ORGANIZATION -> ZeroTrustSection(
                teamAuth = profile.teamAuth, team = profile.team,
                accessClientId = profile.accessClientId, accessClientSecret = profile.accessClientSecret,
                accessEmail = profile.accessEmail, accessToken = profile.accessToken,
                gateway = profile.gateway, enabled = enabled, edit = edit,
            )
            SettingsPage.TUNING -> {
                EngineTuningSection(
                    tlsGroups = profile.tlsGroups, validateSecs = profile.validateSecs,
                    reconnectSecs = profile.reconnectSecs, noDataCheck = profile.noDataCheck,
                    noProfileRetry = profile.noProfileRetry, coreLogLevel = profile.coreLogLevel,
                    enabled = enabled, edit = edit,
                )
                Hint(stringResource(R.string.settings_expert_note))
            }
            SettingsPage.RESET -> ResetSection(enabled = enabled, edit = edit)
            // Destinations that do not touch the engine render their own panel.
            SettingsPage.SHARING, SettingsPage.APPEARANCE, SettingsPage.AUTOMATION,
            SettingsPage.HISTORY, SettingsPage.SETUPS, SettingsPage.ABOUT -> Unit
        }
    }
    picker?.let { target ->
        AppPickerDialog(
            selected = if (target == AppPicker.SPLIT) profile.splitApps else profile.blockedApps,
            onDismiss = { picker = null },
            onConfirm = { apps ->
                if (enabled) {
                    edit { if (target == AppPicker.SPLIT) copy(splitApps = apps) else copy(blockedApps = apps) }
                }
                picker = null
            },
        )
    }
}
