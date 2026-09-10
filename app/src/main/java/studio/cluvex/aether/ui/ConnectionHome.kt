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
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.ConnectionMeta
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.NavRow
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionTitle
import studio.cluvex.aether.ui.components.SignalDiagram
import studio.cluvex.aether.ui.components.StatTile
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
 * The information architecture from the previous pass stands, and it was the
 * right one: ONE hero control, ONE answer in words, progress as a track rather
 * than a list, evidence with a hierarchy, and nothing on screen that the current
 * state does not need. What this pass changes is how all of that is DRAWN and
 * how much of it is live.
 *
 * WHAT WAS STILL WRONG
 *
 *   - THE STATUS CHIP WAS A LABEL. A static pill with an icon and a word, in the
 *     one spot a screen reader reaches first and the eye reaches second. It never
 *     told you anything the ring did not, and while a tunnel was coming up it sat
 *     perfectly still.
 *   - NO LATENCY UNTIL YOU WENT LOOKING. The number that decides whether a
 *     connection is usable lived in a tile in the session card, below the fold,
 *     behind a tap, and only ever appeared if you knew to tap it.
 *   - THE PHASE TRACK WAS FOUR RECTANGLES. Correct, informative, and completely
 *     inert: on the screen where the user is watching for movement, the only
 *     thing moving was the seconds counter.
 *   - THE EVIDENCE WAS A LEDGER OF CARDS. Two stacked cards, five tiles between
 *     them, and the live rates - the fact that proves bytes are moving THROUGH
 *     the tunnel right now - were not on this screen at all. They were in the
 *     notification.
 *
 * WHAT THIS IS NOW
 *
 *   1. A STATUS RAIL. The badge keeps its polite live region and its four words,
 *      and gains a dot that breathes while work is in flight, plus a latency chip
 *      that measures once when a session comes up and re-measures on tap. Battery
 *      contract intact: one probe per tap, one probe per session, never a loop.
 *   2. THE HERO ORB. Unchanged contract, new depth. See [ConnectButton].
 *   3. A GLOWING WAVE TRACKER. The same four phases and the same monotonic clock,
 *      drawn as four capsules with a band of light travelling through the one
 *      that is running. Completed phases glow; upcoming ones are dim. It is the
 *      same integer, animated, and it mirrors itself in RTL so the wave always
 *      travels in the reading direction.
 *   4. A BENTO TELEMETRY GRID. Exit IP as the wide hero tile with its flag and
 *      country, then live download and upload rates with their own meters, then
 *      protocol and session uptime. Compact, scannable, and every readout pinned
 *      LTR because they are instruments, not prose.
 *
 * WHAT IS DELIBERATELY UNTOUCHED: [buttonMode], [connectionStep],
 * [phaseProgress] and [formatSessionUptime] keep their exact contracts, because
 * the presentation tests own them; the state machine, the semantics and the
 * reduced-motion rules are the same ones the rest of the app obeys.
 */

/** Engine, tunnel, verify, ready. */
private const val PHASE_COUNT = 4

