package studio.cluvex.aether.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Choices reserve their indicator space, so selection cannot reflow a label. */
@Composable
fun <T> SegmentedSelector(options: List<T>, selected: T, onSelect: (T) -> Unit,
    label: @Composable (T) -> String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (options.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup()) {
        val stack = maxWidth / options.size < 112.dp || LocalDensity.current.fontScale >= 1.3f
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { Choice(it == selected, enabled, { onSelect(it) }, label(it), Modifier.fillMaxWidth()) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { Choice(it == selected, enabled, { onSelect(it) }, label(it), Modifier.weight(1f)) }
        }
    }
}
@Composable
private fun Choice(selected: Boolean, enabled: Boolean, onClick: () -> Unit, label: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val ink = (if (selected) c.onPrimaryContainer else c.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.45f)
    Row(modifier.clip(shape).background(if (selected) c.primaryContainer else c.surface)
        .border(BorderStroke(1.dp, if (selected && enabled) c.primary else c.outlineVariant), shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), ink)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, modifier = Modifier.weight(1f))
    }
}
