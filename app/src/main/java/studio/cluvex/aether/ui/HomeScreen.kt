package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.ConnectionMeta
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.components.PageHeader
import studio.cluvex.aether.ui.components.PhasePipeline
import studio.cluvex.aether.ui.components.StatusHint
import studio.cluvex.aether.ui.components.TrafficPanel
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.components.contentColorForTone
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

private const val DEST_HOME = 0
private const val DEST_DIAGNOSTICS = 1
private const val DEST_SETTINGS = 2

private const val PAGE_PREFIX = "page:"
private const val DEST_PREFIX = "dest:"

/** The page margin. One value, so nothing in the app is 2dp off anything else. */
private val PAGE = 20.dp

/**
 * The app shell.
 *
 * WHAT CHANGED AND WHY
 *
 * The previous shell was already a big improvement on the drawer it replaced:
 * three destinations on a bottom bar with the primary action pinned above them,
 * which fixed both discoverability (nothing implied that the answer to
 * "connected but nothing loads" was behind a hamburger) and reachability (a
 * 220dp ring in the upper half of the screen where a thumb cannot go).
 *
 * The pass before this one turned Settings from a ~3500dp single scroll into a
 * six-entry HUB, and turned every destination from a column of hairline-separated
 * rows into a column of cards, so the eye can tell which facts belong together.
 *
 * The dock (action capsule + nav bar) is one floating object at the bottom, and
 * it is hidden on settings subpages: a full page has its own back affordance,
 * and leaving a nav bar under it invites the classic "which back do I press".
 *
 * WHAT THIS PASS FIXES — all three are things that only show up once you USE the
 * shell rather than look at it:
 *
 *   1. BACK ONLY WORKED IN ONE PLACE. The BackHandler was enabled exclusively
 *      inside a settings subpage. Everywhere else the system back gesture fell
 *      through to the Activity and CLOSED THE APP. So the flow "tap Diagnostics
 *      because the connection is stuck, read the log, swipe back to watch the
 *      orb" ended with the app gone, mid-connect. Back now unwinds the path the
 *      user actually walked: subpage → hub → home → leave. Nothing about a
 *      bottom nav bar suggests it is a one-way trip, and Android users do not
 *      reach for an on-screen control to go back.
 *   2. EVERY DESTINATION FORGOT ITS SCROLL. Each one called rememberScrollState
 *      inside the AnimatedContent, whose content lambda is a fresh composition
 *      per route key, so nothing survived leaving a destination. Scroll the
 *      engine log to the interesting part, glance at Home, come back — top of
 *      the log again. The states are hoisted out here, where the shell keeps
 *      them, so they survive tab switches AND rotation.
 *   3. THE CONNECT TIMER RESTARTED. Same root cause, much worse consequence: see
 *      [rememberBusyAnchor].
 */
