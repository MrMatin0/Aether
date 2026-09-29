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
import studio.cluvex.aether.core.SmartDnsRuntime
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * Smart DNS ("sanctions DNS"): the device resolves through a provider whose
 * answers point geo-blocked sites at its own SNI proxy abroad, while Aether
 * keeps carrying everything else. See [studio.cluvex.aether.model.ConnectionProfile.smartDns].
 *
 * Takes [validServers] and [validProxies] (counts) rather than the parsed
 * lists, for the same stability reason as [RoutingSection].
 */
@Composable
internal fun SmartDnsSection(
    smartDns: Boolean,
    smartDnsServers: String,
    smartDnsDirect: Boolean,
    smartDnsProxies: String,
    chain: ChainMode,
    validServers: Int,
    validProxies: Int,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    // Set by the live session when the ROM refused the IPv6-free TUN; see
    // SmartDnsRuntime. Shown here because this is the setting it defeats.
    val ipv6Fallback by SmartDnsRuntime.ipv6Fallback.collectAsState()

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
            if (ipv6Fallback) {
                Hint(stringResource(R.string.smart_dns_ipv6_fallback))
            }
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = smartDnsServers,
                onValueChange = { value -> edit { copy(smartDnsServers = value) } },
                label = stringResource(R.string.smart_dns_servers_label),
                placeholder = stringResource(R.string.smart_dns_servers_hint),
                singleLine = false,
                enabled = enabled,
            )
            Hint(stringResource(R.string.smart_dns_servers_help))
            if (smartDnsServers.isNotBlank() && validServers == 0) {
                Hint(stringResource(R.string.smart_dns_servers_invalid))
            }
            Spacer(Modifier.height(EngineSpacing.Field))
            SwitchRow(
                title = stringResource(R.string.smart_dns_direct_title),
                description = stringResource(R.string.smart_dns_direct_desc),
                checked = smartDnsDirect,
                enabled = enabled,
                onChange = { value -> edit { copy(smartDnsDirect = value) } },
            )
            DependentBlock(visible = smartDnsDirect) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                ProfileTextField(
                    value = smartDnsProxies,
                    onValueChange = { value -> edit { copy(smartDnsProxies = value) } },
                    label = stringResource(R.string.smart_dns_proxies_label),
                    placeholder = stringResource(R.string.smart_dns_proxies_hint),
                    singleLine = false,
                    enabled = enabled,
                )
                Hint(stringResource(R.string.smart_dns_proxies_help))
                if (smartDnsProxies.isNotBlank() && validProxies == 0) {
                    Hint(stringResource(R.string.smart_dns_proxies_invalid))
                }
            }
            Hint(stringResource(R.string.smart_dns_private_dns_hint))
        }
    }
}
