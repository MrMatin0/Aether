package studio.cluvex.aether.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.core.BridgeLine
import studio.cluvex.aether.core.SessionTracker
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.PresetStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherMetaLabel

/**
 * Every settings destination, flat on purpose.
 *
 * WHY FLAT: the previous hub opened with a full-bleed primary card for
 * "Connection engine", and that page then hid SEVEN categories behind a row of
 * filter chips which wrapped onto three lines on a normal phone. Reaching MTU
 * was Settings -> hero -> chip row -> scroll, four moves for one number, and
 * the chips looked identical whether they held a default or something the user
 * had changed. The seven engine categories are destinations now, so the deepest
 * a single setting sits is one tap from the hub.
 *
 * Order here is the order of the index, and [SettingsGroup] is the only place
 * that decides which group a destination belongs to. A destination that is not
 * in exactly one group is unreachable, which is what the unit test checks.
 */
enum class SettingsPage {
    CONNECTION, CHAIN, BRIDGES, TRANSPORT,
    ROUTING, SHARING,
    SECURITY, ORGANIZATION,
    APPEARANCE, AUTOMATION, HISTORY, SETUPS,
    TUNING, RESET, ABOUT,
    ;

    /**
     * True when the destination writes [ConnectionProfile], i.e. needs an idle
     * tunnel. Exhaustive on purpose: a new destination cannot be added without
     * deciding whether it is safe to change mid-session.
     */
    internal val editsProfile: Boolean
        get() = when (this) {
            CONNECTION, CHAIN, BRIDGES, TRANSPORT, ROUTING, SECURITY, ORGANIZATION, TUNING,
            RESET, SETUPS -> true
            SHARING, APPEARANCE, AUTOMATION, HISTORY, ABOUT -> false
        }

    /** Saved setups states the lock in its own words, so it is not told twice. */
    internal val showsLockNotice: Boolean
        get() = editsProfile && this != SETUPS
}

/**
 * The index, grouped by what a group does to your traffic.
 *
 * The old groups were "Essentials" and "More", which put appearance next to
 * saved setups and told a user nothing about where to look for split
 * tunneling.
 */
internal enum class SettingsGroup(val label: Int, val pages: List<SettingsPage>) {
    TUNNEL(
        R.string.hub_group_tunnel,
        listOf(
            SettingsPage.CONNECTION,
            SettingsPage.CHAIN,
            SettingsPage.BRIDGES,
            SettingsPage.TRANSPORT,
        ),
    ),
    TRAFFIC(
        R.string.hub_group_traffic,
        listOf(SettingsPage.ROUTING, SettingsPage.SHARING),
    ),
    SAFETY(
        R.string.hub_group_safety,
        listOf(SettingsPage.SECURITY, SettingsPage.ORGANIZATION),
    ),
    APP(
        R.string.hub_group_app,
        listOf(SettingsPage.APPEARANCE, SettingsPage.AUTOMATION, SettingsPage.HISTORY, SettingsPage.SETUPS),
    ),
    HOUSEKEEPING(
        R.string.hub_group_maintenance,
        listOf(SettingsPage.TUNING, SettingsPage.RESET, SettingsPage.ABOUT),
    ),
}

@Composable
internal fun settingsPageTitle(page: SettingsPage): String = stringResource(
    when (page) {
        SettingsPage.CONNECTION -> R.string.section_connection
        SettingsPage.CHAIN -> R.string.section_chain
        SettingsPage.BRIDGES -> R.string.section_bridges
        SettingsPage.TRANSPORT -> R.string.section_transport
        SettingsPage.ROUTING -> R.string.section_routing
        SettingsPage.SHARING -> R.string.share_title
        SettingsPage.SECURITY -> R.string.section_security
        SettingsPage.ORGANIZATION -> R.string.section_zerotrust
        SettingsPage.APPEARANCE -> R.string.page_appearance_title
        SettingsPage.AUTOMATION -> R.string.automation_title
        SettingsPage.HISTORY -> R.string.history_title
        SettingsPage.SETUPS -> R.string.page_setups_title
        SettingsPage.TUNING -> R.string.section_engine_tuning
        SettingsPage.RESET -> R.string.section_reset
        SettingsPage.ABOUT -> R.string.page_about_title
    },
)

