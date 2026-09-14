package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.ConnectionMeta
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionTitle
import studio.cluvex.aether.ui.components.StatusHint
import studio.cluvex.aether.ui.components.ValueRow
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.AetherNumeralLarge
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

/**
 * THE CONNECTION TAB.
 *
 * The information architecture stands, and it was the right one: ONE hero
 * control, ONE answer in words, progress as a track rather than a list,
 * evidence with a hierarchy, and nothing on screen the current state does not
 * need. What this pass changes is that the screen is now ONE SURFACE instead of
 * a column of unrelated rectangles - and that the two facts which explain a
 * wait (which scan is running, which protocol won) are no longer three taps
 * away in settings.
 *
 * WHAT WAS STILL WRONG
 *
 *   - THE SCREEN HAD NO ATMOSPHERE. State lived in exactly two places - the
 *     orb's ring and a small chip - on a flat background. Every VPN client worth
 *     copying tells you whether you are safe from across the room; this one made
 *     you read a label.
 *   - LATENCY WAS A NUMBER WITHOUT A SHAPE. "180 ms" means nothing to most
 *     people. It was coloured, which helped, and colour alone is never the whole
 *     answer.
 *   - THE WAIT WAS UNEXPLAINED. During a three-minute Ironclad scan the screen
 *     said "working" and counted seconds, while the two things the user actually
 *     wants - what is being tried, and what it settled on - were invisible.
 *   - FINISHED PHASES WERE FLAT PAINT. A solid capsule reads as a disabled
 *     control, not as a completed step.
 *
 * WHAT THIS IS NOW
 *
 *   1. AN AURORA WASH, painted behind the viewport in the live state tone and
 *      breathing slowly while work is in flight. It is drawn in [drawBehind] and
 *      driven by ONE [Animatable] read in the draw phase, so the loop costs
 *      repaints and never a recomposition - and under reduced motion it is a
 *      still gradient, not a disabled one.
 *   2. A STATUS RAIL. The badge keeps its polite live region and its four words;
 *      the latency chip gains SIGNAL BARS, so the reading survives both
 *      colour-blindness and not knowing what a millisecond is. Battery contract
 *      restated and intact: one probe when a session comes up, one per tap,
 *      never a loop.
 *   3. A STACK STRIP under the rail: the scan mode about to be used, and the
 *      protocol that actually won. Two chips, read-only, no navigation.
 *   4. THE HERO ORB. Unchanged contract. See [ConnectButton].
 *   5. A GRADIENT PHASE TRACK: finished segments lit with a gradient, the
 *      running one carrying a travelling band, plus an explicit "step N of 4"
 *      so the progress is also a sentence.
 *   6. A BENTO TELEMETRY GRID: exit IP as the wide hero tile over a tonal wash,
 *      then the winning protocol and the session clock. Every readout monospaced
 *      and pinned LTR, because they are instruments, not prose - load-bearing in
 *      Persian, where an IP address otherwise renders reordered.
 *
 * WHAT IS DELIBERATELY UNTOUCHED: [buttonMode], [connectionStep],
 * [phaseProgress] and [formatSessionUptime] keep their exact contracts, because
 * the presentation tests own them; the state machine, the semantics and the
 * reduced-motion rules are the same ones the rest of the app obeys.
 */

/** Engine, tunnel, verify, ready. */
private const val PHASE_COUNT = 4

/**
 * Controller state -> the orb's four visual modes.
 *
 * Disconnecting is BUSY on purpose: it is work in progress, and colouring it as
 * still-protected would invite a second tap on a tunnel that is already going
 * down.
 */
internal fun buttonMode(state: ConnectionState): ButtonMode = when {
    state.isConnected -> ButtonMode.CONNECTED
    state is ConnectionState.Error -> ButtonMode.ERROR
    state.isBusy -> ButtonMode.BUSY
    else -> ButtonMode.IDLE
}

/** Only the forward states have a phase; teardown and failure are not progress. */
internal fun connectionStep(state: ConnectionState): Int? = when (state) {
    is ConnectionState.Launching -> 0
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> 1
    is ConnectionState.Verifying -> 2
    else -> null
}

