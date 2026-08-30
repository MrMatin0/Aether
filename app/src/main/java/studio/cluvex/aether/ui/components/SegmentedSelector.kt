package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherRadius

/**
 * Segmented single-select.
 *
 * WHAT CHANGED AND WHY
 *
 * Before: four background colours cross-faded independently, which meant the
 * selection appeared to teleport — nothing connected the old choice to the new
 * one, and with a wash that faint the control could read as "nothing selected".
 *
 * Now there is ONE indicator that slides. That is not decoration: a moving
 * indicator is the cheapest possible way to show that these options are
 * mutually exclusive, which is the entire semantics of the control.
 *
 * RTL: the offset is applied with [Modifier.offset], which is layout-direction
 * aware, so in the fa locale the indicator travels right-to-left along with the
 * labels instead of sliding out of the control.
 *
 * The selection is an accent wash plus accent text rather than a solid fill:
 * with five of these stacked in a settings form, solid fills produced five
 * competing blocks and the actual connect button stopped being the loudest
 * thing on screen.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(AetherRadius.Field)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(5.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val itemWidth = maxWidth / count
        val index = options.indexOf(selected).coerceAtLeast(0)
        val slide by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
            label = "segslide",
        )
        val indicator by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.primary
                .copy(alpha = if (enabled) 0.18f else 0.07f),
            animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
            label = "segind",
        )

        Box(
            modifier = Modifier
                .offset(x = slide)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(AetherRadius.Chip))
                .background(indicator),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            options.forEach { option ->
                val isSelected = option == selected
                val foreground by animateColorAsState(
                    targetValue = when {
                        isSelected && enabled -> MaterialTheme.colorScheme.primary
                        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                    animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
                    label = "segfg",
                )
                val interaction = remember { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(AetherRadius.Chip))
                        // selectable(), not clickable(): the tint is the only
                        // signal that a segment is current, and a screen reader
                        // cannot see a tint. This announces "selected, 2 of 4".
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(option) },
                        )
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = label(option),
                        color = foreground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
