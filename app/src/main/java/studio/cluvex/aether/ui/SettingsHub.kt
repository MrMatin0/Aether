package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.aetherDuration

// ------------------------------------------------------------------ entry --

/**
 * The redesigned settings hub.
 *
 * Top to bottom: tunnel status strip (the ONE place the lock is explained),
 * search, quick controls, recommended presets, then four collapsible groups
 * whose collapsed headers already carry the live values.
 *
 * Writes go through [onProfileChange] exactly as before: each change is
 * rebased on the latest profile, and profile-locked changes are refused while
 * the tunnel is up (same rule as EngineDestination).
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

/** Old signature, read-only: no quick controls, sheets fall back to full pages. */
@Deprecated(
    "Pass the ConnectionState and onProfileChange to get quick controls and sheets.",
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

internal fun ConnectionState.tunnelTone(): TunnelTone = when {
    isConnected -> TunnelTone.PROTECTED
    this is ConnectionState.Error -> TunnelTone.FAILED
    isBusy -> TunnelTone.WORKING // includes Verifying: never shown as ready
    else -> TunnelTone.IDLE
}

internal fun interface HubEdit {
    fun write(lockable: Boolean, change: ConnectionProfile.() -> ConnectionProfile)
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

    val latestProfile by rememberUpdatedState(profile)
    val latestLocked by rememberUpdatedState(locked)
    val edit: HubEdit? = remember(onProfileChange) {
        onProfileChange?.let { push ->
            HubEdit { lockable, change -> if (!(lockable && latestLocked)) push(latestProfile.change()) }
        }
    }
    val canSheet = state != null && onProfileChange != null
    val openPage: (SettingsPage) -> Unit = { page ->
        focus.clearFocus()
        if (canSheet && page.presentation == PagePresentation.SHEET) sheet = page else onOpen(page)
    }

    val fade = aetherDuration(AetherDur.Quick)
    Column(modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(4.dp))
        if (state != null) {
            TunnelStatusStrip(
                tone = state.tunnelTone(),
                label = statusHeadline(state),
                detail = listOf(protocolLabel(profile.protocol), chainLabel(profile.chain)).joinToString(HUB_DOT),
                locked = locked,
            )
        } else if (locked) {
            LockBadge()
        }
        Spacer(Modifier.height(16.dp))
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
                    if (edit != null) {
                        Spacer(Modifier.height(28.dp))
                        HubSectionLabel(stringResource(R.string.hub_quick_title))
                        Spacer(Modifier.height(12.dp))
                        QuickControls(profile, locked, edit)

                        Spacer(Modifier.height(28.dp))
                        HubSectionLabel(
                            stringResource(R.string.hub_presets_title),
                            trailing = {
                                TextButton(onClick = { openPage(SettingsPage.SETUPS) }) {
                                    Text(stringResource(R.string.hub_preset_manage))
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        PresetStrip(
                            profile = profile,
                            saved = remember(presets) { presets.map { it.name to it.toProfile() } },
                            locked = locked,
                            onApply = { next, name ->
                                if (!locked) {
                                    onProfileChange?.invoke(next)
                                    Toast.makeText(context, context.getString(R.string.presets_applied, name), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
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

@Composable
private fun statusHeadline(state: ConnectionState): String = when (state) {
    is ConnectionState.Reconnecting -> stringResource(R.string.hub_status_reconnecting, state.attempt, state.maxAttempts)
    else -> stringResource(connectionStatusLabel(state))
}

// --------------------------------------------------------- quick controls --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickControls(profile: ConnectionProfile, locked: Boolean, edit: HubEdit) {
    val on = stringResource(R.string.hub_state_on)
    val off = stringResource(R.string.hub_state_off)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            fontScale >= 1.5f -> 1
            maxWidth >= 600.dp -> 4
            else -> 2
        }
        FlowRow(
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val tile = Modifier.weight(1f)
            QuickToggleTile(
                icon = Icons.Rounded.PowerSettingsNew,
                title = stringResource(R.string.hub_quick_kill),
                value = when {
                    profile.killSwitch && profile.strictKillSwitch -> stringResource(R.string.hub_state_kill_strict)
                    profile.killSwitch -> on
                    else -> off
                },
                checked = profile.killSwitch,
                locked = locked,
                onCheckedChange = { v -> edit.write(lockable = true) { copy(killSwitch = v) } },
                modifier = tile,
            )
            QuickToggleTile(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.hub_quick_ipv6),
                value = if (profile.ipv6LeakProtection) on else off,
                checked = profile.ipv6LeakProtection,
                locked = locked,
                onCheckedChange = { v -> edit.write(lockable = true) { copy(ipv6LeakProtection = v) } },
                modifier = tile,
            )
            QuickToggleTile(
                icon = Icons.Rounded.Autorenew,
                title = stringResource(R.string.hub_quick_retry),
                value = if (profile.smartReconnect) stringResource(R.string.hub_state_retry, profile.reconnectRetryLimit) else off,
                checked = profile.smartReconnect,
                locked = locked,
                onCheckedChange = { v -> edit.write(lockable = true) { copy(smartReconnect = v) } },
                modifier = tile,
            )
            // Sharing does not edit the tunnel profile (SettingsPage.SHARING.editsProfile
            // is false), so it stays live mid-session, same as SharePanel.
            QuickToggleTile(
                icon = Icons.Rounded.WifiTethering,
                title = stringResource(R.string.hub_quick_lan),
                value = if (profile.lanShare) on else off,
                checked = profile.lanShare,
                locked = false,
                onCheckedChange = { v -> edit.write(lockable = false) { copy(lanShare = v) } },
                modifier = tile,
            )
        }
    }
}

// ---------------------------------------------------------------- presets --

/**
 * Built-in presets touch ONLY the safety flags, never protocol, chain or
 * transport: those depend on the user's network and a one-tap "stealth"
 * preset that guesses them would do more harm than good.
 * Active state is plain data-class equality, so it is always honest.
 */
internal enum class RecommendedPreset(val title: Int, val detail: Int, val icon: ImageVector) {
    HARDENED(R.string.hub_preset_hardened, R.string.hub_preset_hardened_sub, Icons.Rounded.GppGood),
    BALANCED(R.string.hub_preset_balanced, R.string.hub_preset_balanced_sub, Icons.Rounded.Balance),
    ;

    fun applyTo(p: ConnectionProfile): ConnectionProfile = when (this) {
        HARDENED -> p.copy(killSwitch = true, strictKillSwitch = true, ipv6LeakProtection = true, smartReconnect = true)
        BALANCED -> p.copy(killSwitch = true, strictKillSwitch = false, ipv6LeakProtection = true, smartReconnect = true)
    }

    fun isActive(p: ConnectionProfile): Boolean = applyTo(p) == p
}

@Composable
private fun PresetStrip(
    profile: ConnectionProfile,
    saved: List<Pair<String, ConnectionProfile>>,
    locked: Boolean,
    onApply: (ConnectionProfile, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecommendedPreset.entries.forEach { preset ->
            val name = stringResource(preset.title)
            PresetCard(
                title = name,
                detail = stringResource(preset.detail),
                icon = preset.icon,
                active = preset.isActive(profile),
                enabled = !locked,
                onClick = { onApply(preset.applyTo(profile), name) },
            )
        }
        saved.forEach { (name, preset) ->
            key(name) {
                PresetCard(
                    title = name,
                    detail = connectionStateLine(preset),
                    icon = Icons.Rounded.BookmarkBorder,
                    active = preset == profile,
                    enabled = !locked,
                    onClick = { onApply(preset, name) },
                )
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
