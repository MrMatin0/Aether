package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/** Status, evidence, then configuration. No decorative control toggles the VPN. */
@Composable
internal fun ConnectionHome(
    state: ConnectionState, profile: ConnectionProfile, connectedSince: Long?,
    ipInfo: IpEndpoint?, ipLoading: Boolean, scrollState: ScrollState,
    onOpenEngine: () -> Unit, onOpenDiagnostics: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val tone = when {
        state.isConnected -> accents.protected
        state is ConnectionState.Error -> accents.failed
        state.isBusy -> accents.working
        else -> accents.neutral
    }
    val wash = when {
        state.isConnected -> accents.protectedWash
        state is ConnectionState.Error -> accents.failedWash
        state.isBusy -> accents.workingWash
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    // The busy interval survives tab changes, but a new attempt gets a fresh clock.
    val busy = connectionStep(state) != null
    val started = rememberSaveable(busy) { SystemClock.elapsedRealtime() }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Surface(color = wash, contentColor = tone, shape = MaterialTheme.shapes.small) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(connectionStatusLabel(state)), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(connectionTitle(state), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(connectionHint(state, profile), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        val step = connectionStep(state)
        if (step != null) {
            ConnectionSteps(step)
            ConnectingClock(started)
            Spacer(Modifier.height(24.dp))
        }
        if (state is ConnectionState.Connected) {
            SessionLedger(connectedSince, ipInfo, ipLoading)
            Spacer(Modifier.height(32.dp))
        }
        if (state is ConnectionState.Error) {
            FilledTonalButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.passage_see_error))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
            }
            Spacer(Modifier.height(24.dp))
        }
        Surface(
            onClick = onOpenEngine, color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = accents.brand)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.page_engine_title), style = MaterialTheme.typography.titleMedium)
                    Text(scanLabel(profile.scanMode), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.passage_route), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state is ConnectionState.Idle) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.passage_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val accents = LocalAetherAccents.current
    val labels = listOf(R.string.phase_engine, R.string.phase_tunnel, R.string.phase_verify, R.string.phase_ready)
    Column {
        labels.forEachIndexed { index, label ->
            val current = index == active
            val color = when {
                index < active -> accents.protected
                current -> accents.working
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index < active) Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), tint = color)
                else Text("${index + 1}", Modifier.width(24.dp), color = color, fontFamily = AetherMono)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(label), color = color,
                    style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
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
    Text(stringResource(R.string.busy_elapsed, formatSessionUptime(since, now)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
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
