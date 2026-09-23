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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.AetherNumeralLarge
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * THE CONNECTION TAB, v2: "hero + route".
 *
 * The previous pass got the information architecture right (one control, one
 * answer in words, progress as a track) and this one keeps all of it. What it
 * changes is the thing every modern VPN client puts at the centre of its home
 * screen and this one hid three taps deep: THE ROUTE.
 *
 * WHAT IS NEW
 *
 *   1. ORBITAL HERO. The orb now sits inside a dashed orbit carrying one
 *      satellite per hop of the selected chain. Idle: parked and grey. Busy:
 *      fast orbit in the working tone. Connected: a slow, lit orbit. Error:
 *      frozen in the failure tone. It is drawn in one Canvas driven by one
 *      [Animatable] read in the draw phase - no recomposition per frame - and
 *      under reduced motion it is a still picture that still carries the state.
 *   2. ROUTE CARD. The chain drawn as nodes: You -> hops -> Internet. Nodes
 *      light up as the attempt earns phases (same arithmetic as the orb, see
 *      [litRouteNodes]), the segment being reached carries a travelling band,
 *      and once verified packets flow along the lit path. Under it: exit
 *      country, scan mode and protocol, in one line.
 *   3. ROUTE PICKER, IN PLACE. Tapping the card opens a bottom sheet with the
 *      seven chain modes, cost tiers, and unavailable cores disabled - the same
 *      rules as the Chain settings page. Only while Idle or Error: changing the
 *      route under a live tunnel is a trap, not a control.
 *   4. LIVE TRAFFIC. Two tiles, one per direction: the current rate, a
 *      log-scale level meter ([rateLevel]) and the session total. Read from
 *      [TrafficMonitor], which the service owns - no polling loop here.
 *   5. ONE CONTROL. The dock that duplicated the orb is gone from this tab
 *      (see HomeScreen); the orb is the control.
 *
 * WHAT IS DELIBERATELY UNTOUCHED: [buttonMode], [connectionStep],
 * [phaseProgress] and [formatSessionUptime] keep their exact contracts, because
 * the presentation tests own them; the state machine, the live-region
 * semantics, the battery contract of the latency probe and the reduced-motion
 * rules are the same ones the rest of the app obeys.
 */

/** Engine, tunnel, verify, ready. */
private const val PHASE_COUNT = 4

/** Twelve o'clock, like the orb's own arc. */
private const val ORBIT_START = -90f

private val HERO_HEIGHT = 300.dp
private val NODE_SIZE = 34.dp
private val HOP_CHIP = 28.dp
private val HOP_STEP = 16.dp

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

/**
 * How many nodes of the route (You, every hop, Internet) are lit.
 *
 * Derived from [phaseProgress], so the route, the orb's ring and the phase
 * track all agree on how far the attempt got. "You" is always lit: the device
 * end of the route exists whether or not anything else does.
 */
internal fun litRouteNodes(state: ConnectionState, step: Int?, count: Int): Int {
    if (count <= 0) return 0
    return when {
        state.isConnected -> count
        step != null -> ceil(phaseProgress(step) * count).toInt().coerceIn(1, count)
        else -> 1
    }
}

/**
 * A byte rate as a 0..1 level on a log scale from 1 KB/s to 50 MB/s.
 *
 * Log, not linear: on a linear scale a page load and an idle tunnel are both
 * "empty", and the meter would only ever move for a large download.
 */
