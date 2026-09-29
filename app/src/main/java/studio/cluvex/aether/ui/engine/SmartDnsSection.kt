package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.core.SmartDnsPath
import studio.cluvex.aether.core.SmartDnsRuntime
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.SmartDnsProtocol
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * Smart DNS ("sanctions DNS"): the device resolves through a provider whose
 * answers point geo-blocked sites at its own SNI proxy abroad, while Aether
 * keeps carrying everything else. See [studio.cluvex.aether.model.ConnectionProfile.smartDns].
 *
 * The provider is spoken to over plain DNS, DoH or DoT. There is no manual
 * "direct" switch any more: the session goes through the tunnel first and
 * switches to the direct path by itself when the provider only answers local
 * addresses. The live path is shown here.
 *
 * Takes [validServers] (a count) rather than the parsed list, for the same
 * stability reason as [RoutingSection].
 */
@Composable
internal fun SmartDnsSection(
    smartDns: Boolean,
    smartDnsProtocol: SmartDnsProtocol,
    smartDnsServers: String,
    chain: ChainMode,
    validServers: Int,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    val status by SmartDnsRuntime.status.collectAsState()

    EngineSection(
        title = stringResource(R.string.section_smart_dns),
        subtitle = stringResource(R.string.section_smart_dns_note),
        icon = Icons.Rounded.Dns,
        headerGap = EngineSpacing.SwitchHeader,
        modifier = modifier,
    ) {
        SwitchRow(
            title = stringResource(R.string.smart_dns_title),
            description = stringResource(R.string.smart_dns_desc),
            checked = smartDns,
            enabled = enabled,
            onChange = { value -> edit { copy(smartDns = value) } },
        )
        DependentBlock(visible = smartDns) {
            if (chain != ChainMode.AETHER) {
                Hint(stringResource(R.string.smart_dns_chain_only))
            }
            Spacer(Modifier.height(EngineSpacing.Inline))
            FieldLabel(stringResource(R.string.smart_dns_protocol_title))
            SegmentedSelector(
                options = SmartDnsProtocol.entries,
                selected = smartDnsProtocol,
                onSelect = { value -> edit { copy(smartDnsProtocol = value) } },
                label = { protocolLabel(it) },
                enabled = enabled,
            )
            Hint(
                stringResource(
                    when (smartDnsProtocol) {
                        SmartDnsProtocol.PLAIN -> R.string.smart_dns_protocol_plain_desc
                        SmartDnsProtocol.DOH -> R.string.smart_dns_protocol_doh_desc
                        SmartDnsProtocol.DOT -> R.string.smart_dns_protocol_dot_desc
                    },
                ),
            )
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = smartDnsServers,
                onValueChange = { value -> edit { copy(smartDnsServers = value) } },
                label = stringResource(R.string.smart_dns_servers_label),
                placeholder = stringResource(
                    when (smartDnsProtocol) {
                        SmartDnsProtocol.PLAIN -> R.string.smart_dns_servers_hint_plain
                        SmartDnsProtocol.DOH -> R.string.smart_dns_servers_hint_doh
                        SmartDnsProtocol.DOT -> R.string.smart_dns_servers_hint_dot
                    },
                ),
                singleLine = false,
                enabled = enabled,
            )
            Hint(
                stringResource(
                    when (smartDnsProtocol) {
                        SmartDnsProtocol.PLAIN -> R.string.smart_dns_servers_help_plain
                        SmartDnsProtocol.DOH -> R.string.smart_dns_servers_help_doh
                        SmartDnsProtocol.DOT -> R.string.smart_dns_servers_help_dot
                    },
                ),
            )
            if (smartDnsServers.isNotBlank() && validServers == 0) {
                Hint(stringResource(R.string.smart_dns_servers_invalid))
            }
            Hint(stringResource(R.string.smart_dns_route_auto))
            status?.let { live ->
                Hint(
                    if (live.path == SmartDnsPath.TUNNEL) {
                        stringResource(R.string.smart_dns_live_tunnel)
                    } else {
                        stringResource(R.string.smart_dns_live_direct, live.proxyRanges)
                    },
                )
            }
            Hint(stringResource(R.string.smart_dns_private_dns_hint))
        }
    }
}

@Composable
private fun protocolLabel(protocol: SmartDnsProtocol): String = stringResource(
    when (protocol) {
        SmartDnsProtocol.PLAIN -> R.string.smart_dns_protocol_plain
        SmartDnsProtocol.DOH -> R.string.smart_dns_protocol_doh
        SmartDnsProtocol.DOT -> R.string.smart_dns_protocol_dot
    },
)
