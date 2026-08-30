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
 * Every step is at least a 1.2 jump in size AND changes weight, so hierarchy
 * survives at a glance on a phone in bright sunlight.
 *
 * WHAT CHANGED IN THIS PASS: displayLarge came down from 54sp to 44sp. 54sp was
 * chosen for a state word that sat on its own line; the redesigned home screen
 * puts that word inside the connect orb, where 54sp overflowed a 268dp circle
 * in fa and wrapped mid-word on a 360dp-wide phone. Everything below it is
 * nudged to keep the ratios intact.
 *
 * BIDI NOTE: letter-spacing stays at or near zero everywhere. Wide tracking
 * looks great on Latin all-caps micro labels but it visually tears apart the
 * joined Persian script (فارسی), and this app ships a full fa locale. Size and
 * weight carry the hierarchy instead of tracking.
 */

/*
 * WHY VAZIRMATN
 *
 * FontFamily.Default resolves to Roboto, which has no Persian coverage, so the
 * platform silently hands every fa string to a fallback (usually Noto Naskh
 * Arabic): two unrelated typefaces on one screen, two x-heights, two different
 * weights for the same FontWeight.
 *
 * Vazirmatn draws BOTH scripts — a modern geometric Persian face by Saber
 * Rastikerdar with a matching Latin set and real weights. One family, one
 * rhythm, SIL OFL.
 *
 * The binaries are not in this repo (same rule as the native cores). The
 * fetchVazirmatn Gradle task drops them into app/src/main/res-fonts/font before
 * resource merging, so they are compiled into the APK: no Google Fonts
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
 * Persian sits taller AND deeper than Latin at the same point size: ascenders
 * carry dots (ژ, ث), descenders sweep well below the baseline (ج, ق, گ), and
 * both can land on the same line. Rather than loosening the Latin scale (its
 * tightness is the point), the whole scale is multiplied here for fa only.
 */
const val PERSIAN_LEADING = 1.16f

/** Micro label used for section rules, tile captions and meta rows. */
val AetherMetaLabel = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
)

/** Tabular value: IPs, rates, counters. */
val AetherNumeral = TextStyle(
    fontFamily = AetherMono,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 18.sp,
)

/** The big instrument readout: session uptime, byte totals. */
val AetherNumeralLarge = TextStyle(
    fontFamily = AetherMono,
    fontWeight = FontWeight.Bold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
)

/** Log console line. Small, dense, fixed leading so 400 lines scroll evenly. */
val AetherConsoleLine = TextStyle(
    fontFamily = AetherMono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
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
 * The Material scale, built for one product.
 *
 * [leading] multiplies every line height; pass [PERSIAN_LEADING] for fa. Sizes
 * and weights never change, so the hierarchy is identical in both languages and
 * a screenshot in a support thread is comparable across locales.
 */
fun aetherTypography(leading: Float = 1f) = Typography(
    displayLarge = step(FontWeight.ExtraBold, 44f, 50f, -0.6f, leading),
    displayMedium = step(FontWeight.ExtraBold, 36f, 42f, -0.5f, leading),
    displaySmall = step(FontWeight.Bold, 28f, 34f, -0.3f, leading),
    headlineLarge = step(FontWeight.Bold, 24f, 30f, -0.2f, leading),
    headlineMedium = step(FontWeight.SemiBold, 21f, 27f, 0f, leading),
    headlineSmall = step(FontWeight.SemiBold, 18f, 24f, 0f, leading),
    titleLarge = step(FontWeight.Bold, 17f, 23f, 0f, leading),
    titleMedium = step(FontWeight.SemiBold, 15f, 21f, 0f, leading),
    titleSmall = step(FontWeight.Medium, 13.5f, 19f, 0f, leading),
    bodyLarge = step(FontWeight.Normal, 15f, 23f, 0f, leading),
    bodyMedium = step(FontWeight.Normal, 14f, 21f, 0f, leading),
    bodySmall = step(FontWeight.Normal, 12.5f, 19f, 0f, leading),
    labelLarge = step(FontWeight.SemiBold, 14f, 19f, 0f, leading),
    labelMedium = step(FontWeight.Medium, 12f, 16f, 0.1f, leading),
    labelSmall = step(FontWeight.SemiBold, 11f, 15f, 0.2f, leading),
)

/** The Latin scale. Kept so anything outside the theme can still reference it. */
val AetherTypography = aetherTypography()
