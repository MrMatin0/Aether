package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.ScanModeSelector
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.endpointLabel
import studio.cluvex.aether.ui.ipLabel
import studio.cluvex.aether.ui.protocolLabel

/**
 * The controls that decide whether you connect AT ALL.
 *
 * First card on the page, in both depths, and the only one a user who cannot get
 * online should ever have to touch. Protocol first because it is the one setting
 * that can make the difference between a tunnel and no tunnel; scan mode second
 * because it decides how long the connecting screen lasts.
 */
@Composable
internal fun ConnectionSection(
    protocol: Protocol,
    scanMode: ScanMode,
    endpointMode: EndpointMode,
    manualPeer: String,
    manualRange: String,
    ipVersion: IpVersion,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineSection(
        title = stringResource(R.string.section_connection),
        subtitle = stringResource(R.string.section_connection_note),
        icon = Icons.Rounded.Shield,
        modifier = modifier,
    ) {
        FieldLabel(stringResource(R.string.protocol))
        SegmentedSelector(
            options = Protocol.entries,
            selected = protocol,
            onSelect = { value -> edit { copy(protocol = value) } },
            label = { protocolLabel(it) },
            enabled = enabled,
        )

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.scan_mode))
        ScanModeSelector(
            selected = scanMode,
            onSelect = { value -> edit { copy(scanMode = value) } },
            enabled = enabled,
        )
        Hint(stringResource(R.string.scan_mode_hint))

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.endpoint_mode))
        SegmentedSelector(
            options = EndpointMode.entries,
            selected = endpointMode,
            onSelect = { value -> edit { copy(endpointMode = value) } },
            label = { endpointLabel(it) },
            enabled = enabled,
        )
        DependentBlock(visible = endpointMode == EndpointMode.MANUAL_PEER) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = manualPeer,
                onValueChange = { value -> edit { copy(manualPeer = value) } },
                label = stringResource(R.string.manual_peer_label),
                placeholder = stringResource(R.string.manual_peer_hint),
                enabled = enabled,
            )
        }
        DependentBlock(visible = endpointMode == EndpointMode.MANUAL_RANGE) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = manualRange,
                onValueChange = { value -> edit { copy(manualRange = value) } },
                label = stringResource(R.string.manual_range_label),
                placeholder = stringResource(R.string.manual_range_hint),
                helpText = stringResource(R.string.manual_range_help),
                singleLine = false,
                enabled = enabled,
            )
        }

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.ip_version))
        SegmentedSelector(
            options = IpVersion.entries,
            selected = ipVersion,
            onSelect = { value -> edit { copy(ipVersion = value) } },
            label = { ipLabel(it) },
            enabled = enabled,
        )
    }
}
