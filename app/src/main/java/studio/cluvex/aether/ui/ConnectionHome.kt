package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.StatusHint
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * THE CONNECTION TAB, v3: "one screen, no scroll".
 *
 * v2 got the pieces right (orbital hero, route card, live traffic) and then
 * stacked them into a page taller than most phones, so the answer to "am I
 * protected, and through what" was half below the fold. It also said several
 * things two or three times. This pass keeps every fact and says each ONCE:
 *
 *   REMOVED (each was a repeat of something still on screen)
 *     - the state badge: the same word the orb carries, a third time with the
 *       title. Its live region moved to the title, which is more specific.
 *     - the phase card: its progress is the orb's ring AND the route path, its
 *       step name is the title, and its clock is the orb's readout.
 *     - the session bento: uptime is the orb's readout, protocol and exit
 *       country are the route card's footer.
 *     - the disconnected meta card: four tiles that could only ever read "-".
 *     - the scan note and the "route locked" caption: the hint and the lock
 *       glyph already say them (the caption is now the glyph's description).
 *
 *   WHAT IS LEFT
 *     hero (the orb in its orbit, smaller: 192dp at most, was 256dp)
 *     title + hint
 *     error strip            only on failure
 *     route card             always
 *     live traffic           only while verified
 *     IP strip               the one IP readout, with latency once verified
 *     privacy line           idle, Aether only, when there is room
 *
 * NO SCROLL. The tab measures the height it is given and picks a [HomeDensity];
 * the hero takes whatever is left, and the orb sizes itself from the hero
 * ([heroGeometry]). Landscape and wide windows split into two panes
 * ([useTwoPane]), so a phone on its side or a tablet never stacks a column
 * taller than the window. Font scale counts against the height budget, so
 * large text trades decoration for room instead of clipping.
 *
 * WHAT IS DELIBERATELY UNTOUCHED: [buttonMode], [connectionStep],
 * [phaseProgress], [litRouteNodes], [rateLevel], [shimmerBandStart], [orbDetail]
 * and [formatSessionUptime] keep their exact contracts; the state machine,
 * the battery contract of the latency probe and the reduced-motion rules are
 * the same ones the rest of the app obeys.
 */

/** Engine, tunnel, verify, ready. */
private const val PHASE_COUNT = 4

/** Twelve o'clock, like the orb's own arc. */
private const val ORBIT_START = -90f

/** The orbit (with its satellites) never grows past this, however tall the slot. */
internal val HERO_ORBIT_MAX = 260.dp

/** The orb on the home tab. Was 256dp, which crowded every other element off a phone. */
internal val ORB_HOME_MAX = 192.dp

/** Below this the word inside the orb stops fitting on one line. */
internal val ORB_HOME_MIN = 96.dp

private val NODE_SIZE = 34.dp
private val NODE_SIZE_DENSE = 28.dp
private val HOP_CHIP = 28.dp
private val HOP_STEP = 16.dp

/**
 * How much the tab can show at once, from the height it is given.
 *
 *   ROOMY    everything, at full padding
 *   COMPACT  everything but the privacy line, tighter padding
 *   TIGHT    route card collapses to one row, the hint goes
 *   MINIMAL  as TIGHT, and the traffic tiles go (the orb still says verified)
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
 * The orb is ~78% of the orbit so the satellites always ride OUTSIDE it, and
 * both are clamped so a tablet does not get a dinner plate and a split-screen
 * window still gets a tappable control.
 */
internal fun heroGeometry(width: Dp, height: Dp): Pair<Dp, Dp> {
    val side = minOf(width, height).coerceAtLeast(0.dp)
    val orbit = side.coerceAtMost(HERO_ORBIT_MAX)
    val orb = (orbit * 0.78f).coerceIn(ORB_HOME_MIN, ORB_HOME_MAX)
    return maxOf(orbit, orb) to orb
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
 * Now the ONLY clock on the tab.
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
) {
    val accents = LocalAetherAccents.current
    val mode = buttonMode(state)
    val tone = accentFor(mode)
    val step = connectionStep(state)
    val working = step != null
    val fontScale = LocalDensity.current.fontScale

    // Restarted whenever the attempt starts or ends, and saved, so a rotation
    // mid-connect does not reset the clock the user is timing the wait with.
    val startedAt = rememberSaveable(working) { SystemClock.elapsedRealtime() }
    val elapsed = tickingElapsed(startedAt, working)

    var routeSheet by rememberSaveable { mutableStateOf(false) }
    // The session started (or a reconnect kicked in) while the sheet was open:
    // the choice is no longer the user's to make, so the sheet goes away.
    LaunchedEffect(editable) { if (!editable) routeSheet = false }

    // Manual-only latency: a stale reading must not survive the session. The
    // old disconnected meta card did this; it is gone, the contract is not.
    val verified = state is ConnectionState.Connected
    LaunchedEffect(verified) { if (!verified) PingMonitor.reset() }

    val hero: @Composable (Modifier) -> Unit = { slot ->
        HomeHero(
            state = state,
            mode = mode,
            chain = profile.chain,
            tone = tone,
            connectedSince = connectedSince,
            elapsed = elapsed,
            step = step,
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
                onOpenRoute = { routeSheet = true },
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = slot,
            )
        }

        if (twoPane) {
            Row(
                Modifier
                    .fillMaxSize()
                    .auroraWash(tone, accents.brand, breathing = state.isBusy, focusX = 0.27f, focusY = 0.45f)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    hero(Modifier.weight(1f).fillMaxWidth())
                    HomeHeadline(state, profile, showHint = density != HomeDensity.MINIMAL)
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    details(Modifier.widthIn(max = 520.dp).fillMaxWidth())
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .auroraWash(tone, accents.brand, breathing = state.isBusy, focusX = 0.5f, focusY = 0.28f)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                hero(Modifier.weight(1f).fillMaxWidth())
                HomeHeadline(state, profile, showHint = density <= HomeDensity.COMPACT)
                Spacer(Modifier.height(if (density == HomeDensity.ROOMY) 20.dp else 12.dp))
                // Capped so a portrait tablet gets cards, not banners.
                details(Modifier.widthIn(max = 560.dp).fillMaxWidth())
                Spacer(Modifier.height(if (density == HomeDensity.ROOMY) 16.dp else 10.dp))
            }
        }
    }

    if (routeSheet) {
        RouteSheet(
            selected = profile.chain,
            onDismiss = { routeSheet = false },
            onSelect = { chosen ->
                if (chosen != profile.chain) onProfileChange(profile.copy(chain = chosen))
                routeSheet = false
            },
        )
    }
}