internal fun rateLevel(bytesPerSecond: Long): Float {
    if (bytesPerSecond <= 1024L) return 0f
    val level = log10(bytesPerSecond.toDouble() / 1024.0) / log10(51_200.0)
    return level.toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun ConnectionHome(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    scrollState: ScrollState,
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

    // Restarted whenever the attempt starts or ends, and saved, so a rotation
    // mid-connect does not reset the clock the user is timing the wait with.
    val startedAt = rememberSaveable(working) { SystemClock.elapsedRealtime() }
    val elapsed = tickingElapsed(startedAt, working)

    var routeSheet by rememberSaveable { mutableStateOf(false) }
    // The session started (or a reconnect kicked in) while the sheet was open:
    // the choice is no longer the user's to make, so the sheet goes away.
    LaunchedEffect(editable) { if (!editable) routeSheet = false }

    Column(
        Modifier
            .fillMaxSize()
            .auroraWash(tone, accents.brand, breathing = state.isBusy)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        StatusRail(state, tone, connectedSince)

        OrbitalHero(mode = mode, chain = profile.chain, tone = tone) {
            // THE CONTROL. Not a status graphic with a button somewhere else.
            ConnectButton(
                mode = mode,
                onClick = onToggleConnection,
                stateLabel = stateWord(state),
                actionLabel = stringResource(connectionActionLabel(state)),
                detail = if (working) elapsed else null,
                progress = phaseProgress(step),
            )
        }

        Text(
            connectionTitle(state),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // StatusHint, not a bare Text: it cross-fades between states and already
        // collapses to an instant swap under reduced motion.
        StatusHint(connectionHint(state, profile))
        Spacer(Modifier.height(24.dp))

        if (step != null) {
            PhaseWave(step, tone, elapsed)
            // Only while an endpoint is still being found, and only when there
            // IS an endpoint scan - a Psiphon or Tor-only chain has none.
            if (step <= 1 && profile.chain.usesAether && !profile.hasManualPeer) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.passage_scan_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(16.dp))
        }

        RouteCard(
            profile = profile,
            state = state,
            step = step,
            ipInfo = ipInfo,
            tone = tone,
            editable = editable,
            onOpen = { routeSheet = true },
        )
        Spacer(Modifier.height(16.dp))

        if (state is ConnectionState.Connected) {
            LiveTraffic(connectedSince)
            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.passage_session))
            SessionBento(
                connectedSince = connectedSince,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
                tone = tone,
            )
            Spacer(Modifier.height(16.dp))
        } else if (step == null) {
            // Disconnected or failed: the same question, answered the other way
            // round - where the internet currently thinks you are.
            ConnectionMeta(
                connected = false,
                connectedSince = null,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
            )
            Spacer(Modifier.height(16.dp))
        }

        // The WARP disclosure is about the WARP exit network. With Psiphon or Tor
        // in the chain it would be describing somebody else's network.
        if (state is ConnectionState.Idle && profile.chain == ChainMode.AETHER) {
            NoticeBar(
                text = stringResource(R.string.passage_privacy),
                tone = accents.neutral,
                icon = Icons.Rounded.Lock,
            )
        }
        Spacer(Modifier.height(36.dp))
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

/**
 * THE ATMOSPHERE: a pool of the state colour behind the hero, and a faint pool
 * of the brand colour low on the page so the surface has depth rather than a
 * single spotlight.
 *
 * Applied BEFORE [verticalScroll] on purpose, so it is painted in viewport
 * coordinates and stays put while the content moves over it.
 *
 * Cost: one [Animatable] read inside [drawBehind], which invalidates the draw
 * phase and nothing else. Reduced motion pins it half-lit and steady rather than
 * removing it, because the colour itself is information.
 */
@Composable
private fun Modifier.auroraWash(tone: Color, secondary: Color, breathing: Boolean): Modifier {
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
        val center = Offset(size.width / 2f, size.height * 0.22f)
        val radius = (size.width * (0.95f + 0.08f * t)).coerceAtLeast(1f)
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
        val lowRadius = (size.width * 0.75f).coerceAtLeast(1f)
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
 * The orb, in orbit.
 *
 * One satellite per hop of the selected chain, so the hero already says how
 * many cores this session is built from before anything is read. Decorative to
 * a screen reader: the orb carries the state description, the route card the
 * path.
 */
@Composable
private fun OrbitalHero(
    mode: ButtonMode,
    chain: ChainMode,
    tone: Color,
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

    Box(
        Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics { },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 8.dp.toPx()
            if (radius <= 0f) return@Canvas
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
                drawCircle(dot.copy(alpha = 0.20f), radius = 8.dp.toPx(), center = point)
                drawCircle(dot, radius = 3.5.dp.toPx(), center = point)
            }
        }
        content()
    }
}

/**
 * The top rail: what the connection IS, and how good it is.
 *
 * The badge is still the first thing a screen reader reaches and still the only
 * element announced on every state change. The latency chip is a separate node
 * on purpose - it changes on its own schedule, and it must not make the state
 * badge re-announce itself every time a probe lands.
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

/** The state badge: a breathing dot while work is in flight, steady otherwise. */
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
 * BATTERY CONTRACT: no polling loop. A session gets ONE automatic measurement a
 * second and a bit after it comes up, and one more per tap. Said three ways: a
 * number, a colour, and signal bars.
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
 * The exit, as a flag and a code.
 *
 * Live country once verified; otherwise the country the user asked Tor or
 * Psiphon for; otherwise what actually happens - Cloudflare's edge for Aether
 * alone, the core's own choice for everything else.
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
 * THE ROUTE CARD: which cores carry this session, drawn as a path, plus the
 * three facts that explain a wait (exit, scan mode, protocol).
 *
 * The whole card is the button that opens the route picker, and it is only a
 * button while the profile is editable. Locked, it says why instead of
 * silently ignoring the tap.
 */
