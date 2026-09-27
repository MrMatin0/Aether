package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
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
import studio.cluvex.aether.ui.theme.aetherDuration

// ------------------------------------------------------------------ entry --

/**
 * The settings hub.
 *
 * Top to bottom: lock badge (only while the tunnel is up), search, then four
 * collapsible groups whose collapsed headers already carry the live values.
 *
 * The connection status strip, quick controls and recommended presets that
 * used to sit above the groups are gone: the connection state already lives on
 * the home screen, and every quick toggle / preset duplicated a row that is one
 * tap away inside its group (Security, Sharing, Saved setups).
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

private val GroupSetSaver = listSaver<Set<SettingsGroup>, String>(
    save = { set -> set.map { it.name } },
    restore = { names -> names.mapNotNull { n -> SettingsGroup.entries.find { it.name == n } }.toSet() },
)

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
    var expanded by rememberSaveable(stateSaver = GroupSetSaver) { mutableStateOf(setOf(SettingsGroup.CORE_TUNNEL)) }
    var sheet by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    val canSheet = state != null && onProfileChange != null
    val openPage: (SettingsPage) -> Unit = { page ->
        focus.clearFocus()
        if (canSheet && page.presentation == PagePresentation.SHEET) sheet = page else onOpen(page)
    }

    val fade = aetherDuration(AetherDur.Quick)
    Column(modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(4.dp))
        // No connection status here: Home already shows it. The lock is the
        // only piece of tunnel state the hub needs, because it explains why
        // some rows are disabled.
        if (locked) {
            LockBadge()
            Spacer(Modifier.height(16.dp))
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
                Column {
                    Spacer(Modifier.height(28.dp))
                    SettingsGroup.entries.forEach { group ->
                        val isOpen = group in expanded
                        SectionCard(
                            title = stringResource(group.label),
                            summary = group.summaryPages.mapNotNull { byPage[it]?.state }.joinToString(HUB_DOT).ifEmpty { null },
                            icon = group.icon,
                            expanded = isOpen,
                            onToggle = { expanded = if (isOpen) expanded - group else expanded + group },
                        ) {
                            group.pages.forEach { page ->
                                byPage[page]?.let { entry ->
                                    HubRow(
                                        icon = settingsPageIcon(page),
                                        title = entry.title,
                                        value = entry.state,
                                        note = entry.note,
                                        locked = locked && page.editsProfile,
                                        destructive = page == SettingsPage.RESET,
                                        onClick = { openPage(page) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
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
                showSubtitle = false,
            )
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
        Spacer(Modifier.height(24.dp))
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
        HubSectionLabel(pluralStringResource(R.plurals.hub_search_count, hits.size, hits.size))
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = HubShapes.Card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(8.dp)) {
                hits.forEachIndexed { index, hit ->
                    key(hit.entry.page) {
                        if (index > 0) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        val page = hit.entry.page
                        HubRow(
                            icon = settingsPageIcon(page),
                            title = hit.entry.title,
                            value = hit.entry.state,
                            note = hit.entry.note,
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
}

// ------------------------------------------------------------------ sheet --

@Composable
private fun SheetHeader(page: SettingsPage, locked: Boolean, onExpand: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(settingsPageIcon(page), null, Modifier.padding(top = 4.dp).size(22.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                settingsPageTitle(page),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                settingsPageSubtitle(page),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locked) {
                Spacer(Modifier.height(10.dp))
                LockBadge()
            }
        }
        IconButton(onClick = onExpand) {
            Icon(Icons.Rounded.OpenInFull, stringResource(R.string.hub_open_full))
        }
    }
}
