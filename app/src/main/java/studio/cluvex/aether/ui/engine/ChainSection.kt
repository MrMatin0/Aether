package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Public
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
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
import studio.cluvex.aether.ui.chainDescription
import studio.cluvex.aether.ui.chainLabel
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionRule
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
 * ### Why this page is cards and not a dropdown
 *
 * Three facts decide whether one of these modes is the right choice, and a
 * dropdown could show none of them at once:
 *
 *  1. **Is the core even in this build.** Psiphon, Tor and Xray are optional; a
 *     build without them is valid. That used to be discovered by picking the
 *     mode, tapping Connect, and reading an error - after the app had already
 *     torn down and rebuilt its natives. Unavailable modes are now disabled
 *     here, with the missing core named.
 *  2. **What it costs.** Every added hop is latency, and Tor is minutes of
 *     bootstrap. A user picking blind picks the biggest chain, which is the
 *     slowest one, and then concludes the app is broken.
 *  3. **Which direction "over" runs.** "Over" reads both ways in English and in
 *     Persian, so the literal path is printed on every card rather than once
 *     under the selection.
 *
 * ### Where the per-core options live
 *
 * Directly under the selected mode card, not in a block at the bottom of the
 * page. The Psiphon exit country (and its protocol / CDN fronting options)
 * belongs to the choice the user just made, and so does the Xray config: with
 * a dozen cards in between, a block at the bottom was easy to never see. Only
 * the SELECTED card opens them, so the page never shows two editors for one
 * setting.
 *
 * ### What the Psiphon block used to be
 *
 * A multi-line box asking the user to paste a Psiphon client config as JSON.
 * `PropagationChannelId` and `SponsorId` are issued by the Psiphon network, so
 * that field was asking for something no user of this app can obtain - and with
 * nothing pasted and nothing bundled, every Psiphon mode failed before the core
 * was even started. It is gone: [studio.cluvex.aether.core.PsiphonCore] carries
 * its own config, and the only thing left to choose is where to come out.
 *
 * The Xray block is the opposite case on purpose: there the config IS the
 * user's (their own server), so pasting it is the whole point. See
 * [XrayOptions].
 *
 * ### Why Tor has no exit country any more
 *
 * It needed the geoip database, it was a free-typed two-letter box, and pinning
 * Tor's exit only narrows the relay set on networks where Tor is already the
 * slowest thing in the app. The field (and the StrictNodes switch that only
 * existed for it) is gone, and [studio.cluvex.aether.core.TorCore] no longer
 * writes either option, so a value saved by an older build cannot keep pinning
 * the exit with no UI left to clear it.
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

    EngineSection(
        title = stringResource(R.string.section_chain),
        subtitle = stringResource(R.string.section_chain_note),
        icon = Icons.Rounded.Layers,
        modifier = modifier,
    ) {
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
            Spacer(Modifier.height(EngineSpacing.Field))
        }

        // ONE radio group for every mode: the two headings group them
        // visually without making them two independent selections.
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            FieldLabel(stringResource(R.string.chain_group_single))
            SingleCoreModes.forEach { mode ->
                ChainModeEntry(mode, chain, psiphon, cores, enabled, edit)
            }
            Spacer(Modifier.height(EngineSpacing.Field))
            FieldLabel(stringResource(R.string.chain_group_stacked))
            Hint(stringResource(R.string.chain_group_stacked_note))
            Spacer(Modifier.height(EngineSpacing.Inline))
            StackedModes.forEach { mode ->
                ChainModeEntry(mode, chain, psiphon, cores, enabled, edit)
            }
        }

        DependentBlock(visible = chain.usesTor) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SectionRule(stringResource(R.string.chain_tor_options), topSpace = 24)

            // Said here rather than in docs/CHAINING.md: both of these turn into
            // "the app is broken" bug reports when they are a surprise.
            NoticeBar(
                text = stringResource(R.string.tor_tradeoffs),
                tone = accents.working,
                icon = Icons.Rounded.HourglassEmpty,
            )

            // Bridges are their own destination, and this is where a user finds
            // out they exist: the card above says Tor is blocked outright on many
            // Iranian networks, and without this that is a dead end.
            Hint(stringResource(R.string.chain_tor_bridges_hint))
        }
    }
}

/** The Psiphon fields the Chain page edits, carried together to the options block. */
private data class PsiphonUi(
    val region: String,
    val protocol: PsiphonProtocol,
    val cdnEdgeIps: String,
    val cdnSni: String,
)

