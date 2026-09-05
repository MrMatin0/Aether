package studio.cluvex.aether.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

/** Folio: low-chroma paper with plum reserved for interaction, not connection state.
 * OKLCH tokens are converted once at initialization, never during composition.
 * Existing dark-mode tokens and public aliases remain source-compatible.
 */
private fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    val radians = hue * Math.PI / 180.0
    val a = chroma * cos(radians)
    val b = chroma * sin(radians)
    val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    fun encode(v: Double): Float = (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()
    return Color(
        red = encode(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
        green = encode(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
        blue = encode(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
    )
}

// Preserve the installed dark appearance rather than overriding a user preference.
val Ink00 = Color(0xFF05080F)
val Ink05 = Color(0xFF080C16)
val Ink10 = Color(0xFF0D1320)
val Ink15 = Color(0xFF121929)
val Ink20 = Color(0xFF182034)
val Ink30 = Color(0xFF212B42)
val Ink40 = Color(0xFF303D58)
val InkHigh = Color(0xFFE9EDF8)
val InkMid = Color(0xFF9CA8C3)
val InkLow = Color(0xFF677491)

val Paper00 = oklch(0.975, 0.008, 330.0)
val Paper05 = oklch(0.980, 0.006, 330.0)
val Paper10 = oklch(0.992, 0.004, 330.0)
val Paper15 = oklch(0.962, 0.008, 330.0)
val Paper20 = oklch(0.945, 0.010, 330.0)
val Paper30 = oklch(0.875, 0.012, 330.0)
val Paper40 = oklch(0.610, 0.015, 330.0)
val PaperHigh = oklch(0.245, 0.012, 330.0)
val PaperMid = oklch(0.470, 0.012, 330.0)
val PaperLow = oklch(0.540, 0.012, 330.0)

val Iris = Color(0xFF8792FF)
val IrisDim = Color(0xFF5764EA)
val IrisStrong = oklch(0.450, 0.135, 330.0)
val OnIrisDark = Color(0xFF080B1E)
val IrisWashDark = Color(0xFF161C3A)
val IrisWashLight = oklch(0.935, 0.025, 330.0)

val Mint = Color(0xFF3FE0A6)
val MintStrong = oklch(0.450, 0.090, 155.0)
val OnMintDark = Color(0xFF002215)
val MintWashDark = Color(0xFF07281E)
val MintWashLight = oklch(0.950, 0.025, 155.0)
val Amber = Color(0xFFFFC15E)
val AmberStrong = oklch(0.470, 0.100, 75.0)
val OnAmberDark = Color(0xFF241500)
val AmberWashDark = Color(0xFF2A2008)
val AmberWashLight = oklch(0.950, 0.030, 75.0)
val Rose = Color(0xFFFF808C)
val RoseStrong = oklch(0.470, 0.155, 25.0)
val OnRoseDark = Color(0xFF2B0409)
val RoseWashDark = Color(0xFF2E1015)
val RoseWashLight = oklch(0.950, 0.025, 25.0)
val ConsoleInk = Color(0xFF04060C)
val ConsoleInkLight = Color(0xFF101725)

@Immutable
data class AetherAccents(
    val dark: Boolean,
    val protected: Color, val onProtected: Color, val protectedWash: Color,
    val working: Color, val onWorking: Color, val workingWash: Color,
    val failed: Color, val onFailed: Color, val failedWash: Color,
    val neutral: Color,
    val brand: Color, val onBrand: Color, val brandWash: Color,
    val backdropTop: Color, val backdropBottom: Color,
    val card: Color, val cardBorder: Color, val dock: Color,
    val console: Color, val onConsole: Color,
)

val DarkAccents = AetherAccents(
    dark = true,
    protected = Mint, onProtected = OnMintDark, protectedWash = MintWashDark,
    working = Amber, onWorking = OnAmberDark, workingWash = AmberWashDark,
    failed = Rose, onFailed = OnRoseDark, failedWash = RoseWashDark,
    neutral = InkMid, brand = Iris, onBrand = OnIrisDark, brandWash = IrisWashDark,
    backdropTop = Ink05, backdropBottom = Ink00,
    card = Ink15, cardBorder = Ink30, dock = Ink20, console = ConsoleInk, onConsole = InkHigh,
)
val LightAccents = AetherAccents(
    dark = false,
    protected = MintStrong, onProtected = Paper10, protectedWash = MintWashLight,
    working = AmberStrong, onWorking = Paper10, workingWash = AmberWashLight,
    failed = RoseStrong, onFailed = Paper10, failedWash = RoseWashLight,
    neutral = PaperMid, brand = IrisStrong, onBrand = Paper10, brandWash = IrisWashLight,
    backdropTop = Paper00, backdropBottom = Paper00,
    card = Paper10, cardBorder = Paper30, dock = Paper10, console = ConsoleInkLight, onConsole = InkHigh,
)

// Public compatibility aliases used by older components outside this screen layer.
val Carbon00 = Ink00
val Carbon05 = Ink05
val Carbon10 = Ink10
val Carbon15 = Ink15
val Carbon20 = Ink20
val Carbon30 = Ink30
val Carbon40 = Ink40
val Chalk = InkHigh
val ChalkMuted = InkMid
val ChalkFaint = InkLow
val Signal = Mint
val SignalDeep = MintStrong
val SignalWash = MintWashDark
val OnSignal = OnMintDark
val Ember = Amber
val EmberWash = AmberWashDark
val OnEmber = OnAmberDark
val Clay = Rose
val ClayWash = RoseWashDark
val OnClay = OnRoseDark
val Navy900 = Ink00
val Navy800 = Ink10
val Navy700 = Ink20
val Navy600 = Ink30
val AetherBlue = Iris
val AetherCyan = Iris
val AetherError = Rose
val OnDark = InkHigh
val OnDarkMuted = InkMid