/**
 * The fraction of the orb's ring a phase has earned.
 *
 * A completed phase is a completed segment, so the ring and the track below it
 * can never disagree about how far along the attempt is.
 */
internal fun phaseProgress(step: Int?): Float =
    if (step == null) 0f else ((step + 1).toFloat() / PHASE_COUNT).coerceIn(0f, 1f)

@Composable
internal fun ConnectionHome(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    scrollState: ScrollState,
    onToggleConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val mode = buttonMode(state)
    val tone = accentFor(mode)
    val step = connectionStep(state)
    val working = step != null

    // Restarted whenever the attempt starts or ends, and saved, so a rotation
    // mid-connect does not reset the clock the user is timing the wait with.
    val startedAt = rememberSaveable(working) { SystemClock.elapsedRealtime() }
    val elapsed = tickingElapsed(startedAt, working)

    Column(
        Modifier
            .fillMaxSize()
            .auroraWash(tone, breathing = state.isBusy)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        StatusRail(state, tone, connectedSince)
        Spacer(Modifier.height(12.dp))
        StackStrip(profile, tone)

        // THE CONTROL. Not a status graphic with a button somewhere else.
        ConnectButton(
            mode = mode,
            onClick = onToggleConnection,
            stateLabel = stateWord(state),
            actionLabel = stringResource(connectionActionLabel(state)),
            modifier = Modifier.padding(vertical = 8.dp),
            detail = if (working) elapsed else null,
            progress = phaseProgress(step),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            connectionTitle(state),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        // StatusHint, not a bare Text: it cross-fades between states and already
        // collapses to an instant swap under reduced motion.
        StatusHint(connectionHint(state, profile))
        Spacer(Modifier.height(28.dp))

        if (step != null) {
            PhaseWave(step, tone, elapsed)
            // Only while an endpoint is still being found. Once verification is
            // running the scan is over and the note would be a lie.
            if (step <= 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.passage_scan_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))
        }

        if (state is ConnectionState.Error) {
            // The engine's own words, not a paraphrase, and one route to the log
            // line that produced them.
            NoticeBar(
                text = state.message,
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(12.dp))
            ActionPill(
                label = stringResource(R.string.passage_see_error),
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Terminal,
            )
            Spacer(Modifier.height(28.dp))
        }

        if (state is ConnectionState.Connected) {
            SectionTitle(stringResource(R.string.passage_session))
            SessionBento(
                connectedSince = connectedSince,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
                tone = tone,
            )
            Spacer(Modifier.height(28.dp))
        } else if (step == null) {
            // Disconnected or failed: the same question, answered the other way
            // round - where the internet currently thinks you are.
            ConnectionMeta(
                connected = false,
                connectedSince = null,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
            )
            Spacer(Modifier.height(28.dp))
        }

        if (state is ConnectionState.Idle) {
            // In a bar rather than loose at the bottom of the scroll: it is a
            // standing disclosure about who runs the exit network, and loose
            // grey body text is where standing disclosures go to be ignored.
            NoticeBar(
                text = stringResource(R.string.passage_privacy),
                tone = accents.neutral,
                icon = Icons.Rounded.Lock,
            )
        }
        Spacer(Modifier.height(36.dp))
    }
}

/**
 * THE ATMOSPHERE: one soft pool of the state colour behind the hero.
 *
 * Applied BEFORE [verticalScroll] on purpose, so it is painted in viewport
 * coordinates and stays put while the content moves over it - a light source in
 * the window, not a sticker glued to the top of a long page.
 *
 * Cost: one [Animatable] read inside [drawBehind], which invalidates the draw
 * phase and nothing else. No recomposition, no layout, no state hoisted into the
 * composition. Reduced motion pins it half-lit and steady rather than removing
 * it, because the colour itself is information.
 */
