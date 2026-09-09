package studio.cluvex.aether.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.data.ThemeMode
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorBridgeMode

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

@Composable
internal fun chainLabel(mode: ChainMode): String = when (mode) {
    ChainMode.AETHER -> stringResource(R.string.chain_aether)
    ChainMode.PSIPHON -> stringResource(R.string.chain_psiphon)
    ChainMode.TOR -> stringResource(R.string.chain_tor)
    ChainMode.PSIPHON_OVER_AETHER -> stringResource(R.string.chain_psiphon_over_aether)
    ChainMode.TOR_OVER_AETHER -> stringResource(R.string.chain_tor_over_aether)
    ChainMode.TOR_OVER_PSIPHON -> stringResource(R.string.chain_tor_over_psiphon)
    ChainMode.TOR_OVER_PSIPHON_OVER_AETHER -> stringResource(R.string.chain_tor_over_all)
}

/**
 * WHAT the combination is for, in one line.
 *
 * Not decoration: these seven options are not a preference, they are answers to
 * seven different failures, and "Tor over Psiphon" tells a user nothing about
 * when to reach for it. Without this the fastest thing to do is pick the biggest
 * chain, which is also the slowest thing to run.
 */
@Composable
internal fun chainDescription(mode: ChainMode): String = when (mode) {
    ChainMode.AETHER -> stringResource(R.string.chain_aether_desc)
    ChainMode.PSIPHON -> stringResource(R.string.chain_psiphon_desc)
    ChainMode.TOR -> stringResource(R.string.chain_tor_desc)
    ChainMode.PSIPHON_OVER_AETHER -> stringResource(R.string.chain_psiphon_over_aether_desc)
    ChainMode.TOR_OVER_AETHER -> stringResource(R.string.chain_tor_over_aether_desc)
    ChainMode.TOR_OVER_PSIPHON -> stringResource(R.string.chain_tor_over_psiphon_desc)
    ChainMode.TOR_OVER_PSIPHON_OVER_AETHER -> stringResource(R.string.chain_tor_over_all_desc)
}

/*
 * Bridges.
 *
 * Same rule as the chain modes: the NAME of a bridge source says nothing about
 * when to reach for it, and the difference between the three is the whole point.
 * A built-in bridge is public and therefore the first thing a censor blocks; a
 * requested one is handed to you alone but needs a working path to the bridge
 * server; a pasted one is only as good as wherever it came from.
 */

@Composable
internal fun bridgeModeLabel(mode: TorBridgeMode): String = when (mode) {
    TorBridgeMode.OFF -> stringResource(R.string.bridge_mode_off)
    TorBridgeMode.BUILTIN -> stringResource(R.string.bridge_mode_builtin)
    TorBridgeMode.REQUESTED -> stringResource(R.string.bridge_mode_requested)
    TorBridgeMode.CUSTOM -> stringResource(R.string.bridge_mode_custom)
}

@Composable
internal fun bridgeModeDescription(mode: TorBridgeMode): String = when (mode) {
    TorBridgeMode.OFF -> stringResource(R.string.bridge_mode_off_desc)
    TorBridgeMode.BUILTIN -> stringResource(R.string.bridge_mode_builtin_desc)
    TorBridgeMode.REQUESTED -> stringResource(R.string.bridge_mode_requested_desc)
    TorBridgeMode.CUSTOM -> stringResource(R.string.bridge_mode_custom_desc)
}

internal fun bridgeModeIcon(mode: TorBridgeMode): ImageVector = when (mode) {
    TorBridgeMode.OFF -> Icons.Rounded.Block
    TorBridgeMode.BUILTIN -> Icons.Rounded.Public
    TorBridgeMode.REQUESTED -> Icons.Rounded.Key
    TorBridgeMode.CUSTOM -> Icons.Rounded.ContentPaste
}

@Composable
internal fun bridgeTransportLabel(transport: BridgeTransport): String = when (transport) {
    BridgeTransport.OBFS4 -> stringResource(R.string.bridge_tr_obfs4)
    BridgeTransport.SNOWFLAKE -> stringResource(R.string.bridge_tr_snowflake)
    BridgeTransport.WEBTUNNEL -> stringResource(R.string.bridge_tr_webtunnel)
    BridgeTransport.MEEK -> stringResource(R.string.bridge_tr_meek)
    BridgeTransport.VANILLA -> stringResource(R.string.bridge_tr_vanilla)
}

@Composable
internal fun bridgeTransportDescription(transport: BridgeTransport): String = when (transport) {
    BridgeTransport.OBFS4 -> stringResource(R.string.bridge_tr_obfs4_desc)
    BridgeTransport.SNOWFLAKE -> stringResource(R.string.bridge_tr_snowflake_desc)
    BridgeTransport.WEBTUNNEL -> stringResource(R.string.bridge_tr_webtunnel_desc)
    BridgeTransport.MEEK -> stringResource(R.string.bridge_tr_meek_desc)
    BridgeTransport.VANILLA -> stringResource(R.string.bridge_tr_vanilla_desc)
}

/** One glyph per transport, so the picker is scannable without reading it. */
internal fun bridgeTransportIcon(transport: BridgeTransport): ImageVector = when (transport) {
    BridgeTransport.OBFS4 -> Icons.Rounded.Shield
    BridgeTransport.SNOWFLAKE -> Icons.Rounded.AcUnit
    BridgeTransport.WEBTUNNEL -> Icons.Rounded.Language
    BridgeTransport.MEEK -> Icons.Rounded.Cloud
    BridgeTransport.VANILLA -> Icons.Rounded.Link
}

/*
 * Theme and language live here too, because the settings index paints the
 * CURRENT theme and language on the Appearance row. They were private inside
 * AppearancePanel, which is the same mistake as the scan labels: one screen
 * owning a name every other screen also needs.
 */

@Composable
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
}

@Composable
internal fun appLanguageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.PERSIAN -> stringResource(R.string.language_persian)
}
