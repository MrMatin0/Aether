package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

enum class ButtonMode { IDLE, BUSY, CONNECTED, ERROR }

/**
 * The one place state colour is decided, so every screen agrees on it.
 *
 * Composable now (it was a plain function): the state hues differ between the
 * light and dark palettes, so this has to read the theme rather than return a
 * compile-time constant.
 */
@Composable
fun accentFor(mode: ButtonMode): Color {
    val accents = LocalAetherAccents.current
    return when (mode) {
        ButtonMode.IDLE -> accents.neutral
        ButtonMode.BUSY -> accents.working
        ButtonMode.CONNECTED -> accents.protected
        ButtonMode.ERROR -> accents.failed
    }
}

/** Degrees -> radians. Plain val: a conversion call is not a const expression. */
private const val START_ANGLE = -90f

/** The size the orb was designed at, and the size it still uses when it fits. */
private val ORB_MAX = 256.dp

/**
 * The smallest the orb may get. Below this the state word inside it starts
 * wrapping to three lines and the ring stops reading as a gauge.
 */
private val ORB_MIN = 184.dp

/** Breathing room either side of the orb, so it never touches the page margin. */
private val ORB_GUTTER = 96.dp

/**
 * The centrepiece: a progress orb.
 *
 * WHAT CHANGED AND WHY
 *
 * The previous ring was 72 ticks with a sweeping head. It was honest about
 * "something is happening" and silent about everything else — the sweep looked
 * identical three seconds into a Turbo scan and four minutes into an Ironclad
 * one, which is exactly the situation where people force-quit the app and
 * conclude the tunnel never works.
 *
 * Two changes fixed that:
 *
 *   1. The arc is DETERMINATE. [progress] is driven by the real controller
 *      stage (engine → tunnel → verify → ready), so the ring fills as actual
 *      work completes. A travelling highlight rides the track on top of it,
 *      which keeps the "still alive" signal without pretending to be progress.
 *   2. The words moved INSIDE. The state word and a live counter now sit at the
 *      optical centre of the screen instead of in a separate line below the
 *      ring, so the answer to "am I protected?" and the ring that means it are
 *      one object.
 *
 * Each state still has its own unmistakable behaviour:
 *
 *   IDLE       bare track, nothing animating at all
 *   BUSY       amber arc grows with real progress, highlight sweeps the track
 *   CONNECTED  full mint ring, core fills, one slow halo rotation
 *   ERROR      three broken rose arcs, frozen
 *
 * WHAT CHANGED IN THIS PASS
 *
 *   SIZE. The orb was a hard 256.dp, twice — once on the Box and once on the
 *   Canvas inside it. On a 360dp-wide phone that is 71% of the viewport, and on
 *   a 320dp one (still a live device class, and the width every phone becomes in
 *   split-screen) it left 32dp of margin while the dock, the status hint and the
 *   session card all competed for what was left, so the PRIMARY CONTROL was the
 *   element that got squeezed. It is derived from the viewport now, clamped
 *   between [ORB_MIN] and [ORB_MAX], and the Canvas simply matches its parent so
 *   the two can never disagree again.
 *
 *   MOTION. Both loops now ask [LocalReducedMotion] first. The connected halo is
 *   an INFINITE animation, so on a session that lasts three hours this Canvas was
 *   being redrawn at the refresh rate for three hours, on a phone whose owner may
 *   have explicitly asked the system to remove animations. Under reduced motion
 *   the loops do not run at all (a 0ms infinite repeat would be a spin at
 *   infinite speed) and every remaining tween collapses to the next frame. The
 *   orb keeps every bit of its meaning: the arc still fills, the colour still
 *   changes, the words still change — they just stop moving.
 *
 * PERFORMANCE: the rotation is an [Animatable] read INSIDE the draw lambda — a
 * draw-only state read, so a live orb costs redraws of one Canvas and never a
 * recomposition of the screen around it. While idle it is snapped to 0 with no
 * animation running.
 *
 * ACCESSIBILITY: the ring is a Canvas and the glyph is decorative, so
 * [stateLabel] is published as the node's state description and [actionLabel] as
 * its click label — in the same words the header chip and the dock button use.
 * TalkBack announces "Protected, button, double tap to disconnect".
 */
