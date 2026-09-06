package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import studio.cluvex.aether.R
import studio.cluvex.aether.core.SessionRecord
import studio.cluvex.aether.core.SessionTracker
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherMono

@Composable
fun HistoryPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessions by SessionTracker.sessions.collectAsStateWithLifecycle()
    val behaviour by AppPrefs.state.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sessions.isEmpty()) { if (sessions.isEmpty()) confirming = false }
    ControlSection(stringResource(R.string.history_title), stringResource(R.string.history_count, sessions.size), Icons.Rounded.History, modifier) {
        if (sessions.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                Text(stringResource(if (behaviour.keepHistory) R.string.history_empty else R.string.history_off),
                    Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
                val download = TrafficMonitor.formatBytes(sessions.sumOf { it.downloaded })
                val upload = TrafficMonitor.formatBytes(sessions.sumOf { it.uploaded })
                if (narrow) Column {
                    StatTile(stringResource(R.string.traffic_download), download)
                    StatTile(stringResource(R.string.traffic_upload), upload)
                } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatTile(stringResource(R.string.traffic_download), download, Modifier.weight(1f), Icons.Rounded.South)
                    StatTile(stringResource(R.string.traffic_upload), upload, Modifier.weight(1f), Icons.Rounded.North)
                }
            }
            Spacer(Modifier.height(16.dp))
            (if (showAll) sessions else sessions.take(10)).forEach { record -> Hairline(); SessionRow(record) }
            if (sessions.size > 10) TextButton(onClick = { showAll = !showAll }) {
                Text(stringResource(R.string.passage_details)); Spacer(Modifier.width(8.dp))
                Icon(if (showAll) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            Spacer(Modifier.height(24.dp))
            if (confirming) {
                NoticeBar(stringResource(R.string.ux_clear_history), tone = MaterialTheme.colorScheme.error, icon = Icons.Rounded.DeleteSweep)
                TextButton(onClick = {
                    confirming = false; SessionTracker.clear(context)
                    Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            } else TextButton(onClick = { confirming = true }) {
                Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
@Composable
private fun SessionRow(record: SessionRecord) {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.History, null, Modifier.padding(top = 4.dp).size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(formatter.format(Date(record.startedAt)), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.history_row, formatDuration(record.durationMs), TrafficMonitor.formatBytes(record.downloaded), TrafficMonitor.formatBytes(record.uploaded)),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
