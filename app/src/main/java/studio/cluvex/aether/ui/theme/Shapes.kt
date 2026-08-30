package studio.cluvex.aether.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * The shape scale.
 *
 * Before this file every corner radius in the app was a literal at the call
 * site: 10, 13, 14, 18, 20, 6, 2 — chosen per component, which is why a chip, a
 * field and a card that sat 8dp apart looked like they came from three
 * different apps. Radius now carries meaning: the smaller the element, the
 * tighter the corner, and everything of the same size class matches.
 */
object AetherRadius {
    /** Status pills, level chips, tiny badges. */
    val Chip = 12.dp

    /** Inputs, selectors, dropdown fields, secondary buttons. */
    val Field = 16.dp

    /** The default panel. Everything grouped lives in one of these. */
    val Card = 24.dp

    /** The floating bottom dock and the primary action capsule. */
    val Dock = 26.dp

    /** Dialogs and sheets: the largest thing that is still a box. */
    val Sheet = 30.dp
}

val AetherShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(AetherRadius.Chip),
    medium = RoundedCornerShape(AetherRadius.Field),
    large = RoundedCornerShape(AetherRadius.Card),
    extraLarge = RoundedCornerShape(AetherRadius.Sheet),
)
