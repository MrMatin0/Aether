package studio.cluvex.aether.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.BridgeCatalog
import studio.cluvex.aether.core.BridgeLine
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.PluggableTransports
import studio.cluvex.aether.core.PsiphonCore
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.TorCore
import studio.cluvex.aether.core.XrayCore
import studio.cluvex.aether.core.XrayOutbound
import studio.cluvex.aether.data.BridgeStore
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreRoutes
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.PsiphonProtocol
import studio.cluvex.aether.model.SstpSource
import studio.cluvex.aether.model.TorBridgeMode
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.ScanModeSelector
import studio.cluvex.aether.ui.engine.BridgeChoiceRow
import studio.cluvex.aether.ui.engine.BridgeTransportPicker
import studio.cluvex.aether.ui.engine.ProfileEdit
import studio.cluvex.aether.ui.engine.XrayConfigEditor
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/*
 * THE CORE SHEETS: one ModalBottomSheet per core, opened from the core
 * selector (CoreSelector.kt).
 *
 * Every sheet has the same frame: the core's name as the headline and one
 * line of what it is, a scrollable body of sections (an AetherMetaLabel
 * header, then radio cards), a link to the Settings page that owns the rest,
 * and a Done button that stays put while the body scrolls. 20dp horizontal
 * padding throughout.
 *
 * NO SECOND EDITORS. Each body is assembled from the editor the Settings
 * pages already use: OptionCard + [protocolDescription] and ScanModeSelector
 * (Aether), the Psiphon protocol labels of Labels.kt (Psiphon),
 * BridgeChoiceRow / BridgeTransportPicker (Tor), SstpRelayHero /
 * SstpServerFields / VpnGateBrowserDialog (SSTP) and XrayConfigEditor (Xray).
 * What only Settings has (CDN edges, bridge requests, SSTP sign-in...) stays
 * there, one tap away.
 *
 * Choices apply immediately: there is nothing to "save", the next connect
 * reads the profile. Every write goes through one [ProfileEdit] that is
 * refused once the profile locks, and ConnectionHome closes the sheet then.
 *
 * A core this APK does not ship gets a sheet that names the missing file
 * and nothing to edit: offering options for a core that cannot start is the
 * defect the Chain page already refuses to have.
 */

/** Horizontal padding of every sheet, both sides. */
private val SHEET_GUTTER = 20.dp

/** Between two sections of a sheet. */
private val SECTION_GAP = 24.dp

/** Between two cells of the exit-country grid. */
private val GRID_GAP = 8.dp

/** Narrowest a country cell gets before the grid drops a column. */
private val REGION_CELL_MIN = 96.dp

