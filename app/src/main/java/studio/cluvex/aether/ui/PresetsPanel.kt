package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Names get their own line; actions wrap instead of squeezing the setup name away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsPanel(profile: ConnectionProfile, onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val store = remember(context) { PresetStore(context) }
    val presets by store.presets.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        AetherCard {
            CardHeader(stringResource(R.string.presets_title), subtitle = stringResource(R.string.presets_subtitle), icon = Icons.Rounded.Bookmarks)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text(stringResource(R.string.presets_name_label)) },
                placeholder = { Text(stringResource(R.string.presets_name_placeholder)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            ActionPill(label = stringResource(R.string.presets_save), icon = Icons.Rounded.Save, filled = true,
                enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    val saved = store.save(name, profile)
                    val message = if (saved) context.getString(R.string.presets_saved, name.trim())
                        else context.getString(R.string.presets_full, PresetStore.MAX_PRESETS)
                    if (saved) { name = ""; focus.clearFocus() }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                })
            Spacer(Modifier.height(16.dp))
            if (presets.isEmpty()) Hint(stringResource(R.string.presets_empty))
            if (!enabled) NoticeBar(stringResource(R.string.presets_locked))
            presets.forEach { preset ->
                key(preset.name) {
                    Hairline()
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(enabled = enabled, onClick = {
                                if (enabled) {
                                    focus.clearFocus(force = true)
                                    onProfileChange(preset.toProfile())
                                    Toast.makeText(context, context.getString(R.string.presets_applied, preset.name), Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(stringResource(R.string.presets_apply)) }
                            TextButton(onClick = { deleting = preset.name }) {
                                Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (deleting == preset.name) {
                            Text(stringResource(R.string.ux_delete_setup, preset.name), style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { store.delete(preset.name); deleting = null }) {
                                    Text(stringResource(R.string.presets_delete), color = MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        AetherCard {
            CardHeader(stringResource(R.string.presets_transfer_title),
                subtitle = stringResource(R.string.presets_transfer_sub), icon = Icons.Rounded.Share)
            Spacer(Modifier.height(16.dp))
            ActionPill(label = stringResource(R.string.backup_copy), icon = Icons.Rounded.ContentCopy,
                modifier = Modifier.fillMaxWidth(), onClick = {
                    clipboard.setText(AnnotatedString(PresetStore.exportText(profile)))
                    Toast.makeText(context, R.string.backup_copied, Toast.LENGTH_LONG).show()
                })
            Spacer(Modifier.height(12.dp))
            ActionPill(label = stringResource(R.string.backup_paste), icon = Icons.Rounded.ContentPaste,
                enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (enabled) {
                        val imported = PresetStore.importText(clipboard.getText()?.text)
                        if (imported == null) Toast.makeText(context, R.string.backup_invalid, Toast.LENGTH_LONG).show()
                        else {
                            focus.clearFocus(force = true)
                            onProfileChange(imported)
                            Toast.makeText(context, R.string.backup_imported, Toast.LENGTH_SHORT).show()
                        }
                    }
                })
        }
    }
}
