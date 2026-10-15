package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.noizeLabel
import studio.cluvex.aether.ui.spoofModeLabel

/**
 * Transport & Anti-DPI: how the traffic is shaped on the wire. Promoted to the
 * top of the hub (right under Connection) and split into three cards so the
 * page scans instead of scrolling as one long sheet:
 *
 *  1. Anti-DPI & obfuscation: Amnezia noize, TLS fragmentation (size + delay
 *     side by side when on), and ClientHello spoofing.
 *  2. Protocol & QUIC: the QUIC v2 opener.
 *  3. Packet & network: MTU + keepalive side by side, then the socket mark.
 *
 * Explanations moved behind (?) buttons.
 *
 * SPOOFING lives in card 1 because it is one more way the ClientHello is
 * shaped, next to fragmentation. It only does anything on the MASQUE
 * transports (SNI) and the HTTP/2 carrier (split modes); elsewhere the picker
 * is shown disabled at Off and the (?) says why, instead of offering switches
 * that would do nothing.
 */
@Composable
internal fun TransportSection(
    noize: Noize,
    keepalive: Int,
    mtu: Int,
    fragment: Boolean,
    fragmentSize: String,
    fragmentDelay: String,
    quicV2Opener: Boolean,
    socketMark: String,
    protocol: Protocol,
    masqueHttp2: Boolean,
    spoofMode: SpoofMode,
    spoofSni: String,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // ---------------------------------------------- 1. anti-DPI --
        EngineGroup(label = stringResource(R.string.settings_group_anti_dpi)) {
            FieldLabel(stringResource(R.string.noize_title), info = stringResource(R.string.noize_desc))
            DropdownSelector(
                options = Noize.entries,
                selected = noize,
                onSelect = { value -> edit { copy(noize = value) } },
                label = { noizeLabel(it) },
                enabled = enabled,
            )

            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.fragment_title),
                info = stringResource(R.string.fragment_desc),
                checked = fragment,
                enabled = enabled,
                onChange = { value -> edit { copy(fragment = value) } },
            )
            DependentBlock(visible = fragment) {
                FieldPair(
                    first = { m ->
                        ProfileTextField(
                            value = fragmentSize,
                            onValueChange = { value -> edit { copy(fragmentSize = value) } },
                            label = stringResource(R.string.fragment_size_label),
                            placeholder = stringResource(R.string.fragment_size_hint),
                            enabled = enabled,
                            modifier = m,
                        )
                    },
                    second = { m ->
                        ProfileTextField(
                            value = fragmentDelay,
                            onValueChange = { value -> edit { copy(fragmentDelay = value) } },
                            label = stringResource(R.string.fragment_delay_label),
                            placeholder = stringResource(R.string.fragment_delay_hint),
                            enabled = enabled,
                            modifier = m,
                        )
                    },
                )
                Spacer(Modifier.height(EngineSpacing.Inline))
            }
            EngineDivider()

            // The SNI applies to MASQUE carriers; the split modes only to H2.
            val spoofEditable = protocol.isMasque && masqueHttp2
            Spacer(Modifier.height(EngineSpacing.Field))
            FieldLabel(
                stringResource(R.string.spoof_mode_title),
                info = stringResource(
                    when {
                        !protocol.isMasque -> R.string.spoof_mode_unsupported
                        !masqueHttp2 -> R.string.spoof_mode_needs_h2
                        else -> R.string.spoof_mode_desc
                    },
                ),
            )
            DropdownSelector(
                options = SpoofMode.entries,
                selected = if (spoofEditable) spoofMode else SpoofMode.OFF,
                onSelect = { value -> edit { copy(spoofMode = value) } },
                label = { spoofModeLabel(it) },
                enabled = enabled && spoofEditable,
            )
            if (protocol.isMasque) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                ProfileTextField(
                    value = spoofSni,
                    onValueChange = { value -> edit { copy(spoofSni = value) } },
                    label = stringResource(R.string.spoof_sni_label),
                    placeholder = stringResource(R.string.spoof_sni_hint),
                    info = stringResource(R.string.spoof_sni_help),
                    enabled = enabled,
                )
            }
        }

        // ------------------------------------------ 2. protocol & QUIC --
        Spacer(Modifier.height(EngineSpacing.Group))
        EngineGroup(
            label = stringResource(R.string.settings_group_protocol_quic),
            contentPadding = EngineSpacing.DensePadding,
        ) {
            SwitchRow(
                title = stringResource(R.string.quic_v2_title),
                info = stringResource(R.string.quic_v2_desc),
                checked = quicV2Opener,
                enabled = enabled,
                onChange = { value -> edit { copy(quicV2Opener = value) } },
            )
        }

        // ----------------------------------------- 3. packet & network --
        Spacer(Modifier.height(EngineSpacing.Group))
        EngineGroup(label = stringResource(R.string.settings_group_packet)) {
            FieldPair(
                first = { m ->
                    Column(m) {
                        FieldLabel(stringResource(R.string.mtu_label), info = stringResource(R.string.mtu_desc))
                        DropdownSelector(
                            options = ConnectionProfile.MTU_PRESETS,
                            selected = mtu,
                            onSelect = { value -> edit { copy(mtu = value) } },
                            label = { "$it" },
                            enabled = enabled,
                        )
                    }
                },
                second = { m ->
                    Column(m) {
                        FieldLabel(stringResource(R.string.keepalive_label))
                        DropdownSelector(
                            options = ConnectionProfile.KEEPALIVE_PRESETS,
                            selected = keepalive,
                            onSelect = { value -> edit { copy(keepalive = value) } },
                            label = { if (it == 0) stringResource(R.string.keepalive_default) else "$it" },
                            enabled = enabled,
                        )
                    }
                },
            )
            Spacer(Modifier.height(EngineSpacing.Field))
            ProfileTextField(
                value = socketMark,
                onValueChange = { value -> edit { copy(socketMark = value) } },
                label = stringResource(R.string.socket_mark_label),
                placeholder = stringResource(R.string.socket_mark_hint),
                info = stringResource(R.string.socket_mark_help),
                enabled = enabled,
            )
        }
    }
}
