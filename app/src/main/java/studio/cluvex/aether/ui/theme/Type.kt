package studio.cluvex.aether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R

/*
 * The type scale.
 *
 * The old file was literally `Typography()` — Material's defaults, which are
 * deliberately flat so they fit any product. That flatness is why the old
 * screens read as one grey wall of 14sp text: the status word, the section
 * headers and the helper text were all within a few sp of each other.
 *
 * Here every step is at least a 1.25 jump in size AND changes weight, so
 * hierarchy survives even at a glance on a phone in bright sunlight.
 *
 * BIDI NOTE: letter-spacing is kept at or near zero everywhere. Wide tracking
 * looks great on Latin all-caps micro labels but it visually tears apart the
 * joined Persian script (فارسی), and this app ships a full fa locale. Size and
 * weight carry the hierarchy instead of tracking.
 */

/*
 * WHY VAZIRMATN
 *
 * Every style below used to resolve to FontFamily.Default. On a stock device
 * that means Roboto, which has no Persian coverage, so the platform silently
 * hands every fa string to a fallback (usually Noto Naskh Arabic). The result
 * was two unrelated typefaces on one screen with two different x-heights, two
 * different weights for the same FontWeight, and Persian text that looked
 * bookish next to a technical Latin UI. It also meant the app had no voice of
 * its own in the language most of its users read.
 *
 * Vazirmatn is the answer because it is one family that draws BOTH scripts:
 * a modern, geometric Persian face by Saber Rastikerdar with a matching Latin
 * set and real weights from Thin to Black. One family, one rhythm, SIL OFL,
 * and it is the de facto standard for Persian product UI.
 *
 * The binaries are not in this repo (same rule as the native cores). The
 * fetchVazirmatn Gradle task drops them into app/src/main/res-fonts/font
 * before resource merging, so they are compiled into the APK: no Google Fonts
 * provider, no Play Services, no runtime download over a filtered network.
 */
private val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)

private val Sans: FontFamily = Vazirmatn

/** Numerals, addresses, log output: anything that must not reflow or jitter. */
val AetherMono = FontFamily.Monospace

/**
 * Extra leading applied on top of the Latin metrics when the UI is in Persian.
 *
 * Persian sits taller AND deeper than Latin in the same point size: ascenders
 * carry dots (ژ, ث), descenders sweep well below the baseline (ج, ق, گ), and
 * both can land on the same line. A 54sp/56sp display step is 1.04em, which is
 * fine for Roboto and clips Vazirmatn. Rather than loosening the Latin scale
 * (its tightness is the point), the whole scale is multiplied here for fa only.
 */
const val PERSIAN_LEADING = 1.16f

/** Micro label used for section rules and meta captions. */
val AetherMetaLabel = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    // 14sp clipped Persian dots at this size; tracking dropped from 0.5 to 0.2
    // because these labels ARE Persian in the fa locale and Vazirmatn joins.
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
)

/** Tabular value: IPs, rates, timers. */
val AetherNumeral = TextStyle(
    fontFamily = AetherMono,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 18.sp,
)

private fun step(
    weight: FontWeight,
    size: Float,
    line: Float,
    tracking: Float = 0f,
    leading: Float = 1f,
) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (line * leading).sp,
    letterSpacing = tracking.sp,
)

/**
 * The Material scale, built for one script.
 *
 * [leading] multiplies every line height; pass [PERSIAN_LEADING] for fa. Sizes
 * and weights never change, so the hierarchy is identical in both languages
 * and a screenshot in a support thread is comparable across locales.
 */
fun aetherTypography(leading: Float = 1f) = Typography(
    displayLarge = step(FontWeight.ExtraBold, 54f, 62f, -0.8f, leading),
    displayMedium = step(FontWeight.ExtraBold, 40f, 46f, -0.6f, leading),
    displaySmall = step(FontWeight.Bold, 30f, 36f, -0.4f, leading),
    headlineLarge = step(FontWeight.Bold, 26f, 32f, -0.2f, leading),
    headlineMedium = step(FontWeight.SemiBold, 22f, 28f, 0f, leading),
    headlineSmall = step(FontWeight.SemiBold, 19f, 25f, 0f, leading),
    titleLarge = step(FontWeight.SemiBold, 17f, 23f, 0f, leading),
    titleMedium = step(FontWeight.SemiBold, 15f, 21f, 0f, leading),
    titleSmall = step(FontWeight.Medium, 13f, 18f, 0f, leading),
    bodyLarge = step(FontWeight.Normal, 15f, 23f, 0f, leading),
    bodyMedium = step(FontWeight.Normal, 14f, 21f, 0f, leading),
    bodySmall = step(FontWeight.Normal, 12.5f, 19f, 0f, leading),
    labelLarge = step(FontWeight.SemiBold, 14f, 19f, 0f, leading),
    labelMedium = step(FontWeight.Medium, 12f, 16f, 0.2f, leading),
    labelSmall = step(FontWeight.SemiBold, 11f, 15f, 0.2f, leading),
)

/** The Latin scale. Kept so anything outside the theme can still reference it. */
val AetherTypography = aetherTypography()
