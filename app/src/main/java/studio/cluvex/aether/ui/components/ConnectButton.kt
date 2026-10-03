package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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

/** Twelve o'clock. Every arc and tick starts here. */
private const val START_ANGLE = -90f

/** The tick ladder around the core. 36 keeps every tick separable. */
private const val TICK_COUNT = 36

/**
 * Whether the ladder tick [fraction] of the way round is lit, for an arc of
 * [sweep]. An empty arc lights nothing, and a failure lights nothing either.
 */
internal fun tickLit(fraction: Float, sweep: Float, mode: ButtonMode): Boolean =
    mode != ButtonMode.ERROR && sweep > 0f && fraction <= sweep

/**
 * Whether a radial clock may run at all. The orb itself no longer breathes
 * (v5: it never changes size) and nothing turns inside it any more (v6), but
 * the rule stays the contract for anything that wants a radial clock: only
 * while reaching, enabled, and motion allowed.
 */
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

/** At or above this the orb gets its large glyph and word. */
private val ORB_LARGE = 220.dp

/** Below this the orb switches to its compact type. */
private val ORB_COMPACT = 168.dp

/** Below this the glyph shrinks again and the gaps close up. */
private val ORB_TINY = 120.dp

/**
 * THE HERO CONTROL, v6: "steady glass power core".
 *
 *   IDLE       a dark glass core, quiet track, power glyph. Nothing animates.
 *   BUSY       amber conic arc grows with REAL progress.
 *   CONNECTED  the core FILLS with the protected tone (glyph and words flip to
 *              the on-colour), a halo glows.
 *   ERROR      three broken rose arcs, frozen, ladder unlit.
 *
 * v5 RULE: THE ORB NEVER CHANGES SIZE. No press scale, no stroke squeeze, no
 * breathing rings, no glyph scale-in. Press feedback is light, not geometry:
 * the core's inner glow and rim brighten.
 *
 * v6 RULE: NOTHING SPINS INSIDE THE ORB. The comet that used to sweep the
 * ring is gone; proof of life is the orbit OUTSIDE the button (OrbitalHero in
 * ConnectionHome.kt), and progress is the arc, which only ever moves forward.
 *
 * PERFORMANCE: everything lives in one Canvas that only redraws when a state
 * transition changes its inputs.
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
    // 0 at rest, 1 fully pressed. Light only: it never touches size or stroke.
    val squeeze by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(aetherDuration(AetherDur.Snap), easing = AetherEaseOut),
        label = "press",
    )

    val track = MaterialTheme.colorScheme.outlineVariant
    val focusTone = MaterialTheme.colorScheme.onSurface
    val cardTone = accents.card
    val onCore = accents.onProtected
    val darkTheme = accents.dark

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
        val large = orb >= ORB_LARGE
        val glyphSize = when {
            tiny -> 24.dp
            compact -> 32.dp
            large -> 48.dp
            else -> 40.dp
        }
        val wordStyle = when {
            tiny -> MaterialTheme.typography.labelMedium
            compact -> MaterialTheme.typography.labelLarge
            large -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(orb)
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
            // STATIC LAYERS: redrawn only when a transition changes their inputs.
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val centre = Offset(cx, cy)
                val outerR = size.minDimension / 2f
                val stroke = (size.minDimension * 0.034f).coerceIn(4.dp.toPx(), 8.dp.toPx())
                val inset = (size.minDimension * 0.05f).coerceIn(5.dp.toPx(), 12.dp.toPx())
                val ringR = outerR - stroke / 2f - inset
                if (ringR <= 0f) return@Canvas
                val topLeft = Offset(cx - ringR, cy - ringR)
                val ringSize = Size(ringR * 2f, ringR * 2f)

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

                // 2. HALO. Earned by a verified tunnel, and by nothing else.
                if (core > 0.01f) {
                    drawCircle(
                        color = animatedAccent.copy(alpha = 0.16f * core),
                        radius = ringR,
                        center = centre,
                        style = Stroke(width = stroke * 3.2f),
                    )
                }

                // 3. THE TRACK: always the full circle.
                drawCircle(
                    color = track.copy(alpha = 0.75f),
                    radius = ringR,
                    center = centre,
                    style = Stroke(width = stroke),
                )

                // 4. TICK LADDER, inside the ring: progress as a countable quantity.
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
                    // 5. THE ARC: a wide soft pass for the glow, then a conic
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

                // 6. THE GLASS CORE.
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
                            animatedAccent.copy(alpha = 0.14f + 0.10f * squeeze),
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
                    color = animatedAccent.copy(alpha = 0.24f + 0.30f * core + 0.16f * squeeze),
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
                // A crossfade only: the glyph does not grow in or shrink out.
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = {
                        fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs))
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
