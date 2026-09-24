package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * The log inspector, as the whole destination.
 *
 * WHAT CHANGED, AND WHY:
 *
 *  * FULL HEIGHT. The console used to be a 420dp box inside a scrolling column:
 *    two nested scrolls, and on a phone most of the screen went to controls.
 *    It now takes every pixel the caller gives it ([modifier] carries a weight
 *    or a fixed height), and the controls collapse to one toolbar row plus one
 *    row of pills.
 *  * FOLLOW IS AUTOMATIC. The old switch never turned itself off, so scrolling
 *    up to read was undone by the next line 200 ms later. Now a DRAG pauses
 *    follow, reaching the bottom resumes it, and while paused a pill counts
 *    the lines that arrived since.
 *  * ROWS HAVE STABLE KEYS ([LogLine.seq]). With a full 800-line ring buffer
 *    every new line evicts the head, and index-keyed rows slid one line per
 *    tick under the reader even with follow off.
 *  * FILTERING IS OFF THE MAIN THREAD, and the regex is time-boxed (see
 *    DiagnosticsConsole.kt).
 *  * SECONDARY ACTIONS LIVE IN ONE MENU: regex, hide IPs, copy, share, clear.
 *    Regex and hide-IPs are announced as checkboxes, not as plain buttons.
 *  * TAP A LINE for its full, selectable text and a sanitized copy. The list
 *    itself is no longer wrapped in a SelectionContainer, which fought with
 *    row recycling.
 *
 * The console stays pinned LTR (timestamps, IPs, ports, hex) and the prose
 * around it follows the locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CyberTerminal(
    lines: List<LogLine>,
    onCopy: (maskIps: Boolean) -> Unit,
    onShare: (maskIps: Boolean) -> Unit,
    onCopyLine: (LogLine) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    focusErrors: Boolean = false,
    onFocusConsumed: () -> Unit = {},
) {
    val accents = LocalAetherAccents.current
    val scope = rememberCoroutineScope()

    var filter by rememberSaveable { mutableStateOf(ConsoleFilter.ALL) }
    var search by rememberSaveable { mutableStateOf("") }
    var useRegex by rememberSaveable { mutableStateOf(false) }
    var maskIps by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var anchorSeq by rememberSaveable { mutableStateOf(-1L) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<LogLine?>(null) }

    val listState = rememberLazyListState()
    val query = remember(search, useRegex) { ConsoleQuery(search, useRegex) }

    var slice by remember { mutableStateOf(filterConsoleGuarded(lines, filter, query)) }
    var counts by remember { mutableStateOf(consoleCounts(lines)) }
    LaunchedEffect(lines, filter, query) {
        slice = withContext(Dispatchers.Default) { filterConsoleGuarded(lines, filter, query) }
    }
    LaunchedEffect(lines) {
        counts = withContext(Dispatchers.Default) { consoleCounts(lines) }
    }
    val shown = slice.lines
    val currentShown by rememberUpdatedState(shown)

    // "See the error" on the connection tab lands here with the Errors pill on
    // and the newest error at the bottom of the view.
    LaunchedEffect(focusErrors) {
        if (focusErrors) {
            filter = ConsoleFilter.ERRORS
            search = ""
            follow = true
            onFocusConsumed()
        }
    }

    // Follow the TAIL rather than the item count: a full ring buffer stays 800
    // rows forever, so a count-keyed effect never fires again.
    LaunchedEffect(shown.lastOrNull()?.seq, shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    // A drag by the reader pauses follow. Programmatic scrolls are not drags,
    // so following can never switch itself off.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && follow) {
                follow = false
                anchorSeq = currentShown.lastOrNull()?.seq ?: -1L
            }
        }
    }
    // Back at the bottom and at rest: resume.
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward || listState.isScrollInProgress }
            .collect { away -> if (!away && !follow) follow = true }
    }

    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val newCount = if (follow) 0 else newerThan(shown, anchorSeq)
    val clearEnabled = lines.isNotEmpty()

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LtrOutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        stringResource(R.string.diag_search) +
                            if (useRegex) " · " + stringResource(R.string.diag_regex) else "",
                    )
                },
                placeholder = { Text(stringResource(R.string.diag_search_hint)) },
                supportingText = when {
                    query.malformed -> {
                        {
                            Text(
                                stringResource(R.string.diag_regex_bad),
                                style = MaterialTheme.typography.bodySmall,
                                color = accents.failed,
                            )
                        }
                    }
                    slice.regexTimedOut -> {
                        {
                            Text(
                                stringResource(R.string.diag_regex_slow),
                                style = MaterialTheme.typography.bodySmall,
                                color = accents.working,
                            )
                        }
                    }
                    else -> null
                },
                keyboardType = KeyboardType.Text,
            )
            if (search.isNotEmpty()) {
                IconButton(onClick = { search = "" }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.diag_search_clear))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.diag_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.diag_regex)) },
                        onClick = { useRegex = !useRegex },
                        modifier = Modifier.semantics {
                            role = Role.Checkbox
                            toggleableState = ToggleableState(useRegex)
                        },
                        leadingIcon = { Icon(Icons.Rounded.Code, null) },
                        trailingIcon = { Checkbox(checked = useRegex, onCheckedChange = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.diag_mask_ips)) },
                        onClick = { maskIps = !maskIps },
                        modifier = Modifier.semantics {
                            role = Role.Checkbox
                            toggleableState = ToggleableState(maskIps)
                        },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                        trailingIcon = { Checkbox(checked = maskIps, onCheckedChange = null) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.diag_export)) },
                        onClick = {
                            menuOpen = false
                            onCopy(maskIps)
                        },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        enabled = clearEnabled,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.diag_share)) },
                        onClick = {
                            menuOpen = false
                            onShare(maskIps)
                        },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.diag_clear), color = accents.failed) },
                        onClick = {
                            menuOpen = false
                            confirmClear = true
                        },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = accents.failed) },
                        enabled = clearEnabled,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .background(accents.console)
                        .border(BorderStroke(1.dp, accents.cardBorder), MaterialTheme.shapes.medium),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    if (lines.isEmpty()) R.string.diag_empty_logs else R.string.diag_no_matches,
                                ),
                                Modifier.padding(6.dp),
                                style = AetherConsoleLine,
                                color = InkMid,
                            )
                        }
                    }
                    itemsIndexed(shown, key = { _, line -> line.seq }) { index, line ->
                        val previous = if (index > 0) shown[index - 1] else null
                        val divider = when {
                            line.raw && (previous == null || !previous.raw) -> R.string.diag_session_previous
                            !line.raw && previous != null && previous.raw -> R.string.diag_session_current
                            else -> null
                        }
                        Column(Modifier.fillMaxWidth()) {
                            if (divider != null) SessionDivider(stringResource(divider))
                            ConsoleRow(line, onOpen = { opened = line })
                        }
                    }
                }
            }
            NewLinesPill(
                count = newCount,
                accents = accents,
                onClick = { follow = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
            JumpToNewest(
                visible = !atBottom && newCount == 0,
                accents = accents,
                onClick = {
                    follow = true
                    scope.launch { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            )
        }
    }

    opened?.let { line ->
        LineSheet(
            line = line,
            onCopy = {
                onCopyLine(line)
                opened = null
            },
            onDismiss = { opened = null },
        )
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

/**
 * The jump-to-newest FAB, its OWN composable on purpose: inline inside the Box
 * above, ColumnScope is still an implicit receiver and K2 resolves
 * AnimatedVisibility to the ColumnScope overload (DSL_SCOPE_VIOLATION). Here
 * only the top-level overload is a candidate.
 */
@Composable
private fun JumpToNewest(
    visible: Boolean,
    accents: AetherAccents,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = accents.brand,
            contentColor = accents.onBrand,
        ) {
            Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.diag_jump_bottom))
        }
    }
}

