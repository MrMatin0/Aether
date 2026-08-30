package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * The app backdrop.
 *
 * PERFORMANCE CONTRACT (from the 1.2.2 fix, still honoured): nothing animates
 * behind the UI continuously. The original "aurora" rebuilt three full-screen
 * radial gradients every frame for as long as the app was open, which is what
 * made the whole UI feel heavy.
 *
 * This keeps the steady-state cost of a flat fill — three rects, zero
 * invalidations — while giving the screen depth: a top-lit neutral gradient, a
 * state-coloured bloom behind the connect orb, and a much fainter brand bloom in
 * the opposite corner so the composition is not symmetrical. Only the bloom
 * alpha animates, only on the connect/disconnect transition (~520ms), and it is
 * completely still after that.
 *
 * Both blooms are drawn in whatever the current [accent] is, so the room the
 * app is in changes colour with the tunnel state — which is the one piece of
 * decoration in this UI that is actually load-bearing information.
 */
@Composable
fun AmbientBackground(
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val bloom by animateFloatAsState(
        targetValue = if (active) {
            if (accents.dark) 0.20f else 0.13f
        } else {
            if (accents.dark) 0.08f else 0.05f
        },
        animationSpec = tween(AetherDur.Slow, easing = AetherEaseOut),
        label = "bloom",
    )
    val brand = accents.brand

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to accents.backdropTop,
                        0.55f to accents.backdropBottom,
                        1f to accents.backdropBottom,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = bloom), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.14f),
                        radius = size.maxDimension * 0.62f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(brand.copy(alpha = bloom * 0.4f), Color.Transparent),
                        center = Offset(size.width * 0.06f, size.height * 0.86f),
                        radius = size.maxDimension * 0.5f,
                    ),
                )
            },
    )
}
