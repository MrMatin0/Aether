package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine
import studio.cluvex.aether.ui.theme.AetherAccents
import studio.cluvex.aether.ui.theme.AetherConsoleLine
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.Amber
import studio.cluvex.aether.ui.theme.InkHigh
import studio.cluvex.aether.ui.theme.InkMid
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.Rose

/**
 * The log inspector.
 *
 * WHAT IT REPLACES: a three-way severity segmented control, a follow switch and
 * a LazyColumn of coloured lines. Fine as a console; useless as an instrument,
 * because the two questions a reader actually arrives with - "what broke" and
 * "what was the tunnel doing" - cannot both be answered by filtering on level.
 * A tunnel event is INFO.
 *
 * SO: five quick filters with LIVE COUNTERS (two by severity, two by subject,
 * one for everything), because a badge reading 0 answers "are there errors"
 * without changing the view at all; a search that takes text OR a regex; an
 * auto-scroll lock with a jump-to-newest FAB for when it is off; a copy button
 * that SANITISES; and a real dialog before a clear rather than an inline row
 * that could be tapped twice by accident.
 *
 * TWO PROPERTIES WORTH NAMING:
 *
 *  * The console is pinned LTR and the rest of the panel is not. A log line is a
 *    technical literal - timestamps, IPs, ports, hex - and bidi reordering
 *    mangles all four in Persian. Only the terminal gets that treatment, so the
 *    prose around it still reads right to left.
 *  * The height is a RANGE, not a value. A short log renders short; a long one
 *    stops at the caller's ceiling. The old fixed height left a 300dp black
 *    rectangle under two log lines.
 *
 * Filtering, counting and search live in DiagnosticsConsole.kt as pure
 * functions, so what a reader sees is unit-tested without a composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CyberTerminal(
    lines: List<LogLine>,
    maxHeight: Dp,
    clearEnabled: Boolean,
    onExport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val scope = rememberCoroutineScope()

    var filter by rememberSaveable { mutableStateOf(ConsoleFilter.ALL) }
    var search by rememberSaveable { mutableStateOf("") }
    var useRegex by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val query = remember(search, useRegex) { ConsoleQuery(search, useRegex) }
    val counts = remember(lines) { consoleCounts(lines) }
    val shown = remember(lines, filter, query) { filterConsole(lines, filter, query) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }

    // Follow the TAIL rather than the item count: a full ring buffer stays 800
    // rows forever, so a count-keyed effect never fires again.
    LaunchedEffect(shown.lastOrNull(), filter, query, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    Column(modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConsoleFilter.entries.forEach { entry ->
                FilterPill(
                    label = stringResource(consoleFilterLabel(entry)),
                    count = counts[entry] ?: 0,
                    selected = filter == entry,
                    tone = consoleFilterTone(entry, accents),
                    onClick = { filter = entry },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        LtrOutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.diag_search)) },
            placeholder = { Text(stringResource(R.string.diag_search_hint)) },
            supportingText = {
                Text(
                    stringResource(
                        if (query.malformed) R.string.diag_regex_bad else R.string.diag_search_note,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (query.malformed) {
                        accents.failed
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            keyboardType = KeyboardType.Text,
        )

        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionPill(
                label = stringResource(R.string.diag_regex),
                onClick = { useRegex = !useRegex },
                icon = Icons.Rounded.Code,
                filled = useRegex,
                tint = accents.brand,
            )
            if (search.isNotEmpty()) {
                ActionPill(
                    label = stringResource(R.string.diag_search_clear),
                    onClick = { search = "" },
                    icon = Icons.Rounded.Close,
                )
            }
            ActionPill(
                label = stringResource(R.string.diag_export),
                onClick = onExport,
                icon = Icons.Rounded.ContentCopy,
            )
            ActionPill(
                label = stringResource(R.string.diag_clear),
                onClick = { confirmClear = true },
                icon = Icons.Rounded.DeleteOutline,
                enabled = clearEnabled,
                tint = accents.failed,
            )
        }
        Hint(stringResource(R.string.diag_export_note))

        SwitchRow(
            title = stringResource(R.string.diag_follow),
            description = stringResource(R.string.diag_follow_note),
            checked = follow,
            enabled = true,
            onChange = { follow = it },
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = maxHeight.coerceAtLeast(160.dp))
                            .clip(MaterialTheme.shapes.medium)
                            .background(accents.console)
                            .border(BorderStroke(1.dp, accents.cardBorder), MaterialTheme.shapes.medium),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    stringResource(
                                        if (lines.isEmpty()) {
                                            R.string.diag_empty_logs
                                        } else {
                                            R.string.diag_no_matches
                                        },
                                    ),
                                    style = AetherConsoleLine,
                                    color = InkMid,
                                )
                            }
                        }
                        items(shown) { line -> ConsoleRow(line) }
                    }
                }
            }
            AnimatedVisibility(
                visible = !atBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        follow = true
                        scope.launch {
                            if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
                        }
                    },
                    containerColor = accents.brand,
                    contentColor = accents.onBrand,
                ) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        stringResource(R.string.diag_jump_bottom),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = accents.failed) },
            title = { Text(stringResource(R.string.diag_clear_title)) },
            text = { Text(stringResource(R.string.diag_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text(stringResource(R.string.diag_clear), color = accents.failed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Severity is the colour, and the colour is the same one the rest of the app uses. */
@Composable
private fun ConsoleRow(line: LogLine) {
    val ink = when (line.level) {
        LogLevel.ERROR -> Rose
        LogLevel.WARN -> Amber
        LogLevel.INFO -> InkHigh
        LogLevel.DEBUG -> InkMid
    }
    // The "glow": a severity wash behind the row. A coloured glyph alone is not
    // enough to find one error inside 800 lines of DEBUG while scrolling.
    val wash = when (line.level) {
        LogLevel.ERROR -> Rose.copy(alpha = 0.12f)
        LogLevel.WARN -> Amber.copy(alpha = 0.09f)
        else -> Color.Transparent
    }
    Text(
        line.format(),
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(wash)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr),
        color = ink,
    )
}

/** A quick filter with its live count. The count is the point of the pill. */
@Composable
private fun FilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    tone: Color,
    onClick: () -> Unit,
) {
    val ink = if (selected) contentColorForTone(tone) else tone
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) tone else Color.Transparent,
        contentColor = ink,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = AetherMetaLabel.copy(textDirection = TextDirection.Ltr),
                color = ink.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

private fun consoleFilterLabel(filter: ConsoleFilter): Int = when (filter) {
    ConsoleFilter.ALL -> R.string.log_all
    ConsoleFilter.ERRORS -> R.string.log_err
    ConsoleFilter.WARNINGS -> R.string.log_warn
    ConsoleFilter.TUNNEL -> R.string.log_tunnel
    ConsoleFilter.DNS -> R.string.log_dns
}

private fun consoleFilterTone(filter: ConsoleFilter, accents: AetherAccents): Color = when (filter) {
    ConsoleFilter.ALL -> accents.brand
    ConsoleFilter.ERRORS -> accents.failed
    ConsoleFilter.WARNINGS -> accents.working
    ConsoleFilter.TUNNEL -> accents.protected
    ConsoleFilter.DNS -> accents.brand
}
