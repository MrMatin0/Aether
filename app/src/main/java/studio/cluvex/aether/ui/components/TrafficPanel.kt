package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * Live DATA USAGE.
 *
 * THE 1.3 DECISION STANDS: this is not a speedometer. It used to plot two
 * per-second rates plus a 48-sample sparkline of those rates. The rate belongs
 * in the notification, and that is where it lives — it answers "is anything
 * moving right now", and the moment you need that answer is the moment you are
 * NOT looking at this screen. The sparkline spent 52dp plotting a history nobody
 * acts on: knowing the shape of the last 48 seconds of throughput changes no
 * decision inside a VPN client.
 *
 * What the same space answers instead is the question people open this app with
 * on a metered plan: HOW MUCH have I burned this session. Cumulative volume per
 * direction, the split between them, and the total.
 *
 * WHAT CHANGED IN THIS PASS: it is a card with two tiles instead of two bare
 * columns floating between hairlines, the direction arrows are badges rather than
 * 14dp glyphs, and the split bar has real end caps so a 2%-upload session does
 * not render as a hairline crumb.
 *
 * The counters come from [TrafficMonitor], which the VPN service runs for the
 * lifetime of the session — so unlike an in-composable polling loop, the numbers
 * keep accruing while this screen is closed.
 */
@Composable
fun TrafficPanel(
    connectedSince: Long?,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
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
        targetValue = if (sample.live && (sample.at / 1000L) % 2L == 0L) 1f else 0.25f,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "pulse",
    )

    val downTone = accents.protected
    val upTone = accents.brand
    val downPercent = if (total > 0L) ((sample.downloadBytes * 100L) / total).toInt() else 0
    val upPercent = if (total > 0L) 100 - downPercent else 0

    AetherCard(modifier = modifier) {
        CardHeader(
            title = stringResource(R.string.traffic_usage_title),
            subtitle = stringResource(
                R.string.traffic_usage_total,
                TrafficMonitor.formatBytes(total),
            ),
            icon = Icons.Rounded.SwapVert,
            tint = downTone,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(downTone.copy(alpha = pulse), CircleShape),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(R.string.traffic_usage_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = stringResource(R.string.traffic_download),
                value = TrafficMonitor.formatBytes(sample.downloadBytes),
                icon = Icons.Rounded.ArrowDownward,
                tint = downTone,
                footnote = stringResource(R.string.traffic_usage_share, downPercent),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StatTile(
                label = stringResource(R.string.traffic_upload),
                value = TrafficMonitor.formatBytes(sample.uploadBytes),
                icon = Icons.Rounded.ArrowUpward,
                tint = upTone,
                footnote = stringResource(R.string.traffic_usage_share, upPercent),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        SplitBar(
            downShare = downShare,
            downTint = downTone,
            upTint = upTone,
            track = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Download vs upload, as one 8dp bar.
 *
 * Not a chart: it plots no time axis and carries a single fact — which direction
 * this session actually spent its bytes on. Built from two weighted boxes rather
 * than a Canvas, so it costs a layout pass and no draw code. Both halves are
 * clamped away from zero and independently rounded, so a lopsided session still
 * shows two segments instead of one bar and a rendering artefact.
 */
@Composable
private fun SplitBar(
    downShare: Float,
    downTint: androidx.compose.ui.graphics.Color,
    upTint: androidx.compose.ui.graphics.Color,
    track: androidx.compose.ui.graphics.Color,
) {
    val down = downShare.coerceIn(0.04f, 0.96f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(track.copy(alpha = 0.4f)),
    ) {
        Box(
            modifier = Modifier
                .weight(down)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(downTint),
        )
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .weight(1f - down)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(upTint.copy(alpha = 0.85f)),
        )
    }
}
