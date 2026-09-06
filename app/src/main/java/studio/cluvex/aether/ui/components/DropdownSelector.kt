package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.*

@Composable
fun <T> DropdownSelector(options: List<T>, selected: T, onSelect: (T) -> Unit,
    label: @Composable (T) -> String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    val available = enabled && options.isNotEmpty()
    LaunchedEffect(available) { if (!available) expanded = false }
    val rotation by animateFloatAsState(if (expanded && available) 180f else 0f,
        tween(aetherDuration(AetherDur.Quick), easing = AetherEaseOut), label = "chevron")
    val description = stringResource(if (expanded && available) R.string.ux_expanded else R.string.ux_collapsed)
    Box(modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = !expanded }, enabled = available,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = description },
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (expanded && available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label(selected), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (available) 1f else 0.45f))
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).rotate(rotation),
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (available) 1f else 0.45f))
            }
        }
        DropdownMenu(expanded = expanded && available, onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            options.forEach { option ->
                val chosen = option == selected
                DropdownMenuItem(text = { Text(label(option), style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = chosen }, enabled = available,
                    trailingIcon = { if (chosen) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary) },
                    onClick = { expanded = false; if (available && option in options) onSelect(option) })
            }
        }
    }
}
