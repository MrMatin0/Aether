package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
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

/** Navigation never owns a connection action. A settings page always belongs to its tab. */
internal data class HomeRoute(
    val tab: HomeTab = HomeTab.HOME,
    val page: SettingsPage? = null,
) {
    init {
        require(page == null || tab == HomeTab.SETTINGS)
    }

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

private val HomeRouteSaver = listSaver<HomeRoute, String>(
    save = { it.savedValues() },
    restore = { HomeRoute.restore(it) },
)

/** App shell: navigation, insets and the single connection control. */
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
    var route by rememberSaveable(stateSaver = HomeRouteSaver) { mutableStateOf(HomeRoute()) }
    // Hoisted outside the route composition so switching tabs does not reset scroll.
    val homeScroll = rememberScrollState()
    val diagnosticsScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val editable = state is ConnectionState.Idle || state is ConnectionState.Error
    val haptics = LocalHapticFeedback.current
    BackHandler(enabled = route.canGoBack) { route = route.back() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            HomeHeader(route = route, onBack = { route = route.back() })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Different settings pages must not reuse each other's remembered controls.
                key(route) {
                    val page = route.page
                    when {
                        page != null -> SettingsPageBody(
                            page = page,
                            state = state,
                            profile = profile,
                            onProfileChange = onProfileChange,
                            settingsEnabled = editable,
                        )
                        route.tab == HomeTab.SETTINGS -> SettingsHub(
                            locked = !editable,
                            onOpen = { route = route.open(it) },
                            scrollState = settingsScroll,
                        )
                        route.tab == HomeTab.DIAGNOSTICS -> DiagnosticsDestination(diagnosticsScroll)
                        else -> ConnectionHome(
                            state = state,
                            profile = profile,
                            connectedSince = connectedSince,
                            ipInfo = ipInfo,
                            ipLoading = ipLoading,
                            scrollState = homeScroll,
                            onOpenEngine = { route = route.open(SettingsPage.ENGINE) },
                            onOpenDiagnostics = { route = route.select(HomeTab.DIAGNOSTICS) },
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                ConnectionAction(state = state, onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleConnection()
                })
                HomeNavigation(selected = route.tab, onSelect = { route = route.select(it) })
            }
        }
    }
}

@Composable
private fun HomeHeader(route: HomeRoute, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val page = route.page
        if (page != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.passage_back))
            }
        }
        Text(
            text = if (page != null) settingsPageTitle(page) else stringResource(R.string.app_name),
            style = if (page != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        LanguageToggle(accent = LocalAetherAccents.current.brand)
    }
}

@Composable
private fun DiagnosticsDestination(scrollState: ScrollState) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.nav_diagnostics), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        DiagnosticsPanel(alwaysExpanded = true, consoleMaxHeight = 420.dp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionAction(state: ConnectionState, onClick: () -> Unit) {
    val label = stringResource(when {
        state is ConnectionState.Disconnecting -> R.string.state_disconnecting
        state.isConnected -> R.string.action_disconnect
        state.isBusy -> R.string.action_cancel
        state is ConnectionState.Error -> R.string.action_retry
        else -> R.string.action_connect
    })
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val shape = RoundedCornerShape(18.dp)
    if (state.isConnected || state.isBusy) {
        OutlinedButton(
            onClick = onClick,
            enabled = state !is ConnectionState.Disconnecting,
            shape = shape,
            modifier = modifier,
        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
    } else {
        Button(onClick = onClick, shape = shape, modifier = modifier) {
            Icon(if (state is ConnectionState.Error) Icons.Rounded.Refresh else Icons.Rounded.Shield, null)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HomeNavigation(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val accents = LocalAetherAccents.current
    NavigationBar(
        containerColor = Color.Transparent,
        windowInsets = WindowInsets(0, 0, 0, 0),
        tonalElevation = 0.dp,
    ) {
        HomeTab.entries.forEach { tab ->
            val label = when (tab) {
                HomeTab.HOME -> R.string.nav_home
                HomeTab.DIAGNOSTICS -> R.string.nav_diagnostics
                HomeTab.SETTINGS -> R.string.nav_settings
            }
            val icon = when (tab) {
                HomeTab.HOME -> Icons.Rounded.Shield
                HomeTab.DIAGNOSTICS -> Icons.Rounded.BugReport
                HomeTab.SETTINGS -> Icons.Rounded.Settings
            }
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(icon, null) },
                label = { Text(stringResource(label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accents.brand,
                    selectedTextColor = accents.brand,
                    indicatorColor = accents.brandWash,
                ),
            )
        }
    }
}