/**
 * The sheet for [core]. [xrayReady] gates the "via Xray" underlays;
 * [onXraySaved] hears every save the Xray sheet makes.
 *
 * ModalBottomSheet caps its own width on tablets and in landscape and the
 * body scrolls, so one layout covers a 320dp split screen and a 13in tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoreSheet(
    core: Hop,
    profile: ConnectionProfile,
    editable: Boolean,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
    onXraySaved: (XrayOutbound?) -> Unit,
    onProfileChange: (ConnectionProfile) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(profile)
    val canEdit by rememberUpdatedState(editable)

    // Every write goes through here: refused once locked, and a no-op choice
    // (tapping the selected card) does not rewrite the profile.
    val edit: ProfileEdit = { change ->
        if (canEdit) {
            val next = latest.change()
            if (next != latest) onProfileChange(next)
        }
    }
    val closeThen: (() -> Unit) -> Unit = { then ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    val page = coreSettingsPage(core)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = AetherRadius.Sheet, topEnd = AetherRadius.Sheet),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_GUTTER),
        ) {
            SheetHeader(core)
            Spacer(Modifier.height(16.dp))

            // The body scrolls; the header above and Done below stay put.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    !cores.has(core) -> MissingCoreBody(core)
                    core == Hop.AETHER -> AetherSheetBody(profile, editable, cores, xrayReady, edit)
                    core == Hop.PSIPHON -> PsiphonSheetBody(profile, editable, cores, xrayReady, edit)
                    core == Hop.TOR -> TorSheetBody(profile, editable, cores, xrayReady, edit)
                    core == Hop.SSTP -> SstpSheetBody(profile, editable, edit)
                    else -> XraySheetBody(editable, cores, onXraySaved)
                }

                SheetSectionLabel(stringResource(R.string.qc_advanced_label))
                Spacer(Modifier.height(8.dp))
                CoreSettingsLink(page, coreLinkLabel(core)) { closeThen { onOpenSettings(page) } }
                Spacer(Modifier.height(8.dp))
            }

            FilledTonalButton(
                onClick = { closeThen { } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.qc_done))
            }
        }
    }
}

// ----------------------------------------------------------------- frame --

@Composable
private fun SheetHeader(core: Hop) {
    val accents = LocalAetherAccents.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accents.brandWash),
            contentAlignment = Alignment.Center,
        ) {
            Icon(hopIcon(core), null, Modifier.size(20.dp), accents.brand)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                core.label,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(coreNote(core)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun coreNote(core: Hop): Int = when (core) {
    Hop.AETHER -> R.string.cs_note_aether
    Hop.PSIPHON -> R.string.cs_note_psiphon
    Hop.TOR -> R.string.cs_note_tor
    Hop.SSTP -> R.string.cs_note_sstp
    Hop.XRAY -> R.string.cs_note_xray
}

@Composable
private fun coreLinkLabel(core: Hop): String = stringResource(
    when (core) {
        Hop.AETHER -> R.string.qc_advanced_connection
        Hop.PSIPHON -> R.string.cs_link_psiphon
        Hop.TOR -> R.string.cs_link_tor
        Hop.SSTP -> R.string.cs_link_sstp
        Hop.XRAY -> R.string.cs_link_xray
    },
)

/** A section header: quiet, small, and a heading for TalkBack. */
@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text,
        style = AetherMetaLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

/** One section: header, optional one-line note, content, then the section gap. */
@Composable
private fun SheetSection(
    label: String,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = SECTION_GAP),
    ) {
        SheetSectionLabel(label)
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ---------------------------------------------------------------- Aether --

@Composable
private fun AetherSheetBody(
    profile: ConnectionProfile,
    enabled: Boolean,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
    edit: ProfileEdit,
) {
    SheetSection(stringResource(R.string.cs_section_protocol)) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Protocol.entries.forEach { value ->
                OptionCard(
                    title = protocolLabel(value),
                    description = protocolDescription(value),
                    selected = value == profile.protocol,
                    enabled = enabled,
                    onClick = { edit { copy(protocol = value) } },
                )
            }
        }
        if (profile.protocol == Protocol.MIM) {
            Spacer(Modifier.height(8.dp))
            InfoNote(stringResource(R.string.qc_mim_note))
        }
    }
    SheetSection(stringResource(R.string.cs_section_scan)) {
        // A pinned endpoint skips the scan. The stored mode is kept (and can
        // still be changed) for when the endpoint goes back to automatic.
        if (profile.hasManualPeer) {
            InfoNote(stringResource(R.string.qc_pinned_note))
            Spacer(Modifier.height(8.dp))
        }
        // The Settings page's own selector: same offered modes, same ETA copy.
        ScanModeSelector(
            selected = profile.scanMode,
            onSelect = { value -> edit { copy(scanMode = value) } },
            enabled = enabled,
        )
    }
    UnderlaySection(Hop.AETHER, profile.chain, cores, xrayReady, enabled, edit)
}

/** What the transport is for, in one line. */
@Composable
internal fun protocolDescription(protocol: Protocol): String = stringResource(
    when (protocol) {
        Protocol.AUTO -> R.string.qc_protocol_auto_desc
        Protocol.MASQUE -> R.string.qc_protocol_masque_desc
        Protocol.MIM -> R.string.qc_protocol_mim_desc
        Protocol.WIREGUARD -> R.string.qc_protocol_wireguard_desc
        Protocol.GOOL -> R.string.qc_protocol_gool_desc
    },
)

// --------------------------------------------------------------- Psiphon --

