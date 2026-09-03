package studio.cluvex.aether.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth

/*
 * Enum -> localized label, in one place.
 *
 * These used to be private helpers inside AdvancedPanel, which meant the home
 * screen could not name the scan mode it was waiting on without duplicating the
 * mapping. Shared now, so a label is defined exactly once.
 */

@Composable
internal fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.AUTO -> stringResource(R.string.protocol_auto)
    Protocol.MASQUE -> stringResource(R.string.protocol_masque)
    Protocol.WIREGUARD -> stringResource(R.string.protocol_wireguard)
    Protocol.GOOL -> stringResource(R.string.protocol_gool)
}

@Composable
internal fun scanLabel(mode: ScanMode): String = when (mode) {
    ScanMode.TURBO -> stringResource(R.string.scan_mode_turbo)
    ScanMode.PRECISE -> stringResource(R.string.scan_mode_precise)
    ScanMode.ULTRA -> stringResource(R.string.scan_mode_ultra)
}

/** What the mode does, in one line. Shown WITH the choice, not after it. */
@Composable
internal fun scanDescription(mode: ScanMode): String = when (mode) {
    ScanMode.TURBO -> stringResource(R.string.scan_mode_turbo_desc)
    ScanMode.PRECISE -> stringResource(R.string.scan_mode_precise_desc)
    ScanMode.ULTRA -> stringResource(R.string.scan_mode_ultra_desc)
}

/** Roughly how long that mode takes, so the wait is a choice and not a surprise. */
@Composable
internal fun scanEta(mode: ScanMode): String = when (mode) {
    ScanMode.TURBO -> stringResource(R.string.scan_mode_turbo_eta)
    ScanMode.PRECISE -> stringResource(R.string.scan_mode_precise_eta)
    ScanMode.ULTRA -> stringResource(R.string.scan_mode_ultra_eta)
}

@Composable
internal fun ipLabel(ip: IpVersion): String = when (ip) {
    IpVersion.V4 -> stringResource(R.string.ip_v4)
    IpVersion.V6 -> stringResource(R.string.ip_v6)
    IpVersion.BOTH -> stringResource(R.string.ip_both)
}

@Composable
internal fun noizeLabel(n: Noize): String = when (n) {
    Noize.OFF -> stringResource(R.string.noize_off)
    Noize.LIGHT -> stringResource(R.string.noize_light)
    Noize.FIREWALL -> stringResource(R.string.noize_firewall)
    Noize.BALANCED -> stringResource(R.string.noize_balanced)
    Noize.GFW -> stringResource(R.string.noize_gfw)
    Noize.AGGRESSIVE -> stringResource(R.string.noize_aggressive)
}

@Composable
internal fun endpointLabel(m: EndpointMode): String = when (m) {
    EndpointMode.AUTO -> stringResource(R.string.endpoint_auto)
    EndpointMode.MANUAL_PEER -> stringResource(R.string.endpoint_peer)
    EndpointMode.MANUAL_RANGE -> stringResource(R.string.endpoint_range)
}

@Composable
internal fun teamAuthLabel(a: TeamAuth): String = when (a) {
    TeamAuth.OFF -> stringResource(R.string.team_auth_off)
    TeamAuth.SERVICE_TOKEN -> stringResource(R.string.team_auth_service)
    TeamAuth.EMAIL -> stringResource(R.string.team_auth_email)
    TeamAuth.TOKEN -> stringResource(R.string.team_auth_token)
}

@Composable
internal fun splitLabel(m: SplitMode): String = when (m) {
    SplitMode.OFF -> stringResource(R.string.split_off)
    SplitMode.INCLUDE -> stringResource(R.string.split_include)
    SplitMode.EXCLUDE -> stringResource(R.string.split_exclude)
}
