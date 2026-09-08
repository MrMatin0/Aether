package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.ThemeMode
import studio.cluvex.aether.ui.components.*

/**
 * Keep existing stored theme preferences; this redesign adds no theme modes.
 *
 * The theme write goes through AppPrefs.mutate for the same reason as the
 * Automation switches: `behaviour.copy(themeMode = it)` would carry this
 * composition's stale copy of the automation flags and could revert a toggle
 * the user flipped a moment earlier.
 *
 * The two labels this used to keep private now live in Labels.kt, because the
 * settings index shows the current theme and language on its Appearance row.
 */
@Composable
fun AppearancePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val behaviour by AppPrefs.state.collectAsState()
    val storedLanguage = AppLocale.stored(context)
    Column(modifier.fillMaxWidth()) {
        ControlSection(stringResource(R.string.theme_title), stringResource(R.string.theme_subtitle), Icons.Rounded.Palette) {
            SegmentedSelector(listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT), behaviour.themeMode,
                onSelect = { mode -> AppPrefs.mutate(context) { it.copy(themeMode = mode) } }, label = { themeModeLabel(it) })
            Hint(stringResource(R.string.theme_hint))
        }
        Spacer(Modifier.height(32.dp)); Hairline(); Spacer(Modifier.height(32.dp))
        ControlSection(stringResource(R.string.language_title), stringResource(R.string.language_subtitle), Icons.Rounded.Language) {
            SegmentedSelector(listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.PERSIAN), storedLanguage,
                onSelect = { switchLanguage(context, it) }, label = { appLanguageLabel(it) })
            Hint(stringResource(R.string.language_hint))
        }
    }
}
