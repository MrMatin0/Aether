package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EngineTor
import studio.cluvex.aether.model.EngineTorBridges
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * What engine v2.0.0 added: a second MASQUE hop, the QUIC v2 opener, a tor the
 * engine runs itself, and a socket mark.
 *
 * WHY ITS OWN CARD rather than four rows spread across Transport, Chain and
 * Tuning: these options only exist on a v2.0.0 engine, and three of them
 * interact with settings on OTHER pages - MASQUE-in-MASQUE needs the MASQUE
 * transport, engine Tor conflicts with the Tor chain modes, reverse Tor
 * overrides the transport choice entirely. A user who cannot see that
 * relationship reads the result as the app ignoring them, so the constraints
 * are stated where the switch is, next to the switch that is subject to them.
 *
 * Every control here is dormant by default, which is also what the engine does
 * without them - so this card can be ignored completely and the session is the
 * one core 1.8.0 would have built.
 */
@Composable
internal fun CoreV2Section(
    protocol: Protocol,
    chain: ChainMode,
    masqueInMasque: Boolean,
    mimOuterPeer: String,
    mimInnerPeer: String,
    quicV2Opener: Boolean,
    socketMark: String,
    engineTor: EngineTor,
    engineTorBridges: EngineTorBridges,
    engineTorBridgeLines: String,
    engineTorCountry: String,
    engineTorBindPort: Int,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    // The app's own tor core owns the Tor chain modes. Offering the engine's
    // tor at the same time would let a user configure two tor instances that
    // race each other's bootstrap - so the selector goes quiet and SAYS why,
    // rather than accepting a choice ConnectionProfile would then drop.
    val torAvailable = chain == ChainMode.AETHER
    val torOn = torAvailable && engineTor != EngineTor.OFF

    EngineSection(
        title = stringResource(R.string.section_core_v2),
        subtitle = stringResource(R.string.section_core_v2_note),
        icon = Icons.Rounded.Layers,
        modifier = modifier,
    ) {
        SwitchRow(
            title = stringResource(R.string.mim_title),
            description = stringResource(R.string.mim_desc),
            checked = masqueInMasque,
            enabled = enabled,
            onChange = { value -> edit { copy(masqueInMasque = value) } },
        )
        DependentBlock(visible = masqueInMasque) {
            // WireGuard and gool have no second MASQUE hop to make, and the
            // engine would refuse the flag rather than ignore it. Said here
            // instead of hidden, because the switch is still on and the user
            // needs to know it is doing nothing on this transport.
            if (protocol == Protocol.WIREGUARD || protocol == Protocol.GOOL) {
                Hint(stringResource(R.string.mim_protocol_note))
            }
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = mimOuterPeer,
                onValueChange = { value -> edit { copy(mimOuterPeer = value) } },
                label = stringResource(R.string.mim_outer_label),
                placeholder = stringResource(R.string.mim_peer_hint),
                helpText = stringResource(R.string.mim_outer_help),
                enabled = enabled,
            )
            Spacer(Modifier.height(EngineSpacing.Inline))
            ProfileTextField(
                value = mimInnerPeer,
                onValueChange = { value -> edit { copy(mimInnerPeer = value) } },
                label = stringResource(R.string.mim_inner_label),
                placeholder = stringResource(R.string.mim_peer_hint),
                helpText = stringResource(R.string.mim_inner_help),
                enabled = enabled,
            )
        }

        EngineDivider()
        SwitchRow(
            title = stringResource(R.string.quic_v2_title),
            description = stringResource(R.string.quic_v2_desc),
            checked = quicV2Opener,
            enabled = enabled,
            onChange = { value -> edit { copy(quicV2Opener = value) } },
        )

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.engine_tor_label))
        DropdownSelector(
            options = EngineTor.entries,
            // Shows OFF while a Tor chain mode is selected, because OFF is
            // what the session will actually run with.
            selected = if (torAvailable) engineTor else EngineTor.OFF,
            onSelect = { value -> edit { copy(engineTor = value) } },
            label = { engineTorLabel(it) },
            enabled = enabled && torAvailable,
        )
        Hint(
            if (torAvailable) {
                engineTorDescription(engineTor)
            } else {
                stringResource(R.string.engine_tor_chain_conflict)
            },
        )
        DependentBlock(visible = torOn) {
            Spacer(Modifier.height(EngineSpacing.Field))
            FieldLabel(stringResource(R.string.engine_tor_bridges_label))
            DropdownSelector(
                options = EngineTorBridges.entries,
                selected = engineTorBridges,
                onSelect = { value -> edit { copy(engineTorBridges = value) } },
                label = { engineTorBridgesLabel(it) },
                enabled = enabled,
            )
            Hint(engineTorBridgesDescription(engineTorBridges))
            DependentBlock(visible = engineTorBridges == EngineTorBridges.CUSTOM) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                ProfileTextField(
                    value = engineTorBridgeLines,
                    onValueChange = { value -> edit { copy(engineTorBridgeLines = value) } },
                    label = stringResource(R.string.engine_tor_bridge_lines_label),
                    placeholder = stringResource(R.string.engine_tor_bridge_lines_hint),
                    helpText = stringResource(R.string.engine_tor_bridge_lines_help),
                    singleLine = false,
                    enabled = enabled,
                )
            }
            DependentBlock(visible = engineTorBridges != EngineTorBridges.OFF) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                ProfileTextField(
                    value = engineTorCountry,
                    onValueChange = { value -> edit { copy(engineTorCountry = value) } },
                    label = stringResource(R.string.engine_tor_country_label),
                    placeholder = stringResource(R.string.engine_tor_country_hint),
                    helpText = stringResource(R.string.engine_tor_country_help),
                    enabled = enabled,
                )
            }
            Spacer(Modifier.height(EngineSpacing.Inline))
            SecondsField(
                value = engineTorBindPort,
                onValueChange = { value -> edit { copy(engineTorBindPort = value) } },
                label = stringResource(R.string.engine_tor_bind_label),
                placeholder = ConnectionProfile.ENGINE_TOR_BIND_PORT_DEFAULT.toString(),
                max = MAX_PORT,
                enabled = enabled,
            )
            Hint(stringResource(R.string.engine_tor_bind_help))
            Spacer(Modifier.height(EngineSpacing.Inline))
            // The flags above need an engine compiled with its `tor` feature.
            // A build without it rejects them at startup, so this is the one
            // setting on the page whose availability the app cannot promise.
            Hint(stringResource(R.string.engine_tor_feature_note))
        }

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

