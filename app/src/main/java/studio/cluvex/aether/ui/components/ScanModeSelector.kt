package studio.cluvex.aether.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.ui.scanDescription
import studio.cluvex.aether.ui.scanEta
import studio.cluvex.aether.ui.scanLabel

/** Keep the choice and its time cost readable even with large Persian text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanModeSelector(
    selected: ScanMode, onSelect: (ScanMode) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScanMode.entries.forEach { mode ->
            val chosen = mode == selected
            val colors = MaterialTheme.colorScheme
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (chosen) colors.primaryContainer else colors.surface,
                border = BorderStroke(1.dp, if (chosen) colors.primary else colors.outlineVariant)) {
                Row(Modifier.fillMaxWidth().selectable(selected = chosen, enabled = enabled,
                    role = Role.RadioButton, onClick = { onSelect(mode) }).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(scanLabel(mode), style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
                            Text(scanEta(mode), style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(scanDescription(mode), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f))
                    }
                    Spacer(Modifier.width(8.dp))
                    RadioButton(selected = chosen, enabled = enabled, onClick = null)
                }
            }
        }
    }
}
