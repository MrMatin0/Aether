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
import studio.cluvex.aether.ui.theme.Carbon00
import studio.cluvex.aether.ui.theme.Carbon05

/**
 * The app backdrop.
 *
 * PERFORMANCE CONTRACT (from the 1.2.2 fix, still honoured): nothing animates
 * behind the UI continuously. The old "aurora" rebuilt three full-screen
 * radial gradients every frame for as long as the app was open, which is what
 * made the whole UI feel heavy; the replacement was a single flat fill.
 *
 * This version keeps the cost of a flat fill in the steady state — two rects,
 * zero invalidations — but gives the screen a top-lit gradient and a faint
 * accent wash so the connect ring is not floating on a dead black slab. The
 * wash alpha only animates on the connect/disconnect transition (~520 ms) and
 * is completely still after that.
 */
@Composable
fun AmbientBackground(
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val wash by animateFloatAsState(
        targetValue = if (active) 0.13f else 0.045f,
        animationSpec = tween(AetherDur.Slow, easing = AetherEaseOut),
        label = "wash",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to Carbon05,
                        0.5f to Carbon00,
                        1f to Carbon00,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = wash), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.16f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            },
    )
}
