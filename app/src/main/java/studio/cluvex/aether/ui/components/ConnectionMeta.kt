package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor

/**
 * Shows, under the main button:
 *   - the IP + country flag (exit server when connected, operator IP when not),
 *   - the desktop-parity info row (1.2.4): PROTOCOL / ENDPOINT / live LATENCY,
 *   - a live HH:MM:SS uptime counter while connected.
 */
@Composable
fun ConnectionMeta(
    connected: Boolean,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IpBadge(connected = connected, ipInfo = ipInfo, ipLoading = ipLoading)

        // Desktop-parity info row (1.2.4): directly under the IP badge,
        // exactly like the Windows edition. Replaces the old standalone
        // ping badge, whose job the LATENCY cell now does live.
        MetaRow(connected = connected)

        if (connectedSince != null) {
            ConnectionTimer(connectedSince = connectedSince)
        }
    }
}

@Composable
private fun IpBadge(
    connected: Boolean,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
) {
    val label = if (connected) {
        stringResource(R.string.ip_server_label)
    } else {
        stringResource(R.string.ip_your_label)
    }

    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val value = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> "$flag  ${ipInfo.ip}"
        else -> stringResource(R.string.ip_unavailable)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ip",
            ) { shown ->
                Text(
                    text = shown,
                    // BiDi fix: "104.28.197.15" + country flag is LTR technical
                    // text; in the Persian (RTL) locale the BiDi algorithm
                    // reordered the digits/dots. Pin the direction to LTR.
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Desktop-parity info row (ported from the Windows edition's meta cells):
 * PROTOCOL, ENDPOINT and a live LATENCY, in one card with three equal cells
 * so nothing overlaps or overflows on narrow screens. All values are pinned
 * LTR where they are technical (IP:port, milliseconds).
 */
@Composable
private fun MetaRow(connected: Boolean) {
    val meta by EngineMeta.state.collectAsState()
    val ping by PingMonitor.state.collectAsState()

    // Live latency like the desktop edition: re-measure every few seconds
    // while the tunnel is up. PingMonitor serialises concurrent runs behind
    // a mutex and each run is one cheap TCP handshake through the tunnel, so
    // a 4 s cadence stays battery-friendly.
    LaunchedEffect(connected) {
        while (connected) {
            PingMonitor.pingOnce(viaTunnel = true)
            delay(LATENCY_REFRESH_MS)
        }
    }

    val protocol = if (connected) meta.protocol ?: "\u2014" else "\u2014"
    val endpoint = if (connected) meta.endpoint ?: "\u2026" else "\u2014"
    val latency = when {
        !connected -> "\u2014"
        ping.ms >= 0 -> "${ping.ms} ms"
        ping.running -> "\u2026"
        else -> "\u2014"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaCell(
                label = stringResource(R.string.meta_protocol),
                value = protocol,
                ltr = true,
                modifier = Modifier.weight(1f),
            )
            MetaCell(
                label = stringResource(R.string.meta_endpoint),
                value = endpoint,
                ltr = true,
                modifier = Modifier.weight(1f),
            )
            MetaCell(
                label = stringResource(R.string.meta_latency),
                value = latency,
                ltr = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetaCell(
    label: String,
    value: String,
    ltr: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
            ),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val LATENCY_REFRESH_MS = 4_000L

@Composable
private fun ConnectionTimer(connectedSince: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val elapsed = (now - connectedSince).coerceAtLeast(0L) / 1000L
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    val text = "%02d:%02d:%02d".format(h, m, s)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.connected_for),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
