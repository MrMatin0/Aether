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
import androidx.compose.ui.graphics.drawscope.rotate
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
 * Composable: the state hues differ between the light and dark palettes, so
 * this has to read the theme rather than return a compile-time constant.
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

/** The tick ladder around the core. 36 keeps every tick separable. */
private const val TICK_COUNT = 36

/**
 * Whether the ladder tick [fraction] of the way round is lit, for an arc of
 * [sweep]. An empty arc lights nothing, and a failure lights nothing either.
 */
internal fun tickLit(fraction: Float, sweep: Float, mode: ButtonMode): Boolean =
    mode != ButtonMode.ERROR && sweep > 0f && fraction <= sweep

/** A radial clock is useful only while its breathing ring is actually drawn. */
internal fun orbPulseEnabled(mode: ButtonMode, enabled: Boolean, reduced: Boolean): Boolean =
    mode == ButtonMode.BUSY && enabled && !reduced

/** Preserve onProtected contrast across the entire fill, not just its middle stop. */
internal fun connectedCoreColors(accent: Color, dark: Boolean): List<Color> = listOf(
    if (dark) lerp(accent, Color.White, 0.10f) else accent,
    accent,
    lerp(accent, Color.Black, 0.10f),
)

/** The width-driven default's upper bound (callers that pass no diameter). */
private val ORB_MAX = 256.dp

/** The width-driven default's lower bound. */
private val ORB_MIN = 184.dp

/** Breathing room either side, for the width-driven default. */
private val ORB_GUTTER = 56.dp

/** Below this the orb switches to its compact type. */
private val ORB_COMPACT = 168.dp

/** Below this the glyph shrinks again and the gaps close up. */
private val ORB_TINY = 120.dp

