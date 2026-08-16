package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.ConnectionMeta
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.PhasePipeline
import studio.cluvex.aether.ui.components.StatusLine
import studio.cluvex.aether.ui.components.TrafficPanel
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.OnSignal

private const val TAB_CONNECTION = 0
private const val TAB_SETTINGS = 1
private const val TAB_DIAGNOSTICS = 2

/**
 * The app shell.
 *
 * WHAT CHANGED AND WHY
 *
 * Old structure: one scrolling column with a big circle, wrapped in a
 * ModalNavigationDrawer that hid diagnostics + share + advanced + about behind
 * a hamburger, PLUS a second copy of the advanced settings in a bottom sheet
 * reachable from a tuning icon. Three problems fell out of that:
 *
 *   1. Discoverability. Nothing on the home screen implied that the answer to
 *      "connected but nothing loads" was behind the hamburger.
 *   2. Duplication. Settings existed in two places with two different expand
 *      states, and the sheet copy needed a bounded 92% viewport and a deferred
 *      first frame just to avoid Material clipping its own content.
 *   3. Cost. A drawer composes its content while closed, which is why 1.2.2 had
 *      to add a drawerVisible guard. Destinations do not have that problem:
 *      what is not on screen is not composed.
 *
 * New structure: three destinations on a bottom bar, with the primary action
 * pinned directly above it — also a reachability fix, since the old 220dp
 * circle sat in the upper half of the screen where a thumb cannot go.
 *
 * 1.3.0 adds the in-app language pill to the header and four panels to the
 * Settings destination (language, saved setups, automation, session history).
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
    val mode = when {
        state.isConnected -> ButtonMode.CONNECTED
        state.isBusy -> ButtonMode.BUSY
        state is ConnectionState.Error -> ButtonMode.ERROR
        else -> ButtonMode.IDLE
    }
    val accent = accentFor(mode)
    var tab by rememberSaveable { mutableStateOf(TAB_CONNECTION) }

    // Engine arguments are read at launch, so editing them mid-session would
    // silently do nothing until the next connect. Idle and Error are the only
    // states where a change can actually take effect.
    val settingsEnabled = state is ConnectionState.Idle || state is ConnectionState.Error

    Box(modifier = modifier.fillMaxSize()) {
        AmbientBackground(accent = accent, active = state.isConnected)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            TopBar(mode = mode, accent = accent)

            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    fadeIn(tween(AetherDur.Base, easing = AetherEaseOut)) togetherWith
                        fadeOut(tween(AetherDur.Quick))
                },
                label = "tab",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    TAB_SETTINGS -> SettingsTab(
                        state = state,
                        profile = profile,
                        onProfileChange = onProfileChange,
                        settingsEnabled = settingsEnabled,
                    )
                    TAB_DIAGNOSTICS -> DiagnosticsTab()
                    else -> ConnectionTab(
                        state = state,
                        profile = profile,
                        mode = mode,
                        accent = accent,
                        connectedSince = connectedSince,
                        ipInfo = ipInfo,
                        ipLoading = ipLoading,
                        onToggleConnection = onToggleConnection,
                    )
                }
            }

            PrimaryAction(mode = mode, accent = accent, onClick = onToggleConnection)
            TabBar(selected = tab, accent = accent, onSelect = { tab = it })
        }
    }
}

// ---------------------------------------------------------------- chrome ----

/**
 * 1.3.0: the header carries the language pill next to the state pill.
 *
 * It belongs HERE, not in Settings. Someone who opened the app and cannot read
 * it has no way to know which of three destinations, and which of its sections,
 * would let them fix that. Two characters at the top of the first screen do.
 *
 * The title block keeps weight(1f) and the tagline is now clipped to one line,
 * so a long translated tagline can never push the pills off the edge.
 */
@Composable
private fun TopBar(mode: ButtonMode, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        LanguageToggle(accent = accent)
        Spacer(Modifier.width(8.dp))
        StatePill(mode = mode, accent = accent)
    }
}

@Composable
private fun StatePill(mode: ButtonMode, accent: Color) {
    val label = stringResource(
        when (mode) {
            ButtonMode.IDLE -> R.string.pill_off
            ButtonMode.BUSY -> R.string.pill_working
            ButtonMode.CONNECTED -> R.string.pill_secure
            ButtonMode.ERROR -> R.string.pill_failed
        },
    )
    val tint by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "pill",
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = AetherMetaLabel, color = tint, maxLines = 1)
    }
}

