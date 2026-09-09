package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherNumeralLarge
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

private const val DASH = "\u2014"
private const val ELLIPSIS = "\u2026"

@Composable
fun ConnectionMeta(
    connected: Boolean,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val meta by EngineMeta.state.collectAsState()
    val ping by PingMonitor.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ipFadeInMs = aetherDuration(AetherDur.Base)
    val ipFadeOutMs = aetherDuration(AetherDur.Quick)

    LaunchedEffect(connected) {
        if (!connected) PingMonitor.reset()
    }

    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val ipValue = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> ipInfo.ip
        else -> stringResource(R.string.ip_unavailable)
    }
    val tone = if (connected) accents.protected else accents.neutral

    AetherCard(modifier = modifier) {
        CardHeader(
            title = if (connected) stringResource(R.string.ip_server_label) else stringResource(R.string.ip_your_label),
            subtitle = stringResource(if (connected) R.string.meta_exit_note else R.string.meta_origin_note),
            icon = Icons.Rounded.Public,
            tint = tone,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flag.isNotBlank()) {
                Text(text = flag, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(10.dp))
            }
            AnimatedContent(
                targetState = ipValue,
                transitionSpec = { fadeIn(tween(ipFadeInMs)) togetherWith fadeOut(tween(ipFadeOutMs)) },
                label = "ip",
                modifier = Modifier.weight(1f),
            ) { shown ->
                Text(
                    text = shown,
                    style = AetherNumeralLarge.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile(
                label = stringResource(R.string.meta_protocol),
                value = if (connected) meta.protocol ?: DASH else DASH,
                icon = Icons.Rounded.Shield,
                tint = tone,
                mono = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StatTile(
                label = stringResource(R.string.meta_location),
                value = ipInfo?.countryCode?.uppercase(Locale.US) ?: DASH,
                icon = Icons.Rounded.Public,
                tint = tone,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UptimeTile(
                connectedSince = if (connected) connectedSince else null,
                tint = tone,
                modifier = Modifier.weight(1.15f),
            )
            Spacer(Modifier.width(10.dp))
            StatTile(
                label = stringResource(R.string.meta_latency),
                value = when {
                    !connected -> DASH
                    ping.ms >= 0 -> "${ping.ms} ms"
                    ping.running -> ELLIPSIS
                    else -> DASH
                },
                icon = Icons.Rounded.Speed,
                tint = tone,
                footnote = if (connected) stringResource(R.string.meta_latency_hint) else null,
                onClick = if (connected && !ping.running) {
                    { scope.launch { PingMonitor.pingOnce(viaTunnel = true) } }
                } else null,
                onClickLabel = stringResource(R.string.meta_latency_test),
                trailing = if (connected) { { QualityBars(ms = ping.ms) } } else null,
                modifier = Modifier.weight(0.85f),
            )
        }

        if (connected && !meta.endpoint.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            ValueRow(label = stringResource(R.string.meta_endpoint), value = meta.endpoint ?: ELLIPSIS)
        }
    }
}

@Composable
private fun UptimeTile(
    connectedSince: Long?,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    var now by remember(connectedSince) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        if (connectedSince == null) return@LaunchedEffect
        while (true) {
            val tick = System.currentTimeMillis()
            now = tick
            val intoSecond = (tick - connectedSince).coerceAtLeast(0L) % 1000L
            delay((1000L - intoSecond).coerceIn(1L, 1000L))
        }
    }
    val text = if (connectedSince == null) {
        DASH
    } else {
        val elapsed = (now - connectedSince).coerceAtLeast(0L) / 1000L
        String.format(
            Locale.US,
            "%02d:%02d:%02d",
            elapsed / 3600,
            (elapsed % 3600) / 60,
            elapsed % 60,
        )
    }
    StatTile(
        label = stringResource(R.string.connected_for),
        value = text,
        icon = Icons.Rounded.Schedule,
        tint = tint,
        modifier = modifier,
    )
}

@Composable
private fun QualityBars(ms: Long) {
    val accents = LocalAetherAccents.current
    val filled = when {
        ms < 0L -> 0
        ms < 80L -> 5
        ms < 150L -> 4
        ms < 250L -> 3
        ms < 400L -> 2
        else -> 1
    }
    val tone = when {
        filled >= 4 -> accents.protected
        filled >= 2 -> accents.working
        filled == 1 -> accents.failed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            Box(
                Modifier.width(3.dp).height((5 + index * 2).dp).clip(RoundedCornerShape(1.dp)).background(
                    if (index < filled) tone else MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}
