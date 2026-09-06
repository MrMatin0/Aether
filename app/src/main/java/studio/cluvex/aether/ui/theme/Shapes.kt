package studio.cluvex.aether.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Tight control geometry, generous destination surfaces. */
object AetherRadius {
    val Chip = 8.dp
    val Field = 12.dp
    val Card = 20.dp
    val Dock = 24.dp
    val Sheet = 28.dp
}
val AetherShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(AetherRadius.Chip),
    medium = RoundedCornerShape(AetherRadius.Field),
    large = RoundedCornerShape(AetherRadius.Card),
    extraLarge = RoundedCornerShape(AetherRadius.Sheet),
)
