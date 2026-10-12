package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import studio.cluvex.aether.ui.components.accentFor
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherEaseOutExpo
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

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

/**
 * THE APP SHELL, Aurora.
 *
 * A backdrop gradient behind everything, a brand-mark header on the
 * connection tab, and a FLOATING GLASS DOCK instead of a flat navigation bar.
 * The connection tab's dock item carries a live status dot in the state
 * colour, so every other tab still answers "am I protected" at a glance; it
 * replaces the old "Connection - status" text row, which cost a whole line of
 * height on every non-home tab. Wide windows keep the navigation rail.
 */
@Composable
fun HomeScreen(
    state: ConnectionState, profile: ConnectionProfile, connectedSince: Long?,
    ipInfo: IpEndpoint?, ipLoading: Boolean, onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit, modifier: Modifier = Modifier,
) {
    var route by rememberSaveable(stateSaver = HomeRouteSaver) { mutableStateOf(HomeRoute()) }
    var focusErrors by rememberSaveable { mutableStateOf(false) }
    // The connection tab is a fixed, non-scrolling layout (see ConnectionHome),
    // so only Settings keeps a scroll position.
    val settingsScroll = rememberScrollState()
    val pages = rememberSaveableStateHolder()
    val haptics = LocalHapticFeedback.current
    // One rule for the whole shell; the quick controls and the settings pages
    // lock on exactly the same states.
    val editable = quickControlsEditable(state)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalReducedMotion.current
    val accents = LocalAetherAccents.current
    val statusTone = accentFor(buttonMode(state))
    val statusLabel = stringResource(connectionStatusLabel(state))
    BackHandler(route.canGoBack) { route = route.back() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(accents.backdropTop, accents.backdropBottom)))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val rail = maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.5f
            Row(Modifier.fillMaxSize()) {
                if (rail) NavigationRail(containerColor = accents.dock) {
                    Spacer(Modifier.height(28.dp))
                    BrandMark(40.dp)
                    Spacer(Modifier.height(36.dp))
                    destinations.forEach { tab ->
                        val home = tab == HomeTab.HOME
                        NavigationRailItem(
                            selected = route.tab == tab,
                            onClick = { route = route.select(tab) },
                            icon = {
                                TabGlyph(
                                    tab = tab,
                                    tint = LocalContentColor.current,
                                    dot = if (home) statusTone else null,
                                    ring = accents.dock,
                                    status = if (home) statusLabel else null,
                                )
                            },
                            label = { Text(stringResource(tabLabel(tab))) },
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
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
                                        },
                                        // The quick-controls sheet hands over to the page
                                        // that owns the rest; back returns via the hub.
                                        onOpenSettings = { route = route.open(it) })
                                }
                            }
                        }
                    }
                    if (!rail) {
                        FloatingDock(
                            selected = route.tab,
                            statusTone = statusTone,
                            statusLabel = statusLabel,
                            onSelect = { route = route.select(it) },
                            modifier = Modifier
                                .widthIn(max = 520.dp)
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The app's mark: a shield on the brand-to-protected gradient. Decorative. */
@Composable
private fun BrandMark(size: Dp) {
    val accents = LocalAetherAccents.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(accents.brand, accents.protected))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Shield, null, Modifier.size(size * 0.56f), accents.onBrand)
    }
}

/** A tab's icon, with the live status dot on the connection tab. */
@Composable
private fun TabGlyph(tab: HomeTab, tint: Color, dot: Color?, ring: Color, status: String?) {
    Box {
        Icon(tabIcon(tab), null, Modifier.size(22.dp), tint)
        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-1).dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dot)
                    .then(
                        if (status != null) Modifier.semantics { contentDescription = status } else Modifier,
                    ),
            )
        }
    }
}

/**
 * The floating dock: one rounded glass bar above the gesture area. The
 * selected destination sits on a brand-wash pill; the connection item carries
 * the status dot (and the status words, for TalkBack).
 */
@Composable
private fun FloatingDock(
    selected: HomeTab,
    statusTone: Color,
    statusLabel: String,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AetherRadius.Sheet),
        color = accents.dock,
        border = BorderStroke(1.dp, accents.cardBorder),
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier
                .selectableGroup()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { tab ->
                val home = tab == HomeTab.HOME
                DockItem(
                    tab = tab,
                    selected = selected == tab,
                    dot = if (home) statusTone else null,
                    status = if (home) statusLabel else null,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    tab: HomeTab,
    selected: Boolean,
    dot: Color?,
    status: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val fill by animateColorAsState(
        targetValue = if (selected) accents.brandWash else Color.Transparent,
        animationSpec = tween(aetherDuration(AetherDur.Quick), easing = AetherEaseOut),
        label = "dock-fill",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(aetherDuration(AetherDur.Quick), easing = AetherEaseOut),
        label = "dock-ink",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TabGlyph(tab = tab, tint = ink, dot = dot, ring = if (selected) fill else accents.dock, status = status)
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(tabLabel(tab)),
            style = MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeHeader(route: HomeRoute, onBack: () -> Unit) {
    val atHome = route.tab == HomeTab.HOME
    Row(
        Modifier
            .widthIn(max = 880.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = if (atHome) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (route.page != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.passage_back)) }
            Spacer(Modifier.width(8.dp))
        }
        if (atHome) {
            BrandMark(36.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (!atHome) Text(
                // Inside a page the eyebrow names its group, not just "Settings".
                route.page?.let { stringResource(it.group.label) } ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                route.page?.let { settingsPageTitle(it) } ?: stringResource(if (atHome) R.string.app_name else tabLabel(route.tab)),
                style = if (atHome) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
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
