package studio.cluvex.aether.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.core.findActivity
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.ThemeMode

val LocalAetherAccents = staticCompositionLocalOf { DarkAccents }

// Explicit semantic palettes preserve the installed appearance and state meanings.
private val DarkScheme = darkColorScheme(
    primary = Iris, onPrimary = OnIrisDark, primaryContainer = IrisWashDark,
    onPrimaryContainer = Iris, inversePrimary = IrisDim,
    secondary = Mint, onSecondary = OnMintDark, secondaryContainer = MintWashDark, onSecondaryContainer = Mint,
    tertiary = Amber, onTertiary = OnAmberDark, tertiaryContainer = AmberWashDark, onTertiaryContainer = Amber,
    background = Ink00, onBackground = InkHigh,
    surface = Ink10, onSurface = InkHigh, surfaceVariant = Ink20, onSurfaceVariant = InkMid, surfaceTint = Iris,
    surfaceBright = Ink30, surfaceDim = Ink00,
    surfaceContainerLowest = Ink00, surfaceContainerLow = Ink05, surfaceContainer = Ink10,
    surfaceContainerHigh = Ink15, surfaceContainerHighest = Ink20,
    inverseSurface = InkHigh, inverseOnSurface = Ink00,
    error = Rose, onError = OnRoseDark, errorContainer = RoseWashDark, onErrorContainer = Rose,
    outline = Ink40, outlineVariant = Ink30, scrim = Color(0xCC03050A),
)
private val LightScheme = lightColorScheme(
    primary = IrisStrong, onPrimary = Paper10, primaryContainer = IrisWashLight,
    onPrimaryContainer = IrisStrong, inversePrimary = Iris,
    secondary = MintStrong, onSecondary = Paper10, secondaryContainer = MintWashLight, onSecondaryContainer = MintStrong,
    tertiary = AmberStrong, onTertiary = Paper10, tertiaryContainer = AmberWashLight, onTertiaryContainer = AmberStrong,
    background = Paper00, onBackground = PaperHigh,
    surface = Paper10, onSurface = PaperHigh, surfaceVariant = Paper20, onSurfaceVariant = PaperMid, surfaceTint = IrisStrong,
    surfaceBright = Paper10, surfaceDim = Paper20,
    surfaceContainerLowest = Paper10, surfaceContainerLow = Paper05, surfaceContainer = Paper15,
    surfaceContainerHigh = Paper20, surfaceContainerHighest = Paper30,
    inverseSurface = PaperHigh, inverseOnSurface = Paper05,
    error = RoseStrong, onError = Paper10, errorContainer = RoseWashLight, onErrorContainer = RoseStrong,
    outline = Paper40, outlineVariant = Paper30, scrim = Color(0x99101828),
)

@Composable
fun AetherTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context) { AppPrefs.init(context); true }
    val behaviour by AppPrefs.state.collectAsState()
    val dark = when (behaviour.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // View contexts may be ContextThemeWrapper, not Activity itself.
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    // The language decides BOTH the leading multiplier and the bundled face the
    // type scale is built from (ui/theme/Type.kt): naskh for Persian, Vazirmatn
    // for Latin. Distinct FontFamily objects, so Compose's typeface cache cannot
    // serve the previous script's face after an in-app language switch.
    val persian = AppLocale.effective(context) == AppLanguage.PERSIAN
    val typography = remember(persian) {
        aetherTypography(
            leading = if (persian) PERSIAN_LEADING else 1f,
            script = if (persian) UiScript.PERSIAN else UiScript.LATIN,
        )
    }
    CompositionLocalProvider(
        LocalAetherAccents provides if (dark) DarkAccents else LightAccents,
        LocalReducedMotion provides rememberSystemReducedMotion(),
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme,
            typography = typography, shapes = AetherShapes, content = content)
    }
}
