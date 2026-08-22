package studio.cluvex.aether.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.ChalkFaint

private const val DASH = "\u2014"
private const val ELLIPSIS = "\u2026"
private const val LATENCY_REFRESH_MS = 4_000L

/** Width of the label gutter. One number so every row lines up. */
private val LABEL_WIDTH = 96.dp

/**
 * The session ledger: everything factual about the current connection, in one
 * scannable column.
 *
 * WHY IT IS NOT CARDS ANY MORE: this used to be three translucent rounded
 * surfaces stacked on top of each other (IP badge, meta row, timer), each with
 * its own padding and its own centre alignment, inside a screen that was itself
 * a column of cards. Nested cards add borders and shadows without adding
 * meaning, and centred label/value pairs are the hardest possible layout to
 * scan. A hairline ledger with left labels and right-aligned monospaced values
 * reads top-to-bottom in one pass.
 *
 * Every technical value stays pinned LTR — that BiDi fix is load-bearing in
 * the Persian locale, where `104.28.197.15` otherwise renders reordered.
 *
 * 1.4.3: the label gutter is a fixed 96dp so the values align into a column,
 * but the labels in it had `maxLines = 2` and NO overflow strategy, which means
 * Compose falls back to hard clipping. At a large system font scale the Persian
 * labels (آی‌پی سرور, مدت اتصال) were cut through the middle of a glyph, which
 * in a joined script does not look truncated — it looks like a rendering bug.
 * Every bounded line in the ledger now ellipsises instead.
 */
@Composable
fun ConnectionMeta(
    connected: Boolean,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val meta by EngineMeta.state.collectAsState()
    val ping by PingMonitor.state.collectAsState()

    // Live latency, same cadence and same single-owner rule as before:
    // PingMonitor serialises runs behind a mutex, so one cheap TCP handshake
    // every few seconds stays battery-friendly.
    LaunchedEffect(connected) {
        while (connected) {
            PingMonitor.pingOnce(viaTunnel = true)
            delay(LATENCY_REFRESH_MS)
        }
    }

    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val ipValue = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> "$flag ${ipInfo.ip}"
        else -> stringResource(R.string.ip_unavailable)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Hairline()
        LedgerRow(
            label = if (connected) {
                stringResource(R.string.ip_server_label)
            } else {
                stringResource(R.string.ip_your_label)
            },
        ) {
            AnimatedContent(
                targetState = ipValue,
                transitionSpec = {
                    fadeIn(tween(AetherDur.Base)) togetherWith fadeOut(tween(AetherDur.Quick))
                },
                label = "ip",
            ) { shown ->
                Text(
                    text = shown,
                    style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Hairline(alpha = 0.55f)
        LedgerRow(label = stringResource(R.string.meta_protocol)) {
            ValueText(if (connected) meta.protocol ?: DASH else DASH, mono = false)
        }

        Hairline(alpha = 0.55f)
        LedgerRow(label = stringResource(R.string.meta_endpoint)) {
            ValueText(
                text = if (connected) meta.endpoint ?: ELLIPSIS else DASH,
                mono = true,
            )
        }

        Hairline(alpha = 0.55f)
        LedgerRow(label = stringResource(R.string.meta_latency)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connected) {
                    QualityBars(ms = ping.ms)
                    Spacer(Modifier.width(10.dp))
                }
                ValueText(
                    text = when {
                        !connected -> DASH
                        ping.ms >= 0 -> "${ping.ms} ms"
                        ping.running -> ELLIPSIS
                        else -> DASH
                    },
                    mono = true,
                    fill = false,
                )
            }
        }

        if (connectedSince != null) {
            Hairline(alpha = 0.55f)
            UptimeRow(connectedSince)
        }
        Hairline()
    }
}

@Composable
private fun LedgerRow(
    label: String,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun ValueText(text: String, mono: Boolean, fill: Boolean = true) {
    Text(
        text = text,
        style = if (mono) {
            AetherNumeral.copy(textDirection = TextDirection.Ltr)
        } else {
            MaterialTheme.typography.titleMedium
        },
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
    )
}

/**
 * Five bars, filled by latency band. A number alone means nothing to most
 * people; "four of five bars" is instantly legible and matches how everyone
 * already reads signal strength.
 */
@Composable
private fun QualityBars(ms: Long) {
    val filled = when {
        ms < 0L -> 0
        ms < 80L -> 5
        ms < 150L -> 4
        ms < 250L -> 3
        ms < 400L -> 2
        else -> 1
    }
    val tone = when {
        filled >= 4 -> MaterialTheme.colorScheme.primary
        filled >= 2 -> MaterialTheme.colorScheme.secondary
        filled == 1 -> MaterialTheme.colorScheme.error
        else -> ChalkFaint
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

/**
 * Session uptime.
 *
 * Locale.US, not the default locale: AppLocale sets the JVM default to fa when
 * the UI is Persian, so plain "%02d".format() rendered this clock in
 * Persian-Indic digits directly under a latency value and a traffic readout
 * that both already pin Locale.US. One instrument panel, two numbering systems,
 * in a monospaced style whose advance widths only match Latin figures.
 */
@Composable
private fun UptimeRow(connectedSince: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val elapsed = (now - connectedSince).coerceAtLeast(0L) / 1000L
    val text = String.format(
        Locale.US,
        "%02d:%02d:%02d",
        elapsed / 3600,
        (elapsed % 3600) / 60,
        elapsed % 60,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.connected_for),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = AetherMono,
                textDirection = TextDirection.Ltr,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