@Composable
fun HomeScreen(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val mode = when {
        state.isConnected -> ButtonMode.CONNECTED
        state.isBusy -> ButtonMode.BUSY
        state is ConnectionState.Error -> ButtonMode.ERROR
        else -> ButtonMode.IDLE
    }
    val accent = accentFor(mode)

    var dest by rememberSaveable { mutableIntStateOf(DEST_HOME) }
    // Stored as the enum NAME (String is saveable, an enum is not) so the open
    // page survives rotation and process death.
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    val page = openPage?.let(::settingsPageOrNull)
    val inSubpage = dest == DEST_SETTINGS && page != null

    // The whole navigation stack, in one place. Enabled whenever there is
    // somewhere in the app to go back TO, and disabled on the connection screen
    // so back there still means "leave", which is what people expect from a
    // start destination.
    BackHandler(enabled = inSubpage || dest != DEST_HOME) {
        if (inSubpage) openPage = null else dest = DEST_HOME
    }

    // Owned by the shell, not by the destinations: a destination that is not on
    // screen has no composition to remember anything in, so anything the user
    // would be annoyed to lose has to live out here.
    val connectionScroll = rememberScrollState()
    val diagnosticsScroll = rememberScrollState()
    val hubScroll = rememberScrollState()
    val busyAnchor = rememberBusyAnchor(state.isBusy)

    // Engine arguments are read at launch, so editing them mid-session would
    // silently do nothing until the next connect. Idle and Error are the only
    // states where a change can actually take effect.
    val settingsEnabled = state is ConnectionState.Idle || state is ConnectionState.Error

    // Connecting is not instant and the visual answer arrives a beat later, so
    // the tap itself gets a physical acknowledgement. Both entry points (the orb
    // and the dock capsule) go through here, so the feel is identical.
    val haptics = LocalHapticFeedback.current
    val onPrimaryAction: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onToggleConnection()
    }

    val routeKey = if (inSubpage) "$PAGE_PREFIX${page?.name}" else "$DEST_PREFIX$dest"
    val routeIn = aetherDuration(AetherDur.Base)
    val routeOut = aetherDuration(AetherDur.Quick)

    Box(modifier = modifier.fillMaxSize()) {
        AmbientBackground(accent = accent, active = state.isConnected)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (inSubpage && page != null) {
                PageHeader(
                    title = settingsPageTitle(page),
                    subtitle = settingsPageSubtitle(page),
                    onBack = { openPage = null },
                    modifier = Modifier.padding(
                        start = PAGE - 4.dp,
                        end = PAGE,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                )
            } else {
                RootBar(mode = mode, accent = accent)
            }

            AnimatedContent(
                targetState = routeKey,
                transitionSpec = {
                    (
                        fadeIn(tween(routeIn, easing = AetherEaseOut)) +
                            slideInVertically(
                                tween(routeIn, easing = AetherEaseOut),
                            ) { it / 22 }
                        ) togetherWith fadeOut(tween(routeOut))
                },
                label = "route",
                modifier = Modifier.weight(1f),
            ) { key ->
                // Resolved from the KEY, never from the live state. Pressing back
                // clears openPage immediately while the outgoing page is still
                // animating out; reading the state here made that outgoing frame
                // fall through to the home screen for the length of the
                // transition, which looked like the orb flashing behind the page
                // that was leaving.
                val keyPage = remember(key) {
                    if (key.startsWith(PAGE_PREFIX)) {
                        settingsPageOrNull(key.removePrefix(PAGE_PREFIX))
                    } else {
                        null
                    }
                }
                when {
                    keyPage != null -> SettingsPageBody(
                        page = keyPage,
                        state = state,
                        profile = profile,
                        onProfileChange = onProfileChange,
                        settingsEnabled = settingsEnabled,
                    )
                    key == "$DEST_PREFIX$DEST_SETTINGS" -> SettingsHub(
                        locked = !settingsEnabled,
                        onOpen = { openPage = it.name },
                        scrollState = hubScroll,
                    )
                    key == "$DEST_PREFIX$DEST_DIAGNOSTICS" -> DiagnosticsDestination(
                        scrollState = diagnosticsScroll,
                    )
                    else -> ConnectionDestination(
                        state = state,
                        profile = profile,
                        mode = mode,
                        accent = accent,
                        connectedSince = connectedSince,
                        ipInfo = ipInfo,
                        ipLoading = ipLoading,
                        busyAnchor = busyAnchor,
                        scrollState = connectionScroll,
                        onToggleConnection = onPrimaryAction,
                    )
                }
            }

            if (!inSubpage) {
                Dock(
                    mode = mode,
                    accent = accent,
                    brand = accents.brand,
                    selected = dest,
                    onSelect = { target ->
                        // Clearing the open page on every destination change keeps
                        // "Settings" in the nav bar meaning the hub, not whichever
                        // page happened to be open the last time.
                        openPage = null
                        dest = target
                    },
                    onPrimaryAction = onPrimaryAction,
                )
            }
        }
    }
}

/** Never throws on an unknown name: a saved page can outlive a renamed enum. */
private fun settingsPageOrNull(name: String): SettingsPage? =
    runCatching { SettingsPage.valueOf(name) }.getOrNull()

// ---------------------------------------------------------------- chrome ----