/** Every core alone: Aether, Psiphon, Tor, SSTP. Xray is never offered alone. */
private val SingleCoreModes: List<ChainMode> = ChainMode.entries.filterNot { it.isChained }

/** Every stacking, Xray's included, in declaration order. */
private val StackedModes: List<ChainMode> = ChainMode.entries.filter { it.isChained }

/**
 * One mode card, plus - for the mode that is the current selection - the
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
                enabled = enabled,
            )
        }
    }
}

/**
 * Psiphon's own options, shown under the selected Psiphon mode: whether this
 * build can start Psiphon at all, then where it comes out, then which
 * protocols it may use.
 *
 * Indented and bracketed by small gaps so it reads as belonging to the card
 * above it rather than as the next card.
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
            .padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        // The one payload fact that can still stop Psiphon. Said here rather
        // than discovered from a three-minute connect attempt.
        if (serverListBundled) {
            NoticeBar(
                text = stringResource(R.string.psiphon_ready),
                tone = accents.protected,
                icon = Icons.Rounded.CheckCircle,
            )
        } else {
            NoticeBar(
                text = stringResource(R.string.psiphon_serverlist_missing),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
        }

        Spacer(Modifier.height(EngineSpacing.Field))
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

/** How long the Xray field waits after the last change before sealing it. */
private const val XRAY_SAVE_DEBOUNCE_MS = 400L

/**
 * Outlives the Chain page, so a paste made right before leaving it is still
 * sealed (see [XrayOptions]). SupervisorJob: one failed write must not cancel
 * the next one.
 */
private val XrayFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The Xray hop's one input: the user's own server config, shown under the
 * selected Xray mode.
 *
 * ### Why this is not a profile field
 *
 * A share link IS a credential (see [XrayConfigStore]). The profile is
 * exported, travels in the service Intent and sits in DataStore in plaintext,
 * so the config is read from and written to the Keystore-sealed store here,
 * off the main thread, and never goes through [ProfileEdit]. The session reads
 * the same store when the Xray hop starts
 * (studio.cluvex.aether.vpn.session.ChainStack).
 *
 * ### Saving
 *
 * Debounced: a paste is one change, typing is many, and every save is a
 * Keystore operation. Whatever is still pending when the block leaves the
 * screen is flushed on [XrayFlushScope], so backing out right after a paste
 * does not lose it. A write the Keystore refused is said so, instead of
 * looking saved and failing at the next connect.
 *
 * ### Masked
 *
 * Like the Zero Trust secrets: a password keyboard (no suggestions learned
 * from a UUID) and dots on screen (nothing readable over a shoulder or in a
 * screenshot). What the paste was read as is shown underneath instead,
 * without the secret ([XrayOutbound.summary]).
 *
 * Locked like every other field while a session is up or on its way: the
 * running Xray already read its config, so an edit could only look applied.
 */
@Composable
private fun XrayOptions(
    binaryBundled: Boolean,
    enabled: Boolean,
) {
    val accents = LocalAetherAccents.current
    val context = LocalContext.current
    val store = remember(context) { XrayConfigStore(context.applicationContext) }
    // remember, NOT rememberSaveable: the saved-state bundle is no place for a
    // credential, and the sealed store is the copy that survives anyway.
    var text by remember(store) { mutableStateOf("") }
    var loaded by remember(store) { mutableStateOf(false) }
    var sealed by remember(store) { mutableStateOf("") }
    var saveFailed by remember(store) { mutableStateOf(false) }

    LaunchedEffect(store) {
        val stored = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrDefault("") }
        text = stored
        sealed = stored.trim()
        loaded = true
    }
    LaunchedEffect(store, text, loaded) {
        if (!loaded || text.trim() == sealed) return@LaunchedEffect
        delay(XRAY_SAVE_DEBOUNCE_MS)
        val pending = text
        val ok = withContext(Dispatchers.IO) { runCatching { store.write(pending) }.getOrDefault(false) }
        if (ok) sealed = pending.trim()
        saveFailed = !ok
    }
    DisposableEffect(store) {
        onDispose {
            val pending = text
            if (loaded && pending.trim() != sealed) {
                XrayFlushScope.launch { runCatching { store.write(pending) } }
            }
        }
    }

    val parsed = remember(text) { XrayLink.parse(text) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
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
            helpText = stringResource(R.string.xray_config_help),
            singleLine = true,
            masked = true,
            enabled = enabled && loaded,
        )

        if (loaded) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            when {
                text.isBlank() -> NoticeBar(
                    text = stringResource(R.string.xray_config_empty),
                    tone = accents.working,
                    icon = Icons.Rounded.Info,
                )
                parsed == null -> NoticeBar(
                    text = stringResource(R.string.xray_config_invalid),
                    tone = accents.failed,
                    icon = Icons.Rounded.Warning,
                )
                else -> NoticeBar(
                    text = stringResource(R.string.xray_config_ok, xrayReadout(parsed)),
                    tone = accents.protected,
                    icon = Icons.Rounded.CheckCircle,
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
        Spacer(Modifier.height(EngineSpacing.Inline))
        Hint(stringResource(R.string.xray_chain_note))
        Spacer(Modifier.height(EngineSpacing.Inline))
        Hint(stringResource(R.string.xray_config_stored))
    }
}

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
 * be used is shown under it - otherwise a typo would look like a setting that
 * does nothing.
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

    FieldLabel(stringResource(R.string.psiphon_protocol_label))
    Spacer(Modifier.height(EngineSpacing.Inline))
    DropdownSelector(
        options = PsiphonProtocol.entries.toList(),
        selected = protocol,
        onSelect = { choice -> edit { copy(psiphonProtocol = choice) } },
        label = { choice -> labels[choice] ?: choice.name },
        enabled = enabled,
    )
    Spacer(Modifier.height(EngineSpacing.Inline))
    Hint(description)

    if (viaUpstream && protocol != PsiphonProtocol.AUTO) {
        Spacer(Modifier.height(EngineSpacing.Inline))
        NoticeBar(
            text = stringResource(R.string.psiphon_cdn_quic_note),
            tone = LocalAetherAccents.current.working,
            icon = Icons.Rounded.Public,
        )
    }

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
        singleLine = true,
        enabled = enabled,
    )
    Hint(stringResource(R.string.psiphon_cdn_help))
}

