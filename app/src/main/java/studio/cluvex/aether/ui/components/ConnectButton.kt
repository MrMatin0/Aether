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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * The tick ladder inside the ring. 36 and not 72: at 72 the ticks visually
 * merge into a second, thinner ring; at 36 each tick is separable.
 */
private const val TICK_COUNT = 36

/**
 * Whether the ladder tick [fraction] of the way round is lit, for an arc of
 * [sweep]. An empty arc lights nothing, and a failure lights nothing either.
 */
internal fun tickLit(fraction: Float, sweep: Float, mode: ButtonMode): Boolean =
    mode != ButtonMode.ERROR && sweep > 0f && fraction <= sweep

/** The width-driven default's upper bound (callers that pass no diameter). */
private val ORB_MAX = 256.dp

/** The width-driven default's lower bound. */
private val ORB_MIN = 184.dp

/** Breathing room either side, for the width-driven default. */
private val ORB_GUTTER = 56.dp

/** Below this the orb switches to its compact type: smaller glyph, word and readout. */
private val ORB_COMPACT = 168.dp

/** Below this the glyph shrinks again and the gaps close up. */
private val ORB_TINY = 120.dp

/**
 * THE HERO CONTROL: a layered progress orb.
 *
 * Layers, each carrying meaning: ambient bloom (state, legible across the
 * room), pulsing rings (only while reaching an endpoint), neon halo (only once
 * verified), the gauge track with its tick ladder (progress as a quantity), and
 * the glass core the words sit on.
 *
 *   IDLE       bare track and ladder, nothing animating at all
 *   BUSY       amber arc grows with real progress, rings pulse, comet sweeps
 *   CONNECTED  full mint ring, glass core lit, neon halo breathing
 *   ERROR      three broken rose arcs, frozen, ladder unlit
 *
 * SIZE. [diameter], when given, is used as-is: the home tab measures the space
 * it has left and passes the result, so the orb never pushes anything off a
 * non-scrolling screen. Without it the orb falls back to the width-driven
 * default (parent width minus [ORB_GUTTER], clamped to [ORB_MIN]..[ORB_MAX]).
 * The ring stroke is a fraction of the diameter (4..9dp), and below
 * [ORB_COMPACT] / [ORB_TINY] the glyph, word and readout step down, so a small
 * orb is a smaller gauge rather than a big gauge with its words spilling out.
 * The Canvas matches the tappable circle exactly, so geometry and hit target
 * can never disagree; even the smallest orb stays far above the 48dp target.
 *
 * PRESS scales the orb, thickens the ring and lifts the core's tint. ENABLED =
 * false is for teardown: no tap, no squeeze, no outward rings, dimmed words.
 * FOCUS draws its own ring, because the ripple is switched off. MOTION asks
 * [LocalReducedMotion] first and never starts a loop under reduced motion.
 * PERFORMANCE: loop clocks are read inside the draw lambda only.
 *
 * ACCESSIBILITY: [stateLabel] is the node's state description and
 * [actionLabel] its click label. TalkBack: "Protected, button, double tap to
 * disconnect".
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
    enabled: Boolean = true,
    diameter: Dp? = null,
) {
    val accents = LocalAetherAccents.current
    val accent = accentFor(mode)
    val reduced = LocalReducedMotion.current

    // transitionSpec is a PLAIN lambda: resolve the durations in composition.
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
            // Never zero while working: a ring with no arc reads as "nothing started".
            ButtonMode.BUSY -> progress.coerceIn(0.08f, 1f)
            ButtonMode.ERROR -> 1f
            ButtonMode.IDLE -> 0f
        },
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "sweep",
    )
    val content by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.6f,
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "content",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
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
    val focusTone = MaterialTheme.colorScheme.onSurface
    val cardTone = accents.card
    val showComet = !reduced && (mode == ButtonMode.BUSY || mode == ButtonMode.CONNECTED)
    // Outward rings mean "reaching". A teardown is not reaching for anything.
    val showPulse = !reduced && enabled && mode == ButtonMode.BUSY

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val orb = diameter ?: (maxWidth - ORB_GUTTER).coerceIn(ORB_MIN, ORB_MAX)
        val compact = orb < ORB_COMPACT
        val tiny = orb < ORB_TINY
        val glyphSize = when {
            tiny -> 20.dp
            compact -> 26.dp
            else -> 30.dp
        }
        val wordStyle = when {
            tiny -> MaterialTheme.typography.titleSmall
            compact -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleLarge
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(orb)
                .scale(press)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = actionLabel,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { stateDescription = stateLabel },
        ) {
            // matchParentSize: every bit of ring geometry is derived from the
            // Canvas's own size, so it has to be exactly the tappable circle.
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val centre = Offset(cx, cy)
                val outerR = size.minDimension / 2f
                // A fraction of the diameter: 9dp on the old 256dp orb, never
                // thinner than 4dp, so a small orb is a smaller gauge.
                val baseStroke = (size.minDimension * 0.035f).coerceIn(4.dp.toPx(), 9.dp.toPx())
                val stroke = baseStroke * (1f + 0.10f * squeeze)
                val inset = (size.minDimension * 0.024f).coerceIn(3.dp.toPx(), 6.dp.toPx())
                val ringR = outerR - stroke / 2f - inset
                if (ringR <= 0f) return@Canvas
                val topLeft = Offset(cx - ringR, cy - ringR)
                val ringSize = Size(ringR * 2f, ringR * 2f)
                val turn = spin.value
                val phase = wave.value

                // 1. AMBIENT BLOOM.
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
                if (showPulse) {
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
                            radius = ringR + 2.dp.toPx() + inset * 1.2f * phase,
                            center = centre,
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }
                }

                // 4. THE GAUGE TRACK: always the full circle.
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
                val tickGap = (size.minDimension * 0.02f).coerceIn(3.dp.toPx(), 5.dp.toPx())
                val tickOuter = ringR - stroke / 2f - tickGap
                val tickInner = tickOuter - tickGap
                if (tickInner > 0f) {
                    repeat(TICK_COUNT) { index ->
                        val fraction = index.toFloat() / TICK_COUNT
                        val radians = ((START_ANGLE + fraction * 360f) * PI / 180f).toFloat()
                        val dx = cos(radians)
                        val dy = sin(radians)
                        val lit = tickLit(fraction, sweep, mode)
                        drawLine(
                            color = if (lit) {
                                animatedAccent.copy(alpha = 0.45f)
                            } else {
                                track.copy(alpha = 0.45f)
                            },
                            start = Offset(cx + dx * tickInner, cy + dy * tickInner),
                            end = Offset(cx + dx * tickOuter, cy + dy * tickOuter),
                            strokeWidth = 1.2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }

                if (mode == ButtonMode.ERROR) {
                    // Three broken arcs, frozen. A failure should look interrupted.
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
                    // The arc, twice: a wide soft pass for the glow, then the real one.
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

                // The travelling comet: proof of life, never mistaken for progress.
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

                // 6. THE GLASS CORE: a surface for the words.
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

                // 7. FOCUS RING, on the inner edge of the hit circle.
                if (focused && enabled) {
                    val focusStroke = 2.dp.toPx()
                    drawCircle(
                        color = focusTone,
                        radius = outerR - focusStroke,
                        center = centre,
                        style = Stroke(width = focusStroke),
                    )
                }
            }

            val icon: ImageVector = when (mode) {
                ButtonMode.CONNECTED -> Icons.Rounded.Bolt
                ButtonMode.ERROR -> Icons.Rounded.Warning
                else -> Icons.Rounded.PowerSettingsNew
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // Proportional: the inset has to stay inside the core disc.
                modifier = Modifier
                    .padding(horizontal = orb * 0.14f)
                    .alpha(content),
            ) {
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
                        modifier = Modifier.size(glyphSize),
                    )
                }
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                AnimatedContent(
                    targetState = stateLabel,
                    transitionSpec = {
                        fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs))
                    },
                    label = "word",
                ) { word ->
                    Text(
                        text = word,
                        style = wordStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                    // A capsule of the state colour: reads as a gauge, not a caption.
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(animatedAccent.copy(alpha = 0.12f))
                            .padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            // Counters are instrument readouts: monospaced, Latin
                            // figures, pinned LTR.
                            text = detail,
                            style = (
                                if (compact) {
                                    MaterialTheme.typography.labelSmall
                                } else {
                                    MaterialTheme.typography.labelMedium
                                }
                                ).copy(
                                fontFamily = AetherMono,
                                textDirection = TextDirection.Ltr,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
