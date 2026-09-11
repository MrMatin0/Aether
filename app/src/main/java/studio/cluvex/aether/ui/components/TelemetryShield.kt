package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseInOut
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherNumeralLarge
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * The diagnostics hero: overall network health as one instrument.
 *
 * WHAT IT REPLACES: a 48dp icon next to a headline. That told a reader the same
 * thing the words underneath already said, and nothing about HOW BROKEN the
 * pipeline was - four failed checks and one failed check looked identical.
 *
 * WHAT IT SHOWS, and why each part is load-bearing rather than decorative:
 *
 *  * The ARC is passed/total. It is the only element on the screen that answers
 *    "how far did it get" without reading four rows.
 *  * The BLOOM is the state colour, so the card reads correctly out of the
 *    corner of an eye: emerald protected, amber probing, rose fault.
 *  * The SWEEP rotates only while something is actually probing. A radar that
 *    always spins is a lie about a system at rest, and this app's motion
 *    contract (ui/components/AmbientBackground, ui/components/PhasePipeline) is
 *    explicit that nothing loops in the steady state.
 *
 * MOTION IS OPTIONAL, NOT DECORATIVE: both loops are created only when there is
 * something to show AND the phone is not set to remove animations, so on a
 * reduced-motion device this is a still diagram with the same information in it.
 * A VPN client is an instrument; a nervous instrument reads as an uncertain one.
 *
 * The canvas is cleared for accessibility - a screen reader gets the verdict and
 * the stat rows, which is the whole content, not a description of a circle.
 */
@Composable
internal fun TelemetryShield(
    tone: Color,
    verdict: String,
    subtitle: String,
    passed: Int,
    total: Int,
    active: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
    stats: @Composable ColumnScope.() -> Unit = {},
) {
    val accents = LocalAetherAccents.current
    val ring = MaterialTheme.colorScheme.outlineVariant
    val motion = active && !LocalReducedMotion.current

    val sweep = if (motion) {
        val transition = rememberInfiniteTransition(label = "radar-sweep")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(AetherDur.Halo, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        ).value
    } else {
        0f
    }

    val pulse = if (motion) {
        val transition = rememberInfiniteTransition(label = "radar-pulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(AetherDur.Loop, easing = AetherEaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else {
        0f
    }

    val integrity by animateFloatAsState(
        targetValue = if (total <= 0) 0f else passed.toFloat() / total.toFloat(),
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "integrity",
    )

    AetherCard(modifier, tone = tone) {
        Box(
            Modifier.fillMaxWidth().height(216.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f - 10.dp.toPx()
                if (radius <= 0f) return@Canvas

                // Glassmorphic bloom. Breathes only while probing.
                val bloom = (if (accents.dark) 0.22f else 0.14f) + pulse * 0.08f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tone.copy(alpha = bloom), Color.Transparent),
                        center = center,
                        radius = radius * 1.25f,
                    ),
                    radius = radius * 1.25f,
                    center = center,
                )

                // Static graticule: three rings and a crosshair.
                listOf(0.42f, 0.71f, 1f).forEach { scale ->
                    drawCircle(
                        color = ring,
                        radius = radius * scale,
                        center = center,
                        style = Stroke(1.dp.toPx()),
                    )
                }
                drawLine(
                    ring,
                    Offset(center.x - radius, center.y),
                    Offset(center.x + radius, center.y),
                    1.dp.toPx(),
                )
                drawLine(
                    ring,
                    Offset(center.x, center.y - radius),
                    Offset(center.x, center.y + radius),
                    1.dp.toPx(),
                )

                // The rotating sweep, only when something is in flight.
                if (motion) {
                    val inner = radius * 0.94f
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color.Transparent, tone.copy(alpha = 0.30f)),
                            center = center,
                        ),
                        startAngle = sweep,
                        sweepAngle = 68f,
                        useCenter = true,
                        topLeft = Offset(center.x - inner, center.y - inner),
                        size = Size(inner * 2f, inner * 2f),
                    )
                }

                // Integrity arc: how far the pipeline actually got.
                if (integrity > 0f) {
                    drawArc(
                        color = tone,
                        startAngle = -90f,
                        sweepAngle = 360f * integrity.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    when {
                        failed -> Icons.Rounded.GppMaybe
                        total > 0 && passed == total -> Icons.Rounded.VerifiedUser
                        active -> Icons.Rounded.Radar
                        else -> Icons.Rounded.Shield
                    },
                    null,
                    Modifier.size(44.dp),
                    tone,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (total <= 0) "\u2013" else "$passed/$total",
                    style = AetherNumeralLarge.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(verdict, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        stats()
    }
}
