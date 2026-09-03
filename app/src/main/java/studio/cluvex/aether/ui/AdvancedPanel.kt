package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.ScanModeSelector
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.components.ValueRow
import studio.cluvex.aether.ui.theme.LocalAetherAccents

private enum class Depth { BASIC, FULL }

/**
 * The engine settings page.
 *
 * ROOT CAUSE THIS FIXES (usability): the original panel presented roughly forty
 * controls as one flat list inside a collapsed card. Protocol - which decides
 * whether you connect at all - sat at the same visual level as the TLS curve
 * list, so people changed MTU or fragment sizes at random, could not get back to
 * a working configuration, and reported the tunnel as broken. Nothing said which
 * controls matter, and nothing said the defaults are usually right.
 *
 * The Essentials / Everything split already fixed the WHAT. What it did not fix
 * was the SHAPE: even in Essentials it was one uninterrupted ribbon of fields
 * with hairline headings, so the boundary between "things that decide whether you
 * connect" and "things that decide what goes through the tunnel" was a 1px rule.
 *
 * So every group is now a card with a badge and a name. Same controls, same
 * order, same profile writes - but a scroll through it has landmarks, and the
 * card is the unit a screenshot can be cropped to when someone asks for help.
 *
 * The scan mode is the one control here that is no longer a dropdown: it is a
 * three-option radio group that states what each mode does and how long it
 * takes (see [ScanModeSelector]). It decides how long the user sits on the
 * connecting screen, which is not something a collapsed one-word row can say.
 *
 * The depth choice is [rememberSaveable] now: it used to reset to Essentials on
 * every rotation and every return to the page, which is maddening when you are
 * three fields deep in Everything.
 *
 * Every field still writes to exactly the same ConnectionProfile property
 * through the same synchronous onProfileChange path, so the scrambled-input fix
 * and the DataStore write behaviour are untouched. Technical fields remain
 * LtrOutlinedTextField - never a bare OutlinedTextField.
 *
 * [startExpanded] is kept for source compatibility with older callers; the
 * surface is a page now, so it is always open.
 */