@Composable
fun ConnectButton(
    mode: ButtonMode,
    onClick: () -> Unit,
    stateLabel: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: Float = 0f,
) {
    val accents = LocalAetherAccents.current
    val accent = accentFor(mode)
    val reduced = LocalReducedMotion.current

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val diameter = remember(screenWidthDp) {
        (screenWidthDp.dp - ORB_GUTTER).coerceIn(ORB_MIN, ORB_MAX)
    }

    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "accent",
    )
    val core by animateFloatAsState(
        targetValue = if (mode == ButtonMode.CONNECTED) 1f else 0f,
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "core",
    )
    val sweep by animateFloatAsState(
        targetValue = when (mode) {
            ButtonMode.CONNECTED -> 1f
            // Never zero while working: a ring with no arc at all reads as
            // "nothing started".
            ButtonMode.BUSY -> progress.coerceIn(0.08f, 1f)
            ButtonMode.ERROR -> 1f
            ButtonMode.IDLE -> 0f
        },
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "sweep",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(aetherDuration(AetherDur.Snap), easing = AetherEaseOut),
        label = "press",
    )

    val spin = remember { Animatable(0f) }
    LaunchedEffect(mode, reduced) {
        // Reduced motion: the loops are not slowed down, they are not started.
        // An infinite repeat with a 0ms duration is not "no animation", it is an
        // invalidation every single frame forever.
        if (reduced) {
            spin.snapTo(0f)
            return@LaunchedEffect
        }
        when (mode) {
            ButtonMode.BUSY -> {
                spin.snapTo(0f)
                spin.animateTo(1f, infiniteRepeatable(tween(1600, easing = LinearEasing)))
            }
            ButtonMode.CONNECTED -> {
                spin.snapTo(0f)
                spin.animateTo(
                    1f,
                    infiniteRepeatable(tween(AetherDur.Halo, easing = LinearEasing)),
                )
            }
            else -> spin.snapTo(0f)
        }
    }

    val track = MaterialTheme.colorScheme.outlineVariant
    val cardTone = accents.card
    val showHighlight = !reduced &&
        (mode == ButtonMode.BUSY || mode == ButtonMode.CONNECTED)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
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
        // matchParentSize, not a second hardcoded diameter: the ring geometry is
        // all derived from the Canvas's own size, so the Canvas must be exactly
        // the tappable circle and nothing else.
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 8.dp.toPx()
            val ringR = size.minDimension / 2f - stroke / 2f - 2.dp.toPx()
            val topLeft = Offset(cx - ringR, cy - ringR)
            val ringSize = Size(ringR * 2f, ringR * 2f)
            val turn = spin.value

            // Outer bloom. Earned only by a verified tunnel.
            if (core > 0.01f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.20f * core),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                )
            }

            // The track: always the full circle, so the ring reads as a gauge
            // with a maximum rather than an arc floating in space.
            drawArc(
                color = track,
                startAngle = START_ANGLE,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (mode == ButtonMode.ERROR) {
                // Three broken arcs, frozen. A failure should look interrupted,
                // not merely coloured differently.
                repeat(3) { index ->
                    drawArc(
                        color = animatedAccent,
                        startAngle = START_ANGLE + index * 120f + 12f,
                        sweepAngle = 96f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            } else if (sweep > 0.001f) {
                drawArc(
                    color = animatedAccent,
                    startAngle = START_ANGLE,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // The travelling highlight: proof of life, never mistaken for
            // progress because it laps the whole ring regardless of the arc.
            // Skipped under reduced motion, where a parked highlight would just
            // be a bright notch of the same colour sitting on the arc, saying
            // nothing.
            if (showHighlight) {
                drawArc(
                    color = animatedAccent.copy(
                        alpha = if (mode == ButtonMode.BUSY) 0.95f else 0.55f,
                    ),
                    startAngle = START_ANGLE + turn * 360f,
                    sweepAngle = if (mode == ButtonMode.BUSY) 34f else 58f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // The core disc.
            val discR = ringR * 0.74f
            drawCircle(color = cardTone, radius = discR)
            if (core > 0.01f) {
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.26f * core),
                            animatedAccent.copy(alpha = 0.04f * core),
                        ),
                        startY = cy - discR,
                        endY = cy + discR,
                    ),
                    radius = discR,
                )
            }
            drawCircle(
                color = animatedAccent.copy(alpha = 0.16f + 0.20f * core),
                radius = discR,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        val icon = when (mode) {
            ButtonMode.CONNECTED -> Icons.Rounded.Bolt
            ButtonMode.ERROR -> Icons.Rounded.Warning
            else -> Icons.Rounded.PowerSettingsNew
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Proportional, not a fixed 30dp: the inset has to stay inside the
            // core disc, and the disc is now a function of the diameter.
            modifier = Modifier.padding(horizontal = diameter * 0.12f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedAccent,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = stateLabel,
                transitionSpec = {
                    fadeIn(tween(aetherDuration(AetherDur.Base))) togetherWith
                        fadeOut(tween(aetherDuration(AetherDur.Quick)))
                },
                label = "word",
            ) { word ->
                Text(
                    text = word,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    // Counters are instrument readouts: monospaced, Latin
                    // figures, pinned LTR. Prose keeps the locale's own digits.
                    text = detail,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = AetherMono,
                        textDirection = TextDirection.Ltr,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
