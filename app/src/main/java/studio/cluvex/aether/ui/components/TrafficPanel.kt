package studio.cluvex.aether.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SocksTunBridge

/**
 * Live traffic meter shown while connected, like mainstream VPN apps:
 * instantaneous download/upload rate plus session totals.
 *
 * Data is the SUM of every possible traffic path, so the meter works in every
 * mode:
 *  - hev-socks5-tunnel's cumulative counters (system-VPN mode), exposed as
 *    direction-corrected totals via HevTunnel.traffic(). In proxy mode the TUN
 *    is skipped and this source is null.
 *  - SocksTunBridge.active: the userspace filter bridge that replaces hev when
 *    per-app blocking is on, and is then the only source of TUN byte counts.
 *  - ShareBridge.traffic(): bytes relayed through the local SOCKS5/HTTP share
 *    listeners. In proxy mode this is the ONLY source (external apps like
 *    Psiphon connect through it); in system-VPN mode it additionally counts
 *    LAN-sharing clients.
 *
 * Counters are polled once per second; rates are computed from deltas against
 * a monotonic clock (SystemClock.elapsedRealtime, immune to wall-clock jumps).
 * Negative deltas (core restart during auto-reconnect, or a new sharing
 * session resetting bridge counters) are clamped to zero and the baseline is
 * rebased automatically.
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

    LaunchedEffect(connectedSince) {
        var lastDown = -1L
        var lastUp = -1L
        var lastAt = 0L
        while (true) {
            val hev = HevTunnel.traffic()
            // Per-app blocking replaces hev with the userspace bridge, which is
            // then the ONLY source of TUN byte counts — without it the meter sat
            // at 0 B/s for the whole session in that mode.
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrafficCell(
                icon = Icons.Rounded.ArrowDownward,
                tint = Color(0xFF32E0C4),
                label = stringResource(R.string.traffic_download),
                rate = downRate,
                total = downTotal,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
            )
            TrafficCell(
                icon = Icons.Rounded.ArrowUpward,
                tint = Color(0xFF4C8DFF),
                label = stringResource(R.string.traffic_upload),
                rate = upRate,
                total = upTotal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrafficCell(
    icon: ImageVector,
    tint: Color,
    label: String,
    rate: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f)) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = formatRate(rate),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.traffic_total, formatBytes(total)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
