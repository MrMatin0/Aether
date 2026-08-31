package studio.cluvex.aether.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/** One installed app the user can pick for split tunneling. */
data class AppEntry(val packageName: String, val label: String)

/**
 * A multi-select dialog listing the device's launchable apps, used to pick which
 * apps a split-tunnel or block policy applies to.
 *
 * WHAT CHANGED AND WHY
 *
 *  - There is a COUNT in the header and SELECT-ALL / CLEAR beside the search
 *    field. "Everything except my banking app" meant tapping thirty rows one at
 *    a time, which is the worst interaction that was left in this app.
 *  - Selection is a tappable row with a check chip instead of a Material
 *    Checkbox: the checkbox forced a 48dp column of empty space on the leading
 *    edge and drew its own accent, competing with the state colour.
 *  - Package names are pinned LTR. They are identifiers, not prose, and in the
 *    fa locale `com.example.app` was being reordered around the RTL base
 *    direction.
 *
 * Apps are still loaded off the main thread — PackageManager queries are slow on
 * devices with many apps, and this dialog is opened from a scrolling form.
 *
 * WHAT CHANGED IN THIS PASS, and it is the reason this dialog felt sticky on a
 * phone with a lot of apps installed:
 *
 *  - The selection was a SnapshotStateList. Every visible row read it (to decide
 *    its own checked state) via `contains`, which is a LINEAR scan, and every
 *    toggle invalidated the whole list, so one tap cost O(rows x selection) list
 *    walks plus a recomposition of every row on screen. With 300 launchable apps
 *    and "Select all" pressed, that is exactly the state the interaction is
 *    designed for and exactly where it stuttered. It is an immutable Set behind a
 *    single state now: contains is O(1), and a toggle allocates one small set.
 *  - A row is a `toggleable` node with `Role.Checkbox`. It was a `clickable`, so
 *    TalkBack read out three hundred identical buttons and never once said
 *    whether an app was selected — in a dialog whose ENTIRE purpose is choosing
 *    which apps bypass the tunnel. Getting that wrong silently leaks traffic.
 *  - Rows have a 48dp floor, because they are a list of small tap targets that
 *    people scroll fast.
 */
@Composable
fun AppPickerDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected.toSet()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(AetherRadius.Sheet),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                CardHeader(
                    title = stringResource(R.string.apps_dialog_title),
                    subtitle = stringResource(R.string.apps_selected, chosen.size),
                    icon = Icons.Rounded.Apps,
                    tint = accents.brand,
                )

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    shape = RoundedCornerShape(AetherRadius.Field),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                val loaded = apps
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill(
                        label = stringResource(R.string.apps_select_all),
                        onClick = {
                            loaded?.let { all ->
                                chosen = all.mapTo(LinkedHashSet()) { it.packageName }
                            }
                        },
                        enabled = loaded != null,
                        modifier = Modifier.weight(1f),
                    )
                    ActionPill(
                        label = stringResource(R.string.apps_select_none),
                        onClick = { chosen = emptySet() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 360.dp)
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loaded == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.apps_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val filtered = remember(query, loaded) {
                            if (query.isBlank()) {
                                loaded
                            } else {
                                loaded.filter {
                                    it.label.contains(query, ignoreCase = true) ||
                                        it.packageName.contains(query, ignoreCase = true)
                                }
                            }
                        }
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filtered, key = { it.packageName }) { app ->
                                AppRow(
                                    app = app,
                                    checked = app.packageName in chosen,
                                    tint = accents.brand,
                                    onToggle = {
                                        val pkg = app.packageName
                                        chosen = if (pkg in chosen) {
                                            chosen - pkg
                                        } else {
                                            chosen + pkg
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill(
                        label = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ActionPill(
                        label = stringResource(R.string.apps_done),
                        onClick = { onConfirm(chosen.toList()) },
                        filled = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(AetherRadius.Chip))
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (checked) tint else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = contentColorForTone(tint),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall.copy(
                    textDirection = TextDirection.Ltr,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Lists launchable apps (excluding ourselves), sorted by display label. */
private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                AppEntry(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
