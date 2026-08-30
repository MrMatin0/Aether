package studio.cluvex.aether.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Kept as a thin forwarder to [AppearancePanel].
 *
 * The language controls merged into the Appearance page, because theme and
 * language answer the same question ("can I read this") and splitting them
 * across two places was how the old Settings list ended up with a language
 * selector three scrolls below MTU. This alias exists so any out-of-tree caller
 * or patch that still names LanguagePanel keeps compiling.
 */
@Deprecated(
    message = "Language moved into AppearancePanel, which also carries the theme control.",
    replaceWith = ReplaceWith("AppearancePanel(modifier)"),
)
@Composable
fun LanguagePanel(modifier: Modifier = Modifier) {
    AppearancePanel(modifier)
}
