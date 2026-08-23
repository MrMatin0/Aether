package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono

/**
 * Live DATA USAGE.
 *
 * WHAT CHANGED AND WHY
 *
 * This panel used to be a speedometer: two per-second rates plus a 48-sample
 * sparkline of those rates. Both are gone.
 *
 * The rate belongs in the notification, and that is where it now lives — it is
 * a "is anything moving right now" signal, and the moment you need it is the
 * moment you are NOT looking at this screen. Keeping a second copy here meant
 * the most prominent number in the app answered a question the user had
 * already answered by pulling down the shade, and the sparkline spent 52dp
 * plotting a history nobody acts on: knowing the shape of the last 48 seconds
 * of throughput changes no decision inside a VPN client.
 *
 * What the same screen real estate answers instead is the question people
 * actually open this app with on a metered Iranian mobile plan: HOW MUCH have
 * I burned this session. So the cells now show cumulative download and upload
 * VOLUME, still refreshed once a second, with the session total and the split
 * between the two directions.
 *
 * The counters come from [TrafficMonitor], which the VPN service runs for the
 * lifetime of the session — so unlike the old in-composable polling loop, the
 * numbers keep accruing while this screen is closed.
 */
@Composable
fun TrafficPanel(
    connectedSince: Long?,
    modifier: Modifier = Modifier,
) {
    val latest by TrafficMonitor.sample.collectAsState()

    // A fresh session must never show the previous one's last reading, not even
    // for the one frame between "Connected" and the monitor's first tick.
    val zero = remember(connectedSince) { TrafficMonitor.Sample() }
    val sample = if (latest.live) latest else zero

    val total = sample.totalBytes
    val downShare by animateFloatAsState(
        targetValue = if (total > 0L) sample.downloadBytes.toFloat() / total.toFloat() else 0.5f,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "share",
    )
    // A one-second heartbeat, driven by the sample's own timestamp: proof that
    // the readout is live and not a frozen leftover.
    val pulse by animateFloatAsState(
        targetValue = if (sample.live && (sample.at / 1000L) % 2L == 0L) 1f else 0.3f,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "pulse",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.traffic_usage_title),
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = pulse),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.traffic_usage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            UsageCell(
                icon = Icons.Rounded.ArrowDownward,
                tint = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.traffic_download),
                bytes = sample.downloadBytes,
                share = if (total > 0L) sample.downloadBytes else 0L,
                total = total,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            UsageCell(
                icon = Icons.Rounded.ArrowUpward,
                tint = MaterialTheme.colorScheme.onBackground,
                label = stringResource(R.string.traffic_upload),
                bytes = sample.uploadBytes,
                share = if (total > 0L) sample.uploadBytes else 0L,
                total = total,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        SplitBar(
            downShare = downShare,
            downTint = MaterialTheme.colorScheme.primary,
            upTint = MaterialTheme.colorScheme.onBackground,
            track = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.traffic_usage_total,
                TrafficMonitor.formatBytes(total),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** One direction: cumulative volume large, its share of the session small. */
@Composable
private fun UsageCell(
    icon: ImageVector,
    tint: Color,
    label: String,
    bytes: Long,
    share: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val percent = if (total > 0L) ((share * 100L) / total).toInt() else 0
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(14.dp),
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
            text = TrafficMonitor.formatBytes(bytes),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = AetherMono,
                textDirection = TextDirection.Ltr,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.traffic_usage_share, percent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Download vs upload, as one 6dp bar.
 *
 * Not a chart: it plots no time axis and carries a single fact — which
 * direction this session actually spent its bytes on. Built from two weighted
 * boxes rather than a Canvas, so it costs a layout pass and no draw code.
 */
@Composable
private fun SplitBar(
    downShare: Float,
    downTint: Color,
    upTint: Color,
    track: Color,
) {
    val down = downShare.coerceIn(0.02f, 0.98f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track.copy(alpha = 0.4f)),
    ) {
        Box(
            modifier = Modifier
                .weight(down)
                .fillMaxWidth()
                .height(6.dp)
                .background(downTint),
        )
        Spacer(
            modifier = Modifier
                .width(2.dp)
                .height(6.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f - down)
                .fillMaxWidth()
                .height(6.dp)
                .background(upTint.copy(alpha = 0.75f))
                .padding(0.dp),
        )
    }
}
