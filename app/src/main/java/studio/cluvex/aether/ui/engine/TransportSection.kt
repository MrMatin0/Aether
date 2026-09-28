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
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.SpoofMode
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.noizeLabel
import studio.cluvex.aether.ui.spoofModeLabel

/**
 * How the traffic is shaped on the wire: obfuscation, packet sizes, TLS/QUIC
 * tricks, resolvers. Expert depth only.
 *
 * SPOOFING lives here rather than on its own page because it is one more way
 * the ClientHello is shaped, next to fragmentation and ECH - the settings it
 * combines with, not a feature to hunt for. The whole group is only shown
 * while the protocol CAN shape a ClientHello at all: the MASQUE transports for
 * the SNI, and the HTTP/2 carrier specifically for the split modes (which is
 * where the reference repo's techniques apply). On WireGuard and gool there is
 * no TLS ClientHello on the wire, so the card says so instead of offering
 * switches that would do nothing.
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
    quicV2Opener: Boolean,
    dnsServers: String,
    socketMark: String,
    protocol: Protocol,
    masqueHttp2: Boolean,
    spoofMode: SpoofMode,
    spoofSni: String,
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
            title = stringResource(R.string.quic_v2_title),
            description = stringResource(R.string.quic_v2_desc),
            checked = quicV2Opener,
            enabled = enabled,
            onChange = { value -> edit { copy(quicV2Opener = value) } },
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

        // ----------------------------------------------------- spoofing --
        // The SNI applies to every MASQUE carrier (H2 and H3 both put it in
        // their ClientHello); the split modes only the HTTP/2 one, whose TCP
        // stream the engine writes itself. A WireGuard session has no
        // ClientHello for any of this, so it gets the explanation rather than
        // dead switches.
        if (protocol.isMasque) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            FieldLabel(stringResource(R.string.spoof_mode_title))
            if (masqueHttp2) {
                DropdownSelector(
                    options = SpoofMode.entries,
                    selected = spoofMode,
                    onSelect = { value -> edit { copy(spoofMode = value) } },
                    label = { spoofModeLabel(it) },
                    enabled = enabled,
                )
                Hint(stringResource(R.string.spoof_mode_desc))
            } else {
                Hint(stringResource(R.string.spoof_mode_needs_h2))
            }
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = spoofSni,
                onValueChange = { value -> edit { copy(spoofSni = value) } },
                label = stringResource(R.string.spoof_sni_label),
                placeholder = stringResource(R.string.spoof_sni_hint),
                helpText = stringResource(R.string.spoof_sni_help),
                enabled = enabled,
            )
        } else {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            Hint(stringResource(R.string.spoof_mode_unsupported))
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

        Spacer(Modifier.height(EngineSpacing.Field))
        ProfileTextField(
            value = socketMark,
            onValueChange = { value -> edit { copy(socketMark = value) } },
            label = stringResource(R.string.socket_mark_label),
            placeholder = stringResource(R.string.socket_mark_hint),
            helpText = stringResource(R.string.socket_mark_help),
            enabled = enabled,
        )
    }
}
