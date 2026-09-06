package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.engine.*

internal enum class EnginePage(val title: Int) {
    CONNECTION(R.string.section_connection), SECURITY(R.string.section_security),
    ROUTING(R.string.section_routing), TRANSPORT(R.string.section_transport),
    ORGANIZATION(R.string.section_zerotrust), TUNING(R.string.section_engine_tuning),
}
private enum class AppPicker { SPLIT, BLOCKED }

/** All six categories are visible. Switching categories never writes a profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvancedPanel(profile: ConnectionProfile, onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") startExpanded: Boolean = false) {
    var page by rememberSaveable { mutableStateOf(EnginePage.CONNECTION) }
    var picker by rememberSaveable { mutableStateOf<AppPicker?>(null) }
    val latest by rememberUpdatedState(profile)
    val latestEnabled by rememberUpdatedState(enabled)
    val focus = LocalFocusManager.current
    val edit: ProfileEdit = remember(onProfileChange) { { change -> if (latestEnabled) onProfileChange(latest.change()) } }
    LaunchedEffect(enabled) { if (!enabled) { picker = null; focus.clearFocus(force = true) } }
    Column(modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EnginePage.entries.forEach { destination ->
                FilterChip(selected = page == destination, onClick = { focus.clearFocus(); page = destination },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Tab },
                    label = { Text(stringResource(destination.title)) }, leadingIcon = {
                        Icon(when (destination) {
                            EnginePage.CONNECTION -> Icons.Rounded.Cable
                            EnginePage.SECURITY -> Icons.Rounded.Shield
                            EnginePage.ROUTING -> Icons.Rounded.Route
                            EnginePage.TRANSPORT -> Icons.Rounded.SwapVert
                            EnginePage.ORGANIZATION -> Icons.Rounded.Business
                            EnginePage.TUNING -> Icons.Rounded.Tune
                        }, null, Modifier.size(18.dp))
                    })
            }
        }
        Hint(stringResource(R.string.settings_expert_note))
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(24.dp))
        key(page) {
            when (page) {
                EnginePage.CONNECTION -> ConnectionSection(protocol = profile.protocol, scanMode = profile.scanMode,
                    endpointMode = profile.endpointMode, manualPeer = profile.manualPeer, manualRange = profile.manualRange,
                    ipVersion = profile.ipVersion, enabled = enabled, edit = edit)
                EnginePage.SECURITY -> {
                    SafetySection(quickReconnect = profile.quickReconnect, killSwitch = profile.killSwitch,
                        strictKillSwitch = profile.strictKillSwitch, enabled = enabled, edit = edit)
                    Spacer(Modifier.height(32.dp))
                    ResilienceSection(ipv6LeakProtection = profile.ipv6LeakProtection, smartReconnect = profile.smartReconnect,
                        reconnectRetryLimit = profile.reconnectRetryLimit, enabled = enabled, edit = edit)
                }
                EnginePage.ROUTING -> {
                    RoutingSection(proxyMode = profile.proxyMode, splitMode = profile.splitMode, splitAppCount = profile.splitApps.size,
                        blockedAppCount = profile.blockedApps.size, enabled = enabled, edit = edit,
                        onPickSplitApps = { picker = AppPicker.SPLIT }, onPickBlockedApps = { picker = AppPicker.BLOCKED })
                    Spacer(Modifier.height(32.dp))
                    RoutesSection(routeBlock = profile.routeBlock, routeDirect = profile.routeDirect, enabled = enabled, edit = edit)
                }
                EnginePage.TRANSPORT -> TransportSection(noize = profile.noize, keepalive = profile.keepalive, mtu = profile.mtu,
                    fragment = profile.fragment, fragmentSize = profile.fragmentSize, fragmentDelay = profile.fragmentDelay,
                    ech = profile.ech, masqueHttp2 = profile.masqueHttp2, dnsServers = profile.dnsServers, enabled = enabled, edit = edit)
                EnginePage.ORGANIZATION -> ZeroTrustSection(teamAuth = profile.teamAuth, team = profile.team,
                    accessClientId = profile.accessClientId, accessClientSecret = profile.accessClientSecret,
                    accessEmail = profile.accessEmail, accessToken = profile.accessToken, gateway = profile.gateway, enabled = enabled, edit = edit)
                EnginePage.TUNING -> EngineTuningSection(tlsGroups = profile.tlsGroups, validateSecs = profile.validateSecs,
                    reconnectSecs = profile.reconnectSecs, noDataCheck = profile.noDataCheck, noProfileRetry = profile.noProfileRetry,
                    coreLogLevel = profile.coreLogLevel, enabled = enabled, edit = edit)
            }
        }
        Spacer(Modifier.height(40.dp))
        ResetSection(enabled = enabled, edit = edit)
    }
    picker?.let { target ->
        AppPickerDialog(selected = if (target == AppPicker.SPLIT) profile.splitApps else profile.blockedApps,
            onDismiss = { picker = null }, onConfirm = { apps ->
                if (enabled) edit { if (target == AppPicker.SPLIT) copy(splitApps = apps) else copy(blockedApps = apps) }
                picker = null
            })
    }
}
