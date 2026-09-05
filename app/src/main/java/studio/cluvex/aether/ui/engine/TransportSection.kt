package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.noizeLabel

/**
 * How the traffic is shaped on the wire: obfuscation, packet sizes, TLS tricks,
 * resolvers. Expert depth only.
 *
 * ROOT CAUSE THIS FIXES (usability): the fragment switch lived here, but the two
 * inputs that only mean anything WHEN it is on - chunk size and inter-fragment
 * delay - lived in the Engine tuning card, two cards and roughly a screen and a
 * half further down. Turning fragmentation on made two fields appear somewhere
 * the user was not looking, and reading the tuning card top to bottom showed two
 * inputs whose owning switch was nowhere on screen. They are one setting, so
 * they are now one group, and the switch sits last in the stack so its extra
 * inputs do not split the row rhythm above it.
 */
@Composable
internal fun TransportSection(
    noize: Noize,
    keepalive: Int,
    mtu: Int,
    fragment: Boolean,
    fragmentSize: String,
    fragmentDelay: String,
    ech: Boolean,
    masqueHttp2: Boolean,
    dnsServers: String,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineSection(
        title = stringResource(R.string.section_transport),
        subtitle = stringResource(R.string.section_transport_note),
        icon = Icons.Rounded.Tune,
        modifier = modifier,
    ) {
        FieldLabel(stringResource(R.string.noize_title))
        DropdownSelector(
            options = Noize.entries,
            selected = noize,
            onSelect = { value -> edit { copy(noize = value) } },
            label = { noizeLabel(it) },
            enabled = enabled,
        )
        Hint(stringResource(R.string.noize_desc))

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.keepalive_label))
        DropdownSelector(
            options = ConnectionProfile.KEEPALIVE_PRESETS,
            selected = keepalive,
            onSelect = { value -> edit { copy(keepalive = value) } },
            label = { if (it == 0) stringResource(R.string.keepalive_default) else "$it" },
            enabled = enabled,
        )

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.mtu_label))
        DropdownSelector(
            options = ConnectionProfile.MTU_PRESETS,
            selected = mtu,
            onSelect = { value -> edit { copy(mtu = value) } },
            label = { "$it" },
            enabled = enabled,
        )
        Hint(stringResource(R.string.mtu_desc))

        Spacer(Modifier.height(EngineSpacing.Divider))
        EngineDivider()
        SwitchRow(
            title = stringResource(R.string.ech_title),
            description = stringResource(R.string.ech_desc),
            checked = ech,
            enabled = enabled,
            onChange = { value -> edit { copy(ech = value) } },
        )
        EngineDivider()
        SwitchRow(
            title = stringResource(R.string.masque_http2),
            description = stringResource(R.string.masque_http2_desc),
            checked = masqueHttp2,
            enabled = enabled,
            onChange = { value -> edit { copy(masqueHttp2 = value) } },
        )
        EngineDivider()
        SwitchRow(
            title = stringResource(R.string.fragment_title),
            description = stringResource(R.string.fragment_desc),
            checked = fragment,
            enabled = enabled,
            onChange = { value -> edit { copy(fragment = value) } },
        )
        DependentBlock(visible = fragment) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = fragmentSize,
                onValueChange = { value -> edit { copy(fragmentSize = value) } },
                label = stringResource(R.string.fragment_size_label),
                placeholder = stringResource(R.string.fragment_size_hint),
                enabled = enabled,
            )
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = fragmentDelay,
                onValueChange = { value -> edit { copy(fragmentDelay = value) } },
                label = stringResource(R.string.fragment_delay_label),
                placeholder = stringResource(R.string.fragment_delay_hint),
                enabled = enabled,
            )
        }

        Spacer(Modifier.height(EngineSpacing.Field))
        ProfileTextField(
            value = dnsServers,
            onValueChange = { value -> edit { copy(dnsServers = value) } },
            label = stringResource(R.string.dns_label),
            placeholder = stringResource(R.string.dns_hint),
            helpText = stringResource(R.string.dns_help),
            enabled = enabled,
        )
    }
}
