package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.ui.chainDescription
import studio.cluvex.aether.ui.chainLabel
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionRule
import studio.cluvex.aether.ui.components.SwitchRow
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
 * Three facts decide whether one of these seven modes is the right choice, and a
 * dropdown could show none of them at once:
 *
 *  1. **Is the core even in this build.** Psiphon and Tor are optional; a build
 *     without them is valid. That used to be discovered by picking the mode,
 *     tapping Connect, and reading an error - after the app had already torn
 *     down and rebuilt its natives. Unavailable modes are now disabled here,
 *     with the missing core named.
 *  2. **What it costs.** Every added hop is latency, and Tor is minutes of
 *     bootstrap. A user picking blind picks the biggest chain, which is the
 *     slowest one, and then concludes the app is broken.
 *  3. **Which direction "over" runs.** "Over" reads both ways in English and in
 *     Persian, so the literal path is printed on every card rather than once
 *     under the selection.
 *
 * ### What the Psiphon block used to be
 *
 * A multi-line box asking the user to paste a Psiphon client config as JSON.
 * `PropagationChannelId` and `SponsorId` are issued by the Psiphon network, so
 * that field was asking for something no user of this app can obtain - and with
 * nothing pasted and nothing bundled, every Psiphon mode failed before the core
 * was even started. It is gone: [studio.cluvex.aether.core.PsiphonCore] carries
 * its own config, and the only thing left to choose is where to come out.
 */
@Composable
internal fun ChainSection(
    chain: ChainMode,
    psiphonRegion: String,
    torExitCountry: String,
    torStrictNodes: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val context = LocalContext.current
    // Install-time facts: read once, never per recomposition.
    val cores = remember(context) { CoreAvailability.of(context) }

    EngineSection(
        title = stringResource(R.string.section_chain),
        subtitle = stringResource(R.string.section_chain_note),
        icon = Icons.Rounded.Layers,
        modifier = modifier,
    ) {
        val absent = buildList {
            if (!cores.psiphonBinary) add(Hop.PSIPHON.label)
            if (!cores.torBinary) add(Hop.TOR.label)
        }
        if (absent.isNotEmpty()) {
            NoticeBar(
                text = stringResource(R.string.chain_build_missing, absent.joinToString(", ")),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
        }

        // ONE radio group for all seven modes: the two headings group them
        // visually without making them two independent selections.
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            FieldLabel(stringResource(R.string.chain_group_single))
            SingleCoreModes.forEach { mode ->
                ChainModeCard(mode, chain, cores, enabled, edit)
            }
            Spacer(Modifier.height(EngineSpacing.Field))
            FieldLabel(stringResource(R.string.chain_group_stacked))
            Hint(stringResource(R.string.chain_group_stacked_note))
            Spacer(Modifier.height(EngineSpacing.Inline))
            StackedModes.forEach { mode ->
                ChainModeCard(mode, chain, cores, enabled, edit)
            }
        }

        // Psiphon's own options: only shown when a Psiphon hop exists, because
        // an exit country for a core that is not running is noise.
        DependentBlock(visible = chain.usesPsiphon) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SectionRule(stringResource(R.string.chain_psiphon_options), topSpace = 24)

            // The one payload fact that can still stop Psiphon. Said here rather
            // than discovered from a three-minute connect attempt.
            if (cores.psiphonServerList) {
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
                value = psiphonRegion,
                onValueChange = { code -> edit { copy(psiphonRegion = code) } },
                enabled = enabled,
            )
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

            Spacer(Modifier.height(EngineSpacing.Field))
            CountryField(
                value = torExitCountry,
                onValueChange = { value -> edit { copy(torExitCountry = value) } },
                label = stringResource(R.string.tor_exit_label),
                placeholder = stringResource(R.string.tor_exit_hint),
                helpText = if (cores.torGeoipBundled) {
                    stringResource(R.string.tor_exit_help)
                } else {
                    stringResource(R.string.tor_exit_no_geoip)
                },
                // Without the database tor cannot map relays to countries and
                // ignores the request, so an editable field here would be a
                // setting that pretends to work.
                enabled = enabled && cores.torGeoipBundled,
            )

            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.tor_strict_title),
                description = stringResource(R.string.tor_strict_desc),
                checked = torStrictNodes,
                enabled = enabled && cores.torGeoipBundled && torExitCountry.isNotBlank(),
                onChange = { value -> edit { copy(torStrictNodes = value) } },
            )
        }
    }
}

/** Aether alone, Psiphon alone, Tor alone. */
private val SingleCoreModes: List<ChainMode> = ChainMode.entries.filterNot { it.isChained }

/** The four stackings, cheapest first - which is also fewest-hops first. */
private val StackedModes: List<ChainMode> = ChainMode.entries.filter { it.isChained }

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
 * it) rather than of the network.
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
 * Psiphon's exit country: tap, and pick from the countries Psiphon publishes.
 *
 * ### Why this is not a text field any more
 *
 * It used to be the same two-letter box Tor still uses, and for Psiphon that was
 * the wrong shape twice over. `EgressRegion` is a HARD filter in
 * psiphon-tunnel-core, so a code Psiphon has no servers in does not degrade to
 * "anywhere" - it hangs establishment until the budget runs out. And the set of
 * codes that mean anything is a fixed, published list, which a keyboard cannot
 * express: `IR`, `EN` and `UK` are all things a user would reasonably type and
 * none of them is a Psiphon exit.
 *
 * So the list IS the input. Every row carries its flag (derived from the code,
 * see [PsiphonRegions]), Automatic is the first row and the default, and there is
 * no way to enter a value the core will silently ignore.
 */
@Composable
private fun PsiphonExitField(
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
        // that Psiphon does not serve shows as Automatic instead of as a row that
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

/**
 * A two-letter country code and nothing else.
 *
 * tor takes ISO 3166-1 alpha-2 and silently ignores anything else (its torrc
 * never receives it), so a free-text field let the user type "Germany", see it
 * persisted, and get no exit country with no explanation. Constraining the input
 * is the difference between a setting that works and a setting that looks like it
 * works.
 *
 * Psiphon used to share this field and no longer does - see [PsiphonExitField]
 * for why a list beats a keyboard when the valid set is published and finite.
 */
@Composable
private fun CountryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    helpText: String,
    enabled: Boolean,
) {
    LtrOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        inputTransform = { raw ->
            raw.filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase().take(2)
        },
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = { Text(helpText) },
        modifier = Modifier.fillMaxWidth(),
    )
}
