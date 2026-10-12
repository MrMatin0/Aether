package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.LockBadge

/**
 * Every settings destination. UNCHANGED names and order: [HomeRoute] saves
 * `page.name` into the saved-state bundle, so renaming an entry here would
 * silently drop a user back to the hub after process death.
 *
 * What changed is how the hub PRESENTS them: four collapsible groups instead of
 * five flat ones, short panels in a sheet, dense editors as a full page.
 */
enum class SettingsPage {
    CONNECTION, CHAIN, BRIDGES, TRANSPORT,
    ROUTING, SHARING,
    SECURITY, ORGANIZATION,
    APPEARANCE, AUTOMATION, HISTORY, SETUPS,
    TUNING, RESET, ABOUT,
    ;

    /** True when the destination writes [ConnectionProfile], i.e. needs an idle tunnel. */
    internal val editsProfile: Boolean
        get() = when (this) {
            CONNECTION, CHAIN, BRIDGES, TRANSPORT, ROUTING, SECURITY, ORGANIZATION, TUNING,
            RESET, SETUPS -> true
            SHARING, APPEARANCE, AUTOMATION, HISTORY, ABOUT -> false
        }

    /** Saved setups states the lock in its own words, so it is not told twice. */
    internal val showsLockNotice: Boolean
        get() = editsProfile && this != SETUPS

    /**
     * Short panels open in a sheet over the hub so the user keeps their place;
     * anything with text fields or long lists gets a real page with its own
     * back stack entry. Exhaustive so a new page has to pick one.
     */
    internal val presentation: PagePresentation
        get() = when (this) {
            SHARING, APPEARANCE, AUTOMATION, RESET, ABOUT -> PagePresentation.SHEET
            CONNECTION, CHAIN, BRIDGES, TRANSPORT, ROUTING, SECURITY, ORGANIZATION,
            HISTORY, SETUPS, TUNING -> PagePresentation.PAGE
        }

    /**
     * Technical terms people actually type, in either language. Deliberately
     * NOT string resources: "MTU" is "MTU" in Persian too, and a translator
     * localising "obfs4" would break search.
     */
    internal val tags: List<String>
        get() = when (this) {
            CONNECTION -> listOf("MASQUE", "WireGuard", "endpoint", "scan", "IPv4", "IPv6", "peer")
            CHAIN -> listOf("Tor", "Psiphon", "chain", "region")
            BRIDGES -> listOf("obfs4", "Snowflake", "WebTunnel", "meek", "bridge")
            TRANSPORT -> listOf("MTU", "DNS", "ECH", "fragment", "noize", "keepalive", "QUIC", "HTTP/2")
            ROUTING -> listOf("split tunnel", "proxy", "apps", "bypass", "block", "direct")
            SHARING -> listOf("LAN", "hotspot", "SOCKS")
            SECURITY -> listOf("kill switch", "lockdown", "IPv6 leak", "reconnect")
            ORGANIZATION -> listOf("Zero Trust", "team", "gateway", "Access")
            APPEARANCE -> listOf("theme", "dark", "language", "فارسی", "English")
            AUTOMATION -> listOf("boot", "auto connect", "launch")
            HISTORY -> listOf("sessions", "log")
            SETUPS -> listOf("preset", "backup", "import", "export")
            TUNING -> listOf("TLS", "log level", "timers", "clients")
            RESET -> listOf("defaults", "reset")
            ABOUT -> listOf("version", "license", "core")
        }
}

internal enum class PagePresentation { PAGE, SHEET }

/**
 * Four groups, named by what they do to your traffic. [summaryPages] are the
 * two destinations whose live values stand in for the whole group on its
 * collapsed card. A page must be in exactly one group (unit-tested).
 */
internal enum class SettingsGroup(
    val label: Int,
    val icon: ImageVector,
    val pages: List<SettingsPage>,
    val summaryPages: List<SettingsPage>,
) {
    CORE_TUNNEL(
        R.string.hub_group_tunnel, Icons.Rounded.Hub,
        listOf(SettingsPage.CONNECTION, SettingsPage.CHAIN, SettingsPage.BRIDGES, SettingsPage.TRANSPORT),
        listOf(SettingsPage.CONNECTION, SettingsPage.CHAIN),
    ),
    ROUTING_SAFETY(
        R.string.hub_group_routing_safety, Icons.Rounded.Shield,
        listOf(SettingsPage.ROUTING, SettingsPage.SHARING, SettingsPage.SECURITY, SettingsPage.ORGANIZATION),
        listOf(SettingsPage.SECURITY, SettingsPage.ROUTING),
    ),
    APP(
        R.string.hub_group_app, Icons.Rounded.Smartphone,
        listOf(SettingsPage.APPEARANCE, SettingsPage.AUTOMATION, SettingsPage.HISTORY, SettingsPage.SETUPS),
        listOf(SettingsPage.APPEARANCE, SettingsPage.SETUPS),
    ),
    ADVANCED(
        R.string.hub_group_advanced, Icons.Rounded.Tune,
        listOf(SettingsPage.TUNING, SettingsPage.RESET, SettingsPage.ABOUT),
        listOf(SettingsPage.TUNING, SettingsPage.ABOUT),
    ),
}

internal val SettingsPage.group: SettingsGroup
    get() = SettingsGroup.entries.first { this in it.pages }

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

private val WHITESPACE = Regex("\\s+")

/**
 * Kept for the existing tests and callers. Now built on [foldSettingsText], so
 * it also folds alef maksura and Persian/Arabic-Indic digits.
 */
internal fun normalizeSettingsText(raw: String): String =
    foldSettingsText(raw).replace(WHITESPACE, " ").trim()

/** Strict all-terms substring match. The hub uses [searchSettings] now. */
internal fun matchesSettingsQuery(query: String, fields: List<String>): Boolean {
    val terms = normalizeSettingsText(query).split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val haystack = fields.joinToString(" ") { normalizeSettingsText(it) }
    return terms.all { haystack.contains(it) }
}

/**
 * One destination's body. Used both as a full page (from [HomeScreen]) and
 * inside the hub's sheet, which already shows the title and subtitle, hence
 * [showSubtitle]. The old full-width lock banner is now a single badge.
 */
@Composable
fun SettingsPageBody(
    page: SettingsPage,
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    settingsEnabled: Boolean,
    modifier: Modifier = Modifier,
    showSubtitle: Boolean = true,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        if (showSubtitle) {
            Spacer(Modifier.height(4.dp))
            Text(
                settingsPageSubtitle(page),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!settingsEnabled && page.showsLockNotice) {
            Spacer(Modifier.height(14.dp))
            LockBadge()
        }
        Spacer(Modifier.height(24.dp))
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