/**
 * THE HERO CONTROL, v4: "glass power core".
 *
 * The language of current consumer VPN clients (one big, unmistakable power
 * control) on top of the instrument the orb always was:
 *
 *   IDLE       a dark glass core, quiet track, power glyph. Nothing animates.
 *   BUSY       amber conic arc grows with REAL progress, a comet with a
 *              gradient tail sweeps the ring, the ring breathes.
 *   CONNECTED  the core FILLS with the protected tone (glyph and words flip to
 *              the on-colour), a slow sheen travels the ring, a halo glows.
 *   ERROR      three broken rose arcs, frozen, ladder unlit.
 *
 * Inside: the power glyph, the ACTION word ("Connect", "Disconnect",
 * "Cancel", "Retry") and, while an attempt or a session runs, its clock. The
 * state word moved out to the status capsule above the hero; it is still the
 * node's state description, so TalkBack reads "Protected, button, double tap
 * to disconnect".
 *
 * SIZE, PRESS, FOCUS, ENABLED, REDUCED MOTION and the draw-phase-only clocks
 * keep the exact contracts of v3.
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
    val bloom by animateFloatAsState(
        targetValue = when (mode) {
            ButtonMode.CONNECTED -> 1f
            ButtonMode.BUSY -> 0.62f
            ButtonMode.ERROR -> 0.46f
            ButtonMode.IDLE -> 0.18f
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
        targetValue = if (pressed && enabled) 0.955f else 1f,
        animationSpec = tween(aetherDuration(AetherDur.Snap), easing = AetherEaseOut),
        label = "press",
    )
    // 0 at rest, 1 fully pressed. Drives the tactile part that is not scale.
    val squeeze = ((1f - press) / 0.045f).coerceIn(0f, 1f)

    // Two independent clocks, read only in the draw lambda.
    val spin = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    val showPulse = orbPulseEnabled(mode, enabled, reduced)

    LaunchedEffect(mode, reduced) {
        if (reduced) {
            spin.snapTo(0f)
            return@LaunchedEffect
        }
        when (mode) {
            ButtonMode.BUSY -> {
                spin.snapTo(0f)
                spin.animateTo(1f, infiniteRepeatable(tween(1300, easing = LinearEasing)))
            }
            ButtonMode.CONNECTED -> {
                spin.snapTo(0f)
                spin.animateTo(1f, infiniteRepeatable(tween(AetherDur.Halo, easing = LinearEasing)))
            }
            else -> spin.snapTo(0f)
        }
    }
    LaunchedEffect(showPulse) {
        wave.snapTo(0f)
        if (showPulse) {
            wave.animateTo(1f, infiniteRepeatable(tween(2000, easing = LinearEasing)))
        }
    }

    val track = MaterialTheme.colorScheme.outlineVariant
    val focusTone = MaterialTheme.colorScheme.onSurface
    val cardTone = accents.card
    val onCore = accents.onProtected
    val darkTheme = accents.dark
    val showComet = !reduced && (mode == ButtonMode.BUSY || mode == ButtonMode.CONNECTED)

    // Words and glyph flip to the on-colour as the core fills.
    val contentTone = lerp(MaterialTheme.colorScheme.onSurface, onCore, core)
    val glyphTone = lerp(animatedAccent, onCore, core)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val orb = diameter ?: (maxWidth - ORB_GUTTER).coerceIn(ORB_MIN, ORB_MAX)
        val compact = orb < ORB_COMPACT
        val tiny = orb < ORB_TINY
        val glyphSize = when {
            tiny -> 24.dp
            compact -> 32.dp
            else -> 40.dp
        }
        val wordStyle = when {
            tiny -> MaterialTheme.typography.labelMedium
            compact -> MaterialTheme.typography.labelLarge
            else -> MaterialTheme.typography.titleMedium
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
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val centre = Offset(cx, cy)
                val outerR = size.minDimension / 2f
                val baseStroke = (size.minDimension * 0.034f).coerceIn(4.dp.toPx(), 8.dp.toPx())
                val stroke = baseStroke * (1f + 0.12f * squeeze)
                val inset = (size.minDimension * 0.05f).coerceIn(5.dp.toPx(), 12.dp.toPx())
                val ringR = outerR - stroke / 2f - inset
                if (ringR <= 0f) return@Canvas
                val topLeft = Offset(cx - ringR, cy - ringR)
                val ringSize = Size(ringR * 2f, ringR * 2f)
                val turn = spin.value

                // 1. AMBIENT BLOOM: legible across the room.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.30f * bloom),
                            animatedAccent.copy(alpha = 0.08f * bloom),
                            Color.Transparent,
                        ),
                        center = centre,
                        radius = outerR,
                    ),
                    radius = outerR,
                    center = centre,
                )

                // 2. BREATHING RING, only while an endpoint is being reached.
                if (showPulse) {
                    val phase = wave.value
                    repeat(2) { index ->
                        val p = (phase + index / 2f) % 1f
                        drawCircle(
                            color = animatedAccent.copy(alpha = (1f - p) * 0.34f),
                            radius = ringR + stroke / 2f + inset * p,
                            center = centre,
                            style = Stroke(width = 1.4.dp.toPx()),
                        )
                    }
                }

                // 3. HALO. Earned by a verified tunnel, and by nothing else.
                if (core > 0.01f) {
                    drawCircle(
                        color = animatedAccent.copy(alpha = 0.16f * core),
                        radius = ringR,
                        center = centre,
                        style = Stroke(width = stroke * 3.2f),
                    )
                }

                // 4. THE TRACK: always the full circle.
                drawCircle(
                    color = track.copy(alpha = 0.75f),
                    radius = ringR,
                    center = centre,
                    style = Stroke(width = stroke),
                )

                // 5. TICK LADDER, inside the ring: progress as a countable quantity.
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
                            color = if (lit) animatedAccent.copy(alpha = 0.55f) else track.copy(alpha = 0.40f),
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
                    // 6. THE ARC: a wide soft pass for the glow, then a conic
                    // gradient that brightens towards the leading edge.
                    drawArc(
                        color = animatedAccent.copy(alpha = 0.20f),
                        startAngle = START_ANGLE,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = stroke * 2.6f, cap = StrokeCap.Round),
                    )
                    val lead = sweep.coerceIn(0.02f, 0.999f)
                    rotate(START_ANGLE, centre) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f to animatedAccent.copy(alpha = 0.55f),
                                lead to animatedAccent,
                                1f to animatedAccent,
                                center = centre,
                            ),
                            startAngle = 0f,
                            sweepAngle = 360f * sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = ringSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }

                // 7. THE COMET: proof of life, never mistaken for progress. A
                // gradient tail behind a bright head.
                if (showComet) {
                    val busy = mode == ButtonMode.BUSY
                    val tail = if (busy) 0.20f else 0.32f
                    val headAlpha = if (busy) 1f else 0.65f
                    rotate(START_ANGLE + turn * 360f, centre) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f to Color.Transparent,
                                (1f - tail) to Color.Transparent,
                                1f to animatedAccent.copy(alpha = headAlpha),
                                center = centre,
                            ),
                            startAngle = -tail * 360f,
                            sweepAngle = tail * 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = ringSize,
                            style = Stroke(width = stroke * 1.15f, cap = StrokeCap.Butt),
                        )
                        val head = Offset(cx + ringR, cy)
                        drawCircle(animatedAccent.copy(alpha = 0.28f * headAlpha), radius = stroke * 1.9f, center = head)
                        drawCircle(animatedAccent.copy(alpha = headAlpha), radius = stroke * 0.72f, center = head)
                    }
                }

                // 8. THE GLASS CORE.
                val discR = ringR * 0.74f
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            lerp(cardTone, Color.White, if (darkTheme) 0.07f else 0f),
                            lerp(cardTone, Color.Black, if (darkTheme) 0.18f else 0.03f),
                        ),
                        startY = cy - discR,
                        endY = cy + discR,
                    ),
                    radius = discR,
                    center = centre,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.14f + 0.08f * squeeze),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy - discR * 0.35f),
                        radius = discR * 1.4f,
                    ),
                    radius = discR,
                    center = centre,
                )
                // ...which fills with the protected tone once verified.
                if (core > 0.01f) {
                    drawCircle(
                        brush = Brush.linearGradient(
                            colors = connectedCoreColors(animatedAccent, darkTheme),
                            start = Offset(cx - discR, cy - discR),
                            end = Offset(cx + discR, cy + discR),
                        ),
                        radius = discR,
                        center = centre,
                        alpha = core,
                    )
                }
                // A white highlight behind light-theme onProtected text would
                // undo the fill's contrast. Keep it only on the unfilled core.
                drawOval(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        startY = cy - discR * 0.94f,
                        endY = cy - discR * 0.05f,
                    ),
                    topLeft = Offset(cx - discR * 0.66f, cy - discR * 0.94f),
                    size = Size(discR * 1.32f, discR * 0.86f),
                    alpha = if (darkTheme) 1f else 1f - core,
                )
                drawCircle(
                    color = animatedAccent.copy(alpha = 0.24f + 0.30f * core + 0.12f * squeeze),
                    radius = discR,
                    center = centre,
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // 9. FOCUS RING, on the inner edge of the hit circle.
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
                ButtonMode.ERROR -> Icons.Rounded.Warning
                else -> Icons.Rounded.PowerSettingsNew
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // Proportional: the inset has to stay inside the core disc.
                modifier = Modifier
                    .padding(horizontal = orb * 0.17f)
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
                        tint = glyphTone,
                        modifier = Modifier.size(glyphSize),
                    )
                }
                Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
                AnimatedContent(
                    targetState = actionLabel,
                    transitionSpec = {
                        fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs))
                    },
                    label = "word",
                ) { word ->
                    Text(
                        text = word,
                        style = wordStyle,
                        color = contentTone,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(lerp(animatedAccent, onCore, core).copy(alpha = 0.16f))
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
                            color = contentTone,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
