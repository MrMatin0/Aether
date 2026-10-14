package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R

/**
 * The engine's resource and stability limits, as a 2x2 grid: how many clients
 * it will serve at once, and how long it waits on a socket before giving up.
 *
 * Blank everywhere means the engine's own sizing, which it derives from the fd
 * limit it raises at startup - a better answer than any constant this app could
 * ship, which is why none of these has a non-zero default. That explanation
 * lives behind the (?) on the card label.
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
    val info = stringResource(R.string.max_clients_help) + "\n\n" + stringResource(R.string.core_resources_help)
    EngineGroup(
        modifier = modifier,
        label = stringResource(R.string.section_core_resources),
        info = info,
    ) {
        FieldPair(
            first = { m ->
                SecondsField(
                    value = maxClients,
                    onValueChange = { value -> edit { copy(maxClients = value) } },
                    label = stringResource(R.string.max_clients_label),
                    placeholder = stringResource(R.string.core_auto_hint),
                    max = MAX_CLIENTS,
                    enabled = enabled,
                    modifier = m,
                )
            },
            second = { m ->
                SecondsField(
                    value = halfCloseSecs,
                    onValueChange = { value -> edit { copy(halfCloseSecs = value) } },
                    label = stringResource(R.string.half_close_secs_label),
                    placeholder = stringResource(R.string.secs_hint),
                    max = EngineLimits.ValidateSecs,
                    enabled = enabled,
                    modifier = m,
                )
            },
        )
        Spacer(Modifier.height(EngineSpacing.Inline))
        FieldPair(
            first = { m ->
                SecondsField(
                    value = tcpKeepaliveSecs,
                    onValueChange = { value -> edit { copy(tcpKeepaliveSecs = value) } },
                    label = stringResource(R.string.tcp_keepalive_secs_label),
                    placeholder = stringResource(R.string.secs_hint),
                    max = EngineLimits.ValidateSecs,
                    enabled = enabled,
                    modifier = m,
                )
            },
            second = { m ->
                SecondsField(
                    value = tcpConnectSecs,
                    onValueChange = { value -> edit { copy(tcpConnectSecs = value) } },
                    label = stringResource(R.string.tcp_connect_secs_label),
                    placeholder = stringResource(R.string.secs_hint),
                    max = MAX_CONNECT_SECS,
                    enabled = enabled,
                    modifier = m,
                )
            },
        )
    }
}

/** Upper bounds, matched to the clamps in ConnectionProfile.toEnv. */
private const val MAX_CLIENTS = 8_192
private const val MAX_CONNECT_SECS = 300