/** The orb in its orbit, sized from whatever height the layout left for it. */
@Composable
private fun HomeHero(
    state: ConnectionState,
    mode: ButtonMode,
    chain: ChainMode,
    tone: Color,
    connectedSince: Long?,
    elapsed: String,
    step: Int?,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val (orbit, orb) = heroGeometry(maxWidth, maxHeight)
        OrbitalHero(
            mode = mode,
            chain = chain,
            tone = tone,
            orb = orb,
            modifier = Modifier.size(orbit),
        ) {
            // Read here, in the hero's own scope: once the tunnel is verified
            // this ticks every second, and the tick should recompose the orb -
            // not the whole tab around it.
            val sessionClock = tickingElapsed(connectedSince, state is ConnectionState.Connected)
            ConnectButton(
                mode = mode,
                onClick = onToggleConnection,
                stateLabel = stateWord(state),
                actionLabel = stringResource(connectionActionLabel(state)),
                detail = orbDetail(state, connectedSince, elapsed, sessionClock),
                progress = phaseProgress(step),
                enabled = connectControlEnabled(state),
                diameter = orb,
            )
        }
    }
}

/**
 * The title and the sentence under it. The title carries the live region the
 * state badge used to: it is the most specific words on screen for the state.
 */
@Composable
private fun HomeHeadline(state: ConnectionState, profile: ConnectionProfile, showHint: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            connectionTitle(state),
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showHint) {
            Spacer(Modifier.height(4.dp))
            StatusHint(connectionHint(state, profile))
        }
    }
}

