package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.engine.*

/** Stable names are saved across rotation and destination changes. */
internal enum class EnginePage(val title: Int) {
    CONNECTION(R.string.section_connection),
    SECURITY(R.string.section_security),
    ROUTING(R.string.section_routing),
    TRANSPORT(R.string.section_transport),
    ORGANIZATION(R.string.section_zerotrust),
    TUNING(R.string.section_engine_tuning),
}
private enum class AppPicker { SPLIT, BLOCKED }

/** Focused, addressable sections instead of a forty-control scrolling form.
 * Profile edits, secret storage, validation and the native engine are unchanged.
 */
@Composable
fun AdvancedPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") startExpanded: Boolean = false,
) {
    var page by rememberSaveable { mutableStateOf(EnginePage.CONNECTION) }
    var picker by rememberSaveable { mutableStateOf<AppPicker?>(null) }
    val latest by rememberUpdatedState(profile)
    val edit: ProfileEdit = remember(onProfileChange) {
        { change -> onProfileChange(latest.change()) }
    }
    // A connection can start from the system tile while the picker is open.
    LaunchedEffect(enabled) { if (!enabled) picker = null }
    Column(modifier.fillMaxWidth()) {
        FieldLabel(stringResource(R.string.passage_details))
        DropdownSelector(
            options = EnginePage.entries, selected = page, onSelect = { page = it },
            label = { stringResource(it.title) },
        )
        Hint(stringResource(R.string.settings_expert_note))
        Spacer(Modifier.height(24.dp))
        key(page) {
            when (page) {
                EnginePage.CONNECTION -> ConnectionSection(
                    protocol = profile.protocol, scanMode = profile.scanMode,
                    endpointMode = profile.endpointMode, manualPeer = profile.manualPeer,
                    manualRange = profile.manualRange, ipVersion = profile.ipVersion,
                    enabled = enabled, edit = edit,
                )
                EnginePage.SECURITY -> {
                    SafetySection(quickReconnect = profile.quickReconnect, killSwitch = profile.killSwitch,
                        strictKillSwitch = profile.strictKillSwitch, enabled = enabled, edit = edit)
                    Spacer(Modifier.height(24.dp))
                    ResilienceSection(ipv6LeakProtection = profile.ipv6LeakProtection,
                        smartReconnect = profile.smartReconnect, reconnectRetryLimit = profile.reconnectRetryLimit,
                        enabled = enabled, edit = edit)
                }
                EnginePage.ROUTING -> {
                    RoutingSection(proxyMode = profile.proxyMode, splitMode = profile.splitMode,
                        splitAppCount = profile.splitApps.size, blockedAppCount = profile.blockedApps.size,
                        enabled = enabled, edit = edit,
                        onPickSplitApps = { picker = AppPicker.SPLIT }, onPickBlockedApps = { picker = AppPicker.BLOCKED })
                    Spacer(Modifier.height(24.dp))
                    RoutesSection(routeBlock = profile.routeBlock, routeDirect = profile.routeDirect, enabled = enabled, edit = edit)
                }
                EnginePage.TRANSPORT -> TransportSection(
                    noize = profile.noize, keepalive = profile.keepalive, mtu = profile.mtu,
                    fragment = profile.fragment, fragmentSize = profile.fragmentSize, fragmentDelay = profile.fragmentDelay,
                    ech = profile.ech, masqueHttp2 = profile.masqueHttp2, dnsServers = profile.dnsServers,
                    enabled = enabled, edit = edit,
                )
                EnginePage.ORGANIZATION -> ZeroTrustSection(
                    teamAuth = profile.teamAuth, team = profile.team, accessClientId = profile.accessClientId,
                    accessClientSecret = profile.accessClientSecret, accessEmail = profile.accessEmail,
                    accessToken = profile.accessToken, gateway = profile.gateway, enabled = enabled, edit = edit,
                )
                EnginePage.TUNING -> EngineTuningSection(
                    tlsGroups = profile.tlsGroups, validateSecs = profile.validateSecs,
                    reconnectSecs = profile.reconnectSecs, noDataCheck = profile.noDataCheck,
                    noProfileRetry = profile.noProfileRetry, coreLogLevel = profile.coreLogLevel,
                    enabled = enabled, edit = edit,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        ResetSection(enabled = enabled, edit = edit)
    }
    picker?.let { target ->
        AppPickerDialog(
            selected = if (target == AppPicker.SPLIT) profile.splitApps else profile.blockedApps,
            onDismiss = { picker = null },
            onConfirm = { apps ->
                if (enabled) edit {
                    if (target == AppPicker.SPLIT) copy(splitApps = apps) else copy(blockedApps = apps)
                }
                picker = null
            },
        )
    }
}
