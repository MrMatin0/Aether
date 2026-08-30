package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.ComponentCheck
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine
import studio.cluvex.aether.ui.theme.AetherConsoleLine
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.Amber
import studio.cluvex.aether.ui.theme.InkHigh
import studio.cluvex.aether.ui.theme.InkLow
import studio.cluvex.aether.ui.theme.InkMid
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.Rose

private enum class LogFilter { ALL, WARN, ERROR }

/**
 * The screen that answers "it says connected, so why does nothing load?".
 *
 * WHAT CHANGED AND WHY
 *
 * It was one long column: a 10dp status dot with a caption, three buttons, a
 * numbered check list, then the console — all at the same visual level, so the
 * VERDICT (the only part most people need) was the smallest thing on the screen.
 *
 * Now it is three cards, in the order a person actually reads them:
 *
 *   1. VERDICT. Stated in words at headline size, with a matching badge. "All
 *      checks passed — traffic should flow" is the whole answer for most
 *      failures, and it used to be 12.5sp of grey text beside a dot.
 *   2. CHECKS. Still numbered, so "step 3 failed" is something a user can say
 *      out loud in a bug report, with the state as a coloured chip.
 *   3. CONSOLE. With the level filter, because the interesting line is usually
 *      one WARN inside four hundred DEBUG lines, and autoscroll, so a running
 *      scan streams instead of having to be dragged.
 *
 * The console keeps a fixed dark surface in BOTH themes, and its level colours
 * come from the dark palette on purpose: a monospaced log on a white card reads
 * as prose, and this is the one place someone is scanning for a single red line.
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
    val accents = LocalAetherAccents.current
    var expanded by remember { mutableStateOf(alwaysExpanded) }
    val checks by DiagnosticsLog.checks.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val verdict = verdictFor(checks)

    Column(modifier = modifier.fillMaxWidth()) {
        AetherCard(tone = verdict.tone) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (alwaysExpanded) {
                            Modifier
                        } else {
                            Modifier.clickable { expanded = !expanded }
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(icon = verdict.icon, tint = verdict.tone, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diag_title),
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(verdict.captionRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (!alwaysExpanded) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    ActionPill(
                        label = stringResource(R.string.diag_run),
                        onClick = { scope.launch { Diagnostics.run() } },
                        icon = Icons.Rounded.Refresh,
                        filled = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ActionPill(
                            label = stringResource(R.string.diag_clear),
                            onClick = { DiagnosticsLog.clear() },
                            icon = Icons.Rounded.Close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(14.dp))
                AetherCard {
                    CardHeader(
                        title = stringResource(R.string.diag_checks),
                        subtitle = stringResource(R.string.diag_checks_note),
                        icon = Icons.Rounded.Check,
                        tint = accents.brand,
                    )
                    if (checks.isEmpty()) {
                        Hint(stringResource(R.string.diag_idle))
                    } else {
                        Spacer(Modifier.height(6.dp))
                        checks.forEachIndexed { index, check ->
                            if (index > 0) Hairline(alpha = 0.5f)
                            CheckRow(index = index + 1, check = check)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                AetherCard {
                    CardHeader(
                        title = stringResource(R.string.diag_console),
                        subtitle = stringResource(R.string.diag_console_note),
                        icon = Icons.Rounded.Terminal,
                        tint = accents.brand,
                    )
                    Spacer(Modifier.height(14.dp))
                    LogConsole(maxHeight = consoleMaxHeight)
                }
            }
        }
    }
}

private data class Verdict(val tone: Color, val icon: ImageVector, val captionRes: Int)

@Composable
private fun verdictFor(checks: List<ComponentCheck>): Verdict {
    val accents = LocalAetherAccents.current
    return when {
        checks.isEmpty() -> Verdict(
            accents.neutral,
            Icons.Rounded.Refresh,
            R.string.diag_idle,
        )
        checks.any { it.state == CheckState.FAIL } -> Verdict(
            accents.failed,
            Icons.Rounded.Warning,
            R.string.diag_problem,
        )
        checks.all { it.state == CheckState.PASS } -> Verdict(
            accents.protected,
            Icons.Rounded.Check,
            R.string.diag_all_ok,
        )
        else -> Verdict(accents.working, Icons.Rounded.Refresh, R.string.diag_idle)
    }
}

@Composable
private fun CheckRow(index: Int, check: ComponentCheck) {
    val accents = LocalAetherAccents.current
    val color = when (check.state) {
        CheckState.PASS -> accents.protected
        CheckState.FAIL -> accents.failed
        CheckState.RUNNING -> accents.working
        CheckState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = AetherMetaLabel.copy(
                fontFamily = AetherMono,
                textDirection = TextDirection.Ltr,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun LogConsole(maxHeight: Dp) {
    val accents = LocalAetherAccents.current
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp, max = maxHeight)
            .clip(RoundedCornerShape(AetherRadius.Field))
            .background(accents.console)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(scroll),
    ) {
        if (shown.isEmpty()) {
            Text(
                text = stringResource(R.string.diag_empty_logs),
                style = AetherConsoleLine,
                color = InkLow,
            )
        } else {
            Column {
                shown.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = glyph(line.level),
                            style = AetherConsoleLine,
                            color = logColor(line.level),
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            text = line.format(),
                            style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr),
                            color = logColor(line.level),
                        )
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

/**
 * Console level colours. Deliberately the DARK palette's hues in both themes:
 * the console surface is always dark, so the light theme's darker, paper-safe
 * variants would be nearly invisible on it.
 */
private fun logColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Rose
    LogLevel.WARN -> Amber
    LogLevel.INFO -> InkHigh.copy(alpha = 0.86f)
    LogLevel.DEBUG -> InkMid.copy(alpha = 0.7f)
}
