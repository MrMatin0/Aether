package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import studio.cluvex.aether.R
import studio.cluvex.aether.core.SessionRecord
import studio.cluvex.aether.core.SessionTracker
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.StatTile
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * What the tunnel actually did, session by session.
 *
 * The live meter dies with the session, so "did it hold for four hours or four
 * minutes" and "what did that evening cost me in data" were unanswerable. Ten
 * rows of duration + bytes answer both.
 *
 * WHAT CHANGED: the totals used to be one accent-coloured caption above the
 * list. They are now two tiles, because on a metered SIM the totals ARE the
 * feature and the ten rows are the supporting detail — and this panel now sits
 * directly under the switch that controls whether any of it is recorded, which is
 * where it always belonged.
 */
@Composable
fun HistoryPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    val sessions by SessionTracker.sessions.collectAsState()
    val behaviour by AppPrefs.state.collectAsState()

    AetherCard(modifier = modifier) {
        CardHeader(
            title = stringResource(R.string.history_title),
            subtitle = stringResource(
                R.string.history_count,
                sessions.size,
            ),
            icon = Icons.Rounded.History,
            tint = accents.brand,
        )

        if (sessions.isEmpty()) {
            Hint(
                stringResource(
                    if (behaviour.keepHistory) R.string.history_empty else R.string.history_off,
                ),
            )
            return@AetherCard
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = stringResource(R.string.traffic_download),
                value = formatBytes(sessions.sumOf { it.downloaded }),
                tint = accents.protected,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StatTile(
                label = stringResource(R.string.traffic_upload),
                value = formatBytes(sessions.sumOf { it.uploaded }),
                tint = accents.brand,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))
        sessions.take(10).forEachIndexed { index, record ->
            if (index > 0) Hairline(alpha = 0.5f)
            SessionRow(record)
        }

        Spacer(Modifier.height(14.dp))
        ActionPill(
            label = stringResource(R.string.history_clear),
            icon = Icons.Rounded.DeleteSweep,
            tint = MaterialTheme.colorScheme.error,
            onClick = {
                SessionTracker.clear(context)
                Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun SessionRow(record: SessionRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatWhen(record.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.history_row,
                    formatDuration(record.durationMs),
                    formatBytes(record.downloaded),
                    formatBytes(record.uploaded),
                ),
                // Durations and byte counts are numerals: never let the BiDi
                // algorithm reorder them in the Persian locale.
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = AetherMono,
                    textDirection = TextDirection.Ltr,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Locale-aware, because the JVM default locale follows the in-app language. */
private fun formatWhen(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun formatBytes(v: Long): String {
    if (v < 1024L) return "$v B"
    val kb = v / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
