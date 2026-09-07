package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.ui.chainDescription
import studio.cluvex.aether.ui.chainLabel
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * WHICH cores carry the session, and their own options.
 *
 * Deliberately its own page rather than a row inside Connection: picking a chain
 * changes what every other page even means (a Tor-only session has no endpoint
 * to scan and no WARP protocol to choose), so it is not a knob that belongs
 * between two engine knobs.
 *
 * The dropdown is followed by two things, always: what the combination is FOR,
 * and the literal path the traffic takes. "Over" reads in both directions in
 * English and in Persian, and a user who guesses the direction wrong will
 * conclude the feature is broken when it is working exactly as asked.
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
    EngineSection(
        title = stringResource(R.string.section_chain),
        subtitle = stringResource(R.string.section_chain_note),
        icon = Icons.Rounded.Layers,
        modifier = modifier,
    ) {
        FieldLabel(stringResource(R.string.chain_mode))
        DropdownSelector(
            options = ChainMode.entries,
            selected = chain,
            onSelect = { value -> edit { copy(chain = value) } },
            label = { chainLabel(it) },
            enabled = enabled,
        )
        Hint(chainDescription(chain))
        Spacer(Modifier.height(EngineSpacing.Inline))
        Hint(stringResource(R.string.chain_path, chain.pathLabel()))

        // Psiphon's own options: only shown when a Psiphon hop exists, because
        // an egress region for a core that is not running is noise.
        DependentBlock(visible = chain.usesPsiphon) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            Spacer(Modifier.height(EngineSpacing.Field))
            ProfileTextField(
                value = psiphonRegion,
                onValueChange = { value -> edit { copy(psiphonRegion = value) } },
                label = stringResource(R.string.psiphon_region_label),
                placeholder = stringResource(R.string.psiphon_region_hint),
                helpText = stringResource(R.string.psiphon_region_help),
                enabled = enabled,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
            ProfileTextField(
                value = psiphonConfig,
                onValueChange = { value -> edit { copy(psiphonConfig = value) } },
                label = stringResource(R.string.psiphon_config_label),
                placeholder = stringResource(R.string.psiphon_config_hint),
                helpText = stringResource(R.string.psiphon_config_help),
                singleLine = false,
                enabled = enabled,
            )
        }

        DependentBlock(visible = chain.usesTor) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            Spacer(Modifier.height(EngineSpacing.Field))
            Hint(stringResource(R.string.tor_note))
            Spacer(Modifier.height(EngineSpacing.Field))
            ProfileTextField(
                value = torExitCountry,
                onValueChange = { value -> edit { copy(torExitCountry = value) } },
                label = stringResource(R.string.tor_exit_label),
                placeholder = stringResource(R.string.tor_exit_hint),
                helpText = stringResource(R.string.tor_exit_help),
                enabled = enabled,
            )
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.tor_strict_title),
                description = stringResource(R.string.tor_strict_desc),
                checked = torStrictNodes,
                enabled = enabled && torExitCountry.isNotBlank(),
                onChange = { value -> edit { copy(torStrictNodes = value) } },
            )
        }
    }
}
