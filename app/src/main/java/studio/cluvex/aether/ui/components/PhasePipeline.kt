package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/** Compatibility component: wrap full labels and avoid a permanent animation loop.
 * Progress is represented by completed checks and a selected current step.
 */
@Composable
fun PhasePipeline(step: Int, labels: List<String>, accent: Color, active: Boolean, modifier: Modifier = Modifier) {
    val current = step.coerceIn(0, labels.size)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEachIndexed { index, label ->
            val done = index < current
            val working = index == current && active
            val tone = if (done || working) accent else MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { selected = working },
                verticalAlignment = Alignment.CenterVertically) {
                if (done) Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), tint = tone)
                else Text("${index + 1}", Modifier.widthIn(min = 24.dp), color = tone, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(12.dp))
                Text(label, Modifier.weight(1f), color = tone,
                    style = if (working) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall)
            }
        }
    }
}