@Composable
private fun PrimaryAction(mode: ButtonMode, accent: Color, onClick: () -> Unit) {
    val label = stringResource(
        when (mode) {
            ButtonMode.IDLE -> R.string.action_connect
            ButtonMode.BUSY -> R.string.action_cancel
            ButtonMode.CONNECTED -> R.string.action_disconnect
            ButtonMode.ERROR -> R.string.action_retry
        },
    )
    val icon: ImageVector = when (mode) {
        ButtonMode.IDLE -> Icons.Rounded.PowerSettingsNew
        ButtonMode.BUSY -> Icons.Rounded.Close
        ButtonMode.CONNECTED -> Icons.Rounded.Bolt
        ButtonMode.ERROR -> Icons.Rounded.Refresh
    }
    val filled = mode == ButtonMode.CONNECTED
    val tint by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "action",
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp)
            .height(58.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (filled) tint else Color.Transparent,
        contentColor = if (filled) OnSignal else tint,
        border = if (filled) null else BorderStroke(1.5.dp, tint.copy(alpha = 0.5f)),
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
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TabBar(selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Hairline(alpha = 0.7f)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            TabItem(
                label = stringResource(R.string.nav_connection),
                active = selected == TAB_CONNECTION,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(TAB_CONNECTION) },
            )
            TabItem(
                label = stringResource(R.string.nav_settings),
                active = selected == TAB_SETTINGS,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(TAB_SETTINGS) },
            )
            TabItem(
                label = stringResource(R.string.nav_diagnostics),
                active = selected == TAB_DIAGNOSTICS,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(TAB_DIAGNOSTICS) },
            )
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
        label = "tabtint",
    )
    val indicator by animateDpAsState(
        targetValue = if (active) 20.dp else 0.dp,
        animationSpec = tween(AetherDur.Base, easing = AetherEaseOut),
        label = "tabind",
    )
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(indicator)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent),
        )
    }
}

// ------------------------------------------------------------ destinations --

@Composable
private fun ConnectionTab(
    state: ConnectionState,
    profile: ConnectionProfile,
    mode: ButtonMode,
    accent: Color,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        ConnectButton(mode = mode, onClick = onToggleConnection)
        Spacer(Modifier.height(20.dp))

        StatusLine(
            title = stateTitle(state),
            subtitle = stateSubtitle(state, profile),
            accent = when (mode) {
                ButtonMode.CONNECTED, ButtonMode.ERROR -> accent
                else -> MaterialTheme.colorScheme.onBackground
            },
        )

        if (step >= 0) {
            Spacer(Modifier.height(24.dp))
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
            Spacer(Modifier.height(12.dp))
            ElapsedCounter(active = busy)
        }

        if (state.isConnected) {
            Spacer(Modifier.height(30.dp))
            TrafficPanel(connectedSince = connectedSince)
        }

        Spacer(Modifier.height(30.dp))
        ConnectionMeta(
            connected = state.isConnected,
            connectedSince = connectedSince,
            ipInfo = ipInfo,
            ipLoading = ipLoading,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A visible clock while the engine works.
 *
 * Without it, a four-minute Ironclad scan and a hung process look identical,
 * and people force-quit the app mid-scan.
 */
@Composable
private fun ElapsedCounter(active: Boolean) {
    var seconds by remember(active) { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                delay(1000L)
                seconds += 1
            }
        }
    }
    Text(
        text = stringResource(
            R.string.busy_elapsed,
            "%d:%02d".format(seconds / 60, seconds % 60),
        ),
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = AetherMono,
            textDirection = TextDirection.Ltr,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The Settings destination.
 *
 * Order is deliberate and follows "how likely is this to be the reason you came
 * here":
 *
 *   1. Language — if the app is in a language you cannot read, every other
 *      section below is unusable, so it can never sit under them.
 *   2. Engine settings (AdvancedPanel) — the controls that decide whether a
 *      connection succeeds at all.
 *   3. Saved setups — the settings that worked, re-applied in one tap. Gated on
 *      [settingsEnabled] like the engine settings, because a setup rewrites the
 *      same arguments and those are only read at launch.
 *   4. Automation — when Aether connects by itself, and what it keeps.
 *   5. Session history — what the tunnel actually did, after the fact.
 *   6. Share / About — occasional, so last.
 */
@Composable
private fun SettingsTab(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    settingsEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        if (!settingsEnabled) {
            Spacer(Modifier.height(4.dp))
            NoticeBar(text = stringResource(R.string.settings_locked))
        }
        LanguagePanel()
        AdvancedPanel(
            profile = profile,
            onProfileChange = onProfileChange,
            enabled = settingsEnabled,
        )
        PresetsPanel(
            profile = profile,
            onProfileChange = onProfileChange,
            enabled = settingsEnabled,
        )
        AutomationPanel()
        HistoryPanel()
        SharePanel(
            state = state,
            profile = profile,
            onProfileChange = onProfileChange,
        )
        AboutPanel()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DiagnosticsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 6.dp),
    ) {
        DiagnosticsPanel(alwaysExpanded = true, consoleMaxHeight = 340.dp)
        Spacer(Modifier.height(32.dp))
    }
}

// ----------------------------------------------------------------- copy -----

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

@Composable
private fun stateSubtitle(state: ConnectionState, profile: ConnectionProfile): String =
    when (state) {
        is ConnectionState.Idle -> stringResource(R.string.tap_to_connect)
        // The exit IP + flag lives in the ledger below, so the subtitle never
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