/** The rate a speed meter treats as "full bar", before the square-root curve. */
private const val METER_CEILING = 4_000_000f

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
    onOpenEngine: () -> Unit,
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
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        StatusRail(state, tone, connectedSince)

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
            Spacer(Modifier.height(12.dp))
            RoutePreview(profile, tone)
            Spacer(Modifier.height(28.dp))
        }

        Hairline()
        NavRow(
            title = stringResource(R.string.page_engine_title),
            onClick = onOpenEngine,
            // Straight to the page that owns protocol and scan mode, painted
            // with what is currently set, so the row answers before it is opened.
            subtitle = protocolLabel(profile.protocol) + " \u00B7 " + scanLabel(profile.scanMode),
            icon = Icons.Rounded.Tune,
        )
        Hairline()
        NavRow(
            title = stringResource(R.string.nav_diagnostics),
            onClick = onOpenDiagnostics,
            subtitle = stringResource(R.string.diag_console_note),
            icon = Icons.Rounded.Terminal,
        )
        Hairline()

        if (state is ConnectionState.Idle) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.passage_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(36.dp))
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
                        drawCircle(color = tone.copy(alpha = alpha * 0.28f), radius = size.minDimension / 2f)
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
 * there before the user starts looking for it), and one more per tap. That is
 * two TCP handshakes for a session somebody actually looked at, against a
 * previous design where the number simply did not exist unless it was hunted
 * down.
 *
 * The colour is the reading: mint under 80ms, amber to 250ms, rose beyond it. A
 * bare number means nothing to most people; a green number means "fine".
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
    val label = when {
        ping.running -> "\u2026"
        ping.ms >= 0L -> String.format(Locale.US, "%d ms", ping.ms)
        ping.error -> stringResource(R.string.action_retry)
        else -> stringResource(R.string.meta_latency_test)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = band,
        shape = MaterialTheme.shapes.small,
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
            Icon(Icons.Rounded.Speed, null, Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
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
 * The four phases as ONE animated object.
 *
 * Four capsules, the running one carrying a band of light that travels through
 * it, the finished ones glowing, the future ones dim. Same integer as the old
 * four-row list and the old flat bar; the difference is that a user watching a
 * three-minute Ironclad scan can now see the screen is alive without reading the
 * clock.
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
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(labels[current]),
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
                drawRoundRect(
                    color = when {
                        done -> tone
                        running -> track.copy(alpha = 0.55f)
                        else -> track.copy(alpha = 0.32f)
                    },
                    topLeft = Offset(left, top),
                    size = Size(segmentWidth, barHeight),
                    cornerRadius = radius,
                )
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
 *   row 1  the exit IP, wide, with its flag and country - the fact that proves
 *          traffic is leaving through the tunnel, and the first thing anyone
 *          checks
 *   row 2  live download and upload rate, each with its own meter and its own
 *          session total
 *   row 3  the protocol that actually won, and how long this session has been up
 *
 * The rates come from [TrafficMonitor], which the VPN service runs for the
 * lifetime of the session - so the counters keep accruing while this screen is
 * closed, and a fresh session can never show the previous one's last reading.
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
    val accents = LocalAetherAccents.current
    val meta by EngineMeta.state.collectAsStateWithLifecycle()
    val latest by TrafficMonitor.sample.collectAsStateWithLifecycle()

    // A fresh session must never show the previous one's last reading, not even
    // for the one frame between "Connected" and the monitor's first tick.
    val zero = remember(connectedSince) { TrafficMonitor.Sample() }
    val sample = if (latest.live) latest else zero
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
                label = stringResource(R.string.traffic_download),
                value = TrafficMonitor.formatRate(sample.downloadRate),
                icon = Icons.Rounded.ArrowDownward,
                tint = accents.protected,
                meter = meterFraction(sample.downloadRate),
                footnote = stringResource(
                    R.string.traffic_total,
                    TrafficMonitor.formatBytes(sample.downloadBytes),
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            BentoTile(
                label = stringResource(R.string.traffic_upload),
                value = TrafficMonitor.formatRate(sample.uploadRate),
                icon = Icons.Rounded.ArrowUpward,
                tint = accents.brand,
                meter = meterFraction(sample.uploadRate),
                footnote = stringResource(
                    R.string.traffic_total,
                    TrafficMonitor.formatBytes(sample.uploadBytes),
                ),
                modifier = Modifier.weight(1f),
            )
        }
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
        Column(Modifier.padding(18.dp)) {
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
 * a table row wearing a grid's spacing.
 */
@Composable
private fun BentoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = true,
    meter: Float? = null,
    footnote: String? = null,
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
                    Icon(icon, null, Modifier.size(15.dp), tint)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    label,
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
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
            if (meter != null) {
                Spacer(Modifier.height(10.dp))
                MeterBar(meter, tint)
            }
            if (!footnote.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A rate, as a bar.
 *
 * Two weighted boxes, so it costs a layout pass and no draw code. The fill never
 * reaches zero width: an idle second should read as "nothing moving", not as a
 * broken widget.
 */
@Composable
private fun MeterBar(fraction: Float, tint: Color) {
    val safe = fraction.coerceIn(0.02f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Box(
            Modifier
                .weight(safe)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.45f)),
                    ),
                ),
        )
        Spacer(Modifier.weight(1f - safe))
    }
}

/**
 * Rate -> bar fill.
 *
 * Square root, not linear: mobile throughput spends most of its time in the
 * bottom tenth of its own range, and a linear meter would sit visually empty for
 * every ordinary browsing session and only move for a download.
 */
private fun meterFraction(rate: Long): Float =
    sqrt((rate.toFloat() / METER_CEILING).coerceIn(0f, 1f))

/**
 * What is about to happen, before it happens.
 *
 * Idle used to be the emptiest state on the screen, which is backwards: it is
 * the only state where the user still has a decision to make. The topology
 * drawing earns its space here (it is a diagram of the thing being described,
 * next to the words describing it) and the two tiles name the protocol and scan
 * mode that this tap will actually use - the two settings that decide whether
 * the attempt succeeds at all.
 */
@Composable
private fun RoutePreview(profile: ConnectionProfile, tone: Color) {
    AetherCard {
        CardHeader(
            title = stringResource(R.string.passage_route_title),
            subtitle = stringResource(R.string.passage_route),
            icon = Icons.Rounded.Public,
            tint = tone,
        )
        SignalDiagram(tone, verified = false, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile(
                label = stringResource(R.string.meta_protocol),
                value = protocolLabel(profile.protocol),
                icon = Icons.Rounded.Shield,
                tint = tone,
                mono = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StatTile(
                label = stringResource(R.string.scan_mode),
                value = scanLabel(profile.scanMode),
                icon = Icons.Rounded.Tune,
                tint = tone,
                mono = false,
                footnote = scanEta(profile.scanMode),
                modifier = Modifier.weight(1f),
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
