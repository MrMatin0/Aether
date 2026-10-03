package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * THE CONNECTION TAB, v7: "Aurora, in reach", with the words cut back.
 *
 *   status capsule     the state in one word, with a live dot that FADES
 *   hero               the glass power core inside its orbit; satellites (one
 *                      per hop) ride the orbit with a tail
 *   quick controls     route, protocol, scan: the current value of each, one
 *                      tap from the sheet that changes it (QuickControls.kt)
 *   error strip        only on failure
 *   session stats      only while verified
 *   location card      exit, route path (roomy windows), IP footer
 *
 * v7 CHANGES
 *
 *   FEWER WORDS The title and hint sentence under the orb ("Ready to
 *               connect." / "Connect through Cloudflare WARP in one tap.")
 *               and the WARP privacy line are gone. The capsule and the orb's
 *               own words already said the same thing; the capsule now carries
 *               the live region the title used to.
 *   BIGGER ORB  The height that copy used goes to the button: [heroReserve]
 *               lost the headline's share, [HERO_ORBIT_MAX] and
 *               [ORB_HOME_MAX] grew, and the orb takes a larger share of its
 *               orbit. It still keeps one size through every state.
 *
 * v6 CHANGES (still true)
 *
 *   IN REACH    The three settings that decide whether and how fast you get
 *               online sit directly under the button. The quick-controls
 *               sheet covers route, protocol and scan in one place and links
 *               to the Settings page that owns the rest.
 *   ONE FACT,   The location card does not repeat chain, scan mode and
 *   ONCE        protocol, and is not a click target: the deck above it owns
 *               those facts and that action.
 *
 * WHAT IS DELIBERATELY UNTOUCHED: [buttonMode], [connectionStep],
 * [phaseProgress], [litRouteNodes], [rateLevel], [shimmerBandStart], [orbDetail],
 * [homeDensity], [useTwoPane] and [formatSessionUptime] keep their exact
 * contracts; [heroGeometry] and [heroSlotHeight] keep theirs with new bounds.
 */

/** Engine, tunnel, verify, ready. */
private const val PHASE_COUNT = 4

/** Twelve o'clock, like the orb's own arc. */
private const val ORBIT_START = -90f

/** The orbit (with its satellites) never grows past this, however tall the slot. */
internal val HERO_ORBIT_MAX = 340.dp

/**
 * The orb on the home tab. Up from 208dp, which read as too small once the
 * copy under it was gone; still short of a dinner plate on a tablet.
 */
internal val ORB_HOME_MAX = 264.dp

/** Below this the word inside the orb stops fitting on one line. */
internal val ORB_HOME_MIN = 96.dp

/** How much of the orbit the orb fills. The satellites must still ride OUTSIDE it. */
private const val ORB_SHARE = 0.80f

private val NODE_SIZE = 32.dp
private val NODE_SIZE_DENSE = 26.dp
private val FLAG_AVATAR = 46.dp
private val FLAG_AVATAR_DENSE = 38.dp

/** Horizontal padding of the one-pane column, both sides. */
private val ONE_PANE_GUTTER = 40.dp

/** Two-pane padding (24 + 24) plus the gap between the panes (24). */
private val TWO_PANE_GUTTER = 72.dp

/**
 * How much the tab can show at once, from the height it is given.
 *
 *   ROOMY    everything, at full padding, route path in the location card
 *   COMPACT  everything but the route path
 *   TIGHT    quick controls go to one line each, session stats lose their
 *            meters
 *   MINIMAL  as TIGHT, and the session stats go (the orb still says verified)
 */
internal enum class HomeDensity { ROOMY, COMPACT, TIGHT, MINIMAL }

/**
 * Larger text needs more height for the same content, so the budget shrinks
 * with the font scale rather than letting a 1.3x user lose the bottom card.
 */
internal fun homeDensity(height: Dp, fontScale: Float = 1f): HomeDensity {
    val budget = height / fontScale.coerceAtLeast(1f)
    return when {
        budget >= 600.dp -> HomeDensity.ROOMY
        budget >= 480.dp -> HomeDensity.COMPACT
        budget >= 380.dp -> HomeDensity.TIGHT
        else -> HomeDensity.MINIMAL
    }
}

/** Landscape phones and wide tablet windows: hero on one side, details on the other. */
internal fun useTwoPane(width: Dp, height: Dp): Boolean = width >= 480.dp && width > height

/**
 * The orbit's box and the orb's diameter for a hero slot of [width] x [height].
 *
 * The orb is [ORB_SHARE] of the orbit so the satellites always ride OUTSIDE
 * it, and both are clamped so a tablet does not get a dinner plate and a
 * split-screen window still gets a tappable control.
 */
internal fun heroGeometry(width: Dp, height: Dp): Pair<Dp, Dp> {
    val side = minOf(width, height).coerceAtLeast(0.dp)
    val orbit = side.coerceAtMost(HERO_ORBIT_MAX)
    val orb = (orbit * ORB_SHARE).coerceIn(ORB_HOME_MIN, ORB_HOME_MAX)
    return maxOf(orbit, orb) to orb
}

/**
 * The height everything that is NOT the hero can need at [density], in the
 * WORST state for that density (verified: quick controls, stats card and the
 * location card). Scaled with the font, like [homeDensity].
 *
 * v7 dropped the headline (title, plus the hint where it showed), so each
 * budget lost that share: about 52dp roomy, 50dp compact, 28dp tight and
 * minimal, and the headline-and-hint block in two-pane.
 */
internal fun heroReserve(density: HomeDensity, twoPane: Boolean, fontScale: Float = 1f): Dp {
    val base = when {
        twoPane -> 64.dp
        density == HomeDensity.ROOMY -> 436.dp
        density == HomeDensity.COMPACT -> 308.dp
        density == HomeDensity.TIGHT -> 274.dp
        else -> 220.dp
    }
    return base * fontScale.coerceAtLeast(1f)
}

/**
 * THE HERO'S FIXED SIDE. A function of the window alone, so the orb keeps one
 * size through idle, connecting, verified and failed: the cards below it may
 * come and go, the button does not move or resize.
 */
internal fun heroSlotHeight(
    width: Dp,
    height: Dp,
    density: HomeDensity,
    twoPane: Boolean,
    fontScale: Float = 1f,
): Dp {
    val across = if (twoPane) (width - TWO_PANE_GUTTER) / 2 else width - ONE_PANE_GUTTER
    val down = height - heroReserve(density, twoPane, fontScale)
    return minOf(across, down).coerceIn(ORB_HOME_MIN, HERO_ORBIT_MAX)
}

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

/**
 * Whether the orb takes a tap at all.
 *
 * Everything but teardown: busy stages must stay cancellable, and Error is a
 * retry.
 */
internal fun connectControlEnabled(state: ConnectionState): Boolean =
    state !is ConnectionState.Disconnecting

/** Only the forward states have a phase; teardown and failure are not progress. */
internal fun connectionStep(state: ConnectionState): Int? = when (state) {
    is ConnectionState.Launching -> 0
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> 1
    is ConnectionState.Verifying -> 2
    else -> null
}

/**
 * The fraction of the orb's ring a phase has earned. A completed phase is a
 * completed segment, so the ring and the route can never disagree.
 */
internal fun phaseProgress(step: Int?): Float =
    if (step == null) 0f else ((step + 1).toFloat() / PHASE_COUNT).coerceIn(0f, 1f)

/**
 * How many nodes of the route (You, every hop, Internet) are lit. Derived from
 * [phaseProgress]. "You" is always lit.
 */
internal fun litRouteNodes(state: ConnectionState, step: Int?, count: Int): Int {
    if (count <= 0) return 0
    return when {
        state.isConnected -> count
        step != null -> ceil(phaseProgress(step) * count).toInt().coerceIn(1, count)
        else -> 1
    }
}

/** A byte rate as a 0..1 level on a log scale from 1 KB/s to 50 MB/s. */
internal fun rateLevel(bytesPerSecond: Long): Float {
    if (bytesPerSecond <= 1024L) return 0f
    val level = log10(bytesPerSecond.toDouble() / 1024.0) / log10(51_200.0)
    return level.toFloat().coerceIn(0f, 1f)
}

/**
 * Where a travelling band of light starts, in a track that runs from [left] to
 * [left] + [width], [phase] (0..1) of the way through one sweep of a [band]-wide
 * light. Travels the way PROGRESS travels: rightwards in English, leftwards in
 * Persian.
 */
internal fun shimmerBandStart(
    left: Float,
    width: Float,
    band: Float,
    phase: Float,
    rtl: Boolean,
): Float {
    val travel = -band + phase.coerceIn(0f, 1f) * (width + band)
    return if (rtl) left + width - travel - band else left + travel
}

/**
 * The readout under the word inside the orb: the attempt clock while an attempt
 * is in flight, the session clock once the tunnel is verified, nothing at rest.
 * The ONLY clock on the tab.
 */
internal fun orbDetail(
    state: ConnectionState,
    connectedSince: Long?,
    attemptClock: String,
    sessionClock: String,
): String? = when {
    connectionStep(state) != null -> attemptClock
    state is ConnectionState.Connected && connectedSince != null -> sessionClock
    else -> null
}

@Composable
internal fun ConnectionHome(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: (SettingsPage) -> Unit = {},
) {
    val accents = LocalAetherAccents.current
    val mode = buttonMode(state)
    val tone = accentFor(mode)
    val step = connectionStep(state)
    val working = step != null
    val fontScale = LocalDensity.current.fontScale

    // Restarted whenever the attempt starts or ends, and saved, so a rotation
    // mid-connect does not reset the clock the user is timing the wait with.
    // Only the STAMP lives here; the ticking happens inside the hero.
    val startedAt = rememberSaveable(working) { SystemClock.elapsedRealtime() }

    // Which quick-controls section is open, or none. Saved, so a rotation
    // keeps the sheet on the section the user was looking at.
    var controls by rememberSaveable { mutableStateOf<QuickControl?>(null) }
    // The session started (or a reconnect kicked in) while the sheet was open:
    // the choice is no longer the user's to make, so the sheet goes away.
    LaunchedEffect(editable) { if (!editable) controls = null }

    // Manual-only latency: a stale reading must not survive the session.
    val verified = state is ConnectionState.Connected
    LaunchedEffect(verified) { if (!verified) PingMonitor.reset() }

    val hero: @Composable (Modifier, Dp) -> Unit = { slot, side ->
        HomeHero(
            state = state,
            mode = mode,
            chain = profile.chain,
            tone = tone,
            connectedSince = connectedSince,
            startedAt = startedAt,
            working = working,
            step = step,
            side = side,
            onToggleConnection = onToggleConnection,
            modifier = slot,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = homeDensity(maxHeight, fontScale)
        val twoPane = useTwoPane(maxWidth, maxHeight)
        val details: @Composable (Modifier) -> Unit = { slot ->
            HomeDetails(
                state = state,
                profile = profile,
                step = step,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
                connectedSince = connectedSince,
                tone = tone,
                editable = editable,
                density = density,
                onOpenControl = { controls = it },
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = slot,
            )
        }

        if (twoPane) {
            val heroSide = heroSlotHeight(maxWidth, maxHeight, density, twoPane = true, fontScale = fontScale)
            Row(
                Modifier
                    .fillMaxSize()
                    .auroraWash(tone, accents.brand, focusX = 0.27f, focusY = 0.50f)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(4.dp))
                    StatusCapsule(state, mode, tone)
                    hero(Modifier.fillMaxWidth().height(heroSide), heroSide)
                    // Slack goes BELOW the hero, so nothing above it ever moves.
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    details(
                        Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        } else {
            val reserve = heroReserve(density, twoPane = false, fontScale = fontScale)
            val heroSide = heroSlotHeight(maxWidth, maxHeight, density, twoPane = false, fontScale = fontScale)
            // Centre the block for its TALLEST state, once, from the window:
            // a fixed lead, so nothing above the cards ever moves.
            val lead = ((maxHeight - heroSide - reserve) / 2).coerceIn(0.dp, 56.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .auroraWash(tone, accents.brand, focusX = 0.5f, focusY = 0.30f)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(lead + (if (density == HomeDensity.ROOMY) 6.dp else 2.dp)))
                StatusCapsule(state, mode, tone)
                hero(Modifier.fillMaxWidth().height(heroSide), heroSide)
                Spacer(Modifier.height(if (density == HomeDensity.ROOMY) 18.dp else 10.dp))
                // The cards grow downwards into the remaining space. The scroll
                // is only a safety net for a window smaller than any budget;
                // on a phone the content fits and it never engages.
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Capped so a portrait tablet gets cards, not banners.
                    details(
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(if (density == HomeDensity.ROOMY) 12.dp else 8.dp))
            }
        }
    }

    controls?.let { section ->
        ConnectionControlsSheet(
            initial = section,
            profile = profile,
            editable = editable,
            onProfileChange = onProfileChange,
            onOpenSettings = onOpenSettings,
            onDismiss = { controls = null },
        )
    }
}

// ------------------------------------------------------------ status capsule --

private fun capsuleLabel(state: ConnectionState): Int = when {
    state.isConnected -> R.string.aurora_status_protected
    state is ConnectionState.Error -> R.string.aurora_status_failed
    state is ConnectionState.Disconnecting -> R.string.aurora_status_stopping
    state.isBusy -> R.string.aurora_status_securing
    else -> R.string.aurora_status_unprotected
}

/**
 * The state in one word, on a pill of its own colour, with a live dot whose
 * halo fades in and out (fast while reaching, slow once protected). The dot
 * never changes size. Static at rest, on failure and under reduced motion.
 *
 * Since v7 the word is the tab's live region: with the title gone it is the
 * one piece of copy that names the state.
 */
@Composable
private fun StatusCapsule(state: ConnectionState, mode: ButtonMode, tone: Color) {
    val reduced = LocalReducedMotion.current
    val shown by animateColorAsState(
        targetValue = tone,
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "capsule",
    )
    val live = mode == ButtonMode.BUSY || mode == ButtonMode.CONNECTED
    val beat = remember { Animatable(0f) }
    LaunchedEffect(mode, reduced) {
        beat.snapTo(0f)
        if (reduced || !live) return@LaunchedEffect
        beat.animateTo(
            1f,
            infiniteRepeatable(tween(if (mode == ButtonMode.BUSY) 1100 else 2200, easing = LinearEasing)),
        )
    }
    Surface(
        shape = CircleShape,
        color = shown.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, shown.copy(alpha = 0.32f)),
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .padding(start = 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                Modifier
                    .size(14.dp)
                    .clearAndSetSemantics { },
            ) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f
                if (live && !reduced) {
                    // 0 -> 1 -> 0 over one beat: a fade, not a growth.
                    val glow = 1f - abs(2f * beat.value - 1f)
                    drawCircle(shown.copy(alpha = 0.45f * glow), radius = r * 0.8f, center = c)
                }
                drawCircle(shown, radius = r * 0.4f, center = c)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(capsuleLabel(state)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = shown,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------- hero --

/** The orb in its orbit, at the fixed [side] the tab worked out from the window. */
@Composable
private fun HomeHero(
    state: ConnectionState,
    mode: ButtonMode,
    chain: ChainMode,
    tone: Color,
    connectedSince: Long?,
    startedAt: Long,
    working: Boolean,
    step: Int?,
    side: Dp,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (orbit, orb) = heroGeometry(side, side)
    Box(modifier, contentAlignment = Alignment.Center) {
        OrbitalHero(
            mode = mode,
            chain = chain,
            tone = tone,
            orb = orb,
            modifier = Modifier.size(orbit),
        ) {
            // Both clocks are read HERE, in the hero's own scope: they tick
            // every second, and the tick should recompose the orb - not the
            // whole tab around it.
            val attemptClock = tickingElapsed(startedAt, working)
            val sessionClock = tickingElapsed(connectedSince, state is ConnectionState.Connected)
            ConnectButton(
                mode = mode,
                onClick = onToggleConnection,
                stateLabel = stateWord(state),
                actionLabel = stringResource(connectionActionLabel(state)),
                detail = orbDetail(state, connectedSince, attemptClock, sessionClock),
                progress = phaseProgress(step),
                enabled = connectControlEnabled(state),
                diameter = orb,
            )
        }
    }
}

/**
 * Everything below the hero, in one column, each fact once. The quick
 * controls lead: they are the next thing a user reaches for after the button
 * (and, after a failure, before trying it again).
 */
@Composable
private fun HomeDetails(
    state: ConnectionState,
    profile: ConnectionProfile,
    step: Int?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    connectedSince: Long?,
    tone: Color,
    editable: Boolean,
    density: HomeDensity,
    onOpenControl: (QuickControl) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val gap = if (density == HomeDensity.ROOMY) 12.dp else 8.dp
    val connected = state is ConnectionState.Connected
    // Disconnected or failed: where the internet currently thinks you are.
    // Verified: the exit. Not mid-attempt or while tearing down: the IP probe
    // is parked in that window and the row would flash "unavailable".
    val showIp = connected || (step == null && state !is ConnectionState.Disconnecting)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        QuickControls(
            profile = profile,
            state = state,
            editable = editable,
            compact = density >= HomeDensity.TIGHT,
            onOpen = onOpenControl,
        )

        if (state is ConnectionState.Error) {
            // The engine's own words, and one route to the log line behind them.
            ErrorStrip(state.message, accents.failed, onOpenDiagnostics)
        }

        if (connected && density != HomeDensity.MINIMAL) {
            SessionStats(connectedSince, compact = density == HomeDensity.TIGHT)
        }

        LocationCard(
            profile = profile,
            state = state,
            step = step,
            ipInfo = ipInfo,
            ipLoading = ipLoading,
            connectedSince = connectedSince,
            tone = tone,
            editable = editable,
            showIp = showIp,
            expanded = density == HomeDensity.ROOMY,
            dense = density != HomeDensity.ROOMY,
        )
    }
}

/** A failure in one row: the engine's message, and the way to its log line. */
@Composable
private fun ErrorStrip(message: String, tone: Color, onOpenDiagnostics: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Card),
        color = tone.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(Icons.Rounded.Warning, tone)
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = onOpenDiagnostics,
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(Icons.Rounded.Terminal, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.passage_see_error),
                    Modifier.widthIn(max = 140.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * THE ATMOSPHERE: a pool of the state colour behind the hero ([focusX],
 * [focusY] as fractions of the tab), a faint brand pool low on the page and
 * another high on the start side.
 *
 * STATIC since v5: v4 breathed it while connecting, which redrew three
 * full-screen gradients every frame. The brushes are built once per size or
 * colour change ([drawWithCache]) and only replayed after that.
 */
private fun Modifier.auroraWash(
    tone: Color,
    secondary: Color,
    focusX: Float,
    focusY: Float,
): Modifier = this.drawWithCache {
    val center = Offset(size.width * focusX, size.height * focusY)
    val radius = (size.minDimension * 0.95f).coerceAtLeast(1f)
    val pool = Brush.radialGradient(
        colors = listOf(
            tone.copy(alpha = 0.20f),
            tone.copy(alpha = 0.04f),
            Color.Transparent,
        ),
        center = center,
        radius = radius,
    )
    val low = Offset(size.width * 0.92f, size.height * 0.95f)
    val lowRadius = (size.minDimension * 0.80f).coerceAtLeast(1f)
    val lowPool = Brush.radialGradient(
        colors = listOf(secondary.copy(alpha = 0.08f), Color.Transparent),
        center = low,
        radius = lowRadius,
    )
    val high = Offset(size.width * 0.05f, size.height * 0.08f)
    val highRadius = (size.minDimension * 0.55f).coerceAtLeast(1f)
    val highPool = Brush.radialGradient(
        colors = listOf(secondary.copy(alpha = 0.06f), Color.Transparent),
        center = high,
        radius = highRadius,
    )
    onDrawBehind {
        drawCircle(brush = pool, radius = radius, center = center)
        drawCircle(brush = lowPool, radius = lowRadius, center = low)
        drawCircle(brush = highPool, radius = highRadius, center = high)
    }
}

/**
 * The orb in its orbit. One satellite per hop rides the orbit with a short
 * tail while reaching (fast) and once protected (slow). Decorative to a
 * screen reader. When the slot is so small the orbit would cut through the
 * orb, the field is not drawn.
 *
 * This is the ONLY thing on the hero that turns: the button inside it has no
 * spinning layer of its own (ConnectButton v6).
 *
 * PERFORMANCE: the rings are one static Canvas. The satellites are drawn ONCE
 * at their start angles and the whole layer is rotated by [graphicsLayer], so
 * the orbit costs a matrix update per frame instead of a redraw. v4's sonar
 * waves (expanding circles, a redraw every frame for the entire session) are
 * gone: they also read as the button pulsing bigger and smaller.
 */
@Composable
private fun OrbitalHero(
    mode: ButtonMode,
    chain: ChainMode,
    tone: Color,
    orb: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val orbit = remember { Animatable(0f) }
    LaunchedEffect(mode, reduced) {
        val period = when (mode) {
            ButtonMode.BUSY -> 4200
            ButtonMode.CONNECTED -> 18000
            else -> 0
        }
        orbit.snapTo(0f)
        if (reduced || period == 0) return@LaunchedEffect
        orbit.animateTo(1f, infiniteRepeatable(tween(period, easing = LinearEasing)))
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    val satellites = chain.hops.size.coerceAtLeast(1)
    val live = mode == ButtonMode.CONNECTED || mode == ButtonMode.BUSY || mode == ButtonMode.ERROR
    val moving = !reduced && (mode == ButtonMode.BUSY || mode == ButtonMode.CONNECTED)
    val base = if (live) tone else track

    Box(modifier, contentAlignment = Alignment.Center) {
        // The field: static, redrawn only when the state changes.
        Canvas(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics { },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 8.dp.toPx()
            val orbR = orb.toPx() / 2f
            if (radius < orbR + 6.dp.toPx()) return@Canvas

            // A quiet guide ring halfway out, so the field reads as depth.
            drawCircle(
                color = base.copy(alpha = if (live) 0.14f else 0.20f),
                radius = orbR + (radius - orbR) * 0.5f,
                center = centre,
                style = Stroke(width = 1.dp.toPx()),
            )

            // The orbit.
            drawCircle(
                color = base.copy(alpha = if (live) 0.30f else 0.35f),
                radius = radius,
                center = centre,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(3.dp.toPx(), 7.dp.toPx()),
                    ),
                ),
            )
        }

        // The satellites: drawn once, turned by the layer.
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = orbit.value * 360f }
                .clearAndSetSemantics { },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 8.dp.toPx()
            val orbR = orb.toPx() / 2f
            if (radius < orbR + 6.dp.toPx()) return@Canvas
            val orbitTopLeft = Offset(centre.x - radius, centre.y - radius)
            val orbitSize = Size(radius * 2f, radius * 2f)
            repeat(satellites) { index ->
                val degrees = ORBIT_START + index * (360f / satellites)
                if (moving) {
                    // The tail: where the satellite just was.
                    drawArc(
                        color = tone.copy(alpha = 0.38f),
                        startAngle = degrees - 28f,
                        sweepAngle = 28f,
                        useCenter = false,
                        topLeft = orbitTopLeft,
                        size = orbitSize,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                val radians = Math.toRadians(degrees.toDouble())
                val point = Offset(
                    centre.x + radius * cos(radians).toFloat(),
                    centre.y + radius * sin(radians).toFloat(),
                )
                drawCircle(base.copy(alpha = 0.22f), radius = 8.dp.toPx(), center = point)
                drawCircle(base, radius = 3.5.dp.toPx(), center = point)
            }
        }
        content()
    }
}

// ------------------------------------------------------------- session stats --

/**
 * Live traffic, one card, one half per direction: rate now, a level meter and
 * the session total. [compact] drops the meters and labels for short windows.
 * A fresh session never shows the previous one's last reading.
 */
@Composable
private fun SessionStats(connectedSince: Long?, compact: Boolean) {
    val accents = LocalAetherAccents.current
    val latest by TrafficMonitor.sample.collectAsStateWithLifecycle()
    val zero = remember(connectedSince) { TrafficMonitor.Sample() }
    val sample = if (latest.live) latest else zero
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Dock),
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RateCell(
                label = stringResource(R.string.traffic_download),
                rate = sample.downloadRate,
                total = sample.downloadBytes,
                icon = Icons.Rounded.ArrowDownward,
                tint = accents.protected,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .width(1.dp)
                    .height(if (compact) 28.dp else 52.dp)
                    .background(accents.cardBorder),
            )
            RateCell(
                label = stringResource(R.string.traffic_upload),
                rate = sample.uploadRate,
                total = sample.uploadBytes,
                icon = Icons.Rounded.ArrowUpward,
                tint = accents.brand,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RateCell(
    label: String,
    rate: Long,
    total: Long,
    icon: ImageVector,
    tint: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val level by animateFloatAsState(
        targetValue = rateLevel(rate),
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "rate",
    )
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(
                icon = icon,
                tint = tint,
                size = if (compact) 22.dp else 28.dp,
                description = if (compact) label else null,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (!compact) {
                    Text(
                        label,
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    TrafficMonitor.formatRate(rate),
                    style = (if (compact) AetherNumeral.copy(fontSize = 14.sp) else AetherNumeral)
                        .copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clearAndSetSemantics { },
                ) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = track.copy(alpha = 0.4f), cornerRadius = radius)
                    val width = size.width * level
                    if (width > 0.5f) {
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        drawRoundRect(
                            brush = Brush.horizontalGradient(listOf(tint.copy(alpha = 0.45f), tint)),
                            topLeft = Offset(if (rtl) size.width - width else 0f, 0f),
                            size = Size(width, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    TrafficMonitor.formatBytes(total),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = AetherMono,
                        textDirection = TextDirection.Ltr,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ------------------------------------------------------------- location card --

private data class RouteNode(val label: String, val icon: ImageVector)

/** One glyph per core. Shared with the route choices in QuickControls.kt. */
internal fun hopIcon(hop: Hop): ImageVector = when (hop) {
    Hop.AETHER -> Icons.Rounded.Bolt
    Hop.PSIPHON -> Icons.Rounded.Cloud
    Hop.TOR -> Icons.Rounded.Layers
}

/** You -> the hops in the order traffic enters them -> Internet. */
@Composable
private fun routeNodes(chain: ChainMode): List<RouteNode> {
    val you = stringResource(R.string.conn_node_you)
    val internet = stringResource(R.string.conn_node_internet)
    return buildList {
        add(RouteNode(you, Icons.Rounded.Smartphone))
        chain.hops.asReversed().forEach { add(RouteNode(it.label, hopIcon(it))) }
        add(RouteNode(internet, Icons.Rounded.Public))
    }
}

/**
 * The exit, as a flag and a code: live country once verified; otherwise the
 * country asked of Tor or Psiphon; otherwise Cloudflare's edge / auto.
 */
@Composable
private fun exitReadout(
    profile: ConnectionProfile,
    state: ConnectionState,
    ipInfo: IpEndpoint?,
): Pair<String, String> {
    val live = ipInfo?.countryCode?.trim()?.uppercase(Locale.US)
        ?.takeIf { state.isConnected && it.length == 2 }
    val requested = when {
        profile.chain.usesTor -> profile.torExitCountry
        profile.chain.usesPsiphon -> PsiphonRegions.sanitize(profile.psiphonRegion)
        else -> ""
    }.trim().uppercase(Locale.US)
    return when {
        live != null -> PsiphonRegions.flag(live) to live
        requested.length == 2 -> PsiphonRegions.flag(requested) to requested
        profile.chain == ChainMode.AETHER ->
            PsiphonRegions.GLOBE to stringResource(R.string.conn_exit_warp)
        else -> PsiphonRegions.GLOBE to stringResource(R.string.conn_exit_auto)
    }
}

/**
 * A two-letter region code as a country name in the UI language ("DE" ->
 * "Germany" / its Persian name). Anything else ("Cloudflare edge") passes
 * through untouched, and so does a code the platform has no name for.
 */
@Composable
private fun countryName(code: String): String {
    val locale = LocalConfiguration.current.locales.get(0)
    return remember(code, locale) {
        if (code.length == 2 && code.all { it in 'A'..'Z' }) {
            runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != code }
                ?: code
        } else {
            code
        }
    }
}

/**
 * THE LOCATION CARD: where this session comes out, how it gets there, and
 * what the internet sees. Read-only since v6: the route, protocol and scan
 * mode are shown (and changed) by the quick controls above it, so this card
 * no longer repeats them in a subtitle or opens a picker of its own.
 *
 * [expanded] draws the route path; [dense] tightens padding. The border takes
 * the state colour while the profile is locked, the same cue as before.
 */
@Composable
private fun LocationCard(
    profile: ConnectionProfile,
    state: ConnectionState,
    step: Int?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    connectedSince: Long?,
    tone: Color,
    editable: Boolean,
    showIp: Boolean,
    expanded: Boolean,
    dense: Boolean,
) {
    val accents = LocalAetherAccents.current
    val chain = profile.chain
    val nodes = routeNodes(chain)
    val lit = litRouteNodes(state, step, nodes.size)
    val (flag, exit) = exitReadout(profile, state, ipInfo)
    val place = countryName(exit)
    val connected = state is ConnectionState.Connected
    val pad = if (dense) 14.dp else 16.dp

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Dock),
        color = accents.card,
        border = BorderStroke(1.dp, if (editable) accents.cardBorder else tone.copy(alpha = 0.35f)),
    ) {
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(tone.copy(alpha = 0.10f), Color.Transparent)),
                    )
                    .padding(horizontal = pad, vertical = if (dense) 10.dp else 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagAvatar(
                        flag = flag,
                        tone = tone,
                        size = if (dense) FLAG_AVATAR_DENSE else FLAG_AVATAR,
                        lit = connected,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .semantics(mergeDescendants = true) { },
                    ) {
                        Text(
                            stringResource(R.string.aurora_location_label),
                            style = AetherMetaLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            place,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (expanded) {
                    Spacer(Modifier.height(if (dense) 10.dp else 14.dp))
                    RoutePath(
                        nodes = nodes,
                        lit = lit,
                        tone = tone,
                        reaching = step != null,
                        broken = state is ConnectionState.Error,
                        nodeSize = if (dense) NODE_SIZE_DENSE else NODE_SIZE,
                    )
                }
            }

            if (showIp) {
                HorizontalDivider(color = accents.cardBorder)
                IpRow(
                    connected = connected,
                    connectedSince = connectedSince,
                    ipInfo = ipInfo,
                    ipLoading = ipLoading,
                    tone = tone,
                    modifier = Modifier.padding(horizontal = pad, vertical = if (dense) 6.dp else 8.dp),
                )
            }
        }
    }
}

/** The exit's flag on a soft disc of the state colour. Decorative. */
@Composable
private fun FlagAvatar(flag: String, tone: Color, size: Dp, lit: Boolean) {
    val accents = LocalAetherAccents.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        tone.copy(alpha = if (lit) 0.30f else 0.16f),
                        accents.brand.copy(alpha = 0.10f),
                    ),
                ),
            )
            .border(1.dp, tone.copy(alpha = if (lit) 0.60f else 0.30f), CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(flag, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * THE ONE IP READOUT, as the location card's footer. Your own IP at rest, the
 * exit IP once verified - with the latency chip at its end, because latency
 * is a property of that same exit. Monospaced and pinned LTR: in Persian an
 * IP otherwise renders reordered.
 */
@Composable
private fun IpRow(
    connected: Boolean,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val value = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> ipInfo.ip
        else -> stringResource(R.string.ip_unavailable)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (connected) Icons.Rounded.Shield else Icons.Rounded.Public,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (connected) tone else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        // A weighted label beside an unweighted address is measured last and
        // can receive zero width. Give label and address separate lines so
        // a narrow card cannot silently lose whether this is your or server IP.
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { },
        ) {
            Text(
                stringResource(if (connected) R.string.ip_server_label else R.string.ip_your_label),
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (flag.isNotBlank()) {
                    Text(flag, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = AetherMono,
                        textDirection = TextDirection.Ltr,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (connected) {
            Spacer(Modifier.width(8.dp))
            LatencyChip(connectedSince)
        }
    }
}

/**
 * Live latency, at the end of the IP row.
 *
 * BATTERY CONTRACT: no polling loop. A session gets ONE automatic measurement a
 * second and a bit after it comes up, and one more per tap.
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
        color = band.copy(alpha = 0.10f),
        contentColor = band,
        shape = CircleShape,
        border = BorderStroke(1.dp, band.copy(alpha = 0.30f)),
    ) {
        Row(
            Modifier
                .clickable(
                    enabled = !ping.running,
                    onClickLabel = stringResource(R.string.meta_latency_test),
                    role = Role.Button,
                ) { scope.launch { PingMonitor.pingOnce(viaTunnel = true) } }
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
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
 * The route as nodes and links: done (solid), reaching (dashed, travelling
 * band), broken (dashed, failure tone), future (dashed, dim). RTL mirrors from
 * the draw scope's layout direction.
 *
 * Only the reaching link animates. v4 also ran packets along every done link
 * for the whole verified session: a per-frame redraw that lasted hours.
 */
@Composable
private fun RoutePath(
    nodes: List<RouteNode>,
    lit: Int,
    tone: Color,
    reaching: Boolean,
    broken: Boolean,
    nodeSize: Dp,
) {
    val reduced = LocalReducedMotion.current
    val accents = LocalAetherAccents.current
    val flow = remember { Animatable(0f) }
    val moving = !reduced && reaching
    LaunchedEffect(moving) {
        flow.snapTo(0f)
        if (!moving) return@LaunchedEffect
        flow.animateTo(1f, infiniteRepeatable(tween(1400, easing = LinearEasing)))
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    val description = nodes.joinToString(" \u2192 ") { it.label }
    val nodeWidth = if (nodes.size <= 3) 60.dp else 50.dp

    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.Top,
    ) {
        nodes.forEachIndexed { index, node ->
            val on = index < lit
            Column(
                Modifier.width(nodeWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(nodeSize)
                        .clip(CircleShape)
                        .background(
                            if (on) {
                                Brush.radialGradient(listOf(tone.copy(alpha = 0.28f), tone.copy(alpha = 0.10f)))
                            } else {
                                Brush.radialGradient(listOf(accents.card, accents.card))
                            },
                        )
                        .border(
                            1.dp,
                            if (on) tone.copy(alpha = 0.70f) else accents.cardBorder,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        node.icon,
                        contentDescription = null,
                        modifier = Modifier.size(nodeSize * 0.47f),
                        tint = if (on) tone else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    node.label,
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index < nodes.lastIndex) {
                val done = index + 1 < lit
                val active = reaching && index + 1 == lit
                val snapped = broken && index + 1 == lit
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(nodeSize),
                ) {
                    val y = size.height / 2f
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val stroke = 2.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    val start = Offset(0f, y)
                    val end = Offset(size.width, y)
                    when {
                        done -> drawLine(tone.copy(alpha = 0.85f), start, end, stroke, StrokeCap.Round)
                        active -> {
                            drawLine(
                                track.copy(alpha = 0.6f), start, end, stroke,
                                StrokeCap.Round, dash,
                            )
                            if (!reduced) {
                                val band = size.width * 0.5f
                                val left = shimmerBandStart(0f, size.width, band, flow.value, rtl)
                                drawLine(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Transparent, tone, Color.Transparent),
                                        startX = left,
                                        endX = left + band,
                                    ),
                                    start = start,
                                    end = end,
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                            } else {
                                val half = size.width / 2f
                                drawLine(
                                    tone,
                                    if (rtl) Offset(size.width, y) else start,
                                    Offset(half, y),
                                    stroke,
                                    StrokeCap.Round,
                                )
                            }
                        }
                        snapped -> drawLine(tone, start, end, stroke, StrokeCap.Round, dash)
                        else -> drawLine(
                            track.copy(alpha = 0.6f), start, end, stroke,
                            StrokeCap.Round, dash,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconChip(
    icon: ImageVector,
    tint: Color,
    size: Dp = 28.dp,
    description: String? = null,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(size * 0.54f), tint)
    }
}

/**
 * A monotonic clock, for an in-flight attempt or a live session. Sleeps to the
 * next whole second of the period, lifecycle-scoped, and stopped entirely when
 * nothing is running.
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

/** The state word: the orb's state description for TalkBack. */
@Composable
private fun stateWord(state: ConnectionState): String = stringResource(
    when {
        state.isConnected -> R.string.pill_secure
        state is ConnectionState.Error -> R.string.pill_failed
        state.isBusy -> R.string.pill_working
        else -> R.string.pill_off
    },
)
