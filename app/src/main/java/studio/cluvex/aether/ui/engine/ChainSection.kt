package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.PsiphonCdnFronting
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.XrayLink
import studio.cluvex.aether.core.XrayOutbound
import studio.cluvex.aether.data.XrayConfigStore
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.PsiphonProtocol
import studio.cluvex.aether.ui.chainLabel
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.InfoTip
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * WHICH cores carry the session, and their own options.
 *
 * Deliberately its own page rather than a row inside Connection: picking a chain
 * changes what every other page even means (a Tor-only session has no endpoint
 * to scan and no WARP protocol to choose), so it is not a knob that belongs
 * between two engine knobs.
 *
 * ### Why this page is rows and not a dropdown
 *
 * Three facts decide whether one of these modes is the right choice, and a
 * dropdown could show none of them at once:
 *
 *  1. **Is the core even in this build.** Psiphon, Tor and Xray are optional; a
 *     build without them is valid. Unavailable modes are disabled here, with the
 *     missing core named.
 *  2. **What it costs.** Every added hop is latency, and Tor is minutes of
 *     bootstrap. The cost badge says so on every row.
 *  3. **Which direction "over" runs.** "Over" reads both ways in English and in
 *     Persian, so the literal path is printed on every row, always LTR.
 *
 * Each row is compact: title, path, cost badge. The multi-line description is
 * gone; the two group labels and the (?) carry what is left of it.
 *
 * ### Where the per-core options live
 *
 * Directly under the selected mode row. Only the SELECTED row opens them, so
 * the page never shows two editors for one setting.
 *
 * ### NoticeBars
 *
 * Only for things that are actually wrong: a core binary or the Psiphon server
 * list missing from this build, an Xray config that does not parse, or one that
 * could not be saved. Everything else is behind a (?).
 *
 * ### Why Tor has no exit country any more
 *
 * It needed the geoip database, it was a free-typed two-letter box, and pinning
 * Tor's exit only narrows the relay set on networks where Tor is already the
 * slowest thing in the app. [studio.cluvex.aether.core.TorCore] no longer writes
 * either option, so a value saved by an older build cannot keep pinning the exit
 * with no UI left to clear it.
 */
@Composable
internal fun ChainSection(
    chain: ChainMode,
    psiphonRegion: String,
    psiphonProtocol: PsiphonProtocol,
    psiphonCdnEdgeIps: String,
    psiphonCdnSni: String,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val context = LocalContext.current
    // Install-time facts: read once, never per recomposition.
    val cores = remember(context) { CoreAvailability.of(context) }
    val psiphon = PsiphonUi(psiphonRegion, psiphonProtocol, psiphonCdnEdgeIps, psiphonCdnSni)

    Column(modifier.fillMaxWidth()) {
        val absent = buildList {
            if (!cores.psiphonBinary) add(Hop.PSIPHON.label)
            if (!cores.torBinary) add(Hop.TOR.label)
            if (!cores.xrayBinary) add(Hop.XRAY.label)
        }
        if (absent.isNotEmpty()) {
            NoticeBar(
                text = stringResource(R.string.chain_build_missing, absent.joinToString(", ")),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(EngineSpacing.Group))
        }

        // ONE radio group for every mode: the two cards group them visually
        // without making them two independent selections.
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            EngineGroup(
                label = stringResource(R.string.chain_group_single),
                contentPadding = EngineSpacing.ListPadding,
            ) {
                SingleCoreModes.forEach { mode ->
                    ChainModeEntry(mode, chain, psiphon, cores, enabled, edit)
                }
            }
            Spacer(Modifier.height(EngineSpacing.Group))
            EngineGroup(
                label = stringResource(R.string.chain_group_stacked),
                info = stringResource(R.string.chain_group_stacked_note),
                contentPadding = EngineSpacing.ListPadding,
            ) {
                StackedModes.forEach { mode ->
                    ChainModeEntry(mode, chain, psiphon, cores, enabled, edit)
                }
            }
        }

        // Tor's trade-offs and the pointer to Bridges, on demand: both turn into
        // "the app is broken" reports when they are a surprise, but neither is
        // an error, so neither earns a permanent banner.
        DependentBlock(visible = chain.usesTor) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            TorNoteRow()
        }
    }
}

@Composable
private fun TorNoteRow() {
    val tradeoffs = stringResource(R.string.tor_tradeoffs)
    val bridges = stringResource(R.string.chain_tor_bridges_hint)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.HourglassEmpty, null, Modifier.size(16.dp), LocalAetherAccents.current.working)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.chain_tor_options),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoTip("$tradeoffs\n\n$bridges")
    }
}

/** The Psiphon fields the Chain page edits, carried together to the options block. */
private data class PsiphonUi(
    val region: String,
    val protocol: PsiphonProtocol,
    val cdnEdgeIps: String,
    val cdnSni: String,
)

