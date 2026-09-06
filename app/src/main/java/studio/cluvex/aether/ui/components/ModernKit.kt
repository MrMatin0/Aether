package studio.cluvex.aether.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A shared editorial section, not another card around every form. */
@Composable
internal fun ControlSection(
    title: String, subtitle: String? = null, icon: ImageVector? = null,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, null, Modifier.padding(top = 4.dp).size(24.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/** Static topology, never a second connect button or simulated live telemetry. */
@Composable
internal fun SignalDiagram(tone: Color, verified: Boolean, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.background
    Box(modifier.height(112.dp).fillMaxWidth().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val cx = size.width / 2f
            val radius = 32.dp.toPx()
            val inset = 24.dp.toPx()
            drawLine(line, Offset(inset, cy), Offset(size.width - inset, cy), 1.dp.toPx())
            listOf(inset, size.width - inset).forEach { x ->
                drawCircle(surface, 7.dp.toPx(), Offset(x, cy))
                drawCircle(tone, 5.dp.toPx(), Offset(x, cy), style = Stroke(1.5.dp.toPx()))
            }
            drawCircle(surface, radius + 12.dp.toPx(), Offset(cx, cy))
            drawCircle(line, radius + 12.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawCircle(tone, radius, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            if (!verified) {
                drawLine(tone, Offset(cx - 9.dp.toPx(), cy), Offset(cx + 9.dp.toPx(), cy), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        if (verified) Icon(Icons.Rounded.Check, null, Modifier.size(28.dp), tone)
    }
}
