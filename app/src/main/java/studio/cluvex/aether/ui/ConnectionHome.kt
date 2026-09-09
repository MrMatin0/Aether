package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.ConnectionMeta
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SignalDiagram
import studio.cluvex.aether.ui.components.StatTile
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.LocalAetherAccents

private const val PHASE_COUNT = 4

internal fun buttonMode(state: ConnectionState): ButtonMode = when {
    state.isConnected -> ButtonMode.CONNECTED
    state is ConnectionState.Error -> ButtonMode.ERROR
    state.isBusy -> ButtonMode.BUSY
    else -> ButtonMode.IDLE
}

internal fun connectionStep(state: ConnectionState): Int? = when (state) {
    is ConnectionState.Launching -> 0
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> 1
    is ConnectionState.Verifying -> 2
    else -> null
}

internal fun phaseProgress(step: Int?): Float =
    if (step == null) 0f else ((step + 1).toFloat() / PHASE_COUNT).coerceIn(0f, 1f)

@Composable
internal fun ConnectionHome(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    onToggleConnection: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val mode = buttonMode(state)
    val tone = accentFor(mode)
    val step = connectionStep(state)
    val working = step != null
    val startedAt = rememberSaveable(working) { SystemClock.elapsedRealtime() }
    val elapsed = tickingElapsed(startedAt, working)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateChip(state, tone)
        ConnectButton(
            mode = mode,
            onClick = onToggleConnection,
            stateLabel = stateWord(state),
            actionLabel = stringResource(connectionActionLabel(state)),
            modifier = Modifier.padding(vertical = 4.dp),
            detail = if (working) elapsed else null,
            progress = phaseProgress(step),
        )
        Text(
            connectionTitle(state),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            connectionHint(state, profile),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        when {
            step != null -> {
                PhaseTrack(step, tone, elapsed)
                if (step <= 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.passage_scan_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state is ConnectionState.Error -> NoticeBar(
                text = state.message,
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            state is ConnectionState.Connected -> ConnectionMeta(
                connected = true,
                connectedSince = connectedSince,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
            )
            else -> RoutePreview(profile, tone)
        }
    }
}

@Composable
private fun StateChip(state: ConnectionState, tone: Color) {
    val icon = when {
        state.isConnected -> Icons.Rounded.Check
        state is ConnectionState.Error -> Icons.Rounded.Warning
        else -> Icons.Rounded.Shield
    }
    Surface(color = stateWash(state), contentColor = tone, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(connectionStatusLabel(state)), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PhaseTrack(active: Int, tone: Color, elapsed: String) {
    val labels = listOf(R.string.phase_engine, R.string.phase_tunnel, R.string.phase_verify, R.string.phase_ready)
    val current = active.coerceIn(0, labels.lastIndex)
    AetherCard {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.passage_phase_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                elapsed,
                style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, _ ->
                val fill = when {
                    index < current -> tone
                    index == current -> tone.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(fill))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                Text(
                    stringResource(label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RoutePreview(profile: ConnectionProfile, tone: Color) {
    AetherCard {
        CardHeader(
            title = stringResource(R.string.passage_route_title),
            subtitle = stringResource(R.string.passage_route),
            icon = Icons.Rounded.Public,
            tint = tone,
        )
        SignalDiagram(tone, verified = false, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile(
                label = stringResource(R.string.meta_protocol),
                value = protocolLabel(profile.protocol),
                icon = Icons.Rounded.Shield,
                tint = tone,
                mono = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StatTile(
                label = stringResource(R.string.scan_mode),
                value = scanLabel(profile.scanMode),
                icon = Icons.Rounded.Tune,
                tint = tone,
                mono = false,
                footnote = scanEta(profile.scanMode),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun tickingElapsed(since: Long, running: Boolean): String {
    var now by remember(since) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(since, running, lifecycle) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val tick = SystemClock.elapsedRealtime()
                now = tick
                val intoSecond = (tick - since).coerceAtLeast(0L) % 1000L
                delay((1000L - intoSecond).coerceIn(1L, 1000L))
            }
        }
    }
    return formatSessionUptime(since, now)
}

@Composable
private fun stateWord(state: ConnectionState): String = stringResource(
    when {
        state.isConnected -> R.string.pill_secure
        state is ConnectionState.Error -> R.string.pill_failed
        state.isBusy -> R.string.pill_working
        else -> R.string.pill_off
    },
)

@Composable
private fun stateWash(state: ConnectionState): Color {
    val a = LocalAetherAccents.current
    return when {
        state.isConnected -> a.protectedWash
        state is ConnectionState.Error -> a.failedWash
        state.isBusy -> a.workingWash
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun connectionTitle(state: ConnectionState): String = stringResource(
    when (state) {
        is ConnectionState.Idle -> R.string.passage_ready
        is ConnectionState.Launching -> R.string.state_launching
        is ConnectionState.Connecting -> R.string.state_connecting
        is ConnectionState.Verifying -> R.string.state_verifying
        is ConnectionState.Connected -> R.string.passage_connected
        is ConnectionState.Reconnecting -> R.string.state_reconnecting
        is ConnectionState.Disconnecting -> R.string.state_disconnecting
        is ConnectionState.Error -> R.string.passage_failed
    },
)

@Composable
private fun connectionHint(state: ConnectionState, profile: ConnectionProfile): String = when (state) {
    is ConnectionState.Idle -> stringResource(R.string.passage_start_hint)
    is ConnectionState.Connected -> stringResource(R.string.passage_connected_hint)
    is ConnectionState.Launching, is ConnectionState.Connecting -> stringResource(R.string.busy_hint, scanLabel(profile.scanMode))
    is ConnectionState.Verifying -> stringResource(R.string.state_verify_hint)
    is ConnectionState.Reconnecting -> stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
    is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
    is ConnectionState.Error -> state.message
}