/** Every core alone: Aether, Psiphon, Tor, SSTP and Xray, in declaration order. */
private val SingleCoreModes: List<ChainMode> = ChainMode.entries.filterNot { it.isChained }

/** Every stacking, Xray's included, in declaration order. */
private val StackedModes: List<ChainMode> = ChainMode.entries.filter { it.isChained }

/**
 * One mode row, plus - for the mode that is the current selection - the
 * options of the cores it runs, opened right underneath it: Psiphon's for a
 * Psiphon mode, the Xray config for an Xray mode (both, for Psiphon over Xray).
 */
@Composable
private fun ColumnScope.ChainModeEntry(
    mode: ChainMode,
    selected: ChainMode,
    psiphon: PsiphonUi,
    cores: CoreAvailability.Snapshot,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    ChainModeCard(mode, selected, cores, enabled, edit)
    if (mode.usesPsiphon) {
        DependentBlock(visible = mode == selected) {
            PsiphonOptions(
                psiphon = psiphon,
                // Psiphon over Aether: its upstream is a SOCKS5 hop, no UDP.
                viaUpstream = mode.upstreamOf(Hop.PSIPHON) != null,
                serverListBundled = cores.psiphonServerList,
                enabled = enabled,
                edit = edit,
            )
        }
    }
    if (mode.usesXray) {
        DependentBlock(visible = mode == selected) {
            XrayOptions(
                binaryBundled = cores.xrayBinary,
                alone = !mode.isChained,
                enabled = enabled,
            )
        }
    }
}

/**
 * Psiphon's own options, shown under the selected Psiphon mode: a warning only
 * if this build cannot start Psiphon, then where it comes out, then which
 * protocols it may use.
 */
@Composable
private fun PsiphonOptions(
    psiphon: PsiphonUi,
    viaUpstream: Boolean,
    serverListBundled: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    val accents = LocalAetherAccents.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
    ) {
        // The one payload fact that can still stop Psiphon.
        if (!serverListBundled) {
            NoticeBar(
                text = stringResource(R.string.psiphon_serverlist_missing),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
        }

        PsiphonExitField(
            value = psiphon.region,
            onValueChange = { code -> edit { copy(psiphonRegion = code) } },
            enabled = enabled,
        )

        Spacer(Modifier.height(EngineSpacing.Field))
        PsiphonCdnFrontingFields(
            protocol = psiphon.protocol,
            edgeIps = psiphon.cdnEdgeIps,
            sni = psiphon.cdnSni,
            viaUpstream = viaUpstream,
            enabled = enabled,
            edit = edit,
        )
    }
}

/** How long the Xray field waits after the last change before saving it. */
private const val XRAY_SAVE_DEBOUNCE_MS = 400L

/**
 * Outlives the Chain page, so a paste made right before leaving it is still
 * saved (see [XrayOptions]). SupervisorJob: one failed write must not cancel
 * the next one.
 */
private val XrayFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The Xray hop's one input: the user's own server config, shown under the
 * selected Xray mode - stacked, or [alone] in [ChainMode.XRAY].
 *
 * ### Why this is not a profile field
 *
 * A share link IS a credential (see [XrayConfigStore]). The profile is
 * exported, travels in the service Intent and sits in DataStore, so the config
 * is read from and written to its own on-device store here, off the main
 * thread, and never goes through [ProfileEdit]. The session reads the same
 * store when the Xray hop starts (studio.cluvex.aether.vpn.session.ChainStack).
 *
 * ### Saving
 *
 * Debounced: a paste is one change, typing is many. Whatever is still pending
 * when the block leaves the screen is flushed on [XrayFlushScope], so backing
 * out right after a paste does not lose it. A write that failed is said so,
 * instead of looking saved and failing at the next connect.
 *
 * ### Plain text
 *
 * Not masked: a normal keyboard, the config readable on screen, several lines
 * so a JSON or subscription paste can be checked before connecting. Bounded in
 * height and scrolls inside itself. What the paste was read as is shown as one
 * compact line underneath ([XrayOutbound.summary]); only an unparseable or
 * unsaved config earns a NoticeBar.
 *
 * Locked like every other field while a session is up or on its way: the
 * running Xray already read its config, so an edit could only look applied.
 */
