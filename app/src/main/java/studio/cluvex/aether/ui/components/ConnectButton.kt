package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.Carbon15
import studio.cluvex.aether.ui.theme.ChalkMuted
import studio.cluvex.aether.ui.theme.Clay
import studio.cluvex.aether.ui.theme.Ember
import studio.cluvex.aether.ui.theme.OnSignal
import studio.cluvex.aether.ui.theme.Signal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ButtonMode { IDLE, BUSY, CONNECTED, ERROR }

/** The one place state colour is decided, so every screen agrees on it. */
fun accentFor(mode: ButtonMode): Color = when (mode) {
    ButtonMode.IDLE -> ChalkMuted
    ButtonMode.BUSY -> Ember
    ButtonMode.CONNECTED -> Signal
    ButtonMode.ERROR -> Clay
}

private const val TICKS = 72

/** Degrees -> radians. Plain val: a conversion call is not a const expression. */
private val DEG = (PI / 180.0).toFloat()

/**
 * The centrepiece: an aperture ring of 72 ticks around a power glyph.
 *
 * Why a ring of ticks and not the old halo + spinner: the halo pulsed the same
 * way whether the tunnel was up or the engine had been scanning for four
 * minutes, so the button told you almost nothing. Here each state has its own
 * unmistakable behaviour:
 *
 *   IDLE       ticks dim and completely still (no animation running at all)
 *   BUSY       an amber head sweeps the ring — visible proof work is happening
 *   CONNECTED  the ring lifts to a steady glow, the core fills solid Signal
 *   ERROR      alternating clay ticks, frozen
 *
 * The sweep is an [Animatable] read INSIDE the draw lambda: that is a draw-only
 * state read, so an active ring costs redraws of one Canvas, never a
 * recomposition of the screen around it. While idle it is snapped to 0 and no
 * animation is running.
 *
 * ACCESSIBILITY: everything above is painted on a Canvas, and the glyph in the
 * middle is decorative, so until now the app's primary control was a silent
 * 244dp box. [stateLabel] is published as the node's state and [actionLabel] as
 * the click label, both in the same words the header pill and the pinned button
 * use, so TalkBack announces "Protected, button, double tap to disconnect".
 */
@Composable
fun ConnectButton(
    mode: ButtonMode,
    onClick: () -> Unit,
    stateLabel: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
) {
    val accent = accentFor(mode)
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(AetherDur.Slow, easing = AetherEaseOut),
        label = "accent",
    )
    val core by animateFloatAsState(
        targetValue = if (mode == ButtonMode.CONNECTED) 1f else 0f,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "core",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(AetherDur.Snap, easing = AetherEaseOut),
        label = "press",
    )

    val sweep = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        when (mode) {
            ButtonMode.BUSY -> {
                sweep.snapTo(0f)
                sweep.animateTo(1f, infiniteRepeatable(tween(1500, easing = LinearEasing)))
            }
            ButtonMode.CONNECTED -> {
                sweep.snapTo(0f)
                sweep.animateTo(1f, infiniteRepeatable(tween(5200, easing = LinearEasing)))
            }
            else -> sweep.snapTo(0f)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(244.dp)
            .scale(press)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { stateDescription = stateLabel },
    ) {
        Canvas(modifier = Modifier.size(244.dp)) {
            val radius = size.minDimension / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val head = sweep.value
            val outer = radius - 1.dp.toPx()
            val inner = outer - 15.dp.toPx()
            val stroke = 2.dp.toPx()

            for (i in 0 until TICKS) {
                val t = i / TICKS.toFloat()
                val rad = (t * 360f - 90f) * DEG
                val dx = cos(rad)
                val dy = sin(rad)
                val alpha = when (mode) {
                    ButtonMode.IDLE -> 0.15f
                    ButtonMode.ERROR -> if (i % 3 == 0) 0.42f else 0.12f
                    ButtonMode.BUSY -> {
                        val d = ((t - head) + 1f) % 1f
                        if (d < 0.26f) 0.14f + 0.86f * (1f - d / 0.26f) else 0.11f
                    }
                    ButtonMode.CONNECTED -> {
                        val d = ((t - head) + 1f) % 1f
                        val boost = if (d < 0.32f) 0.45f * (1f - d / 0.32f) else 0f
                        0.36f + boost
                    }
                }
                drawLine(
                    color = animatedAccent.copy(alpha = alpha),
                    start = Offset(cx + dx * inner, cy + dy * inner),
                    end = Offset(cx + dx * outer, cy + dy * outer),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            // Hairline that separates the ring from the core.
            drawCircle(
                color = animatedAccent.copy(alpha = 0.20f),
                radius = radius * 0.60f,
                style = Stroke(width = 1.dp.toPx()),
            )

            // The core. Quiet carbon disc by default; a solid Signal fill (plus
            // a soft bloom) is earned only by a verified tunnel.
            drawCircle(color = Carbon15, radius = radius * 0.46f)
            if (core > 0.01f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animatedAccent.copy(alpha = 0.26f * core), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = radius * 0.95f,
                    ),
                    radius = radius * 0.95f,
                )
                drawCircle(color = animatedAccent.copy(alpha = core), radius = radius * 0.46f)
            }
        }

        val icon = when (mode) {
            ButtonMode.CONNECTED -> Icons.Rounded.Bolt
            ButtonMode.ERROR -> Icons.Rounded.Warning
            else -> Icons.Rounded.PowerSettingsNew
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (mode == ButtonMode.CONNECTED) OnSignal else animatedAccent,
            modifier = Modifier.size(46.dp),
        )
    }
}
