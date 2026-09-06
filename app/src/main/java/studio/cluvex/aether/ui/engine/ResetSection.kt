package studio.cluvex.aether.ui.engine

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.ActionPill

/** Inline confirmation keeps accidental taps from wiping a working configuration. */
@Composable
internal fun ResetSection(enabled: Boolean, edit: ProfileEdit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var confirming by remember(enabled) { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    EngineSection(title = stringResource(R.string.section_reset),
        subtitle = stringResource(R.string.section_reset_note), icon = Icons.Rounded.RestartAlt,
        tint = error, tone = error, headerGap = EngineSpacing.Divider, modifier = modifier) {
        if (confirming && enabled) {
            Text(stringResource(R.string.ux_reset_confirm), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(EngineSpacing.Inline))
            ActionPill(label = stringResource(R.string.reset_settings), tint = error,
                modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = {
                    if (enabled) {
                        focus.clearFocus(force = true)
                        confirming = false
                        edit { ConnectionProfile() }
                        Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
                    }
                })
            TextButton(onClick = { confirming = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        } else {
            ActionPill(label = stringResource(R.string.reset_settings), onClick = { confirming = true },
                icon = Icons.Rounded.RestartAlt, enabled = enabled, tint = error, modifier = Modifier.fillMaxWidth())
        }
    }
}
