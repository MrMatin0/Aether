package studio.cluvex.aether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R

/*
 * THE UI FACE - ONE PER SCRIPT
 *
 * Both faces are BUNDLED and never resolved over the network. That is the whole
 * point: this app is used on filtered networks, so a face that has to be fetched
 * at runtime is a face that is missing exactly when the app is being opened.
 * How they get into the APK: the UI FONTS section of app/build.gradle.kts.
 * Licences, provenance and pinned versions: docs/FONTS.md.
 *
 *   LATIN    Vazirmatn (OFL). Five static weights.
 *   PERSIAN  Noto Naskh Arabic UI (OFL). ONE variable file whose wght axis runs
 *            400..700, so the five weights below are five instances of the same
 *            ~200 KB resource rather than five separate downloads.
 *
 * Why not one face for both scripts, the way Vazirmatn was used before:
 * Noto Naskh Arabic UI is an Arabic-script font - google/fonts lists exactly
 * the `arabic` and `menu` subsets for it - so pointing the English UI at it
 * would hand every Latin glyph to the platform fallback, i.e. the bundled-face
 * guarantee above would silently stop applying to half the UI.
 */
private val LatinSans = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)

// One resource, five instances. The axis stops at 700, so ExtraBold asks for 700
// rather than letting the platform synthesise a fake 800 by smearing outlines -
// which on a naskh face is exactly where joined letters turn to mud.
private fun naskh(weight: FontWeight) = Font(
    resId = R.font.noto_naskh_arabic_ui,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight.coerceAtMost(700)),
    ),
)

private val PersianSans = FontFamily(
    naskh(FontWeight.Normal),
    naskh(FontWeight.Medium),
    naskh(FontWeight.SemiBold),
    naskh(FontWeight.Bold),
    naskh(FontWeight.ExtraBold),
)

/** Which face the type scale is built from. AetherTheme picks it per locale. */
enum class UiScript { LATIN, PERSIAN }

private fun sansFor(script: UiScript) = if (script == UiScript.PERSIAN) PersianSans else LatinSans

val AetherMono = FontFamily.Monospace
const val PERSIAN_LEADING = 1.16f
// Carries no fontFamily ON PURPOSE. Text() merges this over LocalTextStyle, which
// MaterialTheme fills from the typography below, so the one hand-rolled label
// style does not have to know which script is on screen.
val AetherMetaLabel = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
val AetherNumeral = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
val AetherNumeralLarge = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp)
val AetherConsoleLine = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 20.sp)
private fun step(family: FontFamily, weight: FontWeight, size: Int, line: Int, leading: Float) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = (line * leading).sp, letterSpacing = 0.sp,
)
/** Deliberate jumps: 12, 16, 20, 26, 34, 44. Never track joined Persian letters. */
fun aetherTypography(leading: Float = 1f, script: UiScript = UiScript.LATIN): Typography {
    val sans = sansFor(script)
    return Typography(
        displayLarge = step(sans, FontWeight.ExtraBold, 54, 64, leading),
        displayMedium = step(sans, FontWeight.ExtraBold, 44, 52, leading),
        displaySmall = step(sans, FontWeight.Bold, 34, 44, leading),
        headlineLarge = step(sans, FontWeight.Bold, 34, 44, leading),
        headlineMedium = step(sans, FontWeight.Bold, 26, 36, leading),
        headlineSmall = step(sans, FontWeight.SemiBold, 26, 36, leading),
        titleLarge = step(sans, FontWeight.SemiBold, 20, 28, leading),
        titleMedium = step(sans, FontWeight.SemiBold, 16, 24, leading),
        titleSmall = step(sans, FontWeight.SemiBold, 16, 24, leading),
        bodyLarge = step(sans, FontWeight.Normal, 16, 28, leading),
        bodyMedium = step(sans, FontWeight.Normal, 16, 24, leading),
        bodySmall = step(sans, FontWeight.Normal, 14, 24, leading),
        labelLarge = step(sans, FontWeight.SemiBold, 14, 24, leading),
        labelMedium = step(sans, FontWeight.Medium, 12, 20, leading),
        labelSmall = step(sans, FontWeight.Medium, 12, 20, leading),
    )
}
val AetherTypography = aetherTypography()
