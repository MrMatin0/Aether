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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
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
import org.json.JSONObject
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.ui.chainDescription
import studio.cluvex.aether.ui.chainLabel
import studio.cluvex.aether.ui.components.ActionPill
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
 */
@Composable
internal fun ChainSection(
    chain: ChainMode,
    psiphonRegion: String,
    psiphonConfig: String,
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
        // an egress region for a core that is not running is noise.
        DependentBlock(visible = chain.usesPsiphon) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SectionRule(stringResource(R.string.chain_psiphon_options), topSpace = 24)

            val pasted = psiphonConfig.isNotBlank()
            // The core is the authority on what a valid config is, so this only
            // asks "does it parse as a JSON object" - which is also exactly the
            // test PsiphonCore.baseConfig applies before it silently falls back
            // to the bundled asset. Doing it here means the user finds out while
            // they are looking at the field.
            val validJson = remember(psiphonConfig) {
                !pasted || runCatching { JSONObject(psiphonConfig) }.isSuccess
            }
            when {
                pasted && !validJson -> NoticeBar(
                    text = stringResource(R.string.psiphon_config_invalid),
                    tone = accents.failed,
                    icon = Icons.Rounded.Warning,
                )
                pasted -> NoticeBar(
                    text = stringResource(R.string.psiphon_config_pasted),
                    tone = accents.protected,
                    icon = Icons.Rounded.CheckCircle,
                )
                cores.psiphonConfigBundled -> NoticeBar(
                    text = stringResource(R.string.psiphon_config_bundled),
                    tone = accents.protected,
                    icon = Icons.Rounded.CheckCircle,
                )
                else -> NoticeBar(
                    text = stringResource(R.string.psiphon_config_absent),
                    tone = accents.working,
                    icon = Icons.Rounded.Info,
                )
            }

            Spacer(Modifier.height(EngineSpacing.Field))
            ProfileTextField(
                value = psiphonConfig,
                onValueChange = { value -> edit { copy(psiphonConfig = value) } },
                label = stringResource(R.string.psiphon_config_label),
                placeholder = stringResource(R.string.psiphon_config_hint),
                helpText = if (pasted && !validJson) {
                    stringResource(R.string.psiphon_config_invalid_help)
                } else {
                    stringResource(R.string.psiphon_config_help)
                },
                singleLine = false,
                enabled = enabled,
            )
            if (pasted) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                ActionPill(
                    label = stringResource(R.string.psiphon_config_clear),
                    onClick = { edit { copy(psiphonConfig = "") } },
                    icon = Icons.Rounded.Delete,
                    enabled = enabled,
                    tint = accents.failed,
                )
            }

            Spacer(Modifier.height(EngineSpacing.Field))
            CountryField(
                value = psiphonRegion,
                onValueChange = { value -> edit { copy(psiphonRegion = value) } },
                label = stringResource(R.string.psiphon_region_label),
                placeholder = stringResource(R.string.psiphon_region_hint),
                helpText = stringResource(R.string.psiphon_region_help),
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
 * A two-letter country code and nothing else.
 *
 * Both cores take ISO 3166-1 alpha-2 and both silently ignore anything else
 * (PsiphonCore.sanitizedRegion drops it, tor's torrc never receives it), so a
 * free-text field let the user type "Germany", see it persisted, and get no exit
 * country with no explanation. Constraining the input is the difference between
 * a setting that works and a setting that looks like it works.
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