@Composable
private fun PsiphonSheetBody(
    profile: ConnectionProfile,
    enabled: Boolean,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
    edit: ProfileEdit,
) {
    val accents = LocalAetherAccents.current
    // The one payload fact that can still stop Psiphon.
    if (!cores.psiphonServerList) {
        NoticeBar(
            text = stringResource(R.string.psiphon_serverlist_missing),
            tone = accents.failed,
            icon = Icons.Rounded.Warning,
        )
        Spacer(Modifier.height(16.dp))
    }
    SheetSection(stringResource(R.string.cs_section_exit)) {
        PsiphonRegionGrid(
            // Sanitised, like the Chain page: a code no longer offered shows
            // (and runs) as Automatic.
            selected = PsiphonRegions.sanitize(profile.psiphonRegion),
            enabled = enabled,
            onSelect = { code -> edit { copy(psiphonRegion = code) } },
        )
    }
    SheetSection(stringResource(R.string.cs_section_protocol)) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PsiphonProtocol.entries.forEach { value ->
                OptionCard(
                    title = psiphonProtocolLabel(value),
                    description = psiphonProtocolDescription(value),
                    selected = value == profile.psiphonProtocol,
                    enabled = enabled,
                    onClick = { edit { copy(psiphonProtocol = value) } },
                )
            }
        }
        // Same caveat the Chain page gives: through a SOCKS5 hop, no QUIC.
        if (profile.chain.upstreamOf(Hop.PSIPHON) != null && profile.psiphonProtocol != PsiphonProtocol.AUTO) {
            Hint(stringResource(R.string.psiphon_cdn_quic_note))
        }
    }
    UnderlaySection(Hop.PSIPHON, profile.chain, cores, xrayReady, enabled, edit)
}

/**
 * Psiphon's exit countries as a grid of flags. The list IS [PsiphonRegions]
 * (Automatic first), exactly what the Chain page's dropdown offers, so no value
 * the core would silently ignore can be picked here either.
 */
