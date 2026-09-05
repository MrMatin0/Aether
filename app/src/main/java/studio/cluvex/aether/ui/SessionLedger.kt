package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.ui.components.SectionTitle
import studio.cluvex.aether.ui.theme.AetherMono
import java.util.Locale

/** Real rates and totals from the session monitor, never estimated by the view. */
@Composable
internal fun SessionLedger(connectedSince: Long?, ipInfo: IpEndpoint?, ipLoading: Boolean) {
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val traffic by TrafficMonitor.sample.collectAsStateWithLifecycle()
    var details by rememberSaveable(connectedSince) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.passage_session), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Uptime(connectedSince)
        }
        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.traffic_title))
        TrafficRow(stringResource(R.string.traffic_download), if (traffic.live) traffic.downloadRate else 0L, if (traffic.live) traffic.downloadBytes else 0L)
        TrafficRow(stringResource(R.string.traffic_upload), if (traffic.live) traffic.uploadRate else 0L, if (traffic.live) traffic.uploadBytes else 0L)
        Spacer(Modifier.height(24.dp))
        LedgerRow(stringResource(R.string.ip_server_label), when {
            ipInfo?.viaTunnel == true -> ipInfo.ip
            ipLoading -> stringResource(R.string.ip_checking)
            else -> stringResource(R.string.ip_unavailable)
        })
        LatencyRow()
        TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.passage_details))
            Spacer(Modifier.width(8.dp))
            Icon(if (details) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (details) {
            LedgerRow(stringResource(R.string.meta_protocol), meta.protocol.orEmpty())
            if (!meta.endpoint.isNullOrBlank()) LedgerRow(stringResource(R.string.meta_endpoint), meta.endpoint.orEmpty())
        }
    }
}

@Composable
private fun TrafficRow(label: String, rate: Long, total: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(TrafficMonitor.formatRate(rate), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr))
        Text(stringResource(R.string.traffic_total, TrafficMonitor.formatBytes(total)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LatencyRow() {
    val ping by PingMonitor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.meta_latency), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = !ping.running, onClick = { scope.launch { PingMonitor.pingOnce(viaTunnel = true) } }) {
            Text(when {
                ping.error -> stringResource(R.string.action_retry)
                ping.ms >= 0 -> String.format(Locale.US, "%d ms", ping.ms)
                else -> stringResource(R.string.meta_latency_test)
            }, style = MaterialTheme.typography.labelLarge.copy(fontFamily = AetherMono, textDirection = TextDirection.ContentOrLtr))
            Spacer(Modifier.width(8.dp))
            if (ping.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LedgerRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AetherMono, textDirection = TextDirection.ContentOrLtr))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

internal fun formatSessionUptime(since: Long?, now: Long): String {
    if (since == null) return "…"
    val seconds = (now - since).coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

@Composable
private fun Uptime(since: Long?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember(since) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since, lifecycle) {
        if (since == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1000L - (now - since).coerceAtLeast(0) % 1000L)
            }
        }
    }
    Text(formatSessionUptime(since, now),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
