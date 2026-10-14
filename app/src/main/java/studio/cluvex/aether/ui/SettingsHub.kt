package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.data.PresetStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

// ------------------------------------------------------------------ entry --

/**
 * The settings hub.
 *
 * Top to bottom: lock badge (only while the tunnel is up), search, then five
 * intent-based groups as FLAT, always-visible cards with a quiet section label
 * above each (iOS Settings / Mullvad / ProtonVPN). Every destination is visible
 * at a glance; nothing needs a tap to be found. Each row carries its live value
 * inline at the end, so the page only needs opening to change something.
 *
 * The centered reading column stays usable on tablets and in landscape.
 *
 * Writes go through [onProfileChange] exactly as before: profile-locked
 * changes are refused while the tunnel is up (same rule as EngineDestination).
 */
@Composable
fun SettingsHub(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val locked = !(state is ConnectionState.Idle || state is ConnectionState.Error)
    SettingsHubLayout(locked, state, profile, onProfileChange, onOpen, modifier, scrollState)
}

/** Old signature, read-only: sheets fall back to full pages. */
@Deprecated(
    "Pass the ConnectionState and onProfileChange to get sheets.",
    ReplaceWith("SettingsHub(state, profile, onProfileChange, onOpen, modifier, scrollState)"),
)
@Composable
fun SettingsHub(
    locked: Boolean,
    profile: ConnectionProfile,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    SettingsHubLayout(locked, null, profile, null, onOpen, modifier, scrollState)
}

// ----------------------------------------------------------------- layout --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubLayout(
    locked: Boolean,
    state: ConnectionState?,
    profile: ConnectionProfile,
    onProfileChange: ((ConnectionProfile) -> Unit)?,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier,
    scrollState: ScrollState,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val store = remember(context) { PresetStore(context) }
    val presets by store.presets.collectAsStateWithLifecycle()
    val entries = settingsEntries(profile, presets.size)
    val byPage = remember(entries) { entries.associateBy { it.page } }

    var query by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    val canSheet = state != null && onProfileChange != null
    val openPage: (SettingsPage) -> Unit = { page ->
        focus.clearFocus()
        if (canSheet && page.presentation == PagePresentation.SHEET) sheet = page else onOpen(page)
    }

    val fade = aetherDuration(AetherDur.Quick)
    Column(
        modifier.fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        // No connection status here: Home already shows it. The lock is the
        // only piece of tunnel state the hub needs, because it explains why
        // some settings cannot be edited while connected.
        if (locked) {
            LockBadge()
            Spacer(Modifier.height(12.dp))
        }
        HubSearchField(query, onQueryChange = { query = it })

        AnimatedContent(
            targetState = query.isNotBlank(),
            transitionSpec = { fadeIn(tween(fade)) togetherWith fadeOut(tween(fade / 2)) },
            label = "hub-mode",
        ) { searching ->
            if (searching) {
                SearchResults(
                    hits = remember(query, entries) { searchSettings(query, entries) },
                    query = query,
                    locked = locked,
                    onOpen = openPage,
                    onClear = { query = "" },
                )
            } else {
                HubGroups(byPage = byPage, locked = locked, onOpen = openPage)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    // ------------------------------------------------------------- sheet --
    val sheetPage = sheet
    if (sheetPage != null && state != null && onProfileChange != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
        ) {
            SheetHeader(
                page = sheetPage,
                locked = locked && sheetPage.editsProfile,
                onExpand = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        sheet = null
                        onOpen(sheetPage)
                    }
                },
            )
            SettingsPageBody(
                page = sheetPage,
                state = state,
                profile = profile,
                onProfileChange = onProfileChange,
                settingsEnabled = !locked,
            )
        }
    }
}

/** Every group, always open: label + flat card of compact rows. */
@Composable
private fun HubGroups(
    byPage: Map<SettingsPage, SettingsEntry>,
    locked: Boolean,
    onOpen: (SettingsPage) -> Unit,
) {
    Column {
        SettingsGroup.entries.forEach { group ->
            key(group) {
                val rows = group.pages.mapNotNull { byPage[it] }
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    HubGroupCard(label = stringResource(group.label)) {
                        rows.forEachIndexed { index, entry ->
                            key(entry.page) {
                                if (index > 0) HubRowDivider()
                                HubRow(
                                    icon = settingsPageIcon(entry.page),
                                    title = entry.title,
                                    value = entry.state,
                                    locked = locked && entry.page.editsProfile,
                                    destructive = entry.page == SettingsPage.RESET,
                                    onClick = { onOpen(entry.page) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- search --

@Composable
private fun SearchResults(
    hits: List<SettingsHit>,
    query: String,
    locked: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(20.dp))
        if (hits.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.hub_search_empty, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClear) { Text(stringResource(R.string.hub_search_clear)) }
            }
            return@Column
        }
        HubGroupCard(label = pluralStringResource(R.plurals.hub_search_count, hits.size, hits.size)) {
            hits.forEachIndexed { index, hit ->
                key(hit.entry.page) {
                    if (index > 0) HubRowDivider()
                    val page = hit.entry.page
                    HubRow(
                        icon = settingsPageIcon(page),
                        title = hit.entry.title,
                        value = hit.entry.state,
                        eyebrow = stringResource(page.group.label),
                        highlights = hit.titleRanges,
                        tags = hit.matchedTags,
                        locked = locked && page.editsProfile,
                        destructive = page == SettingsPage.RESET,
                        onClick = { onOpen(page) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sheet --

/** Title only: the body below is the explanation, so no subtitle paragraph. */
@Composable
private fun SheetHeader(page: SettingsPage, locked: Boolean, onExpand: () -> Unit) {
    val a = LocalAetherAccents.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(a.brandWash, HubShapes.Badge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(settingsPageIcon(page), null, Modifier.size(20.dp), a.brand)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            settingsPageTitle(page),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (locked) {
            Spacer(Modifier.width(8.dp))
            LockBadge(compact = true)
        }
        IconButton(onClick = onExpand) {
            Icon(Icons.Rounded.OpenInFull, stringResource(R.string.hub_open_full))
        }
    }
}
