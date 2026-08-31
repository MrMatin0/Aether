package studio.cluvex.aether.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/**
 * The session card: everything factual about the current connection.
 *
 * WHAT CHANGED AND WHY
 *
 * This was a hairline ledger — left label, right-aligned monospaced value, six
 * rows, one rule between each. That was a real improvement over the three nested
 * translucent cards it replaced, and it did read in one pass. What it could not
 * do is RANK. Every row had identical weight, so the exit IP — the single fact
 * that proves the tunnel is carrying traffic and the first thing anyone checks —
 * looked exactly as important as the protocol name.
 *
 * So the card now has a headline and supporting readings:
 *
 *   - The IP is the headline, in the large numeral style, with the country flag.
 *     Connected or not, it answers "whose network am I coming out of".
 *   - Protocol / location / latency / uptime are a 2x2 tile grid underneath.
 *   - The internal endpoint is a copyable row at the bottom, because its only
 *     use is being pasted into a bug report.
 *
 * The latency TILE is the probe trigger. There used to be a 14dp refresh button
 * wedged into the right edge of the latency row; now the whole tile is the
 * target, with the same manual-only semantics (nothing probes in the background,
 * ever) and a footnote that says so.
 *
 * Every technical value stays pinned LTR — that BiDi fix is load-bearing in the
 * Persian locale, where `104.28.197.15` otherwise renders reordered.
 */
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

    // Manual-only latency: nothing probes in the background. The value is
    // refreshed exactly when the user taps the tile.
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
            title = if (connected) {
                stringResource(R.string.ip_server_label)
            } else {
                stringResource(R.string.ip_your_label)
            },
            subtitle = stringResource(
                if (connected) R.string.meta_exit_note else R.string.meta_origin_note,
            ),
            icon = Icons.Rounded.Public,
            tint = tone,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flag.isNotBlank()) {
                Text(text = flag, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(10.dp))
            }
            AnimatedContent(
                targetState = ipValue,
                transitionSpec = {
                    fadeIn(tween(aetherDuration(AetherDur.Base))) togetherWith
                        fadeOut(tween(aetherDuration(AetherDur.Quick)))
                },
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

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
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
        Row(modifier = Modifier.fillMaxWidth()) {
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
                } else {
                    null
                },
                onClickLabel = stringResource(R.string.meta_latency_test),
                trailing = if (connected) {
                    { QualityBars(ms = ping.ms) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            UptimeTile(
                connectedSince = if (connected) connectedSince else null,
                tint = tone,
                modifier = Modifier.weight(1f),
            )
        }

        if (connected && !meta.endpoint.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            ValueRow(
                label = stringResource(R.string.meta_endpoint),
                value = meta.endpoint ?: ELLIPSIS,
            )
        }
    }
}

/**
 * Session uptime, ticking once a second.
 *
 * Locale.US, not the default locale: AppLocale sets the JVM default to fa when
 * the UI is Persian, so a plain "%02d".format() rendered this clock in
 * Persian-Indic digits directly beside a latency value that already pins
 * Locale.US. One instrument panel, two numbering systems, in a monospaced style
 * whose advance widths only match Latin figures.
 *
 * WHAT CHANGED IN THIS PASS: the tick. It used to read the clock and then sleep a
 * flat `delay(1000)`, so every tick was late by however long the frame took, and
 * the error ACCUMULATED. On a 60Hz phone that lands on the wrong side of a whole
 * second every few minutes, and the visible symptom is a clock that shows the
 * same second twice and then skips one — on the one readout whose entire job is
 * being trusted. It now sleeps to the next whole second OF THE SESSION, so the
 * digit flips on the boundary and stays on it for hours.
 *
 * It also measures with elapsedRealtime instead of wall-clock time. connectedSince
 * is a monotonic stamp; comparing it against currentTimeMillis meant an NTP
 * correction (routine on a phone that just left airplane mode, which is a very
 * normal way to start a VPN session) could make the uptime jump or run backwards.
 */
@Composable
private fun UptimeTile(
    connectedSince: Long?,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connectedSince) {
        if (connectedSince == null) return@LaunchedEffect
        while (true) {
            val tick = SystemClock.elapsedRealtime()
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

/**
 * Five bars, filled by latency band. A number alone means nothing to most
 * people; "four of five bars" is instantly legible and matches how everyone
 * already reads signal strength.
 */
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
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((5 + index * 2).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (index < filled) tone else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}
