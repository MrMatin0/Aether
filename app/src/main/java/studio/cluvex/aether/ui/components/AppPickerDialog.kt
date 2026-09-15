package studio.cluvex.aether.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** A launchable package, deduplicated even when it exposes several activities. */
data class AppEntry(val packageName: String, val label: String)

private val SelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() }, restore = { it.toSet() },
)

/**
 * Normalize phone-keyboard input before matching app labels and package names.
 * Persian keyboards commonly emit Arabic yeh/kaf, ZWNJ, and compatibility
 * characters that look identical but otherwise make search appear broken.
 */
internal fun normalizeAppSearchText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('\u064a', '\u06cc')
        .replace('\u0649', '\u06cc')
        .replace('\u0643', '\u06a9')
        .replace(Regex("[\\u200c\\u200d\\u200e\\u200f\\u061c]"), "")
        .trim()
        .replace(Regex("\\s+"), " ")

internal fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val terms = normalizeAppSearchText(query).split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return apps
    return apps.filter { app ->
        val searchable = normalizeAppSearchText("${app.label} ${app.packageName}")
        terms.all(searchable::contains)
    }
}

/** Select visible matches without discarding packages missing from the launchable-app list. */
internal fun selectVisibleApps(chosen: Set<String>, visible: List<AppEntry>): Set<String> =
    chosen + visible.map { it.packageName }

/** Stable output prevents semantically identical selections from causing noisy profile writes. */
internal fun orderedAppSelection(chosen: Set<String>, apps: List<AppEntry>): List<String> {
    val visible = apps.map { it.packageName }.filter(chosen::contains)
    val missing = (chosen - visible.toSet()).sorted()
    return visible + missing
}

@Composable
fun AppPickerDialog(selected: List<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(selected.toSet()) }
    LaunchedEffect(context, attempt) {
        failed = false
        apps = null
        try {
            apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val visible = remember(apps, query) { filterApps(apps.orEmpty(), query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            // The entire form can scroll in landscape, split screen, or above the keyboard.
            Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                CardHeader(stringResource(R.string.apps_dialog_title),
                    subtitle = stringResource(R.string.apps_selected, chosen.size),
                    icon = Icons.Rounded.Apps, tint = LocalAetherAccents.current.brand)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.apps_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { chosen = selectVisibleApps(chosen, visible) }, enabled = apps != null && visible.isNotEmpty()) {
                    Text(stringResource(if (query.isBlank()) R.string.apps_select_all else R.string.ux_select_matches))
                }
                TextButton(onClick = { chosen = chosen - visible.map { it.packageName }.toSet() },
                    enabled = apps != null && visible.any { it.packageName in chosen }) {
                    Text(stringResource(R.string.ux_clear_matches))
                }
                when {
                    failed -> {
                        Text(stringResource(R.string.ux_apps_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                    }
                    apps == null -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.apps_loading))
                    }
                    visible.isEmpty() -> Text(stringResource(R.string.ux_apps_empty), modifier = Modifier.padding(vertical = 24.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            val checked = app.packageName in chosen
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .toggleable(checked, role = Role.Checkbox, onValueChange = {
                                    chosen = if (checked) chosen - app.packageName else chosen + app.packageName
                                }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    val loaded = apps
                    if (loaded != null && !failed) onConfirm(orderedAppSelection(chosen, loaded))
                }, enabled = apps != null && !failed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.apps_done))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return pm.queryIntentActivities(intent, 0).mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == context.packageName) return@mapNotNull null
        AppEntry(pkg, info.loadLabel(pm).toString())
    }.distinctBy { it.packageName }.sortedWith { a, b -> collator.compare(a.label, b.label) }
}
