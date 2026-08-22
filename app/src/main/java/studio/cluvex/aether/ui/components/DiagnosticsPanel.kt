package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.ComponentCheck
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.Carbon00
import studio.cluvex.aether.ui.theme.ChalkFaint
import studio.cluvex.aether.ui.theme.ChalkMuted
import studio.cluvex.aether.ui.theme.Clay
import studio.cluvex.aether.ui.theme.Ember
import studio.cluvex.aether.ui.theme.Signal

private enum class LogFilter { ALL, WARN, ERROR }

/**
 * The panel that answers "it says connected, so why does nothing load?".
 *
 * It used to be a collapsed card at the top of a drawer, which meant the one
 * screen that explains a failure was also the hardest to find. It is now a
 * first-class destination ([alwaysExpanded]) with:
 *
 *  - numbered checks, so "step 3 failed" is something a user can actually say
 *    out loud in a bug report,
 *  - a level filter, because the interesting line is usually one WARN inside
 *    four hundred DEBUG lines,
 *  - autoscroll, so a running scan streams instead of having to be dragged.
 *
 * The 1.2.2 performance rule still holds: the log flow is collected only by
 * [LogConsole], and only while the console is actually on screen.
 */
@Composable
fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    alwaysExpanded: Boolean = false,
    consoleMaxHeight: Dp = 300.dp,
) {
    var expanded by remember { mutableStateOf(alwaysExpanded) }
    val checks by DiagnosticsLog.checks.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val overall = overallState(checks)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
        label = "diagchev",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (alwaysExpanded) {
                        Modifier
                    } else {
                        Modifier.clickable(role = Role.Button) { expanded = !expanded }
                    },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = overall.color, size = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.diag_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(overall.captionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!alwaysExpanded) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevron),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                /*
                 * 1.4.3 OVERFLOW FIX. These three controls used to share one
                 * Row where only "Run" carried weight(1f) and Copy and Clear
                 * were free to take their intrinsic width. Their intrinsic
                 * width is label + icon + 36dp of padding each, which on a
                 * 320dp-wide screen (and on ANY screen in the Persian locale,
                 * where the labels are longer) exceeded the row: the last pill
                 * was pushed past the right margin and hard-clipped, so on a
                 * small phone "Clear" was simply not reachable.
                 *
                 * Splitting them also fixes the hierarchy that caused it: Run
                 * is the primary action of this screen and now owns a full-width
                 * row, while the two destructive/secondary actions share the row
                 * below at an equal 50%. Nothing can overflow at any width or
                 * font scale, and the important button is unmistakable.
                 */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                ) {
                    ActionPill(
                        label = stringResource(R.string.diag_run),
                        onClick = { scope.launch { Diagnostics.run() } },
                        icon = Icons.Rounded.Refresh,
                        filled = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ActionPill(
                            label = stringResource(R.string.diag_copy),
                            onClick = {
                                clipboard.setText(AnnotatedString(DiagnosticsLog.exportText()))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.diag_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            icon = Icons.Rounded.ContentCopy,
                            tint = ChalkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        ActionPill(
                            label = stringResource(R.string.diag_clear),
                            onClick = { DiagnosticsLog.clear() },
                            icon = Icons.Rounded.Close,
                            tint = ChalkMuted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                SectionRule(label = stringResource(R.string.diag_checks))

                if (checks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diag_idle),
                        style = MaterialTheme.typography.bodySmall,
                        color = ChalkFaint,
                    )
                } else {
                    Hairline(alpha = 0.55f)
                    checks.forEachIndexed { index, check ->
                        CheckRow(index = index + 1, check = check)
                        Hairline(alpha = 0.55f)
                    }
                }

                SectionRule(label = stringResource(R.string.diag_console))
                LogConsole(maxHeight = consoleMaxHeight)
            }
        }
    }
}

private data class Overall(val color: Color, val captionRes: Int)

private fun overallState(checks: List<ComponentCheck>): Overall = when {
    checks.isEmpty() -> Overall(ChalkFaint, R.string.diag_idle)
    checks.any { it.state == CheckState.FAIL } -> Overall(Clay, R.string.diag_problem)
    checks.all { it.state == CheckState.PASS } -> Overall(Signal, R.string.diag_all_ok)
    else -> Overall(Ember, R.string.diag_idle)
}

@Composable
private fun CheckRow(index: Int, check: ComponentCheck) {
    val color = when (check.state) {
        CheckState.PASS -> Signal
        CheckState.FAIL -> Clay
        CheckState.RUNNING -> Ember
        CheckState.PENDING -> ChalkFaint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = AetherMetaLabel.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
            color = ChalkFaint,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = check.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (check.detail.isNotBlank()) {
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = check.state.name,
            style = AetherMetaLabel,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The log console.
 *
 * 1.4.3 BiDi FIX. Every other technical readout in this app is pinned LTR — the
 * ledger values, the endpoint fields, the uptime clock, the check numbers —
 * because timestamps, IPs and ports are direction-NEUTRAL runs that the Unicode
 * BiDi algorithm reorders around an RTL base direction. The console was the one
 * place that was missed, and it is the worst place to miss: in the Persian
 * locale a line like `12:04:11 hev: connect 162.159.192.1:443 failed` came out
 * with its timestamp, address and port visually rearranged, and the gutter
 * glyph jumped to the opposite edge. That makes the single screen people are
 * asked to screenshot for a bug report actively misleading. The whole console
 * now renders in an LTR layout scope, so log output is byte-order faithful in
 * both languages while the rest of the UI stays RTL.
 */
@Composable
private fun LogConsole(maxHeight: Dp) {
    val lines: List<LogLine> = DiagnosticsLog.lines.collectAsState().value
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val scroll = rememberScrollState()

    val shown = when (filter) {
        LogFilter.ALL -> lines
        LogFilter.WARN -> lines.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
        LogFilter.ERROR -> lines.filter { it.level == LogLevel.ERROR }
    }

    // Follow the tail: a running scan should stream, not need dragging.
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    SegmentedSelector(
        options = listOf(LogFilter.ALL, LogFilter.WARN, LogFilter.ERROR),
        selected = filter,
        onSelect = { filter = it },
        label = {
            when (it) {
                LogFilter.ALL -> stringResource(R.string.log_all)
                LogFilter.WARN -> stringResource(R.string.log_warn)
                LogFilter.ERROR -> stringResource(R.string.log_err)
            }
        },
    )

    Spacer(Modifier.height(12.dp))

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = maxHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(Carbon00)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(scroll),
        ) {
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.diag_empty_logs),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChalkFaint,
                )
            } else {
                Column {
                    shown.forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = glyph(line.level),
                                fontFamily = AetherMono,
                                fontSize = 11.sp,
                                color = logColor(line.level),
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                text = line.format(),
                                fontFamily = AetherMono,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = logColor(line.level),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Level marker. A glyph in the gutter, never a coloured edge stripe. */
private fun glyph(level: LogLevel): String = when (level) {
    LogLevel.ERROR -> "!"
    LogLevel.WARN -> "\u203A"
    LogLevel.INFO -> "\u00B7"
    LogLevel.DEBUG -> " "
}

private fun logColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Clay
    LogLevel.WARN -> Ember
    LogLevel.INFO -> ChalkMuted
    LogLevel.DEBUG -> ChalkFaint
}

@Composable
private fun StatusDot(color: Color, size: Dp) {
    Box(modifier = Modifier.size(size).background(color, CircleShape))
}
