package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut

/**
 * Segmented single-select.
 *
 * The old version filled the selected segment with SOLID primary. With four or
 * five of these stacked in the settings list, the screen ended up with five
 * competing solid accent blocks and the actual connect button no longer stood
 * out. The selection is now an accent wash plus accent text: unmistakable, but
 * quiet enough that solid Signal keeps meaning "primary action".
 *
 * 1.4.3 FIXES TWO REAL DEFECTS:
 *
 * 1. ACCESSIBILITY. The segments were plain `clickable` nodes, so the ONLY
 *    signal of which option is live — an accent wash and an accent text colour
 *    — did not exist for a screen reader. TalkBack read three or five identical
 *    buttons with no indication of the current value, which makes the protocol,
 *    scan-mode and log-filter selectors unusable without sight. They are now
 *    `selectable` with [Role.RadioButton], so the announcement becomes
 *    "Thorough, radio button, selected, 2 of 3". This is exactly the treatment
 *    the language pill already had; the two controls were inconsistent for no
 *    reason.
 *
 * 2. TOUCH TARGET. 11dp of padding around a 19sp line is a ~41dp segment, under
 *    the 48dp minimum. Wrapping each label in a centred [Box] with a
 *    `defaultMinSize` floor fixes the height without changing the layout, and
 *    keeps the label optically centred instead of pinned to the top of a taller
 *    box (which is what happens if you put the constraint on the Text itself).
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.16f else 0.07f)
                } else {
                    Color.Transparent
                },
                animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
                label = "segbg",
            )
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .selectable(
                        selected = isSelected,
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(vertical = 11.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = label(option),
                    color = foreground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}
