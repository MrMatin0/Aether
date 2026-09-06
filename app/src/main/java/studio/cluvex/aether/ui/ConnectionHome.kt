package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.LocalAetherAccents

@Composable
internal fun ConnectionHome(
    state: ConnectionState, profile: ConnectionProfile, connectedSince: Long?,
    ipInfo: IpEndpoint?, ipLoading: Boolean, scrollState: ScrollState,
    onOpenEngine: () -> Unit, onOpenDiagnostics: () -> Unit,
) {
    val a = LocalAetherAccents.current
    val tone = when { state.isConnected -> a.protected; state is ConnectionState.Error -> a.failed; state.isBusy -> a.working; else -> a.neutral }
    val wash = when { state.isConnected -> a.protectedWash; state is ConnectionState.Error -> a.failedWash; state.isBusy -> a.workingWash; else -> MaterialTheme.colorScheme.surfaceVariant }
    val busy = connectionStep(state) != null
    val started = rememberSaveable(busy) { SystemClock.elapsedRealtime() }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        Surface(color = wash, contentColor = tone, shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(when { state.isConnected -> Icons.Rounded.Check; state is ConnectionState.Error -> Icons.Rounded.Warning; else -> Icons.Rounded.Shield }, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(connectionStatusLabel(state)), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(connectionTitle(state), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(connectionHint(state, profile), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SignalDiagram(tone, state.isConnected, Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.passage_route), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        connectionStep(state)?.let { step ->
            ConnectionSteps(step)
            ConnectingClock(started)
            Spacer(Modifier.height(32.dp))
        }
        if (state is ConnectionState.Connected) {
            SessionLedger(connectedSince, ipInfo, ipLoading)
            Spacer(Modifier.height(32.dp))
        }
        if (state is ConnectionState.Error) {
            FilledTonalButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Rounded.Terminal, null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.passage_see_error))
            }
            Spacer(Modifier.height(24.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavRow(title = stringResource(R.string.page_engine_title), subtitle = scanLabel(profile.scanMode),
            icon = Icons.Rounded.Tune, onClick = onOpenEngine)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state is ConnectionState.Idle) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.passage_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(32.dp))
    }
}
internal fun connectionStep(state: ConnectionState): Int? = when (state) {
    is ConnectionState.Launching -> 0
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> 1
    is ConnectionState.Verifying -> 2
    else -> null
}
@Composable
private fun ConnectionSteps(active: Int) {
    val a = LocalAetherAccents.current
    val labels = listOf(R.string.phase_engine, R.string.phase_tunnel, R.string.phase_verify, R.string.phase_ready)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val current = index == active
            val color = when { index < active -> a.protected; current -> a.working; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Surface(color = if (current) a.workingWash else MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (index < active) Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), color)
                    else Text("${index + 1}", Modifier.width(24.dp), color = color, fontFamily = AetherMono)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(label), color = color, modifier = Modifier.weight(1f),
                        style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    if (current) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), color)
                }
            }
        }
    }
}
@Composable
private fun ConnectingClock(since: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(since, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = SystemClock.elapsedRealtime(); delay(1000L) }
        }
    }
    Text(stringResource(R.string.busy_elapsed, formatSessionUptime(since, now)), style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrLtr),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
}
@Composable
private fun connectionTitle(state: ConnectionState): String = stringResource(when (state) {
    is ConnectionState.Idle -> R.string.passage_ready
    is ConnectionState.Launching -> R.string.state_launching
    is ConnectionState.Connecting -> R.string.state_connecting
    is ConnectionState.Verifying -> R.string.state_verifying
    is ConnectionState.Connected -> R.string.passage_connected
    is ConnectionState.Reconnecting -> R.string.state_reconnecting
    is ConnectionState.Disconnecting -> R.string.state_disconnecting
    is ConnectionState.Error -> R.string.passage_failed
})
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