@Composable
internal fun settingsPageSubtitle(page: SettingsPage): String = stringResource(
    when (page) {
        SettingsPage.CONNECTION -> R.string.section_connection_note
        SettingsPage.CHAIN -> R.string.section_chain_note
        SettingsPage.BRIDGES -> R.string.section_bridges_note
        SettingsPage.TRANSPORT -> R.string.section_transport_note
        SettingsPage.ROUTING -> R.string.section_routing_note
        SettingsPage.SHARING -> R.string.page_sharing_sub
        SettingsPage.SECURITY -> R.string.section_security_note
        SettingsPage.ORGANIZATION -> R.string.section_zerotrust_note
        SettingsPage.APPEARANCE -> R.string.page_appearance_sub
        SettingsPage.AUTOMATION -> R.string.hub_automation_sub
        SettingsPage.HISTORY -> R.string.hub_history_sub
        SettingsPage.SETUPS -> R.string.page_setups_sub
        SettingsPage.TUNING -> R.string.section_engine_tuning_note
        SettingsPage.RESET -> R.string.section_reset_note
        SettingsPage.ABOUT -> R.string.page_about_sub
    },
)

internal fun settingsPageIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.CONNECTION -> Icons.Rounded.Cable
    SettingsPage.CHAIN -> Icons.Rounded.Layers
    SettingsPage.BRIDGES -> Icons.Rounded.AltRoute
    SettingsPage.TRANSPORT -> Icons.Rounded.SwapVert
    SettingsPage.ROUTING -> Icons.Rounded.Route
    SettingsPage.SHARING -> Icons.Rounded.WifiTethering
    SettingsPage.SECURITY -> Icons.Rounded.Shield
    SettingsPage.ORGANIZATION -> Icons.Rounded.Business
    SettingsPage.APPEARANCE -> Icons.Rounded.Palette
    SettingsPage.AUTOMATION -> Icons.Rounded.Schedule
    SettingsPage.HISTORY -> Icons.Rounded.History
    SettingsPage.SETUPS -> Icons.Rounded.Bookmarks
    SettingsPage.TUNING -> Icons.Rounded.Tune
    SettingsPage.RESET -> Icons.Rounded.RestartAlt
    SettingsPage.ABOUT -> Icons.Rounded.Info
}

private const val DOT = " \u00B7 "

/**
 * Folds the two letters an Iranian phone keyboard produces onto their Persian
 * twins, and reads a ZWNJ as a space.
 *
 * Without this, searching for the Persian word a user actually types can miss a
 * resource string that spells the same word with ARABIC YEH or a zero-width
 * non-joiner, which is most of the Persian copy in this app.
 */
