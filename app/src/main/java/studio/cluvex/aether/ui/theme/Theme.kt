package studio.cluvex.aether.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale

/*
 * WHY DYNAMIC COLOUR IS GONE
 *
 * The old theme called dynamicDarkColorScheme() on Android 12+, so the app's
 * colours came from the user's wallpaper. Three problems, all real:
 *
 *   1. State colour stopped meaning anything. "Connected" was drawn with
 *      colorScheme.primary on some screens and a hardcoded cyan on others, so
 *      on a warm wallpaper the "safe" colour could come out the same orange as
 *      the "failed" colour.
 *   2. Contrast was a lottery. Wallpaper-derived schemes can land on low
 *      chroma greys where the log console and the hairline rules disappear.
 *   3. A censorship-circumvention tool needs a recognisable identity. If every
 *      phone renders it differently, screenshots in a support thread stop
 *      matching what the person reporting the bug sees.
 *
 * So the scheme is now fixed and hand-built. The full surfaceContainer* family
 * is specified too: dropdown menus and bottom sheets read those roles, and
 * leaving them at Material's defaults is why menus used to appear as slightly
 * purple grey boxes that matched nothing else on screen.
 */
private val AetherColorScheme = darkColorScheme(
    primary = Signal,
    onPrimary = OnSignal,
    primaryContainer = SignalWash,
    onPrimaryContainer = Signal,
    inversePrimary = SignalDeep,

    secondary = Ember,
    onSecondary = OnEmber,
    secondaryContainer = EmberWash,
    onSecondaryContainer = Ember,

    tertiary = Chalk,
    onTertiary = Carbon00,
    tertiaryContainer = Carbon20,
    onTertiaryContainer = Chalk,

    background = Carbon00,
    onBackground = Chalk,

    surface = Carbon10,
    onSurface = Chalk,
    surfaceVariant = Carbon20,
    onSurfaceVariant = ChalkMuted,
    surfaceTint = Signal,

    surfaceBright = Carbon30,
    surfaceDim = Carbon00,
    surfaceContainerLowest = Carbon00,
    surfaceContainerLow = Carbon05,
    surfaceContainer = Carbon10,
    surfaceContainerHigh = Carbon15,
    surfaceContainerHighest = Carbon20,

    inverseSurface = Chalk,
    inverseOnSurface = Carbon00,

    error = Clay,
    onError = OnClay,
    errorContainer = ClayWash,
    onErrorContainer = Clay,

    outline = Carbon40,
    outlineVariant = Carbon30,
    scrim = Color(0xCC050704),
)

/**
 * The app theme. Always dark, always these colours.
 *
 * The type scale is the one thing that is not identical in both languages: fa
 * gets extra leading (see [PERSIAN_LEADING]) because Vazirmatn's Persian ink
 * extent does not fit the tight Latin display steps. The language is read from
 * [AppLocale], the same source of truth the header pill and Settings use, so
 * there is no second place that can disagree about which language is on screen.
 */
@Composable
fun AetherTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    val context = LocalContext.current
    val persian = AppLocale.effective(context) == AppLanguage.PERSIAN
    val typography = remember(persian) {
        aetherTypography(leading = if (persian) PERSIAN_LEADING else 1f)
    }

    MaterialTheme(
        colorScheme = AetherColorScheme,
        typography = typography,
        content = content,
    )
}
