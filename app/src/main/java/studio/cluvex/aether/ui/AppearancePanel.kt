package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.ThemeMode
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.switchLanguage
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * Theme and language, together, on the first page of Settings.
 *
 * They are the same question — "can I read this" — and they used to be in two
 * completely different places: language was a panel three scrolls into the old
 * Settings mega-list, and theme did not exist at all because the app was
 * hard-coded to dark.
 *
 * Language keeps THREE options here where the header switch offers two: "System"
 * is the right default for anyone who never thinks about it, and it is the one
 * thing a two-character pill cannot express.
 */
@Composable
fun AppearancePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    val behaviour by AppPrefs.state.collectAsState()
    val storedLanguage = AppLocale.stored(context)

    Column(modifier = modifier.fillMaxWidth()) {
        AetherCard {
            CardHeader(
                title = stringResource(R.string.theme_title),
                subtitle = stringResource(R.string.theme_subtitle),
                icon = Icons.Rounded.DarkMode,
                tint = accents.brand,
            )
            Spacer(Modifier.height(14.dp))
            SegmentedSelector(
                options = listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT),
                selected = behaviour.themeMode,
                onSelect = { mode ->
                    AppPrefs.update(context, behaviour.copy(themeMode = mode))
                },
                label = { themeLabel(it) },
            )
            Hint(stringResource(R.string.theme_hint))
        }

        Spacer(Modifier.height(16.dp))

        AetherCard {
            CardHeader(
                title = stringResource(R.string.language_title),
                subtitle = stringResource(R.string.language_subtitle),
                icon = Icons.Rounded.Language,
                tint = accents.brand,
            )
            Spacer(Modifier.height(14.dp))
            SegmentedSelector(
                options = listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.PERSIAN),
                selected = storedLanguage,
                onSelect = { language -> switchLanguage(context, language) },
                label = { languageLabel(it) },
            )
            Hint(stringResource(R.string.language_hint))
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.LIGHT -> R.string.theme_light
    },
)

@Composable
private fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.PERSIAN -> R.string.language_persian
    },
)
