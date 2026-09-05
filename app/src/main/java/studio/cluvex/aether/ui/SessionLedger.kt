package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Locale

/** Session-only subscriptions and presentation stay out of the navigation shell. */
@Composable
internal fun SessionLedger(connectedSince: Long?, ipInfo: IpEndpoint?, ipLoading: Boolean) {
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val traffic by TrafficMonitor.sample.collectAsStateWithLifecycle()
    var detailsExpanded by rememberSaveable(connectedSince) { mutableStateOf(false) }
    val ip = when {
        ipInfo?.viaTunnel == true -> ipInfo.ip
        ipLoading -> stringResource(R.string.ip_checking)
        else -> stringResource(R.string.ip_unavailable)
    }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.passage_session),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Uptime(connectedSince)
        }
        Spacer(Modifier.height(10.dp))
        LedgerRow(stringResource(R.string.ip_server_label), ip)
        LedgerRow(stringResource(R.string.meta_protocol), meta.protocol ?: stringResource(R.string.ip_unavailable), mono = false)
        LatencyRow()
        LedgerRow(stringResource(R.string.traffic_download), TrafficMonitor.formatBytes(if (traffic.live) traffic.downloadBytes else 0))
        LedgerRow(stringResource(R.string.traffic_upload), TrafficMonitor.formatBytes(if (traffic.live) traffic.uploadBytes else 0))
        if (!meta.endpoint.isNullOrBlank()) {
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(stringResource(R.string.passage_details))
                Icon(if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (detailsExpanded) LedgerRow(stringResource(R.string.meta_endpoint), meta.endpoint.orEmpty())
        }
    }
}

@Composable
private fun LatencyRow() {
    val ping by PingMonitor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.meta_latency),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = !ping.running,
            onClick = { scope.launch { PingMonitor.pingOnce(viaTunnel = true) } },
        ) {
            Text(
                text = when {
                    ping.error -> stringResource(R.string.action_retry)
                    ping.ms >= 0 -> String.format(Locale.US, "%d ms", ping.ms)
                    else -> stringResource(R.string.meta_latency_test)
                },
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.ContentOrLtr,
                ),
            )
            Spacer(Modifier.width(8.dp))
            if (ping.running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LedgerRow(label: String, value: String, mono: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            modifier = Modifier.weight(1.25f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (mono) FontFamily.Monospace else null,
                textDirection = TextDirection.ContentOrLtr,
            ),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

internal fun formatSessionUptime(since: Long?, now: Long): String {
    if (since == null) return "…"
    val seconds = ((now - since).coerceAtLeast(0) / 1000)
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
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
                delay(1000L - ((now - since).coerceAtLeast(0) % 1000L))
            }
        }
    }
    Text(
        formatSessionUptime(since, now),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