@Composable
private fun PsiphonRegionGrid(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val automatic = stringResource(R.string.psiphon_region_auto)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + GRID_GAP) / (REGION_CELL_MIN + GRID_GAP)).toInt().coerceIn(2, 6)
        Column(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            PsiphonRegions.codes.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    row.forEach { code ->
                        RegionCell(
                            code = code,
                            name = if (code == PsiphonRegions.AUTOMATIC) automatic else PsiphonRegions.name(code),
                            selected = code == selected,
                            enabled = enabled,
                            onClick = { onSelect(code) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // A short last row keeps the cell width of the rows above it.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RegionCell(
    code: String,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AetherRadius.Field)
    Column(
        modifier
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(if (selected) accents.brandWash else colors.surface)
            .border(1.dp, if (selected) accents.brand else accents.cardBorder, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            PsiphonRegions.flag(code),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------- Tor --

@Composable
private fun TorSheetBody(
    profile: ConnectionProfile,
    enabled: Boolean,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
    edit: ProfileEdit,
) {
    val context = LocalContext.current
    // Install-time facts: read once, never per recomposition.
    val plugins = remember(context) { PluggableTransports.of(context) }
    val store = remember(context) { BridgeStore(context) }
    val stored by store.state.collectAsStateWithLifecycle(initialValue = BridgeStore.Snapshot())
    val catalog = remember(context, stored.catalogJson) {
        BridgeCatalog.current(context, stored.catalogJson)
    }
    val picked = remember(profile.torBridgeLines) { BridgeLine.parseAll(profile.torBridgeLines).size }
    val mode = profile.torBridgeMode

    SheetSection(stringResource(R.string.cs_section_bridges)) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            TorBridgeMode.entries.forEach { choice ->
                val active = choice == mode
                BridgeChoiceRow(
                    title = bridgeModeLabel(choice),
                    summary = if (active) bridgeModeDescription(choice) else bridgeModeTag(choice),
                    icon = bridgeModeIcon(choice),
                    selected = active,
                    enabled = enabled,
                    onSelect = { edit { copy(torBridgeMode = choice) } },
                )
            }
        }
        if (mode.isOn) {
            Spacer(Modifier.height(8.dp))
            // The lines decide what tor runs, not the mode: say how many there are.
            if (picked == 0) {
                InfoNote(stringResource(R.string.cs_bridges_none_picked))
            } else {
                Hint(stringResource(R.string.bridges_status_ready, picked))
            }
        }
    }

    when (mode) {
        // Pasted lines carry their own transport token: no picker for them,
        // same as the Bridges page.
        TorBridgeMode.BUILTIN, TorBridgeMode.REQUESTED -> SheetSection(stringResource(R.string.cs_section_transport)) {
            BridgeTransportPicker(
                selected = profile.torBridgeTransport,
                // A transport whose binary is not in this APK is dimmed and
                // unselectable, with the missing binary named on its chip.
                runnable = { type -> plugins.has(type) },
                countOf = { type -> catalog[type]?.size ?: 0 },
                enabled = enabled,
                onSelect = { type -> edit { copy(torBridgeTransport = type) } },
            )
            if (plugins.missing.isNotEmpty()) {
                Hint(
                    stringResource(
                        R.string.bridges_plugins_missing,
                        plugins.missing.mapNotNull { it.plugin?.id }.distinct().joinToString(", "),
                    ),
                )
            }
        }
        TorBridgeMode.CUSTOM -> {
            Hint(stringResource(R.string.bridges_custom_type_note))
            Spacer(Modifier.height(SECTION_GAP))
        }
        TorBridgeMode.OFF -> Unit
    }

    // Not a picker on purpose: TorCore no longer writes ExitNodes (see
    // ChainSection), and offering a control that does nothing is worse than
    // saying so.
    SheetSection(stringResource(R.string.cs_section_exit)) {
        InfoNote(stringResource(R.string.cs_tor_exit_note))
    }
    UnderlaySection(Hop.TOR, profile.chain, cores, xrayReady, enabled, edit)
}

// ------------------------------------------------------------------ SSTP --

@Composable
private fun SstpSheetBody(
    profile: ConnectionProfile,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    val config = profile.sstpConfig
    // Which half of the server section is showing. Starts on where the
    // current config came from; switching it writes nothing by itself.
    var source by rememberSaveable(config.source) { mutableStateOf(config.source) }
    var browsing by rememberSaveable { mutableStateOf(false) }

    InfoNote(stringResource(R.string.cs_sstp_standalone))
    Spacer(Modifier.height(16.dp))
    SheetSection(stringResource(R.string.cs_section_server)) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionCard(
                title = stringResource(R.string.cs_sstp_vpngate),
                description = stringResource(R.string.cs_sstp_vpngate_desc),
                selected = source == SstpSource.VPNGATE,
                enabled = enabled,
                onClick = { source = SstpSource.VPNGATE },
            )
            OptionCard(
                title = stringResource(R.string.cs_sstp_manual),
                description = stringResource(R.string.cs_sstp_manual_desc),
                selected = source == SstpSource.MANUAL,
                enabled = enabled,
                onClick = { source = SstpSource.MANUAL },
            )
        }
        Spacer(Modifier.height(16.dp))
        when (source) {
            // The settings page's own relay card; the list behind it is the
            // one VPN Gate browser (country, sort by country or ping, refresh).
            SstpSource.VPNGATE -> SstpRelayHero(config = config, onBrowse = { browsing = true })
            SstpSource.MANUAL -> SstpServerFields(
                config = config,
                onConfigChange = { changed -> edit { copy(sstpConfig = changed) } },
                enabled = enabled,
            )
        }
    }

    if (browsing) {
        VpnGateBrowserDialog(config = config, enabled = enabled, edit = edit, onDismiss = { browsing = false })
    }
}

// ------------------------------------------------------------------ Xray --

@Composable
private fun XraySheetBody(
    enabled: Boolean,
    cores: CoreAvailability.Snapshot,
    onXraySaved: (XrayOutbound?) -> Unit,
) {
    SheetSection(stringResource(R.string.cs_section_config)) {
        // The Chain page's editor: same store, same debounce and flush, same
        // parser and errors. Here with paste / import and the full readout.
        XrayConfigEditor(
            binaryBundled = cores.xrayBinary,
            alone = true,
            enabled = enabled,
            importActions = true,
            detailedSummary = true,
            onSavedChange = onXraySaved,
        )
        Hint(stringResource(R.string.cs_xray_underlay_hint))
    }
}

// --------------------------------------------------------- missing core --

@Composable
private fun MissingCoreBody(core: Hop) {
    val accents = LocalAetherAccents.current
    NoticeBar(
        text = stringResource(R.string.cs_core_missing, core.label, coreBinary(core)),
        tone = accents.failed,
        icon = Icons.Rounded.Warning,
    )
    Hint(stringResource(R.string.cs_core_missing_hint))
    Spacer(Modifier.height(SECTION_GAP))
}

/** The file a build needs for [core]: what the missing-core sheet names. */
private fun coreBinary(core: Hop): String = when (core) {
    Hop.PSIPHON -> PsiphonCore.BINARY
    Hop.TOR -> TorCore.BINARY
    Hop.XRAY -> XrayCore.BINARY
    // Mandatory / pure Kotlin: never missing, see CoreAvailability.has.
    Hop.AETHER -> "libaether.so"
    Hop.SSTP -> "SstpCore"
}

// -------------------------------------------------------------- underlay --

/**
 * What [core] rides on, as radio cards: the underlay's name, the literal path
 * (always LTR, like the Chain page) and what the combination is for. An
 * underlay that cannot run here stays visible but disabled, with the reason.
 * Nothing for SSTP and Xray, which have only Direct.
 */
@Composable
private fun UnderlaySection(
    core: Hop,
    chain: ChainMode,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    val options = CoreRoutes.underlaysFor(core)
    if (options.size < 2) return
    val current = CoreRoutes.underlayOf(chain)
    SheetSection(
        label = stringResource(R.string.cs_section_underlay),
        note = stringResource(R.string.cs_underlay_note, core.label),
    ) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { underlay ->
                val mode = CoreRoutes.modeOf(core, underlay) ?: return@forEach
                val reason = when (underlayBlock(core, underlay, cores, xrayReady)) {
                    null -> null
                    UnderlayBlock.XRAY_CONFIG_MISSING -> stringResource(R.string.cs_underlay_needs_xray)
                    UnderlayBlock.CORE_MISSING, UnderlayBlock.UNSUPPORTED -> stringResource(
                        R.string.chain_mode_unavailable,
                        cores.missing(mode).joinToString(", ") { it.label },
                    )
                }
                OptionCard(
                    title = underlayLabel(underlay),
                    description = chainDescription(mode),
                    selected = underlay == current,
                    enabled = enabled && reason == null,
                    onClick = { edit { copy(chain = mode) } },
                    meta = mode.pathLabel(),
                    warning = reason,
                )
            }
        }
    }
}

