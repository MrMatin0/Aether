package studio.cluvex.aether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R

/** The bundled face is available before the VPN, in both supported scripts. */
private val Sans = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)
val AetherMono = FontFamily.Monospace
const val PERSIAN_LEADING = 1.16f
val AetherMetaLabel = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
val AetherNumeral = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
val AetherNumeralLarge = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp)
val AetherConsoleLine = TextStyle(fontFamily = AetherMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 20.sp)
private fun step(weight: FontWeight, size: Int, line: Int, leading: Float) = TextStyle(
    fontFamily = Sans, fontWeight = weight, fontSize = size.sp, lineHeight = (line * leading).sp, letterSpacing = 0.sp,
)
/** Deliberate jumps: 12, 16, 20, 26, 34, 44. Never track joined Persian letters. */
fun aetherTypography(leading: Float = 1f) = Typography(
    displayLarge = step(FontWeight.ExtraBold, 54, 64, leading),
    displayMedium = step(FontWeight.ExtraBold, 44, 52, leading),
    displaySmall = step(FontWeight.Bold, 34, 44, leading),
    headlineLarge = step(FontWeight.Bold, 34, 44, leading),
    headlineMedium = step(FontWeight.Bold, 26, 36, leading),
    headlineSmall = step(FontWeight.SemiBold, 26, 36, leading),
    titleLarge = step(FontWeight.SemiBold, 20, 28, leading),
    titleMedium = step(FontWeight.SemiBold, 16, 24, leading),
    titleSmall = step(FontWeight.SemiBold, 16, 24, leading),
    bodyLarge = step(FontWeight.Normal, 16, 28, leading),
    bodyMedium = step(FontWeight.Normal, 16, 24, leading),
    bodySmall = step(FontWeight.Normal, 14, 24, leading),
    labelLarge = step(FontWeight.SemiBold, 14, 24, leading),
    labelMedium = step(FontWeight.Medium, 12, 20, leading),
    labelSmall = step(FontWeight.Medium, 12, 20, leading),
)
val AetherTypography = aetherTypography()
