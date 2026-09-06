package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.cluvex.aether.R
import studio.cluvex.aether.data.PresetStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsPanel(profile: ConnectionProfile, onProfileChange: (ConnectionProfile) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val store = remember(context) { PresetStore(context) }
    val presets by store.presets.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        ControlSection(stringResource(R.string.presets_title), stringResource(R.string.presets_subtitle), Icons.Rounded.Bookmarks) {
            if (!enabled) { NoticeBar(stringResource(R.string.presets_locked)); Spacer(Modifier.height(16.dp)) }
            if (presets.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                    Text(stringResource(R.string.presets_empty), Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            presets.forEachIndexed { index, preset ->
                key(preset.name) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                        IconBadge(Icons.Rounded.BookmarkBorder, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(enabled = enabled, onClick = {
                                    if (enabled) {
                                        focus.clearFocus(force = true); onProfileChange(preset.toProfile())
                                        Toast.makeText(context, context.getString(R.string.presets_applied, preset.name), Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text(stringResource(R.string.presets_apply)) }
                                TextButton(onClick = { deleting = preset.name }) { Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (deleting == preset.name) {
                        NoticeBar(stringResource(R.string.ux_delete_setup, preset.name), tone = MaterialTheme.colorScheme.error, icon = Icons.Rounded.DeleteOutline)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { store.delete(preset.name); deleting = null }) { Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                    if (index < presets.lastIndex) Hairline()
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text(stringResource(R.string.presets_name_label)) }, placeholder = { Text(stringResource(R.string.presets_name_placeholder)) },
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            ActionPill(stringResource(R.string.presets_save), icon = Icons.Rounded.Add, filled = true,
                enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    val saved = store.save(name, profile)
                    val message = if (saved) context.getString(R.string.presets_saved, name.trim()) else context.getString(R.string.presets_full, PresetStore.MAX_PRESETS)
                    if (saved) { name = ""; focus.clearFocus() }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                })
        }
        Spacer(Modifier.height(40.dp)); Hairline(); Spacer(Modifier.height(32.dp))
        ControlSection(stringResource(R.string.presets_transfer_title), stringResource(R.string.presets_transfer_sub), Icons.Rounded.Share) {
            NavRow(stringResource(R.string.backup_copy), icon = Icons.Rounded.ContentCopy, onClick = {
                clipboard.setText(AnnotatedString(PresetStore.exportText(profile)))
                Toast.makeText(context, R.string.backup_copied, Toast.LENGTH_LONG).show()
            })
            Hairline()
            NavRow(stringResource(R.string.backup_paste), icon = Icons.Rounded.ContentPaste, enabled = enabled, onClick = {
                if (enabled) {
                    val imported = PresetStore.importText(clipboard.getText()?.text)
                    if (imported == null) Toast.makeText(context, R.string.backup_invalid, Toast.LENGTH_LONG).show()
                    else {
                        focus.clearFocus(force = true); onProfileChange(imported)
                        Toast.makeText(context, R.string.backup_imported, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }
}