/**
 * One mode, with everything needed to choose it: name, cost badge, literal path,
 * purpose, and - when this build cannot run it - which core is missing.
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
            .padding(vertical = 4.dp)
            .selectable(
                selected = active,
                enabled = enabled && runnable,
                role = Role.RadioButton,
                onClick = { edit { copy(chain = mode) } },
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (active) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
                tint = (if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = alpha),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chainLabel(mode),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    Spacer(Modifier.width(8.dp))
                    CostBadge(mode)
                }
                Spacer(Modifier.height(6.dp))
                // Always LTR: the hops are product names and an arrow, and a
                // mirrored arrow would reverse the meaning of the whole line.
                Text(
                    text = mode.pathLabel(),
                    style = MaterialTheme.typography.bodySmall
                        .copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.85f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = chainDescription(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
                if (!runnable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.chain_mode_unavailable,
                            missing.joinToString(", ") { it.label },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = accents.failed,
                    )
                }
            }
        }
    }
}

/**
 * Fast / moderate / slow, from the hops themselves.
 *
 * Not a benchmark and not pretending to be one: it is the ordering a user needs
 * in order not to reach for the three-core chain first, and the ordering is a
 * property of the modes (Tor is always the slowest thing in any chain that has
 * it) rather than of the network. Xray adds one proxy hop to the user's own
 * server and no bootstrap, so it does not move a mode out of its tier.
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = AetherMetaLabel,
            color = tone,
        )
    }
}

/**
 * Psiphon's exit country: tap, and pick from the countries Psiphon can reach.
 *
 * ### Why this is not a text field
 *
 * `EgressRegion` is a HARD filter in psiphon-tunnel-core, so a code Psiphon has
 * no reachable servers in does not degrade to "anywhere" - it hangs
 * establishment until the budget runs out. And the set of codes that mean
 * anything is a fixed list, which a keyboard cannot express: `IR`, `EN` and `UK`
 * are all things a user would reasonably type and none of them is a Psiphon
 * exit.
 *
 * So the list IS the input. Every row carries its flag (derived from the code,
 * see [PsiphonRegions]), Automatic is the first row and the default, and there is
 * no way to enter a value the core will silently ignore.
 *
 * A [ColumnScope] receiver because this is a vertical run of four rows - label,
 * picker, hint, fallback notice - and not a single widget.
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
    FieldLabel(stringResource(R.string.psiphon_region_label))
    Spacer(Modifier.height(EngineSpacing.Inline))
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
    Spacer(Modifier.height(EngineSpacing.Inline))
    Hint(stringResource(R.string.psiphon_region_help))
    Spacer(Modifier.height(EngineSpacing.Inline))
    NoticeBar(
        text = stringResource(R.string.psiphon_region_fallback),
        tone = LocalAetherAccents.current.working,
        icon = Icons.Rounded.Public,
    )
}
