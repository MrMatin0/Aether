package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
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
import studio.cluvex.aether.ui.components.TrafficPanel
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * THE CONNECTION TAB.
 *
 * WHAT WAS WRONG WITH THE OLD ONE
 *
 * It was an editorial column, and a genuinely tidy one: a state chip, a
 * display-sized sentence, a static topology drawing, four stacked step rows
 * while connecting, and a flat ledger of six equally weighted values once
 * connected. It read top to bottom in one pass.
 *
 * What it could not do is RANK, and it put the primary control somewhere else
 * entirely:
 *
 *   - The largest object on the screen was a DECORATION. The signal diagram is
 *     112dp of canvas that changes exactly once (a dash becomes a tick), and it
 *     sat above the fold in every single state, including the two states where
 *     the user is watching for something to happen.
 *   - The one thing the user came here to do lived in the dock, in a button that
 *     is visually identical whether it is about to protect them or about to drop
 *     their tunnel.
 *   - Connecting took over the whole viewport: four full-width rows, one per
 *     phase, each 48dp+, to communicate a single integer between 0 and 3.
 *   - The ledger ranked nothing. The exit IP - the fact that proves traffic is
 *     actually leaving through the tunnel, and the first thing anyone checks -
 *     had exactly the same weight as the protocol name.
 *   - Idle was almost empty. No IP, no protocol, no scan mode: nothing about the
 *     connection that is about to be attempted.
 *
 * Meanwhile [ConnectButton], [ConnectionMeta] and [TrafficPanel] were already in
 * the tree, already designed for exactly these jobs, and reachable from nothing.
 *
 * WHAT THIS IS NOW
 *
 *   1. ONE HERO CONTROL. The orb is the button. Its arc is determinate and
 *      driven by the real controller stage, the state word and the elapsed clock
 *      sit at its optical centre, and it already honours reduced motion. Tapping
 *      the biggest state-coloured circle on a VPN screen is what everybody tries
 *      first, so it had better be the control.
 *   2. ONE ANSWER, IN WORDS. Headline plus one supporting line, centred under
 *      the orb. Short word inside the ring, full sentence outside it - the ring
 *      is glanceable, the sentence is readable.
 *   3. PROGRESS AS A TRACK, not a list. Four segments, the current phase named
 *      once, a monotonic clock beside it. Same information, one card instead of
 *      a screenful, and the scan-time note is right there so a long wait is a
 *      known cost rather than a hang.
 *   4. EVIDENCE WITH A HIERARCHY. Connected: the exit IP as a headline with its
 *      flag, then protocol / location / latency / uptime as tiles, then session
 *      volume. Disconnected: the SAME card, answering the other question people
 *      actually have - what address am I coming from right now.
 *   5. STATE-SPECIFIC CONTENT. Every state gets the block it needs and none of
 *      the blocks it does not: the route preview only exists before a connection,
 *      the failure notice only exists after one, telemetry only exists while
 *      there is a session to measure.
 *
 * Nothing outside this tab is touched. [connectionStep] and
 * [formatSessionUptime] keep their exact contracts, because the presentation
 * tests own them.
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
        StateChip(state, tone)

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
        Text(
            connectionHint(state, profile),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (step != null) {
            PhaseTrack(step, tone, elapsed)
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
            ConnectionMeta(
                connected = true,
                connectedSince = connectedSince,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
            )
            Spacer(Modifier.height(12.dp))
            TrafficPanel(connectedSince = connectedSince)
            Spacer(Modifier.height(28.dp))
        } else if (step == null) {
            // Disconnected or failed: the same card answers the other question,
            // which is where the internet currently thinks you are.
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
 * The status chip.
 *
 * Kept small and kept at the top: it is the thing a screen reader should reach
 * first, and it is the only element that is announced on every state change
 * (polite live region, merged descendants, so it is one announcement and not
 * three).
 */
@Composable
private fun StateChip(state: ConnectionState, tone: Color) {
    val icon = when {
        state.isConnected -> Icons.Rounded.Check
        state is ConnectionState.Error -> Icons.Rounded.Warning
        else -> Icons.Rounded.Shield
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
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(connectionStatusLabel(state)),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The four phases as ONE object.
 *
 * This replaces four stacked full-width rows. The information content was a
 * single integer; the cost was a screenful, on the exact screen where the user
 * also wants to see the elapsed time and the reassurance that a long scan is
 * normal. A segmented track carries the same integer, names the phase that is
 * running once, and leaves room for both.
 *
 * The clock is pinned LTR and monospaced: it is an instrument readout, and in
 * the Persian locale a bidi-reordered duration is worse than no duration.
 */
@Composable
private fun PhaseTrack(active: Int, tone: Color, elapsed: String) {
    val labels = listOf(
        R.string.phase_engine,
        R.string.phase_tunnel,
        R.string.phase_verify,
        R.string.phase_ready,
    )
    val current = active.coerceIn(0, labels.lastIndex)
    AetherCard {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.passage_phase_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                elapsed,
                style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, _ ->
                val fill = when {
                    index < current -> tone
                    index == current -> tone.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(fill),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
 * The elapsed clock for an in-flight attempt.
 *
 * Sleeps to the next whole second OF THE ATTEMPT rather than a flat 1000ms, so
 * the error does not accumulate into a digit that shows the same second twice
 * and then skips one. Lifecycle-scoped, so a backgrounded app is not redrawing a
 * counter nobody can see - and stopped entirely when nothing is in flight,
 * because an idle screen has no business running a loop.
 */
@Composable
private fun tickingElapsed(since: Long, running: Boolean): String {
    var now by remember(since) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(since, running, lifecycle) {
        if (!running) return@LaunchedEffect
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
 * under the orb where it can wrap. Same four words the chip and the dock use, so
 * the three never disagree.
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