/**
 * The root header.
 *
 * The tagline is gone. It was decoration competing for the only horizontal space
 * the language switch and the state chip have, and on a 360dp phone in fa it
 * ellipsised to nothing useful anyway. What the header carries now is exactly two
 * controls and one fact.
 *
 * The language switch belongs HERE, not in Settings. Someone who opened the app
 * and cannot read it has no way to know which destination, and which of its
 * pages, would let them fix that. Two characters at the top of the first screen
 * do. It is tinted with the BRAND colour, not the state colour: it is a control,
 * and after this redesign state colour means only one thing.
 */
@Composable
private fun RootBar(mode: ButtonMode, accent: Color) {
    val accents = LocalAetherAccents.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PAGE, end = PAGE - 6.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LanguageToggle(accent = accents.brand)
        Spacer(Modifier.width(8.dp))
        StateChip(mode = mode, accent = accent)
    }
}

@Composable
private fun StateChip(mode: ButtonMode, accent: Color) {
    val label = stateLabel(mode)
    val tint by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "chip",
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = AetherMetaLabel, color = tint, maxLines = 1)
    }
}

/**
 * The dock: the primary action and the destinations, as one floating object.
 *
 * Keeping the action visible on every destination is deliberate — the reason to
 * open Diagnostics is usually to then retry — but it is hidden on settings
 * subpages, where a nav bar under a page that has its own back affordance is
 * just two competing ways out.
 */
@Composable
private fun Dock(
    mode: ButtonMode,
    accent: Color,
    brand: Color,
    selected: Int,
    onSelect: (Int) -> Unit,
    onPrimaryAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        PrimaryAction(mode = mode, accent = accent, brand = brand, onClick = onPrimaryAction)
        Spacer(Modifier.height(10.dp))
        NavBar(selected = selected, brand = brand, onSelect = onSelect)
    }
}

/**
 * The primary action.
 *
 * FILLED with the brand colour when the action moves you forward (connect,
 * retry), OUTLINED in the current state colour when it stops something that is
 * already running (cancel, disconnect). The old button filled itself with the
 * state colour when connected, which produced a solid green DISCONNECT — the
 * loudest element on the screen telling you to undo the thing you wanted.
 */
@Composable
private fun PrimaryAction(
    mode: ButtonMode,
    accent: Color,
    brand: Color,
    onClick: () -> Unit,
) {
    val label = actionLabel(mode)
    val icon: ImageVector = when (mode) {
        ButtonMode.IDLE -> Icons.Rounded.PowerSettingsNew
        ButtonMode.BUSY -> Icons.Rounded.Close
        ButtonMode.CONNECTED -> Icons.Rounded.Bolt
        ButtonMode.ERROR -> Icons.Rounded.Refresh
    }
    val forward = mode == ButtonMode.IDLE || mode == ButtonMode.ERROR
    val tint by animateColorAsState(
        targetValue = if (forward) brand else accent,
        animationSpec = tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut),
        label = "action",
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(AetherRadius.Dock),
        color = if (forward) tint else Color.Transparent,
        contentColor = if (forward) contentColorForTone(tint) else tint,
        border = if (forward) null else BorderStroke(1.5.dp, tint.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * The destination bar.
 *
 * Icons are new and they are not decoration: the person most likely to be lost
 * in this app is someone who opened it in a language they cannot read, and a
 * gear, a shield and a bug are readable in every language.
 */
@Composable
private fun NavBar(selected: Int, brand: Color, onSelect: (Int) -> Unit) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(AetherRadius.Dock)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accents.dock)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            label = stringResource(R.string.nav_home),
            icon = Icons.Rounded.Shield,
            active = selected == DEST_HOME,
            brand = brand,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DEST_HOME) },
        )
        NavItem(
            label = stringResource(R.string.nav_diagnostics),
            icon = Icons.Rounded.BugReport,
            active = selected == DEST_DIAGNOSTICS,
            brand = brand,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DEST_DIAGNOSTICS) },
        )
        NavItem(
            label = stringResource(R.string.nav_settings),
            icon = Icons.Rounded.Settings,
            active = selected == DEST_SETTINGS,
            brand = brand,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DEST_SETTINGS) },
        )
    }
}

