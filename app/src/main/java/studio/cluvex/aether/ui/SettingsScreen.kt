package studio.cluvex.aether.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.LocalAetherAccents

enum class SettingsPage { APPEARANCE, ENGINE, SETUPS, AUTOMATION, SHARING, ABOUT }

@Composable
internal fun settingsPageTitle(page: SettingsPage): String = stringResource(when (page) {
    SettingsPage.APPEARANCE -> R.string.page_appearance_title
    SettingsPage.ENGINE -> R.string.page_engine_title
    SettingsPage.SETUPS -> R.string.page_setups_title
    SettingsPage.AUTOMATION -> R.string.page_automation_title
    SettingsPage.SHARING -> R.string.page_sharing_title
    SettingsPage.ABOUT -> R.string.page_about_title
})

@Composable
internal fun settingsPageSubtitle(page: SettingsPage): String = stringResource(when (page) {
    SettingsPage.APPEARANCE -> R.string.page_appearance_sub
    SettingsPage.ENGINE -> R.string.page_engine_sub
    SettingsPage.SETUPS -> R.string.page_setups_sub
    SettingsPage.AUTOMATION -> R.string.page_automation_sub
    SettingsPage.SHARING -> R.string.page_sharing_sub
    SettingsPage.ABOUT -> R.string.page_about_sub
})

internal fun settingsPageIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.APPEARANCE -> Icons.Rounded.Palette
    SettingsPage.ENGINE -> Icons.Rounded.Tune
    SettingsPage.SETUPS -> Icons.Rounded.Bookmarks
    SettingsPage.AUTOMATION -> Icons.Rounded.Schedule
    SettingsPage.SHARING -> Icons.Rounded.WifiTethering
    SettingsPage.ABOUT -> Icons.Rounded.Info
}

/** The engine is primary; personal and occasional controls stay quiet. */
@Composable
fun SettingsHub(
    locked: Boolean, onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier, scrollState: ScrollState = rememberScrollState(),
) {
    val accents = LocalAetherAccents.current
    Column(modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(12.dp))
        Surface(onClick = { onOpen(SettingsPage.ENGINE) }, color = accents.brandWash,
            contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, tint = accents.brand)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = accents.brand)
                }
                Spacer(Modifier.height(24.dp))
                Text(settingsPageTitle(SettingsPage.ENGINE), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(settingsPageSubtitle(SettingsPage.ENGINE), style = MaterialTheme.typography.bodyMedium)
                if (locked) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_locked), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        SectionTitle(stringResource(R.string.hub_group_essentials))
        listOf(SettingsPage.APPEARANCE, SettingsPage.SETUPS).forEach { page -> HubRow(page, onOpen); Hairline() }
        Spacer(Modifier.height(32.dp))
        SectionTitle(stringResource(R.string.hub_group_more))
        listOf(SettingsPage.AUTOMATION, SettingsPage.SHARING, SettingsPage.ABOUT).forEach { page -> HubRow(page, onOpen); Hairline() }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun HubRow(page: SettingsPage, onOpen: (SettingsPage) -> Unit) {
    NavRow(title = settingsPageTitle(page), subtitle = settingsPageSubtitle(page),
        icon = settingsPageIcon(page), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { onOpen(page) })
}

@Composable
fun SettingsPageBody(
    page: SettingsPage, state: ConnectionState, profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit, settingsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(12.dp))
        when (page) {
            SettingsPage.APPEARANCE -> AppearancePanel()
            SettingsPage.ENGINE -> {
                if (!settingsEnabled) { NoticeBar(stringResource(R.string.settings_locked)); Spacer(Modifier.height(24.dp)) }
                AdvancedPanel(profile = profile, onProfileChange = onProfileChange, enabled = settingsEnabled)
            }
            SettingsPage.SETUPS -> PresetsPanel(profile = profile, onProfileChange = onProfileChange, enabled = settingsEnabled)
            SettingsPage.AUTOMATION -> { AutomationPanel(); Spacer(Modifier.height(32.dp)); HistoryPanel() }
            SettingsPage.SHARING -> SharePanel(state = state, profile = profile, onProfileChange = onProfileChange)
            SettingsPage.ABOUT -> AboutPanel()
        }
        Spacer(Modifier.height(32.dp))
    }
}
