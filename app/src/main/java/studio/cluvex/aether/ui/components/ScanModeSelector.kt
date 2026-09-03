package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.ui.scanDescription
import studio.cluvex.aether.ui.scanEta
import studio.cluvex.aether.ui.scanLabel
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * The scan-mode picker.
 *
 * WHAT THIS REPLACES: a dropdown with five entries - turbo, balanced,
 * thorough, stealth, ironclad - and not one word of explanation. Choosing it
 * meant guessing which of two synonyms was slower, and the mode that behaves
 * genuinely differently (every candidate has to carry a real request end to
 * end) was the last item under a name that ranks against nothing. Worse, a
 * dropdown hides every option you did not pick, so the ONE fact that matters
 * here - this choice is a trade between seconds and certainty - was never on
 * screen at all.
 *
 * Three modes, all three visible, each with what it does and roughly how long
 * it takes. The wait is the product of this setting, so the wait is printed on
 * it.
 *
 * It is a radio GROUP, not three buttons: [selectable] with
 * [Role.RadioButton] makes a screen reader announce "Precise, radio button,
 * 2 of 3, selected" instead of three unrelated clickable rows.
 */
@Composable
fun ScanModeSelector(
    selected: ScanMode,
    onSelect: (ScanMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val brand = LocalAetherAccents.current.brand
    Column(modifier = modifier.fillMaxWidth()) {
        ScanMode.entries.forEachIndexed { index, mode ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            ScanModeOption(
                mode = mode,
                chosen = mode == selected,
                brand = brand,
                enabled = enabled,
                onSelect = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun ScanModeOption(
    mode: ScanMode,
    chosen: Boolean,
    brand: Color,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val swap = aetherDuration(AetherDur.Quick)
    val outline by animateColorAsState(
        targetValue = if (chosen) {
            brand.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(swap, easing = AetherEaseOut),
        label = "scanoutline",
    )
    val fill by animateColorAsState(
        targetValue = if (chosen) brand.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(swap, easing = AetherEaseOut),
        label = "scanfill",
    )
    val accent = if (chosen) brand else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Field),
        color = fill,
        border = BorderStroke(if (chosen) 1.5.dp else 1.dp, outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = chosen,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(mode),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = scanLabel(mode),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The time cost, on the option itself. This is the number
                    // people are actually choosing between.
                    Text(
                        text = scanEta(mode),
                        style = AetherMetaLabel,
                        color = accent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = scanDescription(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            // onClick = null: the whole row is the control, and a nested
            // clickable would give a screen reader two things to activate.
            RadioButton(selected = chosen, onClick = null, enabled = enabled)
        }
    }
}

private fun iconFor(mode: ScanMode): ImageVector = when (mode) {
    ScanMode.TURBO -> Icons.Rounded.Bolt
    ScanMode.PRECISE -> Icons.Rounded.Speed
    ScanMode.ULTRA -> Icons.Rounded.Shield
}
