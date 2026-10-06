package studio.cluvex.aether.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.SmartDnsLatency
import studio.cluvex.aether.core.SmartDnsPath
import studio.cluvex.aether.core.SmartDnsRuntime
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.SmartDnsPreset
import studio.cluvex.aether.model.SmartDnsPresetGroup
import studio.cluvex.aether.model.SmartDnsPresets
import studio.cluvex.aether.model.SmartDnsProtocol
import studio.cluvex.aether.model.SmartDnsServer
import studio.cluvex.aether.model.SmartDnsServers
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

/*
 * SMART DNS, ON THE CONNECTION TAB.
 *
 * It used to be a section at the bottom of Settings > Routing, behind a
 * protocol picker the user had to get right first. It is the one setting that
 * decides whether Gemini & co. open at all, and people flip it between
 * attempts, so it now lives under the quick controls:
 *
 *   card    the glyph, what is in use ("GeoHide EU +2"), the live path while a
 *           session runs, and the switch. Tap anywhere else to open the sheet.
 *   sheet   the switch, the ready-made servers (sealed in the app, see
 *           model/SmartDnsPresets.kt), the user's own servers, and a Test chip
 *           next to every one of them.
 *
 * NO PROTOCOL PICKER. UDP, DoH and DoT are detected per entry
 * ([SmartDnsServers.parseAuto]), so presets and typed servers of any kind mix
 * in one list, tried in order: picked presets first (the number on each row is
 * its place), then the user's own.
 *
 * LOCKING follows the rest of the tab: while a session is up or on its way the
 * card's switch and the sheet's choices are disabled - but the sheet still
 * opens and every Test chip still works, because "which of these answers from
 * here" is exactly what someone asks while connected.
 */

// ------------------------------------------------------------------ glyph --

private const val SHIELD =
    "M12,2.6L19.4,5.4V11.1C19.4,15.8 16.3,19.8 12,21.4C7.7,19.8 4.6,15.8 4.6,11.1V5.4Z"
private const val GLOBE = "M12,7.3A4.3,4.3 0,1 1,12,15.9A4.3,4.3 0,1 1,12,7.3Z"
private const val MERIDIAN =
    "M12,7.3C10.7,8.5 10.1,9.9 10.1,11.6C10.1,13.3 10.7,14.7 12,15.9C13.3,14.7 13.9,13.3 13.9,11.6C13.9,9.9 13.3,8.5 12,7.3Z"
private const val EQUATOR = "M7.9,11.6H16.1"
private const val SPARK = "M20.2,1.9L20.7,3.1L21.9,3.6L20.7,4.1L20.2,5.3L19.7,4.1L18.5,3.6L19.7,3.1Z"

/**
 * The Smart DNS mark, drawn from SVG path data: a shield holding a globe, with
 * a spark for "smart". Built as a vector so it is sharp at any size, and
 * painted with the theme's own brand-to-protected gradient (an Icon would tint
 * it flat).
 */