/**
 * The v2.0.0 resource and stability limits: how many clients the engine will
 * serve at once, and how long it waits on a socket before giving up on it.
 *
 * Blank everywhere means the engine's own sizing, which it derives from the fd
 * limit it raises at startup - a better answer than any constant this app could
 * ship, which is why none of these has a non-zero default.
 */
@Composable
internal fun CoreResourceSection(
    maxClients: Int,
    halfCloseSecs: Int,
    tcpKeepaliveSecs: Int,
    tcpConnectSecs: Int,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineSection(
        title = stringResource(R.string.section_core_resources),
        subtitle = stringResource(R.string.section_core_resources_note),
        icon = Icons.Rounded.Memory,
        modifier = modifier,
    ) {
        SecondsField(
            value = maxClients,
            onValueChange = { value -> edit { copy(maxClients = value) } },
            label = stringResource(R.string.max_clients_label),
            placeholder = stringResource(R.string.core_auto_hint),
            max = MAX_CLIENTS,
            enabled = enabled,
        )
        Hint(stringResource(R.string.max_clients_help))
        Spacer(Modifier.height(EngineSpacing.Inline))
        SecondsField(
            value = halfCloseSecs,
            onValueChange = { value -> edit { copy(halfCloseSecs = value) } },
            label = stringResource(R.string.half_close_secs_label),
            placeholder = stringResource(R.string.secs_hint),
            max = EngineLimits.ValidateSecs,
            enabled = enabled,
        )
        Spacer(Modifier.height(EngineSpacing.Inline))
        SecondsField(
            value = tcpKeepaliveSecs,
            onValueChange = { value -> edit { copy(tcpKeepaliveSecs = value) } },
            label = stringResource(R.string.tcp_keepalive_secs_label),
            placeholder = stringResource(R.string.secs_hint),
            max = EngineLimits.ValidateSecs,
            enabled = enabled,
        )
        Spacer(Modifier.height(EngineSpacing.Inline))
        SecondsField(
            value = tcpConnectSecs,
            onValueChange = { value -> edit { copy(tcpConnectSecs = value) } },
            label = stringResource(R.string.tcp_connect_secs_label),
            placeholder = stringResource(R.string.secs_hint),
            max = MAX_CONNECT_SECS,
            enabled = enabled,
        )
        Hint(stringResource(R.string.core_resources_help))
    }
}

/** Upper bounds, matched to the clamps in [ConnectionProfile.toEnv]. */
private const val MAX_PORT = 65_535
private const val MAX_CLIENTS = 8_192
private const val MAX_CONNECT_SECS = 300

@Composable
private fun engineTorLabel(mode: EngineTor): String = stringResource(
    when (mode) {
        EngineTor.OFF -> R.string.engine_tor_off
        EngineTor.IN_TUNNEL -> R.string.engine_tor_in_tunnel
        EngineTor.REVERSE -> R.string.engine_tor_reverse
        EngineTor.ONLY -> R.string.engine_tor_only
    },
)

@Composable
private fun engineTorDescription(mode: EngineTor): String = stringResource(
    when (mode) {
        EngineTor.OFF -> R.string.engine_tor_off_desc
        EngineTor.IN_TUNNEL -> R.string.engine_tor_in_tunnel_desc
        EngineTor.REVERSE -> R.string.engine_tor_reverse_desc
        EngineTor.ONLY -> R.string.engine_tor_only_desc
    },
)

@Composable
private fun engineTorBridgesLabel(source: EngineTorBridges): String = stringResource(
    when (source) {
        EngineTorBridges.AUTO -> R.string.engine_tor_bridges_auto
        EngineTorBridges.ALWAYS -> R.string.engine_tor_bridges_always
        EngineTorBridges.OFF -> R.string.engine_tor_bridges_never
        EngineTorBridges.CUSTOM -> R.string.engine_tor_bridges_custom
    },
)

@Composable
private fun engineTorBridgesDescription(source: EngineTorBridges): String = stringResource(
    when (source) {
        EngineTorBridges.AUTO -> R.string.engine_tor_bridges_auto_desc
        EngineTorBridges.ALWAYS -> R.string.engine_tor_bridges_always_desc
        EngineTorBridges.OFF -> R.string.engine_tor_bridges_never_desc
        EngineTorBridges.CUSTOM -> R.string.engine_tor_bridges_custom_desc
    },
)