/** Everything below the hero, in one column, each fact once. */
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
    onOpenRoute: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val gap = if (density == HomeDensity.ROOMY) 12.dp else 8.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        if (state is ConnectionState.Error) {
            // The engine's own words, and one route to the log line behind them.
            ErrorStrip(state.message, accents.failed, onOpenDiagnostics)
        }

        RouteCard(
            profile = profile,
            state = state,
            step = step,
            ipInfo = ipInfo,
            tone = tone,
            editable = editable,
            expanded = density <= HomeDensity.COMPACT,
            dense = density != HomeDensity.ROOMY,
            onOpen = onOpenRoute,
        )

        if (state is ConnectionState.Connected) {
            if (density != HomeDensity.MINIMAL) LiveTraffic(connectedSince)
            IpStrip(
                connected = true,
                connectedSince = connectedSince,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
                tone = tone,
            )
        } else if (step == null && state !is ConnectionState.Disconnecting) {
            // Disconnected or failed: where the internet currently thinks you
            // are. Not while tearing down: the IP probe is parked in that window
            // and the strip would flash "unavailable".
            IpStrip(
                connected = false,
                connectedSince = null,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
                tone = tone,
            )
        }

        // The WARP disclosure is about the WARP exit network; with Psiphon or
        // Tor in the chain it would describe somebody else's network.
        if (density == HomeDensity.ROOMY && state is ConnectionState.Idle && profile.chain == ChainMode.AETHER) {
            PrivacyLine()
        }
    }
}

/** A failure in one row: the engine's message, and the way to its log line. */
@Composable
private fun ErrorStrip(message: String, tone: Color, onOpenDiagnostics: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = tone.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tone)
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

/** The privacy disclosure as one quiet line instead of a full notice card. */
@Composable
private fun PrivacyLine() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Lock,
            null,
            Modifier.size(14.dp),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.passage_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * THE ONE IP READOUT. Your own IP at rest, the exit IP once verified - with the
 * latency chip at its end, because latency is a property of that same exit.
 * Monospaced and pinned LTR: in Persian an IP otherwise renders reordered.
 */