@Composable
private fun RouteCard(
    profile: ConnectionProfile,
    state: ConnectionState,
    step: Int?,
    ipInfo: IpEndpoint?,
    tone: Color,
    editable: Boolean,
    onOpen: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val chain = profile.chain
    val nodes = routeNodes(chain)
    val lit = litRouteNodes(state, step, nodes.size)
    val (flag, exit) = exitReadout(profile, state, ipInfo)

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
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.Layers, tone)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.conn_route_title),
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        chainLabel(chain),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TierBadge(chain)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (editable) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(if (editable) 22.dp else 16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            RoutePath(
                nodes = nodes,
                lit = lit,
                tone = tone,
                flowing = state.isConnected,
                reaching = step != null,
                broken = state is ConnectionState.Error,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = accents.cardBorder)
            Spacer(Modifier.height(12.dp))

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

            if (!editable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.conn_route_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The route as nodes and links.
 *
 *   done link      solid, and once verified two packets travel along it
 *   reaching link  dashed, with a band of light moving toward the next node
 *   broken link    dashed in the failure tone, where the attempt stopped
 *   future link    dashed and dim
 *
 * RTL: the Row already mirrors the nodes, and each link mirrors its own motion
 * from the draw scope's layout direction, so in Persian traffic flows right to
 * left toward the Internet node. One content description reads the whole path.
 */
@Composable
private fun RoutePath(
    nodes: List<RouteNode>,
    lit: Int,
    tone: Color,
    flowing: Boolean,
    reaching: Boolean,
    broken: Boolean,
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
                        .size(NODE_SIZE)
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
                        modifier = Modifier.size(16.dp),
                        tint = if (on) tone else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
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
                        .height(NODE_SIZE),
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
                                val travel = -band + flow.value * (size.width + band)
                                val left = if (rtl) size.width - travel - band else travel
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
                                // No motion: half a link in the tone says "this
                                // one is being reached" just as clearly.
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

/**
 * Fast / slower / slowest, from the hops themselves - the same ordering the
 * Chain settings page uses, so a mode never has two different price tags.
 */
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
 * The route picker, where the route is.
 *
 * Same rules as the Chain settings page: one radio group for all seven modes,
 * modes whose core is missing from this build are dimmed AND unselectable with
 * the missing core named, and every mode carries its cost tier. Per-core
 * options (exit countries, bridges) stay on the settings page - this sheet
 * answers "which path", not "how is each hop tuned".
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
 * Live traffic, one tile per direction: rate now, a level meter, session total.
 *
 * A fresh session never shows the previous one's last reading, not even for
 * the frame between Connected and the monitor's first tick.
 */
@Composable
private fun LiveTraffic(connectedSince: Long?) {
    val accents = LocalAetherAccents.current
    val latest by TrafficMonitor.sample.collectAsStateWithLifecycle()
    val zero = remember(connectedSince) { TrafficMonitor.Sample() }
    val sample = if (latest.live) latest else zero
    Row(Modifier.fillMaxWidth()) {
        RateTile(
            label = stringResource(R.string.traffic_download),
            rate = sample.downloadRate,
            total = sample.downloadBytes,
            icon = Icons.Rounded.ArrowDownward,
            tint = accents.protected,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
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
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(icon, tint)
                Spacer(Modifier.width(8.dp))
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
                TrafficMonitor.formatRate(rate),
                style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
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
            Spacer(Modifier.height(8.dp))
            Text(
                TrafficMonitor.formatBytes(total),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = AetherMono,
                    textDirection = TextDirection.Ltr,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- phase track --

/**
 * The four phases as ONE animated object: finished capsules lit with a
 * gradient, the running one carrying a travelling band, the future ones dim,
 * plus "step N of 4" so the progress is also a sentence. RTL-mirrored from the
 * draw scope's layout direction; the clock is pinned LTR and monospaced.
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

// ------------------------------------------------------------- session bento --

/**
 * Everything factual about the live session, ranked: the exit IP wide over a
 * tonal wash, then the winning protocol and the session clock. Every value is
 * monospaced and pinned LTR - load-bearing in Persian, where an IP address
 * otherwise renders reordered.
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

/** The headline tile: whose network the internet sees you coming out of. */
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

/** One cell of the grid, with its icon in a tinted chip as the focal point. */
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
                    IconChip(icon, tint)
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

/** The word INSIDE the ring. Same four words the badge uses. */
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

/**
 * The sentence under the title. Chain-aware: "through Cloudflare WARP" and "the
 * scan mode" only mean something when Aether is in the chain.
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
        is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
        is ConnectionState.Error -> state.message
    }
