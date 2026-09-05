package studio.cluvex.aether.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/** Connection overview. Its shortcuts only navigate; they cannot toggle the VPN. */
@Composable
internal fun ConnectionHome(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    scrollState: ScrollState,
    onOpenEngine: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(14.dp))
        ConnectionSummary(state, profile, tone, wash)
        PassageMark(tone)
        val step = connectionStep(state)
        if (step != null) {
            ConnectionSteps(step)
        } else if (state !is ConnectionState.Disconnecting) {
            Text(
                stringResource(R.string.passage_route),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(22.dp))
        EngineShortcut(profile = profile, onClick = onOpenEngine)
        when (state) {
            is ConnectionState.Connected -> SessionLedger(connectedSince, ipInfo, ipLoading)
            is ConnectionState.Idle -> {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.passage_privacy),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is ConnectionState.Error -> TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.passage_see_error)) }
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionSummary(
    state: ConnectionState,
    profile: ConnectionProfile,
    tone: Color,
    wash: Color,
) {
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = wash, contentColor = tone, shape = RoundedCornerShape(24.dp)) {
            Text(
                text = stringResource(when {
                    state.isConnected -> R.string.passage_verified
                    state is ConnectionState.Error -> R.string.pill_failed
                    state.isBusy -> R.string.pill_working
                    else -> R.string.pill_off
                }),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(connectionTitle(state), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            connectionHint(state, profile),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EngineShortcut(profile: ConnectionProfile, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Rounded.Tune, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.page_engine_title), style = MaterialTheme.typography.titleSmall)
            Text(scanLabel(profile.scanMode), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}

/** Null means there is no forward connection pipeline to display. */
internal fun connectionStep(state: ConnectionState): Int? = when (state) {
    is ConnectionState.Launching -> 0
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> 1
    is ConnectionState.Verifying -> 2
    is ConnectionState.Idle, is ConnectionState.Connected,
    is ConnectionState.Disconnecting, is ConnectionState.Error -> null
}

@Composable
private fun ConnectionSteps(active: Int) {
    val accents = LocalAetherAccents.current
    val labels = listOf(R.string.phase_engine, R.string.phase_tunnel, R.string.phase_verify, R.string.phase_ready)
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        labels.forEachIndexed { index, label ->
            val color = when {
                index < active -> accents.protected
                index == active -> accents.working
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (index < active) "✓" else "${index + 1}", color = color, modifier = Modifier.width(32.dp))
                Text(stringResource(label), color = color, modifier = Modifier.weight(1f))
            }
        }
        Text(
            stringResource(R.string.passage_scan_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PassageMark(tone: Color) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.padding(vertical = 12.dp).size(220.dp, 140.dp)) {
        val center = size.width / 2
        val base = size.height * .86f
        val top = size.height * .48f
        repeat(4) { index ->
            val radius = size.width * .32f - size.width * .059f * index
            val path = Path().apply {
                moveTo(center - radius, base)
                lineTo(center - radius, top)
                cubicTo(center - radius, top - radius * .55f, center - radius * .55f, top - radius, center, top - radius)
                cubicTo(center + radius * .55f, top - radius, center + radius, top - radius * .55f, center + radius, top)
                lineTo(center + radius, base)
            }
            drawPath(path, if (index < 2) line else tone, style = Stroke(1.4.dp.toPx()))
        }
        drawLine(line, Offset(size.width * .08f, base), Offset(size.width * .92f, base), 1.dp.toPx())
        drawLine(tone, Offset(center, base + 3.dp.toPx()), Offset(center, size.height), 1.4.dp.toPx(), cap = StrokeCap.Round)
    }
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
