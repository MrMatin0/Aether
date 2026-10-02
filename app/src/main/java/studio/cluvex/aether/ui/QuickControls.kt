package studio.cluvex.aether.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.ScanModeSelector
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

/*
 * QUICK CONTROLS: the three decisions that shape a connection, on the
 * connection tab itself.
 *
 *   Route     which cores carry the session (ChainMode)
 *   Protocol  how the Aether hop talks to WARP (Protocol)
 *   Scan      how hard the Aether hop looks for an edge (ScanMode)
 *
 * WHY THESE THREE, AND ONLY THESE. They are the settings that decide whether
 * and how fast a user gets online, and the ones people change between
 * attempts. Everything else (endpoint pinning, MASQUE-in-MASQUE hops, IP
 * family, Psiphon region, Tor bridges) is set once, needs text input or only
 * matters on some combinations, so it stays in Settings and the sheet links to
 * the exact page that owns it instead of growing a second editor for it.
 *
 * WHY A DECK + ONE SHEET, not three dropdowns. A dropdown can only show a
 * label; every one of these choices needs its cost (speed, wait time, which
 * cores it needs) next to it to be chosen well. The deck shows the current
 * value of all three at a glance and costs one row of height; the sheet has
 * room for descriptions and is the same sheet whichever tile was tapped, with
 * that tile's section already selected.
 *
 * HONEST STATES. A tile never shows a value that is not doing anything:
 *   LOCKED    the tunnel is up or on its way; nothing can change mid-session
 *   NOT_USED  the route has no Aether hop, so protocol and scan are idle
 *   PINNED    a pinned endpoint skips the scan, so the scan mode is idle
 * The stored values are kept in every case (the user comes back to them),
 * this is about what is SHOWN.
 */

/** The three quick decisions, in the order traffic meets them. */
internal enum class QuickControl { ROUTE, PROTOCOL, SCAN }

/** What a tile can honestly say about its own control right now. */
internal enum class ControlAvailability { READY, LOCKED, NOT_USED, PINNED }

/**
 * The ONE rule for "may the profile change now": at rest, or after a failure
 * (which is a retry). Same rule the Settings hub uses for its lock.
 */
internal fun quickControlsEditable(state: ConnectionState): Boolean =
    state is ConnectionState.Idle || state is ConnectionState.Error

internal fun quickControlAvailability(
    control: QuickControl,
    profile: ConnectionProfile,
    editable: Boolean,
): ControlAvailability = when {
    !editable -> ControlAvailability.LOCKED
    control == QuickControl.ROUTE -> ControlAvailability.READY
    !profile.chain.usesAether -> ControlAvailability.NOT_USED
    control == QuickControl.SCAN && profile.hasManualPeer -> ControlAvailability.PINNED
    else -> ControlAvailability.READY
}

/**
 * The Settings pages that own the rest of a control: what the sheet links to.
 * Bridges only when the route actually has a Tor hop to use them.
 */
internal fun advancedPagesFor(control: QuickControl, chain: ChainMode): List<SettingsPage> = when (control) {
    QuickControl.ROUTE ->
        if (chain.usesTor) listOf(SettingsPage.CHAIN, SettingsPage.BRIDGES) else listOf(SettingsPage.CHAIN)
    QuickControl.PROTOCOL, QuickControl.SCAN -> listOf(SettingsPage.CONNECTION)
}

/** Narrowest a tile can be and still show a value like "WireGuard" whole. */
internal val QUICK_TILE_MIN = 96.dp
private val QUICK_GAP = 8.dp

/**
 * Side by side when three tiles fit, stacked rows otherwise. Large text
 * always stacks: a 1.3x Persian label in a third of a phone is an ellipsis.
 */
internal fun quickControlsStacked(width: Dp, fontScale: Float): Boolean =
    fontScale >= 1.3f || width < QUICK_TILE_MIN * QuickControl.entries.size + QUICK_GAP * 2

private data class TileSpec(
    val control: QuickControl,
    val label: String,
    val value: String,
    val icon: ImageVector,
    val tint: Color,
    val availability: ControlAvailability,
    val idle: Boolean,
)

// ---------------------------------------------------------------- the deck --

/**
 * The deck under the Connect button. [compact] is for short windows: one
 * 44dp line per tile, icon and value only (the icon carries the label for
 * TalkBack).
 */
