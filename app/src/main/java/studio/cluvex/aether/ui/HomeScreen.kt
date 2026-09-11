package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.LocalAetherAccents

internal enum class HomeTab { HOME, DIAGNOSTICS, SETTINGS }
internal data class HomeRoute(val tab: HomeTab = HomeTab.HOME, val page: SettingsPage? = null) {
    init { require(page == null || tab == HomeTab.SETTINGS) }
    val canGoBack: Boolean get() = page != null || tab != HomeTab.HOME
    fun select(tab: HomeTab) = HomeRoute(tab)
    fun open(page: SettingsPage) = HomeRoute(HomeTab.SETTINGS, page)
    fun back() = if (page != null) HomeRoute(HomeTab.SETTINGS) else HomeRoute()
    fun savedValues() = listOf(tab.name, page?.name.orEmpty())
    companion object {
        fun restore(values: List<String>): HomeRoute {
            val tab = HomeTab.entries.find { it.name == values.getOrNull(0) } ?: HomeTab.HOME
            val page = SettingsPage.entries.find { it.name == values.getOrNull(1) }
            return HomeRoute(tab, page.takeIf { tab == HomeTab.SETTINGS })
        }
    }
}
private val HomeRouteSaver = listSaver<HomeRoute, String>(save = { it.savedValues() }, restore = { HomeRoute.restore(it) })
internal fun connectionActionLabel(state: ConnectionState): Int = when {
    state is ConnectionState.Disconnecting -> R.string.state_disconnecting
    state.isConnected -> R.string.action_disconnect
    state.isBusy -> R.string.action_cancel
    state is ConnectionState.Error -> R.string.action_retry
    else -> R.string.action_connect
}
internal fun connectionStatusLabel(state: ConnectionState): Int = when {
    state.isConnected -> R.string.passage_verified
    state is ConnectionState.Error -> R.string.pill_failed
    state is ConnectionState.Disconnecting -> R.string.state_disconnecting
    state.isBusy -> R.string.pill_working
    else -> R.string.state_idle
}
private val destinations = listOf(HomeTab.HOME, HomeTab.SETTINGS, HomeTab.DIAGNOSTICS)
private fun tabLabel(tab: HomeTab) = when (tab) {
    HomeTab.HOME -> R.string.nav_connection
    HomeTab.SETTINGS -> R.string.nav_settings
    HomeTab.DIAGNOSTICS -> R.string.nav_diagnostics
}
private fun tabIcon(tab: HomeTab): ImageVector = when (tab) {
    HomeTab.HOME -> Icons.Rounded.Shield
    HomeTab.SETTINGS -> Icons.Rounded.Tune
    HomeTab.DIAGNOSTICS -> Icons.Rounded.Terminal
}