/** "N new lines": what arrived while the reader was scrolled up. Tap to resume. */
@Composable
private fun NewLinesPill(
    count: Int,
    accents: AetherAccents,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = count > 0, modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = accents.brand,
            contentColor = accents.onBrand,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.diag_new_lines, count),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Where the restored previous launch ends and this session begins. */
@Composable
private fun SessionDivider(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = InkMid.copy(alpha = 0.4f))
        Text(
            label,
            Modifier.padding(horizontal = 8.dp),
            style = AetherMetaLabel,
            color = InkMid,
            maxLines = 1,
        )
        HorizontalDivider(Modifier.weight(1f), color = InkMid.copy(alpha = 0.4f))
    }
}

/** Severity is the colour, and the colour is the same one the rest of the app uses. */
@Composable
private fun ConsoleRow(line: LogLine, onOpen: () -> Unit) {
    val ink = when (line.level) {
        LogLevel.ERROR -> Rose
        LogLevel.WARN -> Amber
        LogLevel.INFO -> InkHigh
        LogLevel.DEBUG -> InkMid
    }
    // A severity wash behind the row: a coloured glyph alone is not enough to
    // find one error inside 800 lines of DEBUG while scrolling.
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
            .clickable(
                onClickLabel = stringResource(R.string.diag_line_title),
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr),
        color = ink,
    )
}

/** One line, in full and selectable, with a sanitized copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineSheet(line: LogLine, onCopy: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(stringResource(R.string.diag_line_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SelectionContainer {
                    Text(
                        line.format(),
                        Modifier.fillMaxWidth(),
                        style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ActionPill(
                label = stringResource(R.string.diag_copy_line),
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.ContentCopy,
            )
        }
    }
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
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) tone else Color.Transparent,
        contentColor = ink,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
