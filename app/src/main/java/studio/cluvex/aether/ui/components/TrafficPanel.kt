package studio.cluvex.aether.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SocksTunBridge
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.Signal

private const val HISTORY = 48

/**
 * Live traffic meter.
 *
 * The counter logic below is UNCHANGED from 1.2.4 and deliberately so: the sum
 * of hev's direction-corrected totals + the userspace filter bridge (the only
 * source when per-app blocking is on) + the share bridge (the only source in
 * proxy mode), polled once a second, with rates derived from deltas against a
 * monotonic clock and negative deltas clamped so a core restart rebases
 * instead of printing garbage.
 *
 * What is new is the reading of it. Two numbers that change every second tell
 * you the rate right now but nothing about the last minute, so a tunnel that
 * has quietly stalled looks identical to an idle one. The 48-sample sparkline
 * makes that difference obvious at a glance. The history lists are read inside
 * the Canvas draw lambda, so plotting costs a redraw, not a recomposition.
 */
@Composable
fun TrafficPanel(
    connectedSince: Long?,
    modifier: Modifier = Modifier,
) {
    var downTotal by remember(connectedSince) { mutableLongStateOf(0L) }
    var upTotal by remember(connectedSince) { mutableLongStateOf(0L) }
    var downRate by remember(connectedSince) { mutableLongStateOf(0L) }
    var upRate by remember(connectedSince) { mutableLongStateOf(0L) }
    val downHistory = remember(connectedSince) { mutableStateListOf<Long>() }
    val upHistory = remember(connectedSince) { mutableStateListOf<Long>() }

    LaunchedEffect(connectedSince) {
        var lastDown = -1L
        var lastUp = -1L
        var lastAt = 0L
        while (true) {
            val hev = HevTunnel.traffic()
            val bridge = SocksTunBridge.active?.getStats()
            val share = ShareBridge.traffic()
            val hasSource = hev != null || bridge != null || ShareBridge.active.value
            if (hasSource) {
                val down = (hev?.downloadBytes ?: 0L) + (bridge?.rxBytes ?: 0L) + share.downloadBytes
                val up = (hev?.uploadBytes ?: 0L) + (bridge?.txBytes ?: 0L) + share.uploadBytes
                val now = SystemClock.elapsedRealtime()
                if (lastAt > 0L && now > lastAt) {
                    val dtMs = now - lastAt
                    downRate = ((down - lastDown).coerceAtLeast(0L) * 1000L) / dtMs
                    upRate = ((up - lastUp).coerceAtLeast(0L) * 1000L) / dtMs
                    if (downHistory.size >= HISTORY) downHistory.removeAt(0)
                    if (upHistory.size >= HISTORY) upHistory.removeAt(0)
                    downHistory.add(downRate)
                    upHistory.add(upRate)
                }
                downTotal = down
                upTotal = up
                lastDown = down
                lastUp = up
                lastAt = now
            }
            delay(1000L)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.traffic_title),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            RateCell(
                icon = Icons.Rounded.ArrowDownward,
                tint = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.traffic_download),
                rate = downRate,
                total = downTotal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            RateCell(
                icon = Icons.Rounded.ArrowUpward,
                tint = MaterialTheme.colorScheme.secondary,
                label = stringResource(R.string.traffic_upload),
                rate = upRate,
                total = upTotal,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Sparkline(
            down = downHistory,
            up = upHistory,
            downTint = MaterialTheme.colorScheme.primary,
            upTint = MaterialTheme.colorScheme.secondary,
            baseline = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun RateCell(
    icon: ImageVector,
    tint: Color,
    label: String,
    rate: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.height(14.dp).width(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = formatRate(rate),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = AetherMono,
                textDirection = TextDirection.Ltr,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.traffic_total, formatBytes(total)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Sparkline(
    down: List<Long>,
    up: List<Long>,
    downTint: Color,
    upTint: Color,
    baseline: Color,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(top = 2.dp),
    ) {
        val peak = maxOf(down.maxOrNull() ?: 0L, up.maxOrNull() ?: 0L, 1L).toFloat()
        val step = if (HISTORY > 1) size.width / (HISTORY - 1).toFloat() else size.width
        val floorY = size.height - 1f

        drawLine(
            color = baseline,
            start = androidx.compose.ui.geometry.Offset(0f, floorY),
            end = androidx.compose.ui.geometry.Offset(size.width, floorY),
            strokeWidth = 1f,
        )

        fun buildPath(values: List<Long>): Path? {
            if (values.size < 2) return null
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = index * step
                val y = floorY - (value / peak) * (size.height - 6f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        buildPath(down)?.let { line ->
            val filled = Path()
            filled.addPath(line)
            filled.lineTo((down.size - 1) * step, floorY)
            filled.lineTo(0f, floorY)
            filled.close()
            drawPath(
                path = filled,
                brush = Brush.verticalGradient(
                    listOf(downTint.copy(alpha = 0.20f), Color.Transparent),
                ),
            )
            drawPath(
                path = line,
                color = downTint,
                style = Stroke(width = 2f, cap = StrokeCap.Round),
            )
        }
        buildPath(up)?.let { line ->
            drawPath(
                path = line,
                color = upTint.copy(alpha = 0.9f),
                style = Stroke(width = 2f, cap = StrokeCap.Round),
            )
        }
    }
}

private fun formatBytes(v: Long): String {
    if (v < 1024L) return "$v B"
    val kb = v / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatRate(v: Long): String = formatBytes(v) + "/s"

/** Kept so the accent import is meaningful if the palette is ever swapped. */
private val UnusedAccentAnchor: Color = Signal
