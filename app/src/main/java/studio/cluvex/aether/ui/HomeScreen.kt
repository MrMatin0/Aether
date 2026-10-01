package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherEaseOutExpo
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

internal enum class HomeTab { HOME, DIAGNOSTICS, SETTINGS }
internal data class HomeRoute(val tab: HomeTab = HomeTab.HOME, val page: SettingsPage? = null) {
    init { require(page == null || tab == HomeTab.SETTINGS) }
    val canGoBack: Boolean get() = page != null || tab != HomeTab.HOME
    /** 0 = a tab root, 1 = a settings page. Drives the direction of the transition. */
    val depth: Int get() = if (page != null) 1 else 0
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
    var focusErrors by rememberSaveable { mutableStateOf(false) }
    // The connection tab is a fixed, non-scrolling layout now (see
    // ConnectionHome), so only Settings keeps a scroll position.
    val settingsScroll = rememberScrollState()
    val pages = rememberSaveableStateHolder()
    val haptics = LocalHapticFeedback.current
    val editable = state is ConnectionState.Idle || state is ConnectionState.Error
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalReducedMotion.current
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
                        AnimatedContent(
                            targetState = route,
                            contentKey = { it.savedValues().joinToString("/") },
                            transitionSpec = {
                                when {
                                    reduced -> EnterTransition.None togetherWith ExitTransition.None
                                    // Tab swaps are lateral: cross-fade, no travel.
                                    targetState.depth == initialState.depth ->
                                        fadeIn(tween(AetherDur.Quick, easing = AetherEaseOut)) togetherWith
                                            fadeOut(tween(AetherDur.Quick / 2))
                                    else -> {
                                        // Deeper enters from the END edge, which is the left in Persian.
                                        val sign = (if (targetState.depth > initialState.depth) 1 else -1) * (if (rtl) -1 else 1)
                                        (slideInHorizontally(tween(AetherDur.Base, easing = AetherEaseOutExpo)) { it / 8 * sign } +
                                            fadeIn(tween(AetherDur.Base, easing = AetherEaseOut))) togetherWith
                                            (slideOutHorizontally(tween(AetherDur.Base, easing = AetherEaseOutExpo)) { -it / 12 * sign } +
                                                fadeOut(tween(AetherDur.Quick)))
                                    }
                                }
                            },
                            label = "home-route",
                        ) { target ->
                            pages.SaveableStateProvider(target.savedValues().joinToString("/")) {
                                val page = target.page
                                when {
                                    page != null -> SettingsPageBody(
                                        page, state, profile, onProfileChange, editable, Modifier.fillMaxSize(),
                                    )
                                    target.tab == HomeTab.SETTINGS -> SettingsHub(
                                        state = state,
                                        profile = profile,
                                        onProfileChange = onProfileChange,
                                        onOpen = { route = route.open(it) },
                                        scrollState = settingsScroll,
                                    )
                                    target.tab == HomeTab.DIAGNOSTICS -> DiagnosticsDestination(
                                        focusErrors = focusErrors,
                                        onFocusConsumed = { focusErrors = false },
                                    )
                                    else -> ConnectionHome(state, profile, connectedSince, ipInfo, ipLoading,
                                        editable = editable,
                                        onProfileChange = onProfileChange,
                                        onToggleConnection = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleConnection()
                                        },
                                        onOpenDiagnostics = {
                                            focusErrors = true
                                            route = route.select(HomeTab.DIAGNOSTICS)
                                        })
                                }
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (route.tab != HomeTab.HOME) {
                                TextButton(onClick = { route = route.select(HomeTab.HOME) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.nav_connection) + " · " + stringResource(connectionStatusLabel(state)))
                                }
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
            if (route.tab != HomeTab.HOME) Text(
                // Inside a page the eyebrow names its group, not just "Settings".
                route.page?.let { stringResource(it.group.label) } ?: stringResource(R.string.app_name),
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

@Composable
private fun DiagnosticsDestination(focusErrors: Boolean, onFocusConsumed: () -> Unit) {
    DiagnosticsPanel(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        focusErrors = focusErrors,
        onFocusConsumed = onFocusConsumed,
    )
}