@Composable
internal fun QuickControls(
    profile: ConnectionProfile,
    state: ConnectionState,
    editable: Boolean,
    compact: Boolean,
    onOpen: (QuickControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val fontScale = LocalDensity.current.fontScale
    val notUsed = stringResource(R.string.qc_not_used)

    // The VALUE ignores the lock: a locked tile still says what it is set to.
    val protocolShown = quickControlAvailability(QuickControl.PROTOCOL, profile, editable = true)
    val scanShown = quickControlAvailability(QuickControl.SCAN, profile, editable = true)

    val liveProtocol = meta.protocol?.takeIf { state.isConnected && it.isNotBlank() }
    val specs = listOf(
        TileSpec(
            control = QuickControl.ROUTE,
            label = stringResource(R.string.qc_route),
            value = chainLabel(profile.chain),
            icon = Icons.Rounded.Route,
            tint = tierTone(profile.chain),
            availability = quickControlAvailability(QuickControl.ROUTE, profile, editable),
            idle = false,
        ),
        TileSpec(
            control = QuickControl.PROTOCOL,
            label = stringResource(R.string.qc_protocol),
            value = if (protocolShown == ControlAvailability.NOT_USED) {
                notUsed
            } else {
                // Auto is resolved before launch; once up, say what it became.
                liveProtocol ?: protocolLabel(profile.protocol)
            },
            icon = Icons.Rounded.Cable,
            tint = accents.brand,
            availability = quickControlAvailability(QuickControl.PROTOCOL, profile, editable),
            idle = protocolShown == ControlAvailability.NOT_USED,
        ),
        TileSpec(
            control = QuickControl.SCAN,
            label = stringResource(R.string.qc_scan),
            value = when (scanShown) {
                ControlAvailability.NOT_USED -> notUsed
                ControlAvailability.PINNED -> stringResource(R.string.qc_pinned)
                else -> scanLabel(profile.scanMode.effectiveFor(BuildConfig.CORE_VERSION))
            },
            icon = Icons.Rounded.Speed,
            tint = accents.brand,
            availability = quickControlAvailability(QuickControl.SCAN, profile, editable),
            idle = scanShown != ControlAvailability.READY,
        ),
    )

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (quickControlsStacked(maxWidth, fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(QUICK_GAP)) {
                specs.forEach { spec ->
                    ControlTile(spec, compact, row = true, onClick = { onOpen(spec.control) }, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(QUICK_GAP)) {
                specs.forEach { spec ->
                    ControlTile(spec, compact, row = false, onClick = { onOpen(spec.control) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ControlTile(
    spec: TileSpec,
    compact: Boolean,
    row: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val locked = spec.availability == ControlAvailability.LOCKED
    val shape = RoundedCornerShape(AetherRadius.Card)
    val lockedWords = stringResource(R.string.qc_locked)
    val icon = if (locked) Icons.Rounded.Lock else spec.icon
    val iconTint = if (locked || spec.idle) colors.onSurfaceVariant else spec.tint
    val valueInk = colors.onSurface.copy(alpha = if (spec.idle) 0.62f else 1f)

    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(
                enabled = !locked,
                onClickLabel = stringResource(R.string.qc_change, spec.label),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { if (locked) stateDescription = lockedWords },
        shape = shape,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        when {
            // One line: icon, value. The icon names the control for TalkBack.
            compact -> Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, spec.label, Modifier.size(16.dp), iconTint)
                Spacer(Modifier.width(8.dp))
                Text(
                    spec.value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = valueInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A full-width row: label at the start, value at the end.
            row -> Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(18.dp), iconTint)
                Spacer(Modifier.width(10.dp))
                Text(
                    spec.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    spec.value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = valueInk,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!locked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        null,
                        Modifier.size(20.dp),
                        colors.onSurfaceVariant,
                    )
                }
            }
            // A tile in a row of three: eyebrow, then the value.
            else -> Column(
                Modifier
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(14.dp), iconTint)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        spec.label,
                        Modifier.weight(1f),
                        style = AetherMetaLabel,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    spec.value,
                    style = MaterialTheme.typography.titleSmall,
                    color = valueInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --------------------------------------------------------------- the sheet --

/**
 * One sheet for all three controls, opened on [initial]'s section. Choices
 * apply immediately (there is nothing to "save": the next connect reads the
 * profile), and the sheet stays open so a user can change route AND protocol
 * in one visit. ConnectionHome dismisses it the moment the profile locks.
 *
 * ModalBottomSheet caps its own width on tablets and in landscape, and the
 * body scrolls, so the same sheet works from a 320dp split screen to a 13in
 * tablet without a second layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionControlsSheet(
    initial: QuickControl,
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Install-time facts: read once, never per recomposition.
    val cores = remember(context) { CoreAvailability.of(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var section by rememberSaveable(initial) { mutableStateOf(initial) }
    val latest by rememberUpdatedState(profile)
    val canEdit by rememberUpdatedState(editable)

    // Every write goes through here: refused once locked, and a no-op choice
    // (tapping the selected row) does not rewrite the profile.
    val edit: (ConnectionProfile.() -> ConnectionProfile) -> Unit = { change ->
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = AetherRadius.Sheet, topEnd = AetherRadius.Sheet),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.qc_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.qc_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SectionSwitcher(section, onSelect = { section = it })
            Spacer(Modifier.height(16.dp))

            Crossfade(
                targetState = section,
                animationSpec = tween(aetherDuration(AetherDur.Quick)),
                label = "qc-section",
            ) { shown ->
                Column(Modifier.fillMaxWidth()) {
                    when (shown) {
                        QuickControl.ROUTE -> RouteChoices(profile.chain, cores) { mode ->
                            edit { copy(chain = mode) }
                        }
                        QuickControl.PROTOCOL -> ProtocolChoices(profile, editable) { value ->
                            edit { copy(protocol = value) }
                        }
                        QuickControl.SCAN -> ScanChoices(profile, editable) { value ->
                            edit { copy(scanMode = value) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(R.string.qc_advanced_label),
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    advancedPagesFor(shown, profile.chain).forEach { page ->
                        AdvancedLink(page) { closeThen { onOpenSettings(page) } }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { closeThen { } }) {
                    Text(stringResource(R.string.qc_done))
                }
            }
        }
    }
}

/** Three pills, one per control. Tabs for TalkBack, so the selection is announced. */
@Composable
private fun SectionSwitcher(selected: QuickControl, onSelect: (QuickControl) -> Unit) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val showIcons = LocalDensity.current.fontScale < 1.5f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickControl.entries.forEach { control ->
            val active = control == selected
            val ink = if (active) colors.primary else colors.onSurfaceVariant
            Row(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) accents.brandWash else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(control) })
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcons) {
                    Icon(sectionIcon(control), null, Modifier.size(18.dp), ink)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    sectionLabel(control),
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sectionIcon(control: QuickControl): ImageVector = when (control) {
    QuickControl.ROUTE -> Icons.Rounded.Route
    QuickControl.PROTOCOL -> Icons.Rounded.Cable
    QuickControl.SCAN -> Icons.Rounded.Speed
}

@Composable
private fun sectionLabel(control: QuickControl): String = stringResource(
    when (control) {
        QuickControl.ROUTE -> R.string.qc_route
        QuickControl.PROTOCOL -> R.string.qc_protocol
        QuickControl.SCAN -> R.string.qc_scan
    },
)

// ------------------------------------------------------------------ route --

/** The seven routes, single cores first. Same rules as the Chain settings page. */
@Composable
private fun RouteChoices(
    selected: ChainMode,
    cores: CoreAvailability.Snapshot,
    onSelect: (ChainMode) -> Unit,
) {
    Text(
        stringResource(R.string.conn_route_sheet_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(
            stringResource(R.string.chain_group_single),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ChainMode.entries.filterNot { it.isChained }.forEach { mode ->
            RouteOption(mode, selected, cores, onSelect)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.chain_group_stacked),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ChainMode.entries.filter { it.isChained }.forEach { mode ->
            RouteOption(mode, selected, cores, onSelect)
        }
    }
}

@Composable
private fun RouteOption(
    mode: ChainMode,
    selected: ChainMode,
    cores: CoreAvailability.Snapshot,
    onSelect: (ChainMode) -> Unit,
) {
    val accents = LocalAetherAccents.current
    val missing = cores.missing(mode)
    val runnable = missing.isEmpty()
    val active = mode == selected
    val alpha = if (runnable) 1f else 0.45f
    val tint = if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(AetherRadius.Card)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .selectable(
                selected = active,
                enabled = runnable,
                role = Role.RadioButton,
                onClick = { onSelect(mode) },
            ),
        shape = shape,
        color = if (active) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            HopStack(mode, tint, alpha)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chainLabel(mode),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    TierBadge(mode)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    chainDescription(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!runnable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.chain_mode_unavailable,
                            missing.joinToString(", ") { it.label },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = accents.failed,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint.copy(alpha = alpha),
            )
        }
    }
}

private val HOP_CHIP = 28.dp
private val HOP_STEP = 16.dp

/**
 * The hops of a mode as overlapping chips, entry first. Fixed width so rows
 * align. Offsets follow the layout direction, so in Persian the stack reads
 * from the right like the text beside it.
 */
@Composable
private fun HopStack(mode: ChainMode, tint: Color, alpha: Float) {
    val accents = LocalAetherAccents.current
    val hops = mode.hops.asReversed()
    Box(
        Modifier
            .width(HOP_CHIP + HOP_STEP * 2)
            .height(HOP_CHIP),
    ) {
        hops.forEachIndexed { index, hop ->
            Box(
                Modifier
                    .offset(x = HOP_STEP * index)
                    .size(HOP_CHIP)
                    .clip(CircleShape)
                    .background(accents.card)
                    .border(1.dp, tint.copy(alpha = 0.5f * alpha), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(hopIcon(hop), null, Modifier.size(14.dp), tint.copy(alpha = alpha))
            }
        }
    }
}

/** Fast / slower / slowest, from the hops themselves. */
@Composable
internal fun tierTone(mode: ChainMode): Color {
    val accents = LocalAetherAccents.current
    return when {
        mode.usesTor -> accents.failed
        mode.usesPsiphon -> accents.working
        else -> accents.protected
    }
}

/** Same tiers as the Chain page. */
@Composable
internal fun TierBadge(mode: ChainMode) {
    val tone = tierTone(mode)
    val label = stringResource(
        when {
            mode.usesTor -> R.string.chain_tier_slow
            mode.usesPsiphon -> R.string.chain_tier_moderate
            else -> R.string.chain_tier_fast
        },
    )
    Surface(color = tone.copy(alpha = 0.14f), shape = CircleShape) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = AetherMetaLabel,
            color = tone,
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------- protocol --

@Composable
private fun ProtocolChoices(
    profile: ConnectionProfile,
    enabled: Boolean,
    onSelect: (Protocol) -> Unit,
) {
    if (!profile.chain.usesAether) {
        InfoNote(stringResource(R.string.qc_not_used_note, chainLabel(profile.chain)))
        Spacer(Modifier.height(12.dp))
    }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Protocol.entries.forEach { value ->
            OptionCard(
                title = protocolLabel(value),
                description = protocolDescription(value),
                selected = value == profile.protocol,
                enabled = enabled,
                onClick = { onSelect(value) },
            )
        }
    }
    if (profile.protocol == Protocol.MIM) {
        Spacer(Modifier.height(10.dp))
        InfoNote(stringResource(R.string.qc_mim_note))
    }
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

// ------------------------------------------------------------------- scan --

@Composable
private fun ScanChoices(
    profile: ConnectionProfile,
    enabled: Boolean,
    onSelect: (ScanMode) -> Unit,
) {
    when {
        !profile.chain.usesAether -> {
            InfoNote(stringResource(R.string.qc_not_used_note, chainLabel(profile.chain)))
            Spacer(Modifier.height(12.dp))
        }
        profile.hasManualPeer -> {
            InfoNote(stringResource(R.string.qc_pinned_note))
            Spacer(Modifier.height(12.dp))
        }
    }
    // The Settings page's own selector: same offered modes, same ETA copy.
    ScanModeSelector(selected = profile.scanMode, onSelect = onSelect, enabled = enabled)
}

// ------------------------------------------------------------ shared bits --

@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
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
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = alpha),
                )
            }
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = selected, enabled = enabled, onClick = null)
        }
    }
}

/** Why a choice is idle right now. Not an error: the working tone, quietly. */
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
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A way out to the Settings page that owns the rest. Chevron mirrors in RTL. */
@Composable
private fun AdvancedLink(page: SettingsPage, onClick: () -> Unit) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(AetherRadius.Card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(settingsPageIcon(page), null, Modifier.size(20.dp), accents.brand)
            Spacer(Modifier.width(12.dp))
            Text(
                advancedLinkLabel(page),
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

@Composable
private fun advancedLinkLabel(page: SettingsPage): String = when (page) {
    SettingsPage.CHAIN -> stringResource(R.string.qc_advanced_chain)
    SettingsPage.BRIDGES -> stringResource(R.string.qc_advanced_bridges)
    SettingsPage.CONNECTION -> stringResource(R.string.qc_advanced_connection)
    else -> settingsPageTitle(page)
}
