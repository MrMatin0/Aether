package studio.cluvex.aether.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.NavRow
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionTitle
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * The six settings destinations.
 *
 * ROOT CAUSE THIS FIXES (usability): Settings used to be ONE scrolling
 * destination holding seven panels back to back — language, the full ~40-control
 * engine surface, saved setups, automation, session history, sharing and about —
 * about 3500dp tall. Everything was reachable; nothing was findable. "Where do I
 * turn on always-on VPN" required scrolling past MTU, TLS groups and a Zero
 * Trust client secret field, and the Essentials/Everything toggle only shortened
 * the middle third.
 *
 * Splitting it costs one extra tap and buys a screen you can read in four
 * seconds. The order is by "how likely is this to be why you came here", same
 * principle as before:
 *
 *   1. Appearance — if the app is in a language or a contrast you cannot read,
 *      nothing below it is usable, so it can never sit underneath.
 *   2. Engine — the controls that decide whether a connection succeeds at all.
 *   3. Saved setups — the settings that worked, re-applied in one tap.
 *   4. Automation — when Aether connects by itself, and what it keeps.
 *   5. Share — occasional, and only relevant while connected.
 *   6. About — versions and links, needed once, for a bug report.
 *
 * Session history moved INTO Automation: the switch that decides whether history
 * is recorded and the list it produces are one subject, and having them in
 * different parts of one long scroll was why "turn it off" and "clear it" felt
 * like unrelated features.
 */
enum class SettingsPage { APPEARANCE, ENGINE, SETUPS, AUTOMATION, SHARING, ABOUT }

@Composable
internal fun settingsPageTitle(page: SettingsPage): String = stringResource(
    when (page) {
        SettingsPage.APPEARANCE -> R.string.page_appearance_title
        SettingsPage.ENGINE -> R.string.page_engine_title
        SettingsPage.SETUPS -> R.string.page_setups_title
        SettingsPage.AUTOMATION -> R.string.page_automation_title
        SettingsPage.SHARING -> R.string.page_sharing_title
        SettingsPage.ABOUT -> R.string.page_about_title
    },
)

@Composable
internal fun settingsPageSubtitle(page: SettingsPage): String = stringResource(
    when (page) {
        SettingsPage.APPEARANCE -> R.string.page_appearance_sub
        SettingsPage.ENGINE -> R.string.page_engine_sub
        SettingsPage.SETUPS -> R.string.page_setups_sub
        SettingsPage.AUTOMATION -> R.string.page_automation_sub
        SettingsPage.SHARING -> R.string.page_sharing_sub
        SettingsPage.ABOUT -> R.string.page_about_sub
    },
)

internal fun settingsPageIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.APPEARANCE -> Icons.Rounded.Palette
    SettingsPage.ENGINE -> Icons.Rounded.Tune
    SettingsPage.SETUPS -> Icons.Rounded.Bookmarks
    SettingsPage.AUTOMATION -> Icons.Rounded.Schedule
    SettingsPage.SHARING -> Icons.Rounded.WifiTethering
    SettingsPage.ABOUT -> Icons.Rounded.Info
}

/**
 * The hub. Two grouped cards, six entries, one line of description each.
 *
 * [scrollState] is a parameter rather than an internal `rememberScrollState` so
 * the shell can own it. The hub lives inside an AnimatedContent, whose content
 * lambda is a fresh composition per route, so anything remembered in here is
 * discarded the moment the user taps another destination.
 */
@Composable
fun SettingsHub(
    locked: Boolean,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val accents = LocalAetherAccents.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        if (locked) {
            NoticeBar(text = stringResource(R.string.settings_locked))
            Spacer(Modifier.height(16.dp))
        }

        SectionTitle(stringResource(R.string.hub_group_essentials))
        AetherCard(padding = 14.dp) {
            HubRow(SettingsPage.APPEARANCE, accents.brand, onOpen)
            Hairline(alpha = 0.5f)
            HubRow(SettingsPage.ENGINE, accents.brand, onOpen)
            Hairline(alpha = 0.5f)
            HubRow(SettingsPage.SETUPS, accents.brand, onOpen)
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(stringResource(R.string.hub_group_more))
        AetherCard(padding = 14.dp) {
            HubRow(SettingsPage.AUTOMATION, accents.brand, onOpen)
            Hairline(alpha = 0.5f)
            HubRow(SettingsPage.SHARING, accents.brand, onOpen)
            Hairline(alpha = 0.5f)
            HubRow(SettingsPage.ABOUT, accents.brand, onOpen)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HubRow(
    page: SettingsPage,
    tint: androidx.compose.ui.graphics.Color,
    onOpen: (SettingsPage) -> Unit,
) {
    NavRow(
        title = settingsPageTitle(page),
        subtitle = settingsPageSubtitle(page),
        icon = settingsPageIcon(page),
        tint = tint,
        onClick = { onOpen(page) },
    )
}

/**
 * One settings page.
 *
 * The panels themselves are unchanged in capability — every field still writes to
 * the same ConnectionProfile property through the same synchronous
 * onProfileChange path, so the scrambled-input fix and the DataStore write
 * behaviour are untouched.
 *
 * The scroll state is deliberately NOT hoisted here, unlike the hub's: opening a
 * settings page should start at the top of it every time. Only the destinations
 * you tab away from and back to need to remember where you were.
 */
@Composable
fun SettingsPageBody(
    page: SettingsPage,
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    settingsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        when (page) {
            SettingsPage.APPEARANCE -> AppearancePanel()
            SettingsPage.ENGINE -> {
                if (!settingsEnabled) {
                    NoticeBar(text = stringResource(R.string.settings_locked))
                    Spacer(Modifier.height(14.dp))
                }
                AdvancedPanel(
                    profile = profile,
                    onProfileChange = onProfileChange,
                    enabled = settingsEnabled,
                )
            }
            SettingsPage.SETUPS -> PresetsPanel(
                profile = profile,
                onProfileChange = onProfileChange,
                enabled = settingsEnabled,
            )
            SettingsPage.AUTOMATION -> {
                AutomationPanel()
                Spacer(Modifier.height(16.dp))
                HistoryPanel()
            }
            SettingsPage.SHARING -> SharePanel(
                state = state,
                profile = profile,
                onProfileChange = onProfileChange,
            )
            SettingsPage.ABOUT -> AboutPanel()
        }
        Spacer(Modifier.height(28.dp))
    }
}
