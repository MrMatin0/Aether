package studio.cluvex.aether.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * "Halo" — the palette of the redesigned UI.
 *
 * WHY IT CHANGED AGAIN
 *
 * The previous palette ("Carbon & Signal") fixed a real problem — it made
 * colour semantic — but it fixed it too hard, in two ways:
 *
 *   1. ONE accent for two unrelated jobs. Lime meant "you are protected" AND
 *      "this control is selected" AND "this is a link". So a settings screen
 *      with three selected segments looked exactly as loud as a live tunnel,
 *      and the connect button could not be the brightest thing on screen
 *      without competing with a chip.
 *   2. DARK ONLY, forever. The rationale (identity, comparable screenshots,
 *      guaranteed contrast) is sound, but it also meant the app was unreadable
 *      outdoors on a bright day, which is exactly where a phone gets used.
 *
 * So this palette does two things differently:
 *
 *   - Colour is split by ROLE, not by mood. [Iris] is the brand/interactive
 *     hue: selection, links, switches, focus. [Mint], [Amber] and [Rose] are
 *     the ONLY state hues, and they are used for nothing else. When the screen
 *     goes mint, it means one thing.
 *   - There are two neutral families instead of one: [Ink] for dark, [Paper]
 *     for light. The state hues keep the same identity in both (a darker,
 *     contrast-safe variant is used on paper), so a screenshot from a light
 *     phone is still comparable to one from a dark phone — the argument that
 *     justified the fixed scheme survives the addition of a second mode.
 *
 * Nothing here is #000 or #FFF. Flat black crushes OLED gradients, and pure
 * white glares; both neutrals are tinted a hair toward the brand hue so the
 * whole UI reads as one temperature.
 */

// ---------------------------------------------------------- neutrals: ink ---
// Dark mode. Blue-tinted graphite, seven steps so surfaces can layer without
// borders doing all the work.

/** Page background. */
val Ink00 = Color(0xFF05080F)

/** Top of the page backdrop gradient. */
val Ink05 = Color(0xFF080C16)

/** Default surface. */
val Ink10 = Color(0xFF0D1320)

/** Card fill. */
val Ink15 = Color(0xFF121929)

/** Raised surface: menus, sheets, the dock. */
val Ink20 = Color(0xFF182034)

/** Hairlines and card borders. */
val Ink30 = Color(0xFF212B42)

/** Control outlines. */
val Ink40 = Color(0xFF303D58)

/** Primary text on ink. */
val InkHigh = Color(0xFFE9EDF8)

/** Secondary text on ink. */
val InkMid = Color(0xFF9CA8C3)

/** Tertiary / disabled text on ink. */
val InkLow = Color(0xFF677491)

// -------------------------------------------------------- neutrals: paper ---
// Light mode. Never pure white as the page: a white page with white cards has
// no layering left, so the page is a cool off-white and cards are the white.

/** Page background. */
val Paper00 = Color(0xFFF1F4FB)

/** Top of the page backdrop gradient. */
val Paper05 = Color(0xFFF7F9FE)

/** Default surface / card fill. */
val Paper10 = Color(0xFFFFFFFF)

/** Quiet fill. */
val Paper15 = Color(0xFFF2F5FC)

/** Raised surface: menus, sheets, the dock. */
val Paper20 = Color(0xFFE9EEF8)

/** Hairlines and card borders. */
val Paper30 = Color(0xFFDAE1F0)

/** Control outlines. */
val Paper40 = Color(0xFFBFC9DF)

/** Primary text on paper. */
val PaperHigh = Color(0xFF0A1020)

/** Secondary text on paper. */
val PaperMid = Color(0xFF4A5771)

/** Tertiary / disabled text on paper. */
val PaperLow = Color(0xFF77839C)

// -------------------------------------------------------------- brand: iris -
// Interactive chrome ONLY. Never a connection state.

val Iris = Color(0xFF8792FF)
val IrisDim = Color(0xFF5764EA)
val IrisStrong = Color(0xFF4A55D4)
val OnIrisDark = Color(0xFF080B1E)
val IrisWashDark = Color(0xFF161C3A)
val IrisWashLight = Color(0xFFE7E9FF)

