package studio.cluvex.aether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** At large type or narrow widths, choices become full-width radio rows.
 * No truncation, moving layout indicator, or invisible keyboard focus.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>, selected: T, onSelect: (T) -> Unit,
    label: @Composable (T) -> String, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    if (options.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup()) {
        val stack = maxWidth / options.size < 96.dp || LocalDensity.current.fontScale >= 1.3f
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Choice(option == selected, enabled, { onSelect(option) }, label(option), Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Choice(option == selected, enabled, { onSelect(option) }, label(option), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Choice(selected: Boolean, enabled: Boolean, onClick: () -> Unit, label: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.clip(MaterialTheme.shapes.small)
        .background(if (selected) colors.primaryContainer else colors.surfaceVariant)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), colors.primary.copy(alpha = if (enabled) 1f else 0.45f))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = (if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.weight(1f, fill = false))
    }
}
