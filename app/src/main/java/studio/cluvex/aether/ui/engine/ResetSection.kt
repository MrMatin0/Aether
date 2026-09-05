package studio.cluvex.aether.ui.engine

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.ActionPill

/**
 * The way out of a configuration that does not connect.
 *
 * Present in BOTH depths on purpose: the escape hatch from a broken setup must
 * never be the thing hidden behind an expert toggle. It is toned with the error
 * colour because it discards every field on this page at once.
 */
@Composable
internal fun ResetSection(
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val error = MaterialTheme.colorScheme.error
    EngineSection(
        title = stringResource(R.string.section_reset),
        subtitle = stringResource(R.string.section_reset_note),
        icon = Icons.Rounded.RestartAlt,
        tint = error,
        tone = error,
        headerGap = EngineSpacing.Divider,
        modifier = modifier,
    ) {
        ActionPill(
            label = stringResource(R.string.reset_settings),
            onClick = {
                edit { ConnectionProfile() }
                Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
            },
            icon = Icons.Rounded.RestartAlt,
            enabled = enabled,
            tint = error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
