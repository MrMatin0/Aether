package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SectionRule
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.switchLanguage

/**
 * The full language control: the header pill only offers the two real
 * languages, this one also offers "follow the phone", which is the right
 * default for anyone who never thinks about it.
 *
 * First panel in Settings deliberately: if you are here because the app is in
 * the wrong language, you should not have to scroll past MTU to fix it.
 */
@Composable
fun LanguagePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stored = AppLocale.stored(context)

    Column(modifier = modifier.fillMaxWidth()) {
        SectionRule(label = stringResource(R.string.language_title), topSpace = 20)
        SegmentedSelector(
            options = listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.PERSIAN),
            selected = stored,
            onSelect = { language -> switchLanguage(context, language) },
            label = { languageLabel(it) },
        )
        Hint(stringResource(R.string.language_hint))
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.PERSIAN -> stringResource(R.string.language_persian)
}