// ------------------------------------------------------------ shared bits --

/**
 * One radio choice: title, an optional LTR [meta] line (a path, a code), the
 * description, and an optional [warning] for why it is disabled. Moved here
 * from the quick-controls deck, unchanged apart from those two lines and the
 * 16dp padding of the 8dp grid.
 */
@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    meta: String? = null,
    warning: String? = null,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AetherRadius.Card)
    val alpha = if (enabled) 1f else 0.45f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = if (selected) accents.brandWash else colors.surface,
        border = BorderStroke(1.dp, if (selected) accents.brand else accents.cardBorder),
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) {
                    // Always LTR: product names and arrows, which must not mirror.
                    Text(
                        meta,
                        style = AetherMetaLabel.copy(textDirection = TextDirection.Ltr),
                        color = colors.onSurfaceVariant.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = alpha),
                )
                if (warning != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = accents.failed,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = selected, enabled = enabled, onClick = null)
        }
    }
}

/** Why a choice is idle or limited right now. Not an error: the working tone, quietly. */
@Composable
private fun InfoNote(text: String) {
    val accents = LocalAetherAccents.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Card),
        color = accents.working.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accents.working.copy(alpha = 0.30f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), accents.working)
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The way out to the Settings page that owns the rest. Chevron mirrors in RTL. */
@Composable
private fun CoreSettingsLink(page: SettingsPage, label: String, onClick: () -> Unit) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(AetherRadius.Card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(settingsPageIcon(page), null, Modifier.size(20.dp), accents.brand)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