@Composable
fun AdvancedPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") startExpanded: Boolean = false,
) {
    val accents = LocalAetherAccents.current
    var depth by rememberSaveable { mutableStateOf(Depth.BASIC) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showBlockedPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val full = depth == Depth.FULL
    val brand = accents.brand

    Column(modifier = modifier.fillMaxWidth()) {
        // ---------------------------------------------------------- depth ----

        AetherCard {
            SegmentedSelector(
                options = listOf(Depth.BASIC, Depth.FULL),
                selected = depth,
                onSelect = { depth = it },
                label = {
                    when (it) {
                        Depth.BASIC -> stringResource(R.string.settings_mode_basic)
                        Depth.FULL -> stringResource(R.string.settings_mode_full)
                    }
                },
            )
            Hint(stringResource(R.string.settings_expert_note))
        }

        // ----------------------------------------------------- connection ----

        Spacer(Modifier.height(16.dp))
        AetherCard {
            CardHeader(
                title = stringResource(R.string.section_connection),
                subtitle = stringResource(R.string.section_connection_note),
                icon = Icons.Rounded.Shield,
                tint = brand,
            )
            Spacer(Modifier.height(16.dp))

            FieldLabel(stringResource(R.string.protocol))
            SegmentedSelector(
                options = Protocol.entries,
                selected = profile.protocol,
                onSelect = { onProfileChange(profile.copy(protocol = it)) },
                label = { protocolLabel(it) },
                enabled = enabled,
            )

            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.scan_mode))
            ScanModeSelector(
                selected = profile.scanMode,
                onSelect = { onProfileChange(profile.copy(scanMode = it)) },
                enabled = enabled,
            )
            Hint(stringResource(R.string.scan_mode_hint))

            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.endpoint_mode))
            SegmentedSelector(
                options = EndpointMode.entries,
                selected = profile.endpointMode,
                onSelect = { onProfileChange(profile.copy(endpointMode = it)) },
                label = { endpointLabel(it) },
                enabled = enabled,
            )
            if (profile.endpointMode == EndpointMode.MANUAL_PEER) {
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = profile.manualPeer,
                    onValueChange = { onProfileChange(profile.copy(manualPeer = it)) },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.manual_peer_label)) },
                    placeholder = { Text(stringResource(R.string.manual_peer_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (profile.endpointMode == EndpointMode.MANUAL_RANGE) {
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = profile.manualRange,
                    onValueChange = { onProfileChange(profile.copy(manualRange = it)) },
                    enabled = enabled,
                    singleLine = false,
                    label = { Text(stringResource(R.string.manual_range_label)) },
                    placeholder = { Text(stringResource(R.string.manual_range_hint)) },
                    supportingText = { Text(stringResource(R.string.manual_range_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.ip_version))
            SegmentedSelector(
                options = IpVersion.entries,
                selected = profile.ipVersion,
                onSelect = { onProfileChange(profile.copy(ipVersion = it)) },
                label = { ipLabel(it) },
                enabled = enabled,
            )
        }

        // --------------------------------------------------------- safety ----

        Spacer(Modifier.height(16.dp))
        AetherCard {
            CardHeader(
                title = stringResource(R.string.section_safety),
                subtitle = stringResource(R.string.section_safety_note),
                icon = Icons.Rounded.Lock,
                tint = brand,
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = stringResource(R.string.quick_reconnect),
                description = stringResource(R.string.quick_reconnect_desc),
                checked = profile.quickReconnect,
                enabled = enabled,
                onChange = { onProfileChange(profile.copy(quickReconnect = it)) },
            )
            Hairline(alpha = 0.5f)
            SwitchRow(
                title = stringResource(R.string.kill_switch_title),
                description = stringResource(R.string.kill_switch_desc),
                checked = profile.killSwitch,
                enabled = enabled,
                onChange = { onProfileChange(profile.copy(killSwitch = it)) },
            )
            AnimatedVisibility(visible = profile.killSwitch) {
                Column {
                    Hairline(alpha = 0.5f)
                    SwitchRow(
                        title = stringResource(R.string.strict_kill_switch_title),
                        description = stringResource(R.string.strict_kill_switch_desc),
                        checked = profile.strictKillSwitch,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(strictKillSwitch = it)) },
                    )
                }
            }
        }

        // -------------------------------------------------------- routing ----

        Spacer(Modifier.height(16.dp))
        AetherCard {
            CardHeader(
                title = stringResource(R.string.section_routing),
                subtitle = stringResource(R.string.section_routing_note),
                icon = Icons.Rounded.Apps,
                tint = brand,
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = stringResource(R.string.proxy_mode_title),
                description = stringResource(R.string.proxy_mode_desc),
                checked = profile.proxyMode,
                enabled = enabled,
                onChange = { onProfileChange(profile.copy(proxyMode = it)) },
            )
            AnimatedVisibility(visible = profile.proxyMode) {
                Column {
                    Hint(stringResource(R.string.proxy_endpoints_hint))
                    Spacer(Modifier.height(6.dp))
                    ValueRow(
                        label = stringResource(R.string.proxy_socks_label),
                        value = "127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}",
                    )
                    ValueRow(
                        label = stringResource(R.string.proxy_http_label),
                        value = "127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.split_mode))
            SegmentedSelector(
                options = SplitMode.entries,
                selected = profile.splitMode,
                onSelect = { onProfileChange(profile.copy(splitMode = it)) },
                label = { splitLabel(it) },
                enabled = enabled,
            )
            AnimatedVisibility(visible = profile.splitMode != SplitMode.OFF) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    ActionPill(
                        label = stringResource(R.string.split_select_apps, profile.splitApps.size),
                        onClick = { showAppPicker = true },
                        icon = Icons.Rounded.Apps,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ActionPill(
                label = stringResource(R.string.blocked_select_apps, profile.blockedApps.size),
                onClick = { showBlockedPicker = true },
                icon = Icons.Rounded.Apps,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.blocked_apps_desc))
        }

        // ------------------------------------------------------ everything ---

        if (full) {
            Spacer(Modifier.height(16.dp))
            AetherCard {
                CardHeader(
                    title = stringResource(R.string.section_transport),
                    subtitle = stringResource(R.string.section_transport_note),
                    icon = Icons.Rounded.Tune,
                    tint = brand,
                )
                Spacer(Modifier.height(16.dp))

                FieldLabel(stringResource(R.string.noize_title))
                DropdownSelector(
                    options = Noize.entries,
                    selected = profile.noize,
                    onSelect = { onProfileChange(profile.copy(noize = it)) },
                    label = { noizeLabel(it) },
                    enabled = enabled,
                )
                Hint(stringResource(R.string.noize_desc))

                Spacer(Modifier.height(18.dp))
                FieldLabel(stringResource(R.string.keepalive_label))
                DropdownSelector(
                    options = ConnectionProfile.KEEPALIVE_PRESETS,
                    selected = profile.keepalive,
                    onSelect = { onProfileChange(profile.copy(keepalive = it)) },
                    label = { if (it == 0) stringResource(R.string.keepalive_default) else "$it" },
                    enabled = enabled,
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel(stringResource(R.string.mtu_label))
                DropdownSelector(
                    options = ConnectionProfile.MTU_PRESETS,
                    selected = profile.mtu,
                    onSelect = { onProfileChange(profile.copy(mtu = it)) },
                    label = { "$it" },
                    enabled = enabled,
                )
                Hint(stringResource(R.string.mtu_desc))

                Spacer(Modifier.height(14.dp))
                Hairline(alpha = 0.5f)
                SwitchRow(
                    title = stringResource(R.string.fragment_title),
                    description = stringResource(R.string.fragment_desc),
                    checked = profile.fragment,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(fragment = it)) },
                )
                Hairline(alpha = 0.5f)
                SwitchRow(
                    title = stringResource(R.string.ech_title),
                    description = stringResource(R.string.ech_desc),
                    checked = profile.ech,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(ech = it)) },
                )
                Hairline(alpha = 0.5f)
                SwitchRow(
                    title = stringResource(R.string.masque_http2),
                    description = stringResource(R.string.masque_http2_desc),
                    checked = profile.masqueHttp2,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(masqueHttp2 = it)) },
                )

                Spacer(Modifier.height(18.dp))
                LtrOutlinedTextField(
                    value = profile.dnsServers,
                    onValueChange = { onProfileChange(profile.copy(dnsServers = it)) },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.dns_label)) },
                    placeholder = { Text(stringResource(R.string.dns_hint)) },
                    supportingText = { Text(stringResource(R.string.dns_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            AetherCard {
                CardHeader(
                    title = stringResource(R.string.section_routes),
                    subtitle = stringResource(R.string.section_routes_note),
                    icon = Icons.Rounded.Public,
                    tint = brand,
                )
                Spacer(Modifier.height(16.dp))
                LtrOutlinedTextField(
                    value = profile.routeBlock,
                    onValueChange = { onProfileChange(profile.copy(routeBlock = it)) },
                    enabled = enabled,
                    singleLine = false,
                    label = { Text(stringResource(R.string.route_block_label)) },
                    placeholder = { Text(stringResource(R.string.route_block_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = profile.routeDirect,
                    onValueChange = { onProfileChange(profile.copy(routeDirect = it)) },
                    enabled = enabled,
                    singleLine = false,
                    label = { Text(stringResource(R.string.route_direct_label)) },
                    placeholder = { Text(stringResource(R.string.route_direct_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.routes_help))
            }

            Spacer(Modifier.height(16.dp))
            AetherCard {
                CardHeader(
                    title = stringResource(R.string.section_zerotrust),
                    subtitle = stringResource(R.string.section_zerotrust_note),
                    icon = Icons.Rounded.VpnKey,
                    tint = brand,
                )
                Spacer(Modifier.height(16.dp))

                FieldLabel(stringResource(R.string.team_auth_label))
                DropdownSelector(
                    options = TeamAuth.entries,
                    selected = profile.teamAuth,
                    onSelect = { onProfileChange(profile.copy(teamAuth = it)) },
                    label = { teamAuthLabel(it) },
                    enabled = enabled,
                )
                Hint(stringResource(R.string.team_auth_desc))

                if (profile.teamAuth != TeamAuth.OFF) {
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = profile.team,
                        onValueChange = { onProfileChange(profile.copy(team = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.team_label)) },
                        placeholder = { Text(stringResource(R.string.team_hint)) },
                        supportingText = { Text(stringResource(R.string.team_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    when (profile.teamAuth) {
                        TeamAuth.SERVICE_TOKEN -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessClientId,
                                onValueChange = {
                                    onProfileChange(profile.copy(accessClientId = it))
                                },
                                enabled = enabled,
                                singleLine = true,
                                label = { Text(stringResource(R.string.access_id_label)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            // Masked: an organization credential must not be
                            // readable over a shoulder or land in a screenshot.
                            LtrOutlinedTextField(
                                value = profile.accessClientSecret,
                                onValueChange = {
                                    onProfileChange(profile.copy(accessClientSecret = it))
                                },
                                enabled = enabled,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.access_secret_label)) },
                                supportingText = {
                                    Text(stringResource(R.string.access_secret_help))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TeamAuth.EMAIL -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessEmail,
                                onValueChange = {
                                    onProfileChange(profile.copy(accessEmail = it))
                                },
                                enabled = enabled,
                                singleLine = true,
                                label = { Text(stringResource(R.string.access_email_label)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TeamAuth.TOKEN -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessToken,
                                onValueChange = {
                                    onProfileChange(profile.copy(accessToken = it))
                                },
                                enabled = enabled,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.access_token_label)) },
                                supportingText = {
                                    Text(stringResource(R.string.access_secret_help))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TeamAuth.OFF -> Unit
                    }

                    Spacer(Modifier.height(10.dp))
                    Hairline(alpha = 0.5f)
                    SwitchRow(
                        title = stringResource(R.string.gateway_title),
                        description = stringResource(R.string.gateway_desc),
                        checked = profile.gateway,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(gateway = it)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            AetherCard {
                CardHeader(
                    title = stringResource(R.string.section_security),
                    subtitle = stringResource(R.string.section_security_note),
                    icon = Icons.Rounded.Refresh,
                    tint = brand,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    title = stringResource(R.string.ipv6_leak_title),
                    description = stringResource(R.string.ipv6_leak_desc),
                    checked = profile.ipv6LeakProtection,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(ipv6LeakProtection = it)) },
                )
                Hairline(alpha = 0.5f)
                SwitchRow(
                    title = stringResource(R.string.smart_reconnect_title),
                    description = stringResource(R.string.smart_reconnect_desc),
                    checked = profile.smartReconnect,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(smartReconnect = it)) },
                )
                AnimatedVisibility(visible = profile.smartReconnect) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        FieldLabel(stringResource(R.string.reconnect_limit_label))
                        DropdownSelector(
                            options = listOf(3, 5, 10, 15, 20),
                            selected = profile.reconnectRetryLimit,
                            onSelect = {
                                onProfileChange(profile.copy(reconnectRetryLimit = it))
                            },
                            label = { "$it" },
                            enabled = enabled,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            AetherCard {
                CardHeader(
                    title = stringResource(R.string.section_engine_tuning),
                    subtitle = stringResource(R.string.section_engine_tuning_note),
                    icon = Icons.Rounded.Speed,
                    tint = brand,
                )
                Spacer(Modifier.height(16.dp))

                AnimatedVisibility(visible = profile.fragment) {
                    Column {
                        LtrOutlinedTextField(
                            value = profile.fragmentSize,
                            onValueChange = { onProfileChange(profile.copy(fragmentSize = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.fragment_size_label)) },
                            placeholder = { Text(stringResource(R.string.fragment_size_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        LtrOutlinedTextField(
                            value = profile.fragmentDelay,
                            onValueChange = { onProfileChange(profile.copy(fragmentDelay = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.fragment_delay_label)) },
                            placeholder = { Text(stringResource(R.string.fragment_delay_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                LtrOutlinedTextField(
                    value = profile.tlsGroups,
                    onValueChange = { onProfileChange(profile.copy(tlsGroups = it)) },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.tls_groups_label)) },
                    placeholder = { Text(stringResource(R.string.tls_groups_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = if (profile.validateSecs == 0) "" else profile.validateSecs.toString(),
                    onValueChange = {
                        onProfileChange(
                            profile.copy(
                                validateSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
                            ),
                        )
                    },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.validate_secs_label)) },
                    placeholder = { Text(stringResource(R.string.secs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = if (profile.reconnectSecs == 0) "" else profile.reconnectSecs.toString(),
                    onValueChange = {
                        onProfileChange(
                            profile.copy(
                                reconnectSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
                            ),
                        )
                    },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.reconnect_secs_label)) },
                    placeholder = { Text(stringResource(R.string.secs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                Hairline(alpha = 0.5f)
                SwitchRow(
                    title = stringResource(R.string.no_data_check_title),
                    description = stringResource(R.string.no_data_check_desc),
                    checked = profile.noDataCheck,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(noDataCheck = it)) },
                )
                Hairline(alpha = 0.5f)
                SwitchRow(
                    title = stringResource(R.string.no_profile_retry_title),
                    description = stringResource(R.string.no_profile_retry_desc),
                    checked = profile.noProfileRetry,
                    enabled = enabled,
                    onChange = { onProfileChange(profile.copy(noProfileRetry = it)) },
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel(stringResource(R.string.core_log_level_label))
                DropdownSelector(
                    options = CoreLogLevel.entries,
                    selected = profile.coreLogLevel,
                    onSelect = { onProfileChange(profile.copy(coreLogLevel = it)) },
                    label = { it.name },
                    enabled = enabled,
                )
            }
        }

        // --------------------------------------------------------- reset ----
        // Always reachable, in both depths: the way out of a broken
        // configuration must never be hidden behind an expert toggle.

        Spacer(Modifier.height(16.dp))
        AetherCard(tone = MaterialTheme.colorScheme.error) {
            CardHeader(
                title = stringResource(R.string.section_reset),
                subtitle = stringResource(R.string.section_reset_note),
                icon = Icons.Rounded.RestartAlt,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(14.dp))
            ActionPill(
                label = stringResource(R.string.reset_settings),
                onClick = {
                    onProfileChange(ConnectionProfile())
                    Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
                },
                icon = Icons.Rounded.RestartAlt,
                enabled = enabled,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = profile.splitApps,
            onDismiss = { showAppPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(splitApps = it))
                showAppPicker = false
            },
        )
    }

    if (showBlockedPicker) {
        AppPickerDialog(
            selected = profile.blockedApps,
            onDismiss = { showBlockedPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(blockedApps = it))
                showBlockedPicker = false
            },
        )
    }
}