internal fun normalizeSettingsText(raw: String): String = raw
    .lowercase()
    .replace('\u064A', '\u06CC')
    .replace('\u0643', '\u06A9')
    .replace('\u200C', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()

/**
 * Every term must appear somewhere in the entry, so "kill switch" narrows
 * instead of widening. A blank query matches everything.
 */
internal fun matchesSettingsQuery(query: String, fields: List<String>): Boolean {
    val terms = normalizeSettingsText(query).split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val haystack = fields.joinToString(" ") { normalizeSettingsText(it) }
    return terms.all { haystack.contains(it) }
}

/** One row of the index: what it is called, what it is set to, what it does. */
private data class SettingsEntry(
    val page: SettingsPage,
    val title: String,
    val state: String?,
    val note: String,
) {
    val searchFields: List<String> get() = listOfNotNull(title, state, note)
}

@Composable
fun SettingsHub(
    locked: Boolean,
    profile: ConnectionProfile,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val entries = settingsEntries(profile)
    val byPage = remember(entries) { entries.associateBy { it.page } }
    Column(modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        SettingsSearchField(query) { query = it }
        if (locked) {
            Spacer(Modifier.height(20.dp))
            NoticeBar(stringResource(R.string.settings_locked))
        }
        if (query.isBlank()) {
            SettingsGroup.entries.forEach { group ->
                Spacer(Modifier.height(36.dp))
                GroupRule(stringResource(group.label))
                group.pages.forEachIndexed { index, page ->
                    if (index > 0) Hairline(alpha = 0.5f)
                    byPage[page]?.let { SettingsRow(it, locked, onOpen) }
                }
            }
        } else {
            val matches = entries.filter { matchesSettingsQuery(query, it.searchFields) }
            Spacer(Modifier.height(28.dp))
            if (matches.isEmpty()) {
                NoticeBar(
                    stringResource(R.string.hub_search_empty, query.trim()),
                    tone = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Rounded.SearchOff,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { query = "" }) { Text(stringResource(R.string.hub_search_clear)) }
            } else {
                matches.forEachIndexed { index, entry ->
                    if (index > 0) Hairline(alpha = 0.5f)
                    SettingsRow(entry, locked, onOpen)
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onQueryChange(it.take(48)) },
        singleLine = true,
        label = { Text(stringResource(R.string.hub_search_label)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.hub_search_clear))
                }
            }
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A label and a rule that runs to the edge: a group marker, not another card. */
@Composable
private fun GroupRule(label: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.width(16.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * No trailing chevron: fourteen of them is decoration, and Material settings
 * rows do not carry one. The second line is the LIVE value where there is one,
 * so the hub reads as a configuration summary and the page only has to be
 * opened to change something, not to find out what it says.
 */
@Composable
private fun SettingsRow(entry: SettingsEntry, locked: Boolean, onOpen: (SettingsPage) -> Unit) {
    val destructive = entry.page == SettingsPage.RESET
    val held = locked && entry.page.editsProfile
    val ink = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = entry.title) { onOpen(entry.page) }
            .heightIn(min = 72.dp)
            .padding(vertical = 18.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            settingsPageIcon(entry.page),
            null,
            Modifier.padding(top = 3.dp).size(22.dp),
            if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, color = ink)
            Spacer(Modifier.height(4.dp))
            if (entry.state != null) {
                Text(
                    entry.state,
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (held) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Rounded.Lock,
                stringResource(R.string.hub_locked_row),
                Modifier.padding(top = 4.dp).size(16.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SettingsPageBody(
    page: SettingsPage,
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    settingsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(4.dp))
        // The header above only has room for the title, and a page named
        // "Transport" or "Zero Trust" is not self-explanatory.
        Text(
            settingsPageSubtitle(page),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (!settingsEnabled && page.showsLockNotice) {
            NoticeBar(stringResource(R.string.settings_locked))
            Spacer(Modifier.height(24.dp))
        }
        when (page) {
            SettingsPage.APPEARANCE -> AppearancePanel()
            SettingsPage.AUTOMATION -> AutomationPanel()
            SettingsPage.HISTORY -> HistoryPanel()
            SettingsPage.SETUPS -> PresetsPanel(profile, onProfileChange, settingsEnabled)
            SettingsPage.SHARING -> SharePanel(state, profile, onProfileChange)
            SettingsPage.ABOUT -> AboutPanel()
            else -> EngineDestination(page, profile, onProfileChange, settingsEnabled)
        }
        Spacer(Modifier.height(48.dp))
    }
}

// ----------------------------------------------------------------- summaries --
//
// One fixed list of composable calls, built once per composition, then filtered
// as plain data. Nothing below reads a flow inside a loop.

@Composable
private fun settingsEntries(profile: ConnectionProfile): List<SettingsEntry> {
    val context = LocalContext.current
    val prefs by AppPrefs.state.collectAsStateWithLifecycle()
    val sessions by SessionTracker.sessions.collectAsStateWithLifecycle()
    val store = remember(context) { PresetStore(context) }
    val presets by store.presets.collectAsStateWithLifecycle()
    val language = AppLocale.stored(context)
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: BuildConfig.VERSION_NAME
    }
    return listOf(
        entryOf(SettingsPage.CONNECTION, connectionStateLine(profile)),
        entryOf(SettingsPage.CHAIN, chainLabel(profile.chain)),
        entryOf(SettingsPage.BRIDGES, bridgeStateLine(profile)),
        entryOf(SettingsPage.TRANSPORT, transportStateLine(profile)),
        entryOf(SettingsPage.ROUTING, routingStateLine(profile)),
        entryOf(
            SettingsPage.SHARING,
            stringResource(if (profile.lanShare) R.string.hub_state_on else R.string.hub_state_off),
        ),
        entryOf(SettingsPage.SECURITY, securityStateLine(profile)),
        entryOf(SettingsPage.ORGANIZATION, organizationStateLine(profile)),
        entryOf(
            SettingsPage.APPEARANCE,
            listOf(themeModeLabel(prefs.themeMode), appLanguageLabel(language)).joinToString(DOT),
        ),
        entryOf(
            SettingsPage.AUTOMATION,
            automationStateLine(prefs.autoConnectOnLaunch, prefs.autoConnectOnBoot),
        ),
        entryOf(
            SettingsPage.HISTORY,
            if (prefs.keepHistory) {
                stringResource(R.string.history_count, sessions.size)
            } else {
                stringResource(R.string.history_off)
            },
        ),
        entryOf(
            SettingsPage.SETUPS,
            if (presets.isEmpty()) {
                stringResource(R.string.presets_empty)
            } else {
                stringResource(R.string.hub_state_presets, presets.size)
            },
        ),
        entryOf(SettingsPage.TUNING, tuningStateLine(profile)),
        // Reset has no state to report, only a consequence.
        entryOf(SettingsPage.RESET, null),
        entryOf(
            SettingsPage.ABOUT,
            stringResource(R.string.hub_state_version, version, BuildConfig.CORE_VERSION),
        ),
    )
}

@Composable
private fun entryOf(page: SettingsPage, state: String?): SettingsEntry =
    SettingsEntry(page, settingsPageTitle(page), state, settingsPageSubtitle(page))

@Composable
private fun connectionStateLine(profile: ConnectionProfile): String = listOf(
    protocolLabel(profile.protocol),
    when (profile.endpointMode) {
        EndpointMode.AUTO -> scanLabel(profile.scanMode)
        EndpointMode.MANUAL_PEER -> stringResource(R.string.hub_state_pinned)
        EndpointMode.MANUAL_RANGE -> endpointLabel(EndpointMode.MANUAL_RANGE)
    },
    ipLabel(profile.ipVersion),
).joinToString(DOT)

/**
 * The bridge row: the type and HOW MANY, because one is a single point of
 * failure and the count is the only thing on this page that predicts whether a
 * Tor bootstrap will survive a bridge going down.
 *
 * Counted through [BridgeLine] rather than by counting lines, so a half-pasted
 * line is not advertised on the hub as a working bridge.
 */
@Composable
private fun bridgeStateLine(profile: ConnectionProfile): String {
    if (!profile.torBridgeMode.isOn) return stringResource(R.string.hub_state_off)
    val count = BridgeLine.parseAll(profile.torBridgeLines).size
    if (count == 0) return stringResource(R.string.bridges_state_none)
    return listOf(
        bridgeTransportLabel(profile.torBridgeTransport),
        stringResource(R.string.bridges_state_count, count),
    ).joinToString(DOT)
}

@Composable
private fun transportStateLine(profile: ConnectionProfile): String {
    val parts = listOfNotNull(
        if (profile.noize != Noize.OFF) noizeLabel(profile.noize) else null,
        if (profile.fragment) stringResource(R.string.hub_state_fragment) else null,
        if (profile.ech) stringResource(R.string.hub_state_ech) else null,
        if (profile.mtu != ConnectionProfile.DEFAULT_MTU) stringResource(R.string.hub_state_mtu, profile.mtu) else null,
        if (profile.keepalive > 0) stringResource(R.string.hub_state_keepalive, profile.keepalive) else null,
        if (profile.dnsServers.isNotBlank()) stringResource(R.string.hub_state_dns) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_default) else parts.joinToString(DOT)
}

@Composable
private fun routingStateLine(profile: ConnectionProfile): String {
    val split = profile.splitMode != SplitMode.OFF
    val parts = listOfNotNull(
        if (split) splitLabel(profile.splitMode) else null,
        if (split) stringResource(R.string.hub_state_apps, profile.splitApps.size) else null,
        if (profile.blockedApps.isNotEmpty()) {
            stringResource(R.string.blocked_select_apps, profile.blockedApps.size)
        } else {
            null
        },
        if (profile.proxyMode) stringResource(R.string.hub_state_proxy) else null,
        if (profile.routeBlock.isNotBlank() || profile.routeDirect.isNotBlank()) {
            stringResource(R.string.hub_state_rules)
        } else {
            null
        },
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_all_traffic) else parts.joinToString(DOT)
}

@Composable
private fun securityStateLine(profile: ConnectionProfile): String {
    val parts = listOfNotNull(
        when {
            profile.strictKillSwitch -> stringResource(R.string.hub_state_kill_strict)
            profile.killSwitch -> stringResource(R.string.hub_state_kill)
            else -> null
        },
        if (profile.ipv6LeakProtection) stringResource(R.string.hub_state_ipv6) else null,
        if (profile.smartReconnect) {
            stringResource(R.string.hub_state_retry, profile.reconnectRetryLimit)
        } else {
            null
        },
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_unprotected) else parts.joinToString(DOT)
}

@Composable
private fun organizationStateLine(profile: ConnectionProfile): String = if (!profile.hasTeam) {
    stringResource(R.string.hub_state_off)
} else {
    listOfNotNull(
        stringResource(R.string.hub_state_team, profile.team.trim()),
        teamAuthLabel(profile.teamAuth),
        if (profile.gateway) stringResource(R.string.hub_state_gateway) else null,
    ).joinToString(DOT)
}

@Composable
private fun tuningStateLine(profile: ConnectionProfile): String {
    val parts = listOfNotNull(
        if (profile.coreLogLevel != CoreLogLevel.WARN) {
            stringResource(R.string.hub_state_log, profile.coreLogLevel.raw)
        } else {
            null
        },
        profile.tlsGroups.trim().takeIf { it.isNotEmpty() },
        if (profile.validateSecs > 0 || profile.reconnectSecs > 0) {
            stringResource(R.string.hub_state_timers)
        } else {
            null
        },
        if (profile.noDataCheck) stringResource(R.string.hub_state_no_probe) else null,
        if (profile.noProfileRetry) stringResource(R.string.hub_state_no_retry) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_default) else parts.joinToString(DOT)
}

@Composable
private fun automationStateLine(onLaunch: Boolean, onBoot: Boolean): String {
    val parts = listOfNotNull(
        if (onLaunch) stringResource(R.string.hub_state_launch) else null,
        if (onBoot) stringResource(R.string.hub_state_boot) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.hub_state_manual) else parts.joinToString(DOT)
}
