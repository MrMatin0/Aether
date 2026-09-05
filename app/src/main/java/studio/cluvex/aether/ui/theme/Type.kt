package studio.cluvex.aether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R

/** Bundled for both scripts: no font-provider request over a blocked connection. */
private val Sans = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)
val AetherMono = FontFamily.Monospace
const val PERSIAN_LEADING = 1.16f

// Joined scripts must not inherit Latin-style letter spacing.
val AetherMetaLabel = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
val AetherNumeral = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.Medium,
    fontSize = 16.sp, lineHeight = 24.sp)
val AetherNumeralLarge = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp, lineHeight = 32.sp)
val AetherConsoleLine = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.Normal,
    fontSize = 12.sp, lineHeight = 20.sp)

private fun step(weight: FontWeight, size: Int, line: Int, leading: Float) = TextStyle(
    fontFamily = Sans, fontWeight = weight, fontSize = size.sp,
    lineHeight = (line * leading).sp, letterSpacing = 0.sp,
)

/** Fewer visual roles, readable body text, and no fixed-height text containers. */
fun aetherTypography(leading: Float = 1f) = Typography(
    displayLarge = step(FontWeight.Bold, 48, 56, leading),
    displayMedium = step(FontWeight.Bold, 40, 48, leading),
    displaySmall = step(FontWeight.Bold, 32, 40, leading),
    headlineLarge = step(FontWeight.Bold, 32, 40, leading),
    headlineMedium = step(FontWeight.SemiBold, 26, 34, leading),
    headlineSmall = step(FontWeight.SemiBold, 22, 30, leading),
    titleLarge = step(FontWeight.SemiBold, 20, 28, leading),
    titleMedium = step(FontWeight.SemiBold, 16, 24, leading),
    titleSmall = step(FontWeight.SemiBold, 14, 22, leading),
    bodyLarge = step(FontWeight.Normal, 16, 26, leading),
    bodyMedium = step(FontWeight.Normal, 16, 24, leading),
    bodySmall = step(FontWeight.Normal, 14, 22, leading),
    labelLarge = step(FontWeight.SemiBold, 14, 22, leading),
    labelMedium = step(FontWeight.Medium, 12, 20, leading),
    labelSmall = step(FontWeight.Medium, 12, 20, leading),
)
val AetherTypography = aetherTypography()
