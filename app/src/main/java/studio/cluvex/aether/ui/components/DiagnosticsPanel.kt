package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.*
import studio.cluvex.aether.ui.theme.*

internal enum class LogFilter { ALL, WARN, ERROR }
internal fun matchesLogFilter(level: LogLevel, filter: LogFilter): Boolean = when (filter) {
    LogFilter.ALL -> true
    LogFilter.WARN -> level == LogLevel.WARN || level == LogLevel.ERROR
    LogFilter.ERROR -> level == LogLevel.ERROR
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsPanel(modifier: Modifier = Modifier, alwaysExpanded: Boolean = false, consoleMaxHeight: Dp = 300.dp) {
    val a = LocalAetherAccents.current
    val checks by DiagnosticsLog.checks.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(alwaysExpanded) }
    var running by remember { mutableStateOf(false) }
    var testError by remember { mutableStateOf(false) }
    var clearConfirm by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val failed = testError || checks.any { it.state == CheckState.FAIL }
    val passed = checks.isNotEmpty() && checks.all { it.state == CheckState.PASS }
    val working = running || checks.any { it.state == CheckState.RUNNING }
    val tone = when { failed -> a.failed; working -> a.working; passed -> a.protected; else -> a.neutral }
    val verdict = when { failed -> R.string.diag_problem; working -> R.string.pill_working; passed -> R.string.diag_all_ok; else -> R.string.diag_idle }
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
            IconBadge(when { failed -> Icons.Rounded.Warning; passed -> Icons.Rounded.Check; else -> Icons.Rounded.Terminal }, tone, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(verdict), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (!running) {
                running = true; testError = false
                scope.launch {
                    try { Diagnostics.run() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { testError = true }
                    finally { running = false }
                }
            }
        }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.large) {
            Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(12.dp))
            Text(stringResource(if (running) R.string.pill_working else R.string.diag_run))
        }
        if (!alwaysExpanded) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.diag_title)); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (alwaysExpanded || expanded) {
            Spacer(Modifier.height(40.dp))
            ControlSection(stringResource(R.string.diag_checks), stringResource(R.string.diag_checks_note)) {
                if (checks.isEmpty()) Hint(stringResource(R.string.diag_idle))
                checks.forEachIndexed { index, check -> CheckRow(index + 1, check); Hairline() }
            }
            Spacer(Modifier.height(40.dp))
            ControlSection(stringResource(R.string.diag_console), icon = Icons.Rounded.Terminal) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val text = withContext(Dispatchers.Default) { DiagnosticsLog.exportText() }
                            clipboard.setText(AnnotatedString(text)); Toast.makeText(context, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diag_copy)) }
                    TextButton(onClick = { clearConfirm = !clearConfirm }, enabled = !running) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diag_clear))
                    }
                }
                if (clearConfirm) {
                    NoticeBar(stringResource(R.string.diag_clear) + " · " + stringResource(R.string.diag_console), tone = a.failed, icon = Icons.Rounded.DeleteOutline)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = {
                            clearConfirm = false; scope.launch { withContext(Dispatchers.IO) { DiagnosticsLog.clear() } }
                        }, enabled = !running) { Text(stringResource(R.string.diag_clear), color = a.failed) }
                        TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                Spacer(Modifier.height(16.dp)); LogConsole(consoleMaxHeight)
            }
        }
    }
}
@Composable
private fun CheckRow(index: Int, check: ComponentCheck) {
    val a = LocalAetherAccents.current
    val tone = when (check.state) { CheckState.PASS -> a.protected; CheckState.FAIL -> a.failed; CheckState.RUNNING -> a.working; CheckState.PENDING -> a.neutral }
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.Top) {
        Text(index.toString().padStart(2, '0'), Modifier.width(36.dp), fontFamily = AetherMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.ContentOrLtr))
            if (check.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp)); Text(check.detail, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrLtr), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(when (check.state) {
                CheckState.PASS -> R.string.phase_ready; CheckState.FAIL -> R.string.pill_failed
                CheckState.RUNNING -> R.string.pill_working; CheckState.PENDING -> R.string.phase_verify
            }), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = tone)
        }
        Spacer(Modifier.width(12.dp))
        Icon(when (check.state) { CheckState.PASS -> Icons.Rounded.Check; CheckState.FAIL -> Icons.Rounded.Close; CheckState.RUNNING -> Icons.Rounded.Refresh; CheckState.PENDING -> Icons.Rounded.Schedule },
            null, Modifier.size(20.dp), tone)
    }
}
@Composable
private fun LogConsole(maxHeight: Dp) {
    val a = LocalAetherAccents.current
    val lines by DiagnosticsLog.lines.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    val shown = remember(lines, filter) { lines.filter { matchesLogFilter(it.level, filter) } }
    // Follow the tail rather than item count: a full ring buffer remains 800 rows.
    LaunchedEffect(shown.lastOrNull(), filter, follow) { if (follow && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    SegmentedSelector(LogFilter.entries, filter, { filter = it }, label = {
        stringResource(when (it) { LogFilter.ALL -> R.string.log_all; LogFilter.WARN -> R.string.log_warn; LogFilter.ERROR -> R.string.log_err })
    })
    SwitchRow(stringResource(R.string.diag_console_note), null, follow, enabled = true, onChange = { follow = it })
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SelectionContainer {
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(maxHeight.coerceAtLeast(150.dp)).clip(MaterialTheme.shapes.medium).background(a.console),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { Text(stringResource(R.string.diag_empty_logs), style = AetherConsoleLine, color = InkMid) }
                items(shown) { line ->
                    Text(line.format(), style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr), color = when (line.level) {
                        LogLevel.ERROR -> Rose; LogLevel.WARN -> Amber; LogLevel.INFO -> InkHigh; LogLevel.DEBUG -> InkMid
                    })
                }
            }
        }
    }
}