@Composable
fun HomeScreen(
    state: ConnectionState, profile: ConnectionProfile, connectedSince: Long?,
    ipInfo: IpEndpoint?, ipLoading: Boolean, onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit, modifier: Modifier = Modifier,
) {
    var route by rememberSaveable(stateSaver = HomeRouteSaver) { mutableStateOf(HomeRoute()) }
    val homeScroll = rememberScrollState()
    val diagnosticsScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val pages = rememberSaveableStateHolder()
    val haptics = LocalHapticFeedback.current
    val editable = state is ConnectionState.Idle || state is ConnectionState.Error
    BackHandler(route.canGoBack) { route = route.back() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val rail = maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.5f
            Row(Modifier.fillMaxSize()) {
                if (rail) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(32.dp))
                    Icon(Icons.Rounded.Shield, null, Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(40.dp))
                    destinations.forEach { tab ->
                        NavigationRailItem(selected = route.tab == tab, onClick = { route = route.select(tab) },
                            icon = { Icon(tabIcon(tab), null) }, label = { Text(stringResource(tabLabel(tab))) },
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeHeader(route, onBack = { route = route.back() })
                    Box(Modifier.weight(1f).widthIn(max = 880.dp).fillMaxWidth()) {
                        pages.SaveableStateProvider(route.savedValues().joinToString("/")) {
                            val page = route.page
                            when {
                                page != null -> SettingsPageBody(page, state, profile, onProfileChange, editable)
                                route.tab == HomeTab.SETTINGS -> SettingsHub(!editable, profile,
                                    { route = route.open(it) }, scrollState = settingsScroll)
                                route.tab == HomeTab.DIAGNOSTICS -> DiagnosticsDestination(diagnosticsScroll)
                                else -> ConnectionHome(state, profile, connectedSince, ipInfo, ipLoading, homeScroll,
                                    onToggleConnection = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleConnection()
                                    },
                                    // Straight to the page that owns protocol and scan mode,
                                    // not to a hub the user then has to read.
                                    onOpenEngine = { route = route.open(SettingsPage.CONNECTION) },
                                    onOpenDiagnostics = { route = route.select(HomeTab.DIAGNOSTICS) })
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (route.tab == HomeTab.HOME) ConnectionDock(state) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleConnection()
                            } else TextButton(onClick = { route = route.select(HomeTab.HOME) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.nav_connection) + " \u00B7 " + stringResource(connectionStatusLabel(state)))
                            }
                            if (!rail) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0)) {
                                destinations.forEach { tab ->
                                    NavigationBarItem(selected = route.tab == tab, onClick = { route = route.select(tab) },
                                        icon = { Icon(tabIcon(tab), null) }, label = { Text(stringResource(tabLabel(tab))) },
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(route: HomeRoute, onBack: () -> Unit) {
    Row(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (route.page != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.passage_back)) }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            // Inside a settings destination the eyebrow names the section it came
            // from, so a page called "Transport" is not floating on its own.
            if (route.tab != HomeTab.HOME) Text(
                stringResource(if (route.page != null) R.string.nav_settings else R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(route.page?.let { settingsPageTitle(it) } ?: stringResource(if (route.tab == HomeTab.HOME) R.string.app_name else tabLabel(route.tab)),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.width(12.dp))
        LanguageToggle(accent = LocalAetherAccents.current.brand)
    }
}

/**
 * The diagnostics destination.
 *
 * EDGE-TO-EDGE: the root inserts the status bar, the navigation bar and the IME,
 * which covers the vertical edges and nothing else. A display cutout in
 * landscape and a rounded-corner inset both land on the HORIZONTAL edges, and
 * this destination is the one screen whose content runs right up to them - a
 * monospace console with no natural margin, so a clipped column loses
 * characters rather than whitespace.
 *
 * safeDrawing's horizontal side is resolved by the layout direction, so one
 * modifier is correct in Persian and English; a mirrored start/end pair would
 * be a second thing to keep in sync.
 */
@Composable
private fun DiagnosticsDestination(scrollState: ScrollState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp),
    ) {
        DiagnosticsPanel(alwaysExpanded = true, consoleMaxHeight = 420.dp)
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The connection tab's dock.
 *
 * WHAT THIS REPLACES: a full-width, 60dp-tall primary button. That was the right
 * shape while the tab had no control of its own, and the wrong one the moment the
 * orb became the control - two objects, same intent, 40dp apart, the larger of
 * which is not the primary. It was also styled by STATE rather than by weight
 * (filled to connect, outlined to disconnect), so the most destructive action on
 * the screen looked like the quietest.
 *
 * Now it is a status strip: the state dot and the same status words the chip and
 * the ring use, plus one secondary-weight button. It exists so the action is
 * still reachable once the orb is scrolled off, and it is deliberately
 * FIXED-HEIGHT: the nav bar sits directly underneath, and a dock that changed
 * height per state would shuffle the tab bar while a connection came up.
 *
 * The label still comes from [connectionActionLabel], so Disconnecting offers
 * nothing to tap and every busy stage offers cancellation rather than a second
 * connection.
 */
@Composable
private fun ConnectionDock(state: ConnectionState, onClick: () -> Unit) {
    val accents = LocalAetherAccents.current
    val tone = when {
        state.isConnected -> accents.protected
        state is ConnectionState.Error -> accents.failed
        state.isBusy -> accents.working
        else -> accents.neutral
    }
    val label = stringResource(connectionActionLabel(state))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(tone, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.nav_connection),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                stringResource(connectionStatusLabel(state)),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (state.isConnected || state.isBusy) {
            OutlinedButton(
                onClick = onClick,
                enabled = state !is ConnectionState.Disconnecting,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        } else {
            Button(
                onClick = onClick,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(if (state is ConnectionState.Error) Icons.Rounded.Refresh else Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
