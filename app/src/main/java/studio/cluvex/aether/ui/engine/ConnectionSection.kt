package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.ui.components.ScanModeSelector
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.endpointLabel
import studio.cluvex.aether.ui.ipLabel
import studio.cluvex.aether.ui.protocolLabel

/**
 * The controls that decide whether you connect AT ALL, as four compact cards:
 * Protocol, Scan mode, Endpoint, IP version. Explanations are behind (?).
 *
 * MASQUE-in-MASQUE is one of the protocols here, next to MASQUE and WARP×2,
 * rather than a switch on another page: it is a choice of transport. Its two
 * optional endpoints sit directly under the picker and only exist while it is
 * selected.
 *
 * "MASQUE over HTTP/2" follows the same rule: it only applies to the MASQUE
 * transports, so it lives under the picker and only appears while MASQUE or
 * MASQUE-in-MASQUE is selected.
 */
@Composable
internal fun ConnectionSection(
    protocol: Protocol,
    scanMode: ScanMode,
    endpointMode: EndpointMode,
    manualPeer: String,
    manualRange: String,
    ipVersion: IpVersion,
    mimOuterPeer: String,
    mimInnerPeer: String,
    masqueHttp2: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // ---------------------------------------------------- protocol --
        EngineGroup(
            label = stringResource(R.string.protocol),
            info = if (protocol == Protocol.MIM) stringResource(R.string.mim_desc) else null,
        ) {
            SegmentedSelector(
                options = Protocol.entries,
                selected = protocol,
                onSelect = { value -> edit { copy(protocol = value) } },
                label = { protocolLabel(it) },
                enabled = enabled,
            )
            DependentBlock(visible = protocol == Protocol.MIM) {
                Spacer(Modifier.height(EngineSpacing.Field))
                ProfileTextField(
                    value = mimOuterPeer,
                    onValueChange = { value -> edit { copy(mimOuterPeer = value) } },
                    label = stringResource(R.string.mim_outer_label),
                    placeholder = stringResource(R.string.mim_peer_hint),
                    info = stringResource(R.string.mim_outer_help),
                    enabled = enabled,
                )
                Spacer(Modifier.height(EngineSpacing.Inline))
                ProfileTextField(
                    value = mimInnerPeer,
                    onValueChange = { value -> edit { copy(mimInnerPeer = value) } },
                    label = stringResource(R.string.mim_inner_label),
                    placeholder = stringResource(R.string.mim_peer_hint),
                    info = stringResource(R.string.mim_inner_help),
                    enabled = enabled,
                )
            }
            DependentBlock(visible = protocol.isMasque) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                EngineDivider()
                SwitchRow(
                    title = stringResource(R.string.masque_http2),
                    info = stringResource(R.string.masque_http2_desc),
                    checked = masqueHttp2,
                    enabled = enabled,
                    onChange = { value -> edit { copy(masqueHttp2 = value) } },
                )
            }
        }

        // --------------------------------------------------- scan mode --
        Spacer(Modifier.height(EngineSpacing.Group))
        EngineGroup(
            label = stringResource(R.string.scan_mode),
            info = stringResource(R.string.scan_mode_hint),
        ) {
            ScanModeSelector(
                selected = scanMode,
                onSelect = { value -> edit { copy(scanMode = value) } },
                enabled = enabled,
            )
        }

        // ---------------------------------------------------- endpoint --
        Spacer(Modifier.height(EngineSpacing.Group))
        EngineGroup(label = stringResource(R.string.endpoint_mode)) {
            SegmentedSelector(
                options = EndpointMode.entries,
                selected = endpointMode,
                onSelect = { value -> edit { copy(endpointMode = value) } },
                label = { endpointLabel(it) },
                enabled = enabled,
            )
            DependentBlock(visible = endpointMode == EndpointMode.MANUAL_PEER) {
                Spacer(Modifier.height(EngineSpacing.Field))
                ProfileTextField(
                    value = manualPeer,
                    onValueChange = { value -> edit { copy(manualPeer = value) } },
                    label = stringResource(R.string.manual_peer_label),
                    placeholder = stringResource(R.string.manual_peer_hint),
                    enabled = enabled,
                )
            }
            DependentBlock(visible = endpointMode == EndpointMode.MANUAL_RANGE) {
                Spacer(Modifier.height(EngineSpacing.Field))
                ProfileTextField(
                    value = manualRange,
                    onValueChange = { value -> edit { copy(manualRange = value) } },
                    label = stringResource(R.string.manual_range_label),
                    placeholder = stringResource(R.string.manual_range_hint),
                    info = stringResource(R.string.manual_range_help),
                    singleLine = false,
                    enabled = enabled,
                )
            }
        }

        // -------------------------------------------------- IP version --
        Spacer(Modifier.height(EngineSpacing.Group))
        EngineGroup(label = stringResource(R.string.ip_version)) {
            SegmentedSelector(
                options = IpVersion.entries,
                selected = ipVersion,
                onSelect = { value -> edit { copy(ipVersion = value) } },
                label = { ipLabel(it) },
                enabled = enabled,
            )
        }
    }
}