@Composable
private fun XrayOptions(
    binaryBundled: Boolean,
    alone: Boolean,
    enabled: Boolean,
) {
    val accents = LocalAetherAccents.current
    val context = LocalContext.current
    val store = remember(context) { XrayConfigStore(context.applicationContext) }
    // remember, NOT rememberSaveable: the saved-state bundle is no place for a
    // credential, and the on-device store is the copy that survives anyway.
    var text by remember(store) { mutableStateOf("") }
    var loaded by remember(store) { mutableStateOf(false) }
    var saved by remember(store) { mutableStateOf("") }
    var saveFailed by remember(store) { mutableStateOf(false) }

    LaunchedEffect(store) {
        val stored = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrDefault("") }
        text = stored
        saved = stored.trim()
        loaded = true
    }
    LaunchedEffect(store, text, loaded) {
        if (!loaded || text.trim() == saved) return@LaunchedEffect
        delay(XRAY_SAVE_DEBOUNCE_MS)
        val pending = text
        val ok = withContext(Dispatchers.IO) { runCatching { store.write(pending) }.getOrDefault(false) }
        if (ok) saved = pending.trim()
        saveFailed = !ok
    }
    DisposableEffect(store) {
        onDispose {
            val pending = text
            if (loaded && pending.trim() != saved) {
                XrayFlushScope.launch { runCatching { store.write(pending) } }
            }
        }
    }

    val parsed = remember(text) { XrayLink.parse(text) }
    val info = listOf(
        stringResource(R.string.xray_config_help),
        stringResource(if (alone) R.string.xray_alone_note else R.string.xray_chain_note),
        stringResource(R.string.xray_config_stored),
    ).joinToString("\n\n")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (!binaryBundled) {
            NoticeBar(
                text = stringResource(R.string.xray_missing),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
        }

        ProfileTextField(
            value = text,
            onValueChange = { value -> if (loaded) text = value.take(XrayLink.MAX_LENGTH) },
            label = stringResource(R.string.xray_config_label),
            placeholder = stringResource(R.string.xray_config_hint),
            info = info,
            singleLine = false,
            masked = false,
            enabled = enabled && loaded,
            modifier = Modifier.heightIn(max = XRAY_FIELD_MAX_HEIGHT),
        )

        if (loaded && text.isNotBlank()) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            if (parsed == null) {
                NoticeBar(
                    text = stringResource(R.string.xray_config_invalid),
                    tone = accents.failed,
                    icon = Icons.Rounded.Warning,
                )
            } else {
                Text(
                    text = stringResource(R.string.xray_config_ok, xrayReadout(parsed)),
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.protected,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (saveFailed) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            NoticeBar(
                text = stringResource(R.string.xray_config_not_saved),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
        }
    }
}

/** Tallest the Xray field grows before it scrolls inside itself. */
private val XRAY_FIELD_MAX_HEIGHT = 240.dp

/**
 * What the paste was read as: its own name (when it has one) and the
 * secret-free summary, isolated LTR so a Persian UI does not reorder the
 * address and the dots.
 */
private fun xrayReadout(outbound: XrayOutbound): String {
    val remark = outbound.remark.trim()
    val text = if (remark.isEmpty() || remark == outbound.summary) {
        outbound.summary
    } else {
        "$remark \u00B7 ${outbound.summary}"
    }
    return "\u2066$text\u2069"
}

/**
 * Which protocols Psiphon may use, and the CDN edges / SNI for fronted meek.
 * See [PsiphonProtocol] and docs/CDN_FRONTING.md.
 *
 * The edge field is free text and silently drops what it cannot use (see
 * [PsiphonCdnFronting.parseIpCandidates]), so the number of entries that WILL
 * be used is shown under it - inline validation, not explanation, so it stays.
 */
@Composable
private fun ColumnScope.PsiphonCdnFrontingFields(
    protocol: PsiphonProtocol,
    edgeIps: String,
    sni: String,
    viaUpstream: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    // Resolved OUTSIDE the label lambda, like the exit field: the dropdown
    // renders that lambda once per row.
    val labels = mapOf(
        PsiphonProtocol.AUTO to stringResource(R.string.psiphon_protocol_auto),
        PsiphonProtocol.DIRECT to stringResource(R.string.psiphon_protocol_direct),
        PsiphonProtocol.CDN_FRONTING to stringResource(R.string.psiphon_protocol_cdn),
    )
    val description = when (protocol) {
        PsiphonProtocol.AUTO -> stringResource(R.string.psiphon_protocol_auto_desc)
        PsiphonProtocol.DIRECT -> stringResource(R.string.psiphon_protocol_direct_desc)
        PsiphonProtocol.CDN_FRONTING -> stringResource(R.string.psiphon_protocol_cdn_desc)
    }
    val quicNote = if (viaUpstream && protocol != PsiphonProtocol.AUTO) {
        "\n\n" + stringResource(R.string.psiphon_cdn_quic_note)
    } else {
        ""
    }

    FieldLabel(stringResource(R.string.psiphon_protocol_label), info = description + quicNote)
    DropdownSelector(
        options = PsiphonProtocol.entries.toList(),
        selected = protocol,
        onSelect = { choice -> edit { copy(psiphonProtocol = choice) } },
        label = { choice -> labels[choice] ?: choice.name },
        enabled = enabled,
    )

    Spacer(Modifier.height(EngineSpacing.Field))
    val accepted = remember(edgeIps) { PsiphonCdnFronting.parseIpCandidates(edgeIps).size }
    ProfileTextField(
        value = edgeIps,
        onValueChange = { value -> edit { copy(psiphonCdnEdgeIps = value) } },
        label = stringResource(R.string.psiphon_cdn_ips_label),
        placeholder = stringResource(R.string.psiphon_cdn_ips_hint),
        singleLine = false,
        enabled = enabled,
    )
    if (edgeIps.isNotBlank()) {
        Hint(stringResource(R.string.psiphon_cdn_ips_count, accepted))
    }
    Spacer(Modifier.height(EngineSpacing.Inline))
    ProfileTextField(
        value = sni,
        onValueChange = { value -> edit { copy(psiphonCdnSni = value) } },
        label = stringResource(R.string.psiphon_cdn_sni_label),
        placeholder = stringResource(R.string.psiphon_cdn_sni_hint),
        info = stringResource(R.string.psiphon_cdn_help),
        singleLine = true,
        enabled = enabled,
    )
}

/**
 * One mode, compact: radio, name, literal path, cost badge, and - only when
 * this build cannot run it - which core is missing.
 */
@Composable
private fun ChainModeCard(
    mode: ChainMode,
    selected: ChainMode,
    cores: CoreAvailability.Snapshot,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    val accents = LocalAetherAccents.current
    val missing = cores.missing(mode)
    val runnable = missing.isEmpty()
    val active = mode == selected
    // Dimmed AND unselectable: offering a mode that cannot start is the defect
    // this page exists to remove.
    val alpha = if (runnable) 1f else 0.45f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = active,
                enabled = enabled && runnable,
                role = Role.RadioButton,
                onClick = { edit { copy(chain = mode) } },
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) accents.brandWash else Color.Transparent,
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = (if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = alpha),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = chainLabel(mode),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Always LTR: the hops are product names and an arrow, and a
                // mirrored arrow would reverse the meaning of the whole line.
                Text(
                    text = mode.pathLabel(),
                    style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!runnable) {
                    Text(
                        text = stringResource(
                            R.string.chain_mode_unavailable,
                            missing.joinToString(", ") { it.label },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = accents.failed,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CostBadge(mode)
        }
    }
}

/**
 * Fast / moderate / slow, from the hops themselves.
 *
 * Not a benchmark: it is the ordering a user needs in order not to reach for
 * the three-core chain first. Tor is always the slowest thing in any chain that
 * has it; Xray adds one proxy hop and no bootstrap, so it does not move a mode
 * out of its tier - and Xray alone is fast.
 */
@Composable
private fun CostBadge(mode: ChainMode) {
    val accents = LocalAetherAccents.current
    val label: String
    val tone: Color
    when {
        mode.usesTor -> {
            label = stringResource(R.string.chain_tier_slow)
            tone = accents.failed
        }
        mode.usesPsiphon -> {
            label = stringResource(R.string.chain_tier_moderate)
            tone = accents.working
        }
        else -> {
            label = stringResource(R.string.chain_tier_fast)
            tone = accents.protected
        }
    }
    Surface(color = tone.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = AetherMetaLabel,
            color = tone,
        )
    }
}

