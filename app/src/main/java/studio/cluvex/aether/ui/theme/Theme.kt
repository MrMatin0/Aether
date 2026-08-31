package studio.cluvex.aether.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.ThemeMode

/**
 * The tokens Material has no role for. Static because the whole tree is
 * repainted when the mode flips anyway, and a static local costs nothing to
 * read inside a draw lambda.
 */
val LocalAetherAccents = staticCompositionLocalOf { DarkAccents }

/*
 * WHY THE SCHEME IS STILL HAND-BUILT (and dynamic colour is still gone)
 *
 * Unchanged from the previous redesign, and still the reason:
 *
 *   1. Wallpaper-derived colour destroys state meaning. "Connected" drawn with
 *      colorScheme.primary on a warm wallpaper comes out the same orange as
 *      "working".
 *   2. Contrast becomes a lottery; low-chroma schemes erase the log console
 *      and every hairline.
 *   3. A circumvention tool needs one recognisable identity, or screenshots in
 *      a support thread stop matching what the reporter sees.
 *
 * WHAT IS NEW: there are two schemes now instead of one. Light is not a
 * generated inverse — it is specified, including the whole surfaceContainer*
 * family, because dropdown menus and dialogs read those roles and Material's
 * defaults land on a mauve grey that matches nothing else here.
 */
private val DarkScheme = darkColorScheme(
    primary = Iris,
    onPrimary = OnIrisDark,
    primaryContainer = IrisWashDark,
    onPrimaryContainer = Iris,
    inversePrimary = IrisDim,

    secondary = Mint,
    onSecondary = OnMintDark,
    secondaryContainer = MintWashDark,
    onSecondaryContainer = Mint,

    tertiary = Amber,
    onTertiary = OnAmberDark,
    tertiaryContainer = AmberWashDark,
    onTertiaryContainer = Amber,

    background = Ink00,
    onBackground = InkHigh,

    surface = Ink10,
    onSurface = InkHigh,
    surfaceVariant = Ink20,
    onSurfaceVariant = InkMid,
    surfaceTint = Iris,

    surfaceBright = Ink30,
    surfaceDim = Ink00,
    surfaceContainerLowest = Ink00,
    surfaceContainerLow = Ink05,
    surfaceContainer = Ink10,
    surfaceContainerHigh = Ink15,
    surfaceContainerHighest = Ink20,

    inverseSurface = InkHigh,
    inverseOnSurface = Ink00,

    error = Rose,
    onError = OnRoseDark,
    errorContainer = RoseWashDark,
    onErrorContainer = Rose,

    outline = Ink40,
    outlineVariant = Ink30,
    scrim = Color(0xCC03050A),
)

private val LightScheme = lightColorScheme(
    primary = IrisStrong,
    onPrimary = Color.White,
    primaryContainer = IrisWashLight,
    onPrimaryContainer = IrisStrong,
    inversePrimary = Iris,

    secondary = MintStrong,
    onSecondary = Color.White,
    secondaryContainer = MintWashLight,
    onSecondaryContainer = MintStrong,

    tertiary = AmberStrong,
    onTertiary = Color.White,
    tertiaryContainer = AmberWashLight,
    onTertiaryContainer = AmberStrong,

    background = Paper00,
    onBackground = PaperHigh,

    surface = Paper10,
    onSurface = PaperHigh,
    surfaceVariant = Paper20,
    onSurfaceVariant = PaperMid,
    surfaceTint = IrisStrong,

    surfaceBright = Paper10,
    surfaceDim = Paper20,
    surfaceContainerLowest = Paper10,
    surfaceContainerLow = Paper05,
    surfaceContainer = Paper15,
    surfaceContainerHigh = Paper20,
    surfaceContainerHighest = Paper30,

    inverseSurface = PaperHigh,
    inverseOnSurface = Paper05,

    error = RoseStrong,
    onError = Color.White,
    errorContainer = RoseWashLight,
    onErrorContainer = RoseStrong,

    outline = Paper40,
    outlineVariant = Paper30,
    scrim = Color(0x99101828),
)

/**
 * The app theme.
 *
 * Three things are read from outside Compose, all from the same synchronous
 * sources the rest of the app uses, so nothing can disagree about what is on
 * screen:
 *
 *  - the language, from [AppLocale] — fa gets extra leading (see
 *    [PERSIAN_LEADING]) because Vazirmatn's Persian ink extent does not fit the
 *    tight Latin display steps;
 *  - the theme mode, from [AppPrefs] — SYSTEM (default) follows the platform,
 *    DARK and LIGHT pin it. Pinning matters: half the point of this app is
 *    being usable on a borrowed or oddly-configured phone.
 *  - whether animations are wanted at all, from the platform's animator duration
 *    scale (see [rememberSystemReducedMotion]). Publishing it HERE, at the one
 *    place the whole tree already reads its tokens from, is what makes it
 *    impossible for a new screen to quietly ignore it.
 */
@Composable
fun AetherTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // Defensive: AetherApp.onCreate already did this, but the theme is also the
    // entry point for the crash Activity, which can run in a half-built process.
    remember(context) {
        AppPrefs.init(context)
        true
    }

    val behaviour by AppPrefs.state.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (behaviour.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            // Light content on a dark app, dark content on a light one — the
            // status bar icons were hardcoded to "dark background" before, so a
            // light theme would have rendered white-on-white system icons.
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val persian = AppLocale.effective(context) == AppLanguage.PERSIAN
    val typography = remember(persian) {
        aetherTypography(leading = if (persian) PERSIAN_LEADING else 1f)
    }

    CompositionLocalProvider(
        LocalAetherAccents provides if (dark) DarkAccents else LightAccents,
        LocalReducedMotion provides rememberSystemReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = typography,
            shapes = AetherShapes,
            content = content,
        )
    }
}