@Composable
private fun IpStrip(
    connected: Boolean,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    tone: Color,
) {
    val accents = LocalAetherAccents.current
    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val value = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> ipInfo.ip
        else -> stringResource(R.string.ip_unavailable)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(
                Icons.Rounded.Public,
                if (connected) tone else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (flag.isNotBlank()) {
                        Text(flag, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium.copy(
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
}

/**
 * THE ATMOSPHERE: a pool of the state colour behind the hero ([focusX],
 * [focusY] as fractions of the tab), and a faint pool of the brand colour low
 * on the page. One [Animatable] read inside [drawBehind]: draw phase only.
 */
@Composable
private fun Modifier.auroraWash(
    tone: Color,
    secondary: Color,
    breathing: Boolean,
    focusX: Float,
    focusY: Float,
): Modifier {
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
        val center = Offset(size.width * focusX, size.height * focusY)
        val radius = (size.minDimension * (0.95f + 0.08f * t)).coerceAtLeast(1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tone.copy(alpha = 0.12f + 0.07f * t), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        val low = Offset(size.width * 0.88f, size.height * 0.92f)
        val lowRadius = (size.minDimension * 0.75f).coerceAtLeast(1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.06f), Color.Transparent),
                center = low,
                radius = lowRadius,
            ),
            radius = lowRadius,
            center = low,
        )
    }
}

/**
 * The orb, in orbit. One satellite per hop of the selected chain. Decorative to
 * a screen reader. When the slot is so small the orbit would cut through the
 * orb, the orbit is simply not drawn.
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
        if (reduced || period == 0) {
            orbit.snapTo(0f)
            return@LaunchedEffect
        }
        orbit.snapTo(0f)
        orbit.animateTo(1f, infiniteRepeatable(tween(period, easing = LinearEasing)))
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    val satellites = chain.hops.size.coerceAtLeast(1)
    val live = mode == ButtonMode.CONNECTED || mode == ButtonMode.BUSY || mode == ButtonMode.ERROR

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics { },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 8.dp.toPx()
            if (radius < orb.toPx() / 2f + 6.dp.toPx()) return@Canvas
            drawCircle(
                color = (if (live) tone else track).copy(alpha = if (live) 0.28f else 0.35f),
                radius = radius,
                center = centre,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(3.dp.toPx(), 7.dp.toPx()),
                    ),
                ),
            )
            val turn = orbit.value * 360f
            repeat(satellites) { index ->
                val degrees = ORBIT_START + turn + index * (360f / satellites)
                val radians = Math.toRadians(degrees.toDouble())
                val point = Offset(
                    centre.x + radius * cos(radians).toFloat(),
                    centre.y + radius * sin(radians).toFloat(),
                )
                val dot = if (live) tone else track
                drawCircle(dot.copy(alpha = 0.20f), radius = 7.dp.toPx(), center = point)
                drawCircle(dot, radius = 3.dp.toPx(), center = point)
            }
        }
        content()
    }
}

/**
 * Live latency, at the end of the IP strip.
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
                .heightIn(min = 36.dp)
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

// ---------------------------------------------------------------- route card --

private data class RouteNode(val label: String, val icon: ImageVector)

private fun hopIcon(hop: Hop): ImageVector = when (hop) {
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
 * THE ROUTE CARD: which cores carry this session, plus exit, scan mode and
 * protocol - the only place those three appear on the tab.
 *
 * [expanded] draws the path as nodes; collapsed it is one row with the exit
 * and protocol as its subtitle, for short windows. [dense] tightens padding.
 * The whole card opens the route picker, only while the profile is editable;
 * locked, the lock glyph carries the reason for TalkBack.
 */
@Composable
private fun RouteCard(
    profile: ConnectionProfile,
    state: ConnectionState,
    step: Int?,
    ipInfo: IpEndpoint?,
    tone: Color,
    editable: Boolean,
    expanded: Boolean,
    dense: Boolean,
    onOpen: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val chain = profile.chain
    val nodes = routeNodes(chain)
    val lit = litRouteNodes(state, step, nodes.size)
    val (flag, exit) = exitReadout(profile, state, ipInfo)
    val pad = if (dense) 14.dp else 18.dp

    val tech = buildList {
        if (chain.usesAether) {
            add(
                if (profile.hasManualPeer) {
                    endpointLabel(EndpointMode.MANUAL_PEER)
                } else {
                    scanLabel(profile.scanMode)
                },
            )
            val liveProtocol = meta.protocol?.takeIf { state.isConnected && it.isNotBlank() }
            add(liveProtocol ?: protocolLabel(profile.protocol))
        }
    }.joinToString(" \u00B7 ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = accents.card,
        border = BorderStroke(1.dp, if (editable) accents.cardBorder else tone.copy(alpha = 0.30f)),
    ) {
        Column(
            Modifier
                .clickable(
                    enabled = editable,
                    onClickLabel = stringResource(R.string.conn_route_change),
                    role = Role.Button,
                    onClick = onOpen,
                )
                .background(
                    Brush.verticalGradient(listOf(tone.copy(alpha = 0.09f), Color.Transparent)),
                )
                .padding(horizontal = pad, vertical = if (expanded) pad else 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.Layers, tone)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (expanded) {
                        Text(
                            stringResource(R.string.conn_route_title),
                            style = AetherMetaLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        chainLabel(chain),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!expanded) {
                        // Collapsed: the footer becomes the subtitle.
                        Text(
                            listOf("$flag $exit", tech).filter { it.isNotBlank() }.joinToString(" \u00B7 "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                TierBadge(chain)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (editable) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.Lock,
                    contentDescription = if (editable) null else stringResource(R.string.conn_route_locked),
                    modifier = Modifier.size(if (editable) 22.dp else 16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(if (dense) 12.dp else 18.dp))
                RoutePath(
                    nodes = nodes,
                    lit = lit,
                    tone = tone,
                    flowing = state.isConnected,
                    reaching = step != null,
                    broken = state is ConnectionState.Error,
                    nodeSize = if (dense) NODE_SIZE_DENSE else NODE_SIZE,
                )
                Spacer(Modifier.height(if (dense) 10.dp else 14.dp))
                HorizontalDivider(color = accents.cardBorder)
                Spacer(Modifier.height(if (dense) 8.dp else 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.conn_exit_label),
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(flag, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        exit,
                        style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        tech,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The route as nodes and links: done (solid, packets flow once verified),
 * reaching (dashed, travelling band), broken (dashed, failure tone), future
 * (dashed, dim). RTL mirrors from the draw scope's layout direction.
 */
@Composable
private fun RoutePath(
    nodes: List<RouteNode>,
    lit: Int,
    tone: Color,
    flowing: Boolean,
    reaching: Boolean,
    broken: Boolean,
    nodeSize: Dp,
) {
    val reduced = LocalReducedMotion.current
    val accents = LocalAetherAccents.current
    val flow = remember { Animatable(0f) }
    val moving = !reduced && (flowing || reaching)
    LaunchedEffect(moving) {
        if (!moving) {
            flow.snapTo(0f)
            return@LaunchedEffect
        }
        flow.snapTo(0f)
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
                        .background(if (on) tone.copy(alpha = 0.16f) else accents.card)
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
                        done -> {
                            drawLine(tone.copy(alpha = 0.85f), start, end, stroke, StrokeCap.Round)
                            if (flowing && !reduced) {
                                repeat(2) { k ->
                                    val f = (flow.value + k / 2f) % 1f
                                    val x = if (rtl) size.width * (1f - f) else size.width * f
                                    drawCircle(tone.copy(alpha = 0.25f), 5.dp.toPx(), Offset(x, y))
                                    drawCircle(tone, 2.5.dp.toPx(), Offset(x, y))
                                }
                            }
                        }
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
private fun IconChip(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(28.dp)
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint)
    }
}

/** Fast / slower / slowest, from the hops themselves - same as the Chain page. */
@Composable
private fun TierBadge(mode: ChainMode) {
    val accents = LocalAetherAccents.current
    val label: String
    val tone: Color
    when {
        mode.usesTor -> {
            label = stringResource(R.string.chain_tier_slow)
            tone = accents.failed
        }
        mode.usesPsiphon -> {
            label = stringResource(R.string.chain_tier_moderate)
            tone = accents.working
        }
        else -> {
            label = stringResource(R.string.chain_tier_fast)
            tone = accents.protected
        }
    }
    Surface(color = tone.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = AetherMetaLabel,
            color = tone,
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------- route sheet --

/**
 * The route picker, where the route is. Same rules as the Chain settings page.
 * The sheet itself still scrolls: it is a modal list, not the home tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(
    selected: ChainMode,
    onDismiss: () -> Unit,
    onSelect: (ChainMode) -> Unit,
) {
    val context = LocalContext.current
    // Install-time facts: read once, never per recomposition.
    val cores = remember(context) { CoreAvailability.of(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.conn_route_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.conn_route_sheet_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                Text(
                    stringResource(R.string.chain_group_single),
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ChainMode.entries.filterNot { it.isChained }.forEach { mode ->
                    RouteOption(mode, selected, cores, onSelect)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.chain_group_stacked),
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ChainMode.entries.filter { it.isChained }.forEach { mode ->
                    RouteOption(mode, selected, cores, onSelect)
                }
            }
        }
    }
}

@Composable
private fun RouteOption(
    mode: ChainMode,
    selected: ChainMode,
    cores: CoreAvailability.Snapshot,
    onSelect: (ChainMode) -> Unit,
) {
    val accents = LocalAetherAccents.current
    val missing = cores.missing(mode)
    val runnable = missing.isEmpty()
    val active = mode == selected
    val alpha = if (runnable) 1f else 0.45f
    val tint = if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = active,
                enabled = runnable,
                role = Role.RadioButton,
                onClick = { onSelect(mode) },
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            HopStack(mode, tint, alpha)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chainLabel(mode),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    TierBadge(mode)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    chainDescription(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!runnable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.chain_mode_unavailable,
                            missing.joinToString(", ") { it.label },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = accents.failed,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint.copy(alpha = alpha),
            )
        }
    }
}

/** The hops of a mode as overlapping chips, entry first. Fixed width so rows align. */
@Composable
private fun HopStack(mode: ChainMode, tint: Color, alpha: Float) {
    val accents = LocalAetherAccents.current
    val hops = mode.hops.asReversed()
    Box(
        Modifier
            .width(HOP_CHIP + HOP_STEP * 2)
            .height(HOP_CHIP),
    ) {
        hops.forEachIndexed { index, hop ->
            Box(
                Modifier
                    .offset(x = HOP_STEP * index)
                    .size(HOP_CHIP)
                    .clip(CircleShape)
                    .background(accents.card)
                    .border(1.dp, tint.copy(alpha = 0.5f * alpha), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(hopIcon(hop), null, Modifier.size(14.dp), tint.copy(alpha = alpha))
            }
        }
    }
}

// -------------------------------------------------------------- live traffic --

/**
 * Live traffic, one compact tile per direction: rate now, a level meter and
 * the session total on the meter's line. A fresh session never shows the
 * previous one's last reading.
 */
@Composable
private fun LiveTraffic(connectedSince: Long?) {
    val accents = LocalAetherAccents.current
    val latest by TrafficMonitor.sample.collectAsStateWithLifecycle()
    val zero = remember(connectedSince) { TrafficMonitor.Sample() }
    val sample = if (latest.live) latest else zero
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RateTile(
            label = stringResource(R.string.traffic_download),
            rate = sample.downloadRate,
            total = sample.downloadBytes,
            icon = Icons.Rounded.ArrowDownward,
            tint = accents.protected,
            modifier = Modifier.weight(1f),
        )
        RateTile(
            label = stringResource(R.string.traffic_upload),
            rate = sample.uploadRate,
            total = sample.uploadBytes,
            icon = Icons.Rounded.ArrowUpward,
            tint = accents.brand,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RateTile(
    label: String,
    rate: Long,
    total: Long,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val track = MaterialTheme.colorScheme.outlineVariant
    val level by animateFloatAsState(
        targetValue = rateLevel(rate),
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "rate",
    )
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(icon, tint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        TrafficMonitor.formatRate(rate),
                        style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                            brush = Brush.horizontalGradient(listOf(tint.copy(alpha = 0.55f), tint)),
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

/** The word INSIDE the ring. */
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

/**
 * The sentence under the title. Never a repeat of something already on screen:
 * the error strip owns the engine's words; this line says what to do next.
 */
@Composable
private fun connectionHint(state: ConnectionState, profile: ConnectionProfile): String =
    when (state) {
        is ConnectionState.Idle ->
            if (profile.chain == ChainMode.AETHER) {
                stringResource(R.string.passage_start_hint)
            } else {
                stringResource(R.string.conn_start_hint_chain, chainLabel(profile.chain))
            }
        is ConnectionState.Connected -> stringResource(R.string.passage_connected_hint)
        is ConnectionState.Launching, is ConnectionState.Connecting ->
            if (profile.chain.usesAether) {
                stringResource(R.string.busy_hint, scanLabel(profile.scanMode))
            } else {
                stringResource(R.string.conn_busy_chain, chainLabel(profile.chain))
            }
        is ConnectionState.Verifying -> stringResource(R.string.state_verify_hint)
        is ConnectionState.Reconnecting ->
            stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
        is ConnectionState.Disconnecting -> stringResource(R.string.conn_disconnecting_hint)
        is ConnectionState.Error -> stringResource(R.string.conn_error_hint)
    }