/**
 * Psiphon's exit country: tap, and pick from the countries Psiphon can reach.
 *
 * `EgressRegion` is a HARD filter in psiphon-tunnel-core, so a code Psiphon has
 * no reachable servers in does not degrade to "anywhere" - it hangs
 * establishment until the budget runs out. So the list IS the input: every row
 * carries its flag, Automatic is the first row and the default, and there is no
 * way to enter a value the core will silently ignore. The help and the fallback
 * note are behind the (?).
 */
@Composable
private fun ColumnScope.PsiphonExitField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    // Resolved OUTSIDE the label lambda: it is a string resource, and the
    // dropdown renders that lambda once per row.
    val automatic = "${PsiphonRegions.GLOBE}  " + stringResource(R.string.psiphon_region_auto)
    val info = stringResource(R.string.psiphon_region_help) + "\n\n" + stringResource(R.string.psiphon_region_fallback)
    FieldLabel(stringResource(R.string.psiphon_region_label), info = info)
    DropdownSelector(
        options = PsiphonRegions.codes,
        // Sanitised, so a code saved by an older build (or imported in a config)
        // that is no longer offered shows as Automatic instead of as a row that
        // is not in the list.
        selected = PsiphonRegions.sanitize(value),
        onSelect = onValueChange,
        label = { code ->
            if (code == PsiphonRegions.AUTOMATIC) automatic else PsiphonRegions.label(code)
        },
        enabled = enabled,
    )
}