/**
 * One destination.
 *
 * selectable() with Role.Tab rather than clickable(): the tint and the pill are
 * the ONLY signal that a destination is current, and neither exists for a screen
 * reader. This makes the bar announce "Settings, tab, 3 of 3, selected" instead
 * of three identical buttons.
 */
@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    brand: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val swap = aetherDuration(AetherDur.Quick)
    val content by animateColorAsState(
        targetValue = if (active) brand else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(swap, easing = AetherEaseOut),
        label = "navfg",
    )
    val fill by animateColorAsState(
        targetValue = if (active) brand.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(swap, easing = AetherEaseOut),
        label = "navbg",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AetherRadius.Field))
            .background(fill)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------ destinations --

@Composable
private fun ConnectionDestination(
    state: ConnectionState,
    profile: ConnectionProfile,
    mode: ButtonMode,
    accent: Color,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    busyAnchor: Long?,
    scrollState: ScrollState,
    onToggleConnection: () -> Unit,
) {
    val busy = state.isBusy
    val step = when (state) {
        is ConnectionState.Launching -> 0
        is ConnectionState.Connecting -> 1
        is ConnectionState.Reconnecting -> 1
        is ConnectionState.Verifying -> 2
        else -> -1
    }
    // The orb's arc is driven by the REAL stage, so it grows as work completes
    // instead of spinning at a constant rate for four minutes.
    val progress = when (state) {
        is ConnectionState.Launching -> 0.2f
        is ConnectionState.Connecting -> 0.45f
        is ConnectionState.Reconnecting -> 0.45f
        is ConnectionState.Verifying -> 0.78f
        is ConnectionState.Connected -> 1f
        else -> 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = PAGE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        ConnectButton(
            mode = mode,
            onClick = onToggleConnection,
            // The orb is the primary control and it is drawn on a Canvas, so
            // without these it is an unlabelled box to TalkBack.
            stateLabel = stateTitle(state),
            actionLabel = actionLabel(mode),
            detail = rememberElapsedLabel(busyAnchor),
            progress = progress,
        )
        Spacer(Modifier.height(14.dp))
        StatusHint(text = stateHint(state, profile))

        if (step >= 0) {
            Spacer(Modifier.height(22.dp))
            AetherCard(padding = 16.dp) {
                PhasePipeline(
                    step = step,
                    labels = listOf(
                        stringResource(R.string.phase_engine),
                        stringResource(R.string.phase_tunnel),
                        stringResource(R.string.phase_verify),
                        stringResource(R.string.phase_ready),
                    ),
                    accent = accent,
                    active = busy,
                )
            }
        }

        if (state.isConnected) {
            Spacer(Modifier.height(14.dp))
            TrafficPanel(connectedSince = connectedSince)
        }

        Spacer(Modifier.height(14.dp))
        ConnectionMeta(
            connected = state.isConnected,
            connectedSince = connectedSince,
            ipInfo = ipInfo,
            ipLoading = ipLoading,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DiagnosticsDestination(scrollState: ScrollState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = PAGE, vertical = 4.dp),
    ) {
        DiagnosticsPanel(alwaysExpanded = true, consoleMaxHeight = 360.dp)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * When the current piece of work started, or null when nothing is working.
 *
 * WHY THIS IS SPLIT IN TWO (an anchor here, the ticking in [rememberElapsedLabel])
 *
 * The visible clock inside the orb is what stops a four-minute Ironclad scan and
 * a hung process from looking identical — without it, people force-quit the app
 * mid-scan. Which makes it the last readout in this app that should be allowed to
 * lie, and it did: it counted with a local `seconds` keyed on `remember(active)`,
 * so it restarted from ZERO every time the connection destination left
 * composition (any tab switch) or the Activity was recreated (any rotation).
 * "The scan restarted" is precisely the belief that makes someone kill a scan
 * that was about to succeed.
 *
 * The fix is to stop accumulating and start deriving. What the shell owns is one
 * saved TIMESTAMP, which changes exactly twice per attempt — when work starts and
 * when it stops. The once-a-second tick stays down in the destination that draws
 * it. Hoisting the whole counter up here instead would have worked and cost a
 * recomposition of the entire shell every second while connecting; fixing a state
 * bug by paying for it in frames is not a fix.
 *
 * elapsedRealtime, not currentTimeMillis: a clock correction (routine on a phone
 * that just came out of airplane mode, which is a very normal way to start a VPN
 * session) must not be able to rewind the counter.
 */
@Composable
private fun rememberBusyAnchor(active: Boolean): Long? {
    var startedAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) {
            startedAt = 0L
        } else if (startedAt == 0L) {
            startedAt = SystemClock.elapsedRealtime()
        }
    }
    return if (active && startedAt != 0L) startedAt else null
}

/**
 * A `m:ss` clock counting up from [since], or null when there is nothing to count.
 *
 * The tick sleeps to the next whole second OF THE SESSION rather than a flat
 * `delay(1000)`. A flat delay is late by however long the frame took and the
 * error accumulates, whose visible symptom is a clock that shows the same second
 * twice and then skips one.
 *
 * [formatDuration] pins Locale.US on purpose: AppLocale sets the JVM default to
 * fa, so a plain "%d".format() printed the clock in Persian-Indic digits while
 * the latency and traffic readouts (which already pin Locale.US) stayed Latin.
 * Two numbering systems on one screen, in a monospaced style that only lines up
 * with one of them. Prose keeps the locale's digits; instruments do not.
 */
@Composable
private fun rememberElapsedLabel(since: Long?): String? {
    var now by remember(since) { mutableStateOf(since ?: 0L) }
    LaunchedEffect(since) {
        if (since == null) return@LaunchedEffect
        while (true) {
            val tick = SystemClock.elapsedRealtime()
            now = tick
            val intoSecond = (tick - since).coerceAtLeast(0L) % 1000L
            delay((1000L - intoSecond).coerceIn(1L, 1000L))
        }
    }
    return if (since == null) null else formatDuration((now - since).coerceAtLeast(0L))
}

// ----------------------------------------------------------------- copy -----

/**
 * The coarse state, as shown in the header chip.
 *
 * Two granularities on purpose, and no duplication between them: the chip says
 * WHETHER you are protected (four possible words, readable from across the room)
 * and the orb says WHAT IS HAPPENING right now.
 */
@Composable
private fun stateLabel(mode: ButtonMode): String = stringResource(
    when (mode) {
        ButtonMode.IDLE -> R.string.pill_off
        ButtonMode.BUSY -> R.string.pill_working
        ButtonMode.CONNECTED -> R.string.pill_secure
        ButtonMode.ERROR -> R.string.pill_failed
    },
)

/** What a tap on the orb or the dock capsule will do. */
@Composable
private fun actionLabel(mode: ButtonMode): String = stringResource(
    when (mode) {
        ButtonMode.IDLE -> R.string.action_connect
        ButtonMode.BUSY -> R.string.action_cancel
        ButtonMode.CONNECTED -> R.string.action_disconnect
        ButtonMode.ERROR -> R.string.action_retry
    },
)

/** The precise state, shown inside the orb and read out by TalkBack. */
@Composable
private fun stateTitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> stringResource(R.string.state_idle)
    is ConnectionState.Launching -> stringResource(R.string.state_launching)
    is ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is ConnectionState.Verifying -> stringResource(R.string.state_verifying)
    is ConnectionState.Connected -> stringResource(R.string.state_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.state_reconnecting)
    is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
    is ConnectionState.Error -> stringResource(R.string.state_error)
}

/** Why the state is what it is, and what happens next. */
@Composable
private fun stateHint(state: ConnectionState, profile: ConnectionProfile): String =
    when (state) {
        is ConnectionState.Idle -> stringResource(R.string.tap_to_connect)
        // The exit IP + flag lives in the session card below, so this never
        // leaks the internal 127.0.0.1:port address.
        is ConnectionState.Connected -> stringResource(R.string.tap_to_disconnect)
        is ConnectionState.Launching,
        is ConnectionState.Connecting,
        -> stringResource(R.string.busy_hint, scanLabel(profile.scanMode))
        is ConnectionState.Verifying -> stringResource(R.string.state_verify_hint)
        is ConnectionState.Reconnecting ->
            stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
        is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
        is ConnectionState.Error -> state.message
    }