@Composable
private fun Modifier.auroraWash(tone: Color, breathing: Boolean): Modifier {
    val reduced = LocalReducedMotion.current
    val pulse = remember { Animatable(0.5f) }
    LaunchedEffect(breathing, reduced) {
        if (reduced || !breathing) {
            pulse.snapTo(0.5f)
            return@LaunchedEffect
        }
        pulse.snapTo(0f)
        pulse.animateTo(
            1f,
            infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        )
    }
    return this.drawBehind {
        val t = pulse.value
        val center = Offset(size.width / 2f, size.height * 0.20f)
        val radius = (size.width * (0.92f + 0.08f * t)).coerceAtLeast(1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tone.copy(alpha = 0.10f + 0.07f * t), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/**
 * The top rail: what the connection IS, and how good it is.
 *
 * The badge is still the first thing a screen reader reaches and still the only
 * element announced on every state change (polite live region, merged
 * descendants, so it is one announcement and not three). The latency chip is a
 * separate node on purpose - it changes on its own schedule, and it must not
 * make the state badge re-announce itself every time a probe lands.
 */
@Composable
private fun StatusRail(state: ConnectionState, tone: Color, connectedSince: Long?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateBadge(state, tone)
        Spacer(Modifier.weight(1f))
        if (state is ConnectionState.Connected) LatencyChip(connectedSince)
    }
}

/**
 * The state badge.
 *
 * The dot breathes while work is in flight and is steady otherwise, which means
 * "something is happening" survives being glanced at from outside the orb's
 * radius. It is drawn in [drawBehind] and driven by an [Animatable], so the loop
 * costs one repaint of a 9dp box and never a recomposition.
 */
@Composable
private fun StateBadge(state: ConnectionState, tone: Color) {
    val reduced = LocalReducedMotion.current
    val busy = state.isBusy
    val blink = remember { Animatable(1f) }
    LaunchedEffect(busy, reduced) {
        if (reduced || !busy) {
            blink.snapTo(1f)
            return@LaunchedEffect
        }
        blink.snapTo(0.30f)
        blink.animateTo(
            1f,
            infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        )
    }
    Surface(
        color = stateWash(state),
        contentColor = tone,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .drawBehind {
                        val alpha = blink.value
                        drawCircle(
                            color = tone.copy(alpha = alpha * 0.28f),
                            radius = size.minDimension / 2f,
                        )
                        drawCircle(
                            color = tone.copy(alpha = alpha),
                            radius = size.minDimension / 3.2f,
                        )
                    },
            )
            Spacer(Modifier.width(9.dp))
            Text(
                stringResource(connectionStatusLabel(state)),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Live latency, where the state is.
 *
 * BATTERY CONTRACT, RESTATED: there is still no polling loop anywhere near this.
 * A session gets ONE automatic measurement, a second and a bit after it comes up
 * (long enough that the tunnel has settled, short enough that the number is
 * there before the user starts looking for it), and one more per tap.
 *
 * The reading is now said THREE ways: a number for the expert, a colour for the
 * glance, and three bars for everyone who does not know what a millisecond is -
 * or cannot tell mint from amber. The bars are decorative to a screen reader,
 * which already hears the number.
 */
@Composable
private fun LatencyChip(connectedSince: Long?) {
    val accents = LocalAetherAccents.current
    val ping by PingMonitor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(connectedSince) {
        if (connectedSince == null) return@LaunchedEffect
        delay(1200L)
        PingMonitor.pingOnce(viaTunnel = true)
    }

    val band = when {
        ping.ms < 0L -> MaterialTheme.colorScheme.onSurfaceVariant
        ping.ms < 80L -> accents.protected
        ping.ms < 250L -> accents.working
        else -> accents.failed
    }
    val bars = when {
        ping.ms < 0L -> 0
        ping.ms < 80L -> 3
        ping.ms < 250L -> 2
        else -> 1
    }
    val label = when {
        ping.running -> "\u2026"
        ping.ms >= 0L -> String.format(Locale.US, "%d ms", ping.ms)
        ping.error -> stringResource(R.string.action_retry)
        else -> stringResource(R.string.meta_latency_test)
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = accents.card,
        contentColor = band,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier
                .clickable(
                    enabled = !ping.running,
                    onClickLabel = stringResource(R.string.meta_latency_test),
                    role = Role.Button,
                ) { scope.launch { PingMonitor.pingOnce(viaTunnel = true) } }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                Modifier
                    .size(width = 14.dp, height = 12.dp)
                    .clearAndSetSemantics { },
            ) {
                val slot = size.width / 5f
                repeat(3) { index ->
                    val barHeight = size.height * (0.42f + 0.29f * index)
                    drawRoundRect(
                        color = if (index < bars) band else track.copy(alpha = 0.45f),
                        topLeft = Offset(index * slot * 2f, size.height - barHeight),
                        size = Size(slot, barHeight),
                        cornerRadius = CornerRadius(slot / 2f),
                    )
                }
            }
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = AetherMono,
                    textDirection = TextDirection.Ltr,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * THE STACK STRIP: the two facts that explain the wait.
 *
 * A connection attempt that takes three minutes is not mysterious once you know
 * an Ironclad scan is running; a session that works is not anonymous once you
 * know it came up on MASQUE. Both were already in the app - one buried in engine
 * settings, the other at the bottom of the session card - and neither was
 * visible at the moment it explained something.
 *
 * Read-only by design. These are not shortcuts into settings: the engine page is
 * one tap away in the bottom navigation, and a chip that silently changes the
 * profile mid-attempt is a trap, not a control.
 */
@Composable
private fun StackStrip(profile: ConnectionProfile, tone: Color) {
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassChip(
            icon = Icons.Rounded.Search,
            label = stringResource(R.string.conn_stack_scan),
            value = scanLabel(profile.scanMode),
            tint = tone,
            modifier = Modifier.weight(1f),
        )
        GlassChip(
            icon = Icons.Rounded.Shield,
            label = stringResource(R.string.meta_protocol),
            value = meta.protocol?.takeIf { it.isNotBlank() } ?: "\u2014",
            tint = tone,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A label over a value, on the same bordered surface the bento tiles use. */
@Composable
private fun GlassChip(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(15.dp), tint)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The four phases as ONE animated object.
 *
 * Four capsules: the finished ones lit with a gradient rather than flat paint (a
 * solid capsule reads as a disabled control), the running one carrying a band of
 * light travelling through it, the future ones dim. Same integer as the old
 * four-row list, now also stated in words as "step N of 4", because a bar is a
 * feeling and a sentence is a fact.
 *
 * RTL: the segments and the wave are mirrored from the draw scope's own layout
 * direction, so in Persian progress runs right to left and the labels underneath
 * (a plain Row, which Compose already mirrors) stay attached to their segments.
 *
 * The clock is pinned LTR and monospaced: it is an instrument readout, and in
 * the Persian locale a bidi-reordered duration is worse than no duration.
 */
@Composable
private fun PhaseWave(active: Int, tone: Color, elapsed: String) {
    val reduced = LocalReducedMotion.current
    val labels = listOf(
        R.string.phase_engine,
        R.string.phase_tunnel,
        R.string.phase_verify,
        R.string.phase_ready,
    )
    val current = active.coerceIn(0, labels.lastIndex)
    val shimmer = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) {
            shimmer.snapTo(0f)
            return@LaunchedEffect
        }
        shimmer.snapTo(0f)
        shimmer.animateTo(1f, infiniteRepeatable(tween(1500, easing = LinearEasing)))
    }
    val track = MaterialTheme.colorScheme.outlineVariant

    AetherCard {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.passage_phase_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(
                        R.string.conn_phase_step,
                        current + 1,
                        PHASE_COUNT,
                        stringResource(labels[current]),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tone,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                elapsed,
                style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            val gap = 8.dp.toPx()
            val segmentWidth =
                ((size.width - gap * (PHASE_COUNT - 1)) / PHASE_COUNT).coerceAtLeast(1f)
            val barHeight = 10.dp.toPx()
            val top = (size.height - barHeight) / 2f
            val radius = CornerRadius(barHeight / 2f)
            val mirrored = layoutDirection == LayoutDirection.Rtl
            val phase = shimmer.value

            repeat(PHASE_COUNT) { index ->
                val slot = if (mirrored) PHASE_COUNT - 1 - index else index
                val left = slot * (segmentWidth + gap)
                val done = index < current
                val running = index == current

                // Glow first, so the capsule sits on top of its own halo.
                if (done || running) {
                    val spread = 3.dp.toPx()
                    drawRoundRect(
                        color = tone.copy(alpha = if (done) 0.22f else 0.12f),
                        topLeft = Offset(left - spread, top - spread),
                        size = Size(segmentWidth + spread * 2f, barHeight + spread * 2f),
                        cornerRadius = CornerRadius(barHeight / 2f + spread),
                    )
                }
                if (done) {
                    // Lit, not painted: a vertical gradient gives the capsule a
                    // light source, which is the difference between "finished"
                    // and "switched off".
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(tone, tone.copy(alpha = 0.68f)),
                            startY = top,
                            endY = top + barHeight,
                        ),
                        topLeft = Offset(left, top),
                        size = Size(segmentWidth, barHeight),
                        cornerRadius = radius,
                    )
                } else {
                    drawRoundRect(
                        color = if (running) track.copy(alpha = 0.55f) else track.copy(alpha = 0.32f),
                        topLeft = Offset(left, top),
                        size = Size(segmentWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
                if (running) {
                    if (reduced) {
                        // No travelling band with motion off: a half-filled
                        // capsule says "this one is running" just as clearly.
                        drawRoundRect(
                            color = tone.copy(alpha = 0.55f),
                            topLeft = Offset(left, top),
                            size = Size(segmentWidth, barHeight),
                            cornerRadius = radius,
                        )
                    } else {
                        val bandWidth = segmentWidth * 0.55f
                        val travel = -bandWidth + phase * (segmentWidth + bandWidth)
                        clipRect(
                            left = left,
                            top = top - 1f,
                            right = left + segmentWidth,
                            bottom = top + barHeight + 1f,
                        ) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        tone,
                                        Color.Transparent,
                                    ),
                                    startX = left + travel,
                                    endX = left + travel + bandWidth,
                                ),
                                topLeft = Offset(left, top),
                                size = Size(segmentWidth, barHeight),
                                cornerRadius = radius,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                Text(
                    stringResource(label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= current) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * THE BENTO GRID: everything factual about the live session, ranked.
 *
 *   row 1  the exit IP, wide, over a tonal wash, with its flag and country - the
 *          fact that proves traffic is leaving through the tunnel, and the first
 *          thing anyone checks
 *   row 2  the protocol that actually won, and how long this session has been up
 *
 * Every value is monospaced and pinned LTR. That BiDi fix is load-bearing in the
 * Persian locale, where `104.28.197.15` otherwise renders reordered.
 */
@Composable
private fun SessionBento(
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    tone: Color,
) {
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val uptime = tickingElapsed(connectedSince, connectedSince != null)

    Column(Modifier.fillMaxWidth()) {
        ExitTile(
            ipInfo = ipInfo,
            ipLoading = ipLoading,
            tone = tone,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            BentoTile(
                label = stringResource(R.string.meta_protocol),
                value = meta.protocol ?: "\u2014",
                icon = Icons.Rounded.Shield,
                tint = tone,
                mono = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            BentoTile(
                label = stringResource(R.string.connected_for),
                value = uptime,
                icon = Icons.Rounded.Schedule,
                tint = tone,
                modifier = Modifier.weight(1f),
            )
        }
        if (!meta.endpoint.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            // Copyable, because its only real use is being pasted into a bug
            // report.
            ValueRow(
                label = stringResource(R.string.meta_endpoint),
                value = meta.endpoint.orEmpty(),
            )
        }
    }
}

/**
 * The headline tile: whose network the internet sees you coming out of.
 *
 * The tonal wash behind it is the only gradient on a factual surface in the app,
 * and it is here because this tile is the ANSWER - it should not look like the
 * two tiles under it that merely describe how the answer was reached.
 */
@Composable
private fun ExitTile(
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val country = ipInfo?.countryCode?.uppercase(Locale.US)
    val value = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> ipInfo.ip
        else -> stringResource(R.string.ip_unavailable)
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Column(
            Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(tone.copy(alpha = 0.13f), Color.Transparent),
                    ),
                )
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tone)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ip_server_label),
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (!country.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = tone,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            country,
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag.isNotBlank()) {
                    Text(flag, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    value,
                    Modifier.weight(1f),
                    style = AetherNumeralLarge.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.meta_exit_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One cell of the grid.
 *
 * Its own bordered surface rather than a [StatTile] inside a card: the bento is
 * a grid of objects, and a tile that shares a background with its neighbours is
 * a table row wearing a grid's spacing. The icon sits in a tinted chip so the
 * tile has a focal point at a glance instead of two lines of equal-weight text.
 */
@Composable
private fun BentoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = true,
) {
    val accents = LocalAetherAccents.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(tint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, Modifier.size(14.dp), tint)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    label,
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                value,
                style = if (mono) {
                    AetherNumeral.copy(textDirection = TextDirection.Ltr)
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A monotonic clock, for an in-flight attempt or a live session.
 *
 * Sleeps to the next whole second OF THE PERIOD rather than a flat 1000ms, so
 * the error does not accumulate into a digit that shows the same second twice
 * and then skips one. Lifecycle-scoped, so a backgrounded app is not redrawing a
 * counter nobody can see - and stopped entirely when nothing is running, because
 * an idle screen has no business running a loop.
 */
@Composable
private fun tickingElapsed(since: Long?, running: Boolean): String {
    var now by remember(since) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(since, running, lifecycle) {
        if (!running || since == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val tick = SystemClock.elapsedRealtime()
                now = tick
                val intoSecond = (tick - since).coerceAtLeast(0L) % 1000L
                delay((1000L - intoSecond).coerceIn(1L, 1000L))
            }
        }
    }
    return formatSessionUptime(since, now)
}

/**
 * The word INSIDE the ring.
 *
 * Deliberately not [connectionStatusLabel] and not [connectionTitle]: the ring
 * has room for one or two words at headline size, and the full sentence lives
 * under the orb where it can wrap. Same four words the badge and the dock use,
 * so the three never disagree.
 */
@Composable
private fun stateWord(state: ConnectionState): String = stringResource(
    when {
        state.isConnected -> R.string.pill_secure
        state is ConnectionState.Error -> R.string.pill_failed
        state.isBusy -> R.string.pill_working
        else -> R.string.pill_off
    },
)

@Composable
private fun stateWash(state: ConnectionState): Color {
    val a = LocalAetherAccents.current
    return when {
        state.isConnected -> a.protectedWash
        state is ConnectionState.Error -> a.failedWash
        state.isBusy -> a.workingWash
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun connectionTitle(state: ConnectionState): String = stringResource(
    when (state) {
        is ConnectionState.Idle -> R.string.passage_ready
        is ConnectionState.Launching -> R.string.state_launching
        is ConnectionState.Connecting -> R.string.state_connecting
        is ConnectionState.Verifying -> R.string.state_verifying
        is ConnectionState.Connected -> R.string.passage_connected
        is ConnectionState.Reconnecting -> R.string.state_reconnecting
        is ConnectionState.Disconnecting -> R.string.state_disconnecting
        is ConnectionState.Error -> R.string.passage_failed
    },
)

@Composable
private fun connectionHint(state: ConnectionState, profile: ConnectionProfile): String =
    when (state) {
        is ConnectionState.Idle -> stringResource(R.string.passage_start_hint)
        is ConnectionState.Connected -> stringResource(R.string.passage_connected_hint)
        is ConnectionState.Launching, is ConnectionState.Connecting ->
            stringResource(R.string.busy_hint, scanLabel(profile.scanMode))
        is ConnectionState.Verifying -> stringResource(R.string.state_verify_hint)
        is ConnectionState.Reconnecting ->
            stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
        is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
        is ConnectionState.Error -> state.message
    }
