package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.data.PresetStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * Saved setups + config transfer.
 *
 * Two problems, one page — but they are TWO problems, so they are now two cards
 * instead of one continuous list that ended in a pair of unexplained
 * copy/paste buttons:
 *
 *  1. The settings that get through change with the network. Re-deriving eight
 *     fields from memory every time is how people give up. Save the combination
 *     that worked, re-apply it in one tap.
 *  2. Moving a working configuration to another phone (or getting it from
 *     whoever found it first) meant reading fields out loud. Now it is one
 *     copyable block of text — and because it goes through ProfileCodec, the
 *     Zero Trust secrets are structurally excluded from it.
 *
 * Applying is gated on [enabled] for the same reason the rest of the engine
 * surface is: the engine reads its arguments at launch, so changing them
 * mid-session would silently do nothing.
 */
@Composable
fun PresetsPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    val clipboard = LocalClipboardManager.current
    val store = remember { PresetStore(context) }
    val presets by store.presets.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        AetherCard {
            CardHeader(
                title = stringResource(R.string.presets_title),
                subtitle = stringResource(R.string.presets_subtitle),
                icon = Icons.Rounded.Bookmarks,
                tint = accents.brand,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.presets_name_label)) },
                placeholder = { Text(stringResource(R.string.presets_name_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(AetherRadius.Field),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            ActionPill(
                label = stringResource(R.string.presets_save),
                icon = Icons.Rounded.Save,
                filled = true,
                enabled = name.isNotBlank(),
                onClick = {
                    val saved = store.save(name, profile)
                    val message = if (saved) {
                        context.getString(R.string.presets_saved, name.trim())
                    } else {
                        context.getString(R.string.presets_full, PresetStore.MAX_PRESETS)
                    }
                    if (saved) name = ""
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            if (presets.isEmpty()) {
                Hint(stringResource(R.string.presets_empty))
            } else {
                if (!enabled) {
                    NoticeBar(text = stringResource(R.string.presets_locked))
                    Spacer(Modifier.height(10.dp))
                }
                presets.forEachIndexed { index, preset ->
                    if (index > 0) Hairline(alpha = 0.5f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ActionPill(
                            label = stringResource(R.string.presets_apply),
                            icon = Icons.Rounded.PlayArrow,
                            enabled = enabled,
                            onClick = {
                                onProfileChange(preset.toProfile())
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.presets_applied, preset.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        IconButton(onClick = { store.delete(preset.name) }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.presets_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        AetherCard {
            CardHeader(
                title = stringResource(R.string.presets_transfer_title),
                subtitle = stringResource(R.string.presets_transfer_sub),
                icon = Icons.Rounded.Share,
                tint = accents.brand,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionPill(
                    label = stringResource(R.string.backup_copy),
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        clipboard.setText(AnnotatedString(PresetStore.exportText(profile)))
                        Toast.makeText(context, R.string.backup_copied, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionPill(
                    label = stringResource(R.string.backup_paste),
                    icon = Icons.Rounded.ContentPaste,
                    enabled = enabled,
                    onClick = {
                        val imported = PresetStore.importText(clipboard.getText()?.text)
                        if (imported == null) {
                            Toast.makeText(context, R.string.backup_invalid, Toast.LENGTH_LONG)
                                .show()
                        } else {
                            onProfileChange(imported)
                            Toast.makeText(context, R.string.backup_imported, Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