private fun smartDnsGlyph(start: Color, end: Color): ImageVector {
    val ink = Brush.linearGradient(listOf(start, end), start = Offset(3f, 2f), end = Offset(21f, 22f))
    return ImageVector.Builder(
        name = "SmartDnsGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(
            pathData = addPathNodes(SHIELD),
            fill = ink,
            fillAlpha = 0.16f,
            stroke = ink,
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(pathData = addPathNodes(GLOBE), stroke = ink, strokeLineWidth = 1.3f)
        .addPath(
            pathData = addPathNodes(MERIDIAN),
            stroke = ink,
            strokeLineWidth = 1.1f,
            strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            pathData = addPathNodes(EQUATOR),
            stroke = ink,
            strokeLineWidth = 1.1f,
            strokeLineCap = StrokeCap.Round,
        )
        .addPath(pathData = addPathNodes(SPARK), fill = SolidColor(end))
        .build()
}

/** The glyph on a soft tile; protected-tinted while Smart DNS shapes the session. Decorative. */
@Composable
internal fun SmartDnsBadge(size: Dp, lit: Boolean) {
    val accents = LocalAetherAccents.current
    val glyph = remember(accents.brand, accents.protected) { smartDnsGlyph(accents.brand, accents.protected) }
    val wash by animateColorAsState(
        targetValue = if (lit) accents.protected.copy(alpha = 0.16f) else accents.brandWash,
        animationSpec = tween(aetherDuration(AetherDur.Quick)),
        label = "sdns-wash",
    )
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(wash)
            .border(1.dp, (if (lit) accents.protected else accents.brand).copy(alpha = 0.30f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(rememberVectorPainter(glyph), contentDescription = null, modifier = Modifier.size(size * 0.64f))
    }
}

// ------------------------------------------------------------ test results --

private sealed interface DnsPing {
    data object Running : DnsPing
    data object Failed : DnsPing
    data class Ok(val ms: Long) : DnsPing
}

/**
 * Test results, app-wide: they survive closing and reopening the sheet, so a
 * "Test all" is not thrown away by a stray swipe. Keyed by the preset token or
 * the typed endpoint.
 */
private object DnsPingBoard {
    val results = mutableStateMapOf<String, DnsPing>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun test(key: String, server: SmartDnsServer) {
        if (results[key] == DnsPing.Running) return
        results[key] = DnsPing.Running
        scope.launch {
            val ms = withContext(Dispatchers.IO) { runCatching { SmartDnsLatency.measure(server) }.getOrNull() }
            results[key] = if (ms != null) DnsPing.Ok(ms) else DnsPing.Failed
        }
    }
}

private fun presetKey(preset: SmartDnsPreset): String = preset.token

private fun customKey(server: SmartDnsServer): String = "custom:" + server.label

// ------------------------------------------------------------------- edits --

/**
 * Switching on with nothing usable picks the priority preset, so "on" always
 * does something. Switching off keeps the list for next time.
 */
private fun ConnectionProfile.withSmartDns(on: Boolean): ConnectionProfile {
    if (!on) return copy(smartDns = false)
    if (sanitizedSmartDns().isNotEmpty()) return copy(smartDns = true)
    return copy(
        smartDns = true,
        smartDnsServers = SmartDnsPresets.compose(
            listOf(SmartDnsPresets.DEFAULT.id),
            SmartDnsPresets.customPart(smartDnsServers, smartDnsProtocol),
        ),
        smartDnsProtocol = SmartDnsProtocol.PLAIN,
    )
}

// ------------------------------------------------------------------ labels --

@Composable
private fun presetTitle(preset: SmartDnsPreset): String = when (preset.group) {
    SmartDnsPresetGroup.ENCRYPTED ->
        if (preset.region == "EU" || preset.region == "US") "${preset.title} ${preset.region}" else preset.title
    SmartDnsPresetGroup.CLASSIC -> stringResource(R.string.sdns_node, preset.title)
}

@Composable
private fun presetSubtitle(preset: SmartDnsPreset): String = when (preset.region) {
    "EU" -> stringResource(R.string.sdns_region_eu)
    "US" -> stringResource(R.string.sdns_region_us)
    "GLOBAL" -> stringResource(R.string.sdns_region_global)
    else -> stringResource(
        if (preset.protocol == SmartDnsProtocol.PLAIN) R.string.sdns_kind_udp else R.string.sdns_kind_doh,
    )
}

/** A preset by its name, anything else by its address, isolated LTR so Persian does not reorder it. */
@Composable
private fun serverTitle(server: SmartDnsServer): String {
    val preset = SmartDnsPresets.fromToken(server.alias)
    return if (preset != null) presetTitle(preset) else "\u2066${server.endpoint}\u2069"
}

private fun protocolShort(protocol: SmartDnsProtocol): String = when (protocol) {
    SmartDnsProtocol.PLAIN -> "UDP"
    SmartDnsProtocol.DOH -> "DoH"
    SmartDnsProtocol.DOT -> "DoT"
}

@Composable
private fun smartDnsSummary(profile: ConnectionProfile, servers: List<SmartDnsServer>): String = when {
    !profile.smartDns -> stringResource(R.string.hub_state_off)
    profile.chain != ChainMode.AETHER -> stringResource(R.string.sdns_state_route)
    servers.isEmpty() -> stringResource(R.string.sdns_state_none)
    servers.size == 1 -> serverTitle(servers.first())
    else -> stringResource(R.string.sdns_state_more, serverTitle(servers.first()), servers.size - 1)
}

// -------------------------------------------------------------------- card --

/**
 * The Smart DNS row under the quick controls. [compact] is the one-line form
 * for short windows, like the quick-control tiles.
 */
@Composable
internal fun SmartDnsHomeCard(
    profile: ConnectionProfile,
    editable: Boolean,
    compact: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val live by SmartDnsRuntime.status.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(profile)
    val canEdit by rememberUpdatedState(editable)
    val servers = profile.sanitizedSmartDns()
    val active = profile.usesSmartDns
    val value = smartDnsSummary(profile, servers)
    val path = live?.path?.takeIf { active }
    val shape = RoundedCornerShape(AetherRadius.Card)
    val edge by animateColorAsState(
        targetValue = if (active) accents.protected.copy(alpha = 0.45f) else accents.cardBorder,
        animationSpec = tween(aetherDuration(AetherDur.Quick)),
        label = "sdns-edge",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.sdns_open), role = Role.Button) { open = true },
        shape = shape,
        color = accents.card,
        border = BorderStroke(1.dp, edge),
    ) {
        Row(
            Modifier
                .heightIn(min = if (compact) 48.dp else 60.dp)
                .padding(start = if (compact) 10.dp else 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartDnsBadge(size = if (compact) 30.dp else 40.dp, lit = active)
            Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
            if (compact) {
                Text(
                    stringResource(R.string.section_smart_dns),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface.copy(alpha = if (profile.smartDns) 1f else 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.section_smart_dns),
                        style = AetherMetaLabel,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            value,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface.copy(alpha = if (profile.smartDns) 1f else 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (path != null) {
                            Spacer(Modifier.width(8.dp))
                            PathPill(path)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!editable) {
                Icon(Icons.Rounded.Lock, stringResource(R.string.qc_locked), Modifier.size(16.dp), colors.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Switch(
                checked = profile.smartDns,
                onCheckedChange = { checked -> if (canEdit) onProfileChange(latest.withSmartDns(checked)) },
                enabled = editable,
            )
        }
    }

    if (open) {
        SmartDnsSheet(
            profile = profile,
            editable = editable,
            onProfileChange = onProfileChange,
            onDismiss = { open = false },
        )
    }
}

/** Through the tunnel / direct, while a session runs. */
@Composable
private fun PathPill(path: SmartDnsPath) {
    val accents = LocalAetherAccents.current
    val tone = if (path == SmartDnsPath.TUNNEL) accents.protected else accents.working
    Surface(color = tone.copy(alpha = 0.12f), shape = CircleShape) {
        Text(
            stringResource(if (path == SmartDnsPath.TUNNEL) R.string.sdns_path_tunnel else R.string.sdns_path_direct),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = AetherMetaLabel,
            color = tone,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------- sheet --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartDnsSheet(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(profile)
    val canEdit by rememberUpdatedState(editable)
    val live by SmartDnsRuntime.status.collectAsStateWithLifecycle()

    val selected = remember(profile.smartDnsServers) { SmartDnsPresets.selectedIds(profile.smartDnsServers) }
    var custom by rememberSaveable {
        mutableStateOf(SmartDnsPresets.customPart(profile.smartDnsServers, profile.smartDnsProtocol))
    }
    val customServers = remember(custom) { SmartDnsServers.parse(custom) }
    val inUse = profile.sanitizedSmartDns().size
    val encrypted = remember { SmartDnsPresets.ALL.filter { it.group == SmartDnsPresetGroup.ENCRYPTED } }
    val classic = remember { SmartDnsPresets.ALL.filter { it.group == SmartDnsPresetGroup.CLASSIC } }

    // Every write goes through here: refused once locked, stored with no
    // protocol hint (the transport is detected per entry now), and a no-op
    // does not rewrite the profile.
    val write: (List<String>, String, Boolean?) -> Unit = { ids, text, enable ->
        if (canEdit) {
            val base = latest
            val next = base.copy(
                smartDns = enable ?: base.smartDns,
                smartDnsServers = SmartDnsPresets.compose(ids, text),
                smartDnsProtocol = SmartDnsProtocol.PLAIN,
            )
            if (next != base) onProfileChange(next)
        }
    }
    val testAll: () -> Unit = {
        SmartDnsPresets.ALL.forEach { preset -> preset.server?.let { DnsPingBoard.test(presetKey(preset), it) } }
        customServers.forEach { DnsPingBoard.test(customKey(it), it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = AetherRadius.Sheet, topEnd = AetherRadius.Sheet),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmartDnsBadge(size = 48.dp, lit = profile.usesSmartDns)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.section_smart_dns),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.sdns_sheet_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            MasterSwitch(
                checked = profile.smartDns,
                enabled = editable,
                onChange = { on -> if (canEdit) onProfileChange(latest.withSmartDns(on)) },
            )
            if (profile.chain != ChainMode.AETHER) {
                Spacer(Modifier.height(10.dp))
                SheetNote(stringResource(R.string.smart_dns_chain_only), accents.working, Icons.Rounded.Info)
            }
            if (!editable) {
                Spacer(Modifier.height(10.dp))
                SheetNote(stringResource(R.string.sdns_locked), accents.working, Icons.Rounded.Lock)
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.sdns_presets_label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.sdns_presets_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = testAll, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.sdns_test_all), maxLines = 1)
                }
            }

            Spacer(Modifier.height(12.dp))
            GroupLabel(stringResource(R.string.sdns_group_encrypted), Icons.Rounded.EnhancedEncryption, accents.brand)
            encrypted.forEach { preset ->
                PresetRow(
                    preset = preset,
                    order = selected.indexOf(preset.id) + 1,
                    enabled = editable,
                    onToggle = {
                        val turningOn = preset.id !in selected
                        write(SmartDnsPresets.toggle(selected, preset.id), custom, if (turningOn) true else null)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            GroupLabel(stringResource(R.string.sdns_group_classic), Icons.Rounded.Bolt, colors.onSurfaceVariant)
            classic.forEach { preset ->
                PresetRow(
                    preset = preset,
                    order = selected.indexOf(preset.id) + 1,
                    enabled = editable,
                    onToggle = {
                        val turningOn = preset.id !in selected
                        write(SmartDnsPresets.toggle(selected, preset.id), custom, if (turningOn) true else null)
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.sdns_custom_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = custom,
                onValueChange = { text ->
                    custom = text
                    write(selected, text, null)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = editable,
                placeholder = {
                    Text(
                        stringResource(R.string.sdns_custom_hint),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = AetherMono,
                    textDirection = TextDirection.Ltr,
                ),
                minLines = 2,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(AetherRadius.Card),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.sdns_custom_help),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (custom.isNotBlank() && customServers.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sdns_custom_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = accents.failed,
                )
            }
            if (customServers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                customServers.forEach { server -> CustomRow(server) }
            }

            Spacer(Modifier.height(16.dp))
            HowItWorks(
                inUse = inUse,
                livePath = live?.path,
                proxyRanges = live?.proxyRanges ?: 0,
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(
                    onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                ) {
                    Text(stringResource(R.string.qc_done))
                }
            }
        }
    }
}

@Composable
private fun MasterSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AetherRadius.Card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        shape = shape,
        color = if (checked) accents.brandWash else colors.surface,
        border = BorderStroke(1.dp, if (checked) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.smart_dns_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.sdns_auto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            // The row is the toggle; the switch only shows it.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun GroupLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * One ready-made server. The mark at the start is its place in the try order
 * once picked (1 is asked first); the chip at the end tests it.
 */
@Composable
private fun PresetRow(
    preset: SmartDnsPreset,
    order: Int,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val chosen = order > 0
    val shape = RoundedCornerShape(AetherRadius.Card)
    val alpha = if (enabled) 1f else 0.6f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
        shape = shape,
        color = if (chosen) accents.brandWash else colors.surface,
        border = BorderStroke(1.dp, if (chosen) accents.brand else accents.cardBorder),
    ) {
        Row(
            Modifier
                .heightIn(min = 58.dp)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrderMark(order)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        presetTitle(preset),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (preset.recommended) {
                        Spacer(Modifier.width(6.dp))
                        Surface(color = accents.protected.copy(alpha = 0.14f), shape = CircleShape) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Star, null, Modifier.size(11.dp), accents.protected)
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    stringResource(R.string.sdns_recommended),
                                    style = AetherMetaLabel,
                                    color = accents.protected,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolPill(preset.protocol)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        presetSubtitle(preset),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val key = presetKey(preset)
            PingChip(DnsPingBoard.results[key]) { preset.server?.let { DnsPingBoard.test(key, it) } }
        }
    }
}

/** One server the user typed, with the transport it was read as. */
@Composable
private fun CustomRow(server: SmartDnsServer) {
    val key = customKey(server)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolPill(server.protocol)
        Spacer(Modifier.width(8.dp))
        Text(
            server.label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = AetherMono,
                textDirection = TextDirection.Ltr,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        PingChip(DnsPingBoard.results[key]) { DnsPingBoard.test(key, server) }
    }
}

/** The try-order number on a picked row, an empty ring otherwise. */
@Composable
private fun OrderMark(order: Int) {
    val accents = LocalAetherAccents.current
    val on = order > 0
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (on) accents.brand else Color.Transparent)
            .border(1.5.dp, if (on) accents.brand else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Text(
                order.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accents.onBrand,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProtocolPill(protocol: SmartDnsProtocol) {
    val accents = LocalAetherAccents.current
    val tone = if (protocol == SmartDnsProtocol.PLAIN) MaterialTheme.colorScheme.onSurfaceVariant else accents.brand
    Surface(
        color = tone.copy(alpha = 0.10f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.25f)),
    ) {
        Text(
            protocolShort(protocol),
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = AetherMetaLabel.copy(fontFamily = AetherMono),
            color = tone,
            maxLines = 1,
        )
    }
}

/**
 * Test, testing, the result. Green under 150 ms, amber under 400, red above or
 * on a timeout. Tap again to re-test.
 */
@Composable
private fun PingChip(result: DnsPing?, onTest: () -> Unit) {
    val accents = LocalAetherAccents.current
    val tone = when (result) {
        is DnsPing.Ok -> when {
            result.ms < 150L -> accents.protected
            result.ms < 400L -> accents.working
            else -> accents.failed
        }
        DnsPing.Failed -> accents.failed
        else -> accents.brand
    }
    val label = when (result) {
        is DnsPing.Ok -> String.format(Locale.US, "%d ms", result.ms)
        DnsPing.Failed -> stringResource(R.string.sdns_test_failed)
        DnsPing.Running -> stringResource(R.string.sdns_testing)
        null -> stringResource(R.string.sdns_test)
    }
    val running = result == DnsPing.Running
    Surface(
        color = tone.copy(alpha = 0.10f),
        contentColor = tone,
        shape = CircleShape,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.30f)),
    ) {
        Row(
            Modifier
                .clickable(
                    enabled = !running,
                    onClickLabel = stringResource(R.string.sdns_test),
                    role = Role.Button,
                    onClick = onTest,
                )
                .heightIn(min = 36.dp)
                .widthIn(min = 84.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(12.dp), color = tone, strokeWidth = 1.5.dp)
            } else {
                Icon(
                    when (result) {
                        is DnsPing.Ok -> Icons.Rounded.Speed
                        DnsPing.Failed -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.NetworkCheck
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = tone,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = AetherMono,
                    textDirection = TextDirection.Ltr,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SheetNote(text: String, tone: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Card),
        color = tone.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.30f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(18.dp), tone)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** What happens to the chosen servers, the live path, and the one Android setting that can break it. */
@Composable
private fun HowItWorks(inUse: Int, livePath: SmartDnsPath?, proxyRanges: Int) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Card),
        color = colors.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sdns_how_title), style = MaterialTheme.typography.labelLarge)
            }
            if (inUse > 0) {
                Text(
                    stringResource(R.string.sdns_order_note, inUse),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                )
            }
            Text(
                stringResource(R.string.smart_dns_route_auto),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (livePath != null) {
                Text(
                    if (livePath == SmartDnsPath.TUNNEL) {
                        stringResource(R.string.smart_dns_live_tunnel)
                    } else {
                        stringResource(R.string.smart_dns_live_direct, proxyRanges)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                )
            }
            Text(
                stringResource(R.string.sdns_test_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.smart_dns_private_dns_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