// ------------------------------------------------------------ state: mint ---
/** "Protected": the tunnel is up and verified. */
val Mint = Color(0xFF3FE0A6)
val MintStrong = Color(0xFF0E9B6E)
val OnMintDark = Color(0xFF002215)
val MintWashDark = Color(0xFF07281E)
val MintWashLight = Color(0xFFDDF7EC)

// ----------------------------------------------------------- state: amber ---
/** "Working": launching, scanning, verifying, reconnecting. */
val Amber = Color(0xFFFFC15E)
val AmberStrong = Color(0xFF9A6400)
val OnAmberDark = Color(0xFF241500)
val AmberWashDark = Color(0xFF2A2008)
val AmberWashLight = Color(0xFFFFF0D6)

// ------------------------------------------------------------ state: rose ---
/** "Failed". */
val Rose = Color(0xFFFF808C)
val RoseStrong = Color(0xFFCE3547)
val OnRoseDark = Color(0xFF2B0409)
val RoseWashDark = Color(0xFF2E1015)
val RoseWashLight = Color(0xFFFFE3E6)

// --------------------------------------------------------------- console ----
// The engine log keeps a terminal look in BOTH modes. A monospaced log on a
// white card reads as prose; on near-black it reads as output, and that is the
// one surface where people are scanning for a single red line.
val ConsoleInk = Color(0xFF04060C)
val ConsoleInkLight = Color(0xFF101725)

/**
 * The tokens Material 3 has no role for.
 *
 * Components read these from [studio.cluvex.aether.ui.theme.LocalAetherAccents]
 * instead of importing raw constants, which is what used to make a component
 * silently dark-only.
 */
@Immutable
data class AetherAccents(
    val dark: Boolean,
    /** State: protected. */
    val protected: Color,
    val onProtected: Color,
    val protectedWash: Color,
    /** State: working. */
    val working: Color,
    val onWorking: Color,
    val workingWash: Color,
    /** State: failed. */
    val failed: Color,
    val onFailed: Color,
    val failedWash: Color,
    /** State: off. Deliberately a neutral, not a hue. */
    val neutral: Color,
    /** Brand / interactive. */
    val brand: Color,
    val onBrand: Color,
    val brandWash: Color,
    /** Page backdrop gradient. */
    val backdropTop: Color,
    val backdropBottom: Color,
    /** Card fill + its 1px edge. */
    val card: Color,
    val cardBorder: Color,
    /** The floating bottom dock. */
    val dock: Color,
    /** Engine log console. */
    val console: Color,
    val onConsole: Color,
)

val DarkAccents = AetherAccents(
    dark = true,
    protected = Mint,
    onProtected = OnMintDark,
    protectedWash = MintWashDark,
    working = Amber,
    onWorking = OnAmberDark,
    workingWash = AmberWashDark,
    failed = Rose,
    onFailed = OnRoseDark,
    failedWash = RoseWashDark,
    neutral = InkMid,
    brand = Iris,
    onBrand = OnIrisDark,
    brandWash = IrisWashDark,
    backdropTop = Ink05,
    backdropBottom = Ink00,
    card = Ink15,
    cardBorder = Ink30,
    dock = Ink20,
    console = ConsoleInk,
    onConsole = InkHigh,
)

val LightAccents = AetherAccents(
    dark = false,
    protected = MintStrong,
    onProtected = Color.White,
    protectedWash = MintWashLight,
    working = AmberStrong,
    onWorking = Color.White,
    workingWash = AmberWashLight,
    failed = RoseStrong,
    onFailed = Color.White,
    failedWash = RoseWashLight,
    neutral = PaperMid,
    brand = IrisStrong,
    onBrand = Color.White,
    brandWash = IrisWashLight,
    backdropTop = Paper05,
    backdropBottom = Paper00,
    card = Paper10,
    cardBorder = Paper30,
    dock = Paper10,
    console = ConsoleInkLight,
    onConsole = InkHigh,
)

// --------------------------------------------------------------- aliases ----
// Kept so anything outside ui/ that still names a "Carbon & Signal" token
// compiles. New code must use the tokens above, or better, AetherAccents.

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
