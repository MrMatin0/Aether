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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
 * Composable (it was a plain function once): the state hues differ between the
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

/** Twelve o'clock. Every arc, tick and comet starts here. */
private const val START_ANGLE = -90f

/**
 * The tick ladder inside the ring.
 *
 * 36 and not 72: at 72 the ticks visually merge into a second, thinner ring on
 * a 184dp orb, which is a decoration; at 36 each tick is separable, so a
 * half-filled ladder can actually be counted.
 */
private const val TICK_COUNT = 36

/** The size the orb was designed at, and the size it uses whenever it fits. */
private val ORB_MAX = 256.dp

/**
 * The smallest the orb may get. Below this the state word inside it starts
 * wrapping to a third line and the ring stops reading as a gauge.
 */
private val ORB_MIN = 184.dp

/** Breathing room either side, so the orb never crowds the column it sits in. */
private val ORB_GUTTER = 56.dp

/**
 * THE HERO CONTROL: a layered progress orb.
 *
 * WHAT THIS PASS ADDS, AND WHY
 *
 * The previous orb was already honest - determinate arc, state colour, words at
 * the optical centre - and it was FLAT. One track, one arc, one travelling
 * highlight, one disc. On the screen whose entire job is to make a security
 * state feel unmistakable, the difference between "connecting" and "protected"
 * was a hue and an arc length, which is exactly the kind of difference people
 * miss at arm's length in daylight.
 *
 * So the orb is now built in DEPTH, and each layer carries meaning:
 *
 *   1. AMBIENT BLOOM. A radial gradient behind the ring, scaled by state:
 *      barely there when idle, warm while working, a full mint wash when
 *      verified. It is the layer you read from across the room, before any text
 *      resolves.
 *   2. PULSING RINGS. Three expanding rings, staggered a third of a cycle apart,
 *      only while the tunnel is coming up. Outward motion says "reaching", which
 *      is what the busy states actually are - and unlike a spinner it cannot be
 *      confused with progress, because the determinate arc is right next to it.
 *   3. NEON HALO. Once verified: a wide soft glow on the ring plus one slow
 *      breathing outline. This is the CYBER-MINT signature, and it is earned by
 *      a passed self-test - never shown for Verifying, never for Reconnecting.
 *   4. TICK LADDER. 36 ticks inside the track, lit up to the current progress.
 *      The arc says how far along; the ladder makes that readable as a quantity
 *      rather than a shape.
 *   5. GLASS CORE. A radial-gradient disc, tinted toward the state colour and
 *      brightened on press, with a hairline rim. It gives the words a surface to
 *      sit on instead of floating in the middle of a circle.
 *
 * Each state still has its own unmistakable behaviour:
 *
 *   IDLE       bare track and ladder, nothing animating at all
 *   BUSY       amber arc grows with real progress, rings pulse, comet sweeps
 *   CONNECTED  full mint ring, glass core lit, neon halo breathing
 *   ERROR      three broken rose arcs, frozen, ladder unlit
 *
 * PRESS. [press] scales the whole orb, and the same gesture thickens the ring
 * and lifts the core's tint, so the control deforms under the finger instead of
 * only shrinking. Haptics stay at the call site, where the click actually is.
 *
 * SIZE. Measured from the constraints the parent gives it and clamped between
 * [ORB_MIN] and [ORB_MAX]; the Canvas matches the tappable circle exactly, so
 * geometry and hit target can never disagree. An unbounded width degrades on
 * its own: Dp.Infinity clamps straight to [ORB_MAX].
 *
 * MOTION. Every loop asks [LocalReducedMotion] FIRST and is not started at all
 * when motion is off - a 0ms infinite repeat is an invalidation every frame,
 * forever, which is worse than the thing being removed. Under reduced motion
 * the orb keeps all of its meaning: the arc still fills, the ladder still
 * lights, the bloom still changes colour, the words still swap. Nothing moves.
 *
 * PERFORMANCE. The two loop clocks are [Animatable]s read INSIDE the draw
 * lambda, so a live orb costs redraws of one Canvas and never a recomposition
 * of the screen around it.
 *
 * ACCESSIBILITY (unchanged contract): the ring is a Canvas and the glyph is
 * decorative, so [stateLabel] is published as the node's state description and
 * [actionLabel] as its click label, in the same words the status badge and the
 * dock use. TalkBack announces "Protected, button, double tap to disconnect".
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

    // AnimatedContent's transitionSpec is a PLAIN lambda, not a @Composable one,
    // so aetherDuration (a @Composable read of LocalReducedMotion) cannot be
    // called inside it. Resolve both lengths here, in composition, and let the
    // specs capture the Ints. Still theme-aware, still collapses to 0 under
    // reduced motion.
    val fadeInMs = aetherDuration(AetherDur.Base)
    val fadeOutMs = aetherDuration(AetherDur.Quick)

    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "accent",
    )
    val core by animateFloatAsState(
        targetValue = if (mode == ButtonMode.CONNECTED) 1f else 0f,
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "core",
    )
    // How much ambient light the state has earned. Idle is not black: a control
    // that is completely inert reads as disabled.
    val bloom by animateFloatAsState(
        targetValue = when (mode) {
            ButtonMode.CONNECTED -> 1f
            ButtonMode.BUSY -> 0.58f
            ButtonMode.ERROR -> 0.44f
            ButtonMode.IDLE -> 0.16f
        },
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "bloom",
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
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(aetherDuration(AetherDur.Snap), easing = AetherEaseOut),
        label = "press",
    )
    // 0 at rest, 1 fully pressed. Drives the tactile part that is not scale.
    val squeeze = ((1f - press) / 0.035f).coerceIn(0f, 1f)

    // Two independent clocks: one rotation, one radial pulse. Read only in the
    // draw lambda.
    val spin = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }

    LaunchedEffect(mode, reduced) {
        if (reduced) {
            spin.snapTo(0f)
            return@LaunchedEffect
        }
        when (mode) {
            ButtonMode.BUSY -> {
                spin.snapTo(0f)
                spin.animateTo(1f, infiniteRepeatable(tween(1500, easing = LinearEasing)))
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
    LaunchedEffect(mode, reduced) {
        if (reduced) {
            wave.snapTo(0f)
            return@LaunchedEffect
        }
        when (mode) {
            // Faster while reaching for an endpoint, calm once the tunnel is up.
            ButtonMode.BUSY -> {
                wave.snapTo(0f)
                wave.animateTo(1f, infiniteRepeatable(tween(2100, easing = LinearEasing)))
            }
            ButtonMode.CONNECTED -> {
                wave.snapTo(0f)
                wave.animateTo(1f, infiniteRepeatable(tween(3800, easing = LinearEasing)))
            }
            else -> wave.snapTo(0f)
        }
    }

    val track = MaterialTheme.colorScheme.outlineVariant
    val cardTone = accents.card
    val showComet = !reduced && (mode == ButtonMode.BUSY || mode == ButtonMode.CONNECTED)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val diameter = (maxWidth - ORB_GUTTER).coerceIn(ORB_MIN, ORB_MAX)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
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
            // matchParentSize, not a second hardcoded diameter: every bit of the
            // ring geometry is derived from the Canvas's own size, so the Canvas
            // has to be exactly the tappable circle and nothing else.
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val centre = Offset(cx, cy)
                val outerR = size.minDimension / 2f
                val stroke = 9.dp.toPx() * (1f + 0.10f * squeeze)
                val ringR = outerR - stroke / 2f - 6.dp.toPx()
                if (ringR <= 0f) return@Canvas
                val topLeft = Offset(cx - ringR, cy - ringR)
                val ringSize = Size(ringR * 2f, ringR * 2f)
                val turn = spin.value
                val phase = wave.value

                // 1. AMBIENT BLOOM. The layer that is legible before any text is.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.26f * bloom),
                            animatedAccent.copy(alpha = 0.07f * bloom),
                            Color.Transparent,
                        ),
                        center = centre,
                        radius = outerR,
                    ),
                    radius = outerR,
                    center = centre,
                )

                // 2. PULSING RINGS, only while an endpoint is being reached.
                if (!reduced && mode == ButtonMode.BUSY) {
                    repeat(3) { index ->
                        val p = (phase + index / 3f) % 1f
                        drawCircle(
                            color = animatedAccent.copy(alpha = (1f - p) * 0.30f),
                            radius = ringR * (0.80f + 0.30f * p),
                            center = centre,
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }
                }

                // 3. NEON HALO. Earned by a verified tunnel, and by nothing else.
                if (core > 0.01f) {
                    drawCircle(
                        color = animatedAccent.copy(alpha = 0.13f * core),
                        radius = ringR,
                        center = centre,
                        style = Stroke(width = stroke * 2.6f),
                    )
                    if (!reduced) {
                        drawCircle(
                            color = animatedAccent.copy(alpha = (1f - phase) * 0.26f * core),
                            radius = ringR + 3.dp.toPx() + 9.dp.toPx() * phase,
                            center = centre,
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }
                }

                // 4. THE GAUGE TRACK: always the full circle, so the ring reads as
                // a gauge with a maximum rather than an arc floating in space.
                drawArc(
                    color = track,
                    startAngle = START_ANGLE,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )

                // 5. TICK LADDER: the arc's length, as a countable quantity.
                val tickOuter = ringR - stroke / 2f - 5.dp.toPx()
                val tickInner = tickOuter - 5.dp.toPx()
                if (tickInner > 0f) {
                    repeat(TICK_COUNT) { index ->
                        val fraction = index.toFloat() / TICK_COUNT
                        val radians = ((START_ANGLE + fraction * 360f) * PI / 180f).toFloat()
                        val dx = cos(radians)
                        val dy = sin(radians)
                        val lit = mode != ButtonMode.ERROR && fraction <= sweep
                        drawLine(
                            color = if (lit) {
                                animatedAccent.copy(alpha = 0.45f)
                            } else {
                                track.copy(alpha = 0.45f)
                            },
                            start = Offset(cx + dx * tickInner, cy + dy * tickInner),
                            end = Offset(cx + dx * tickOuter, cy + dy * tickOuter),
                            strokeWidth = 1.4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }

                if (mode == ButtonMode.ERROR) {
                    // Three broken arcs, frozen. A failure should look
                    // interrupted, not merely coloured differently.
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
                    // The arc, twice: a wide soft pass for the glow, then the
                    // real one on top. Cheaper and steadier than a blur.
                    drawArc(
                        color = animatedAccent.copy(alpha = 0.18f),
                        startAngle = START_ANGLE,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = stroke * 2.4f, cap = StrokeCap.Round),
                    )
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

                // The travelling comet: proof of life, never mistaken for
                // progress because it laps the whole ring regardless of the arc.
                // Skipped under reduced motion, where a parked highlight is just
                // a bright notch of the same colour sitting on the arc, saying
                // nothing.
                if (showComet) {
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

                // 6. THE GLASS CORE: a surface for the words, lit by state and by
                // the finger.
                val discR = ringR * 0.74f
                drawCircle(color = cardTone, radius = discR, center = centre)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            lerp(
                                cardTone,
                                animatedAccent,
                                0.08f + 0.16f * core + 0.06f * squeeze,
                            ),
                            cardTone,
                        ),
                        center = Offset(cx, cy - discR * 0.35f),
                        radius = discR * 1.35f,
                    ),
                    radius = discR,
                    center = centre,
                )
                drawCircle(
                    color = animatedAccent.copy(
                        alpha = 0.18f + 0.22f * core + 0.10f * squeeze,
                    ),
                    radius = discR,
                    center = centre,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }

            val icon: ImageVector = when (mode) {
                ButtonMode.CONNECTED -> Icons.Rounded.Bolt
                ButtonMode.ERROR -> Icons.Rounded.Warning
                else -> Icons.Rounded.PowerSettingsNew
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // Proportional, not a fixed 30dp: the inset has to stay inside
                // the core disc, and the disc is a function of the diameter.
                modifier = Modifier.padding(horizontal = diameter * 0.12f),
            ) {
                // The glyph swaps with a scale, not a hard cut: power to bolt is
                // the moment the tunnel became real, and it should feel like one
                // object becoming another.
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = {
                        (
                            fadeIn(tween(fadeInMs)) +
                                scaleIn(tween(fadeInMs), initialScale = 0.72f)
                            ) togetherWith (
                            fadeOut(tween(fadeOutMs)) +
                                scaleOut(tween(fadeOutMs), targetScale = 0.72f)
                            )
                    },
                    label = "glyph",
                ) { glyph ->
                    Icon(
                        imageVector = glyph,
                        contentDescription = null,
                        tint = animatedAccent,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    targetState = stateLabel,
                    transitionSpec = {
                        fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs))
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
                        // figures, pinned LTR. Prose keeps the locale's digits.
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
}
