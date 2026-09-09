package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
                        NavigationRailItem(
                            selected = route.tab == tab,
                            onClick = { route = route.select(tab) },
                            icon = { Icon(tabIcon(tab), null) },
                            label = { Text(stringResource(tabLabel(tab))) },
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeHeader(route, onBack = { route = route.back() })
                    Box(Modifier.weight(1f).widthIn(max = 880.dp).fillMaxWidth()) {
                        pages.SaveableStateProvider(route.savedValues().joinToString("/")) {
                            val page = route.page
                            when {
                                page != null -> SettingsPageBody(page, state, profile, onProfileChange, editable)
                                route.tab == HomeTab.SETTINGS -> SettingsHub(
                                    !editable, profile, { route = route.open(it) }, scrollState = settingsScroll,
                                )
                                route.tab == HomeTab.DIAGNOSTICS -> DiagnosticsDestination(diagnosticsScroll)
                                else -> ConnectionHome(
                                    state = state,
                                    profile = profile,
                                    connectedSince = connectedSince,
                                    ipInfo = ipInfo,
                                    ipLoading = ipLoading,
                                    onToggleConnection = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleConnection()
                                    },
                                )
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                            if (route.tab != HomeTab.HOME) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TextButton(
                                    onClick = { route = route.select(HomeTab.HOME) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.nav_connection) + " \u00B7 " + stringResource(connectionStatusLabel(state)))
                                }
                            }
                            if (!rail) NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                destinations.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route.tab == tab,
                                        onClick = { route = route.select(tab) },
                                        icon = { Icon(tabIcon(tab), null) },
                                        label = { Text(stringResource(tabLabel(tab))) },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                    )
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
    Row(
        Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (route.page != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.passage_back))
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            if (route.tab != HomeTab.HOME) Text(
                stringResource(if (route.page != null) R.string.nav_settings else R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                route.page?.let { settingsPageTitle(it) }
                    ?: stringResource(if (route.tab == HomeTab.HOME) R.string.app_name else tabLabel(route.tab)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.width(12.dp))
        LanguageToggle(accent = LocalAetherAccents.current.brand)
    }
}

@Composable
private fun DiagnosticsDestination(scrollState: ScrollState) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp)) {
        DiagnosticsPanel(alwaysExpanded = true, consoleMaxHeight = 420.dp)
        Spacer(Modifier.height(40.dp))
    }
}
