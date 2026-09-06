package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    LaunchedEffect(sessions.isEmpty()) { if (sessions.isEmpty()) confirming = false }
    AetherCard(modifier = modifier) {
        CardHeader(stringResource(R.string.history_title), subtitle = stringResource(R.string.history_count, sessions.size), icon = Icons.Rounded.History)
        if (sessions.isEmpty()) {
            Hint(stringResource(if (behaviour.keepHistory) R.string.history_empty else R.string.history_off))
            return@AetherCard
        }
        Spacer(Modifier.height(16.dp))
        StatTile(stringResource(R.string.traffic_download), TrafficMonitor.formatBytes(sessions.sumOf { it.downloaded }), modifier = Modifier.fillMaxWidth())
        StatTile(stringResource(R.string.traffic_upload), TrafficMonitor.formatBytes(sessions.sumOf { it.uploaded }), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        sessions.take(10).forEach { record -> Hairline(); SessionRow(record) }
        Spacer(Modifier.height(16.dp))
        if (confirming) {
            Text(stringResource(R.string.ux_clear_history), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                confirming = false
                SessionTracker.clear(context)
                Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
        } else {
            ActionPill(label = stringResource(R.string.history_clear), icon = Icons.Rounded.DeleteSweep,
                tint = MaterialTheme.colorScheme.error, onClick = { confirming = true })
        }
    }
}

@Composable
private fun SessionRow(record: SessionRecord) {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(formatter.format(Date(record.startedAt)), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.history_row, formatDuration(record.durationMs),
            TrafficMonitor.formatBytes(record.downloaded), TrafficMonitor.formatBytes(record.uploaded)),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
