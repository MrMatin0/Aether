package studio.cluvex.aether.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * "Carbon & Signal" — the palette of the redesigned UI.
 *
 * WHY IT CHANGED: the old palette was deep navy + neon cyan/blue, i.e. exactly
 * what every other tunnel app looks like, and the accent carried no meaning
 * (cyan was used for "connected", for icons, for links and for selected chips
 * at the same time). The new palette does two things instead:
 *
 *   1. Neutrals are a warm CARBON, tinted a hair toward the accent hue rather
 *      than being pure grey/black. Nothing here is #000 or #FFF: flat black
 *      crushes OLED gradients and pure white glares in a dark UI.
 *   2. Colour is SEMANTIC and rationed. Signal (lime) means "protected", Ember
 *      means "working", Clay means "failed". Nothing decorative is tinted, so
 *      when the screen goes lime the user knows exactly what that means from
 *      across the room.
 */

// ---------------------------------------------------------------- carbon ----

/** Page background. */
val Carbon00 = Color(0xFF090B08)

/** Top-of-page background, used for the vertical backdrop gradient. */
val Carbon05 = Color(0xFF0D100B)

/** Default surface. */
val Carbon10 = Color(0xFF12160F)

/** Raised surface (menus, sheets). */
val Carbon15 = Color(0xFF171C14)

/** Highest surface / quiet fills. */
val Carbon20 = Color(0xFF1D2319)

/** Hairline rules. */
val Carbon30 = Color(0xFF262E20)

/** Outlines and control borders. */
val Carbon40 = Color(0xFF333D2B)

// ------------------------------------------------------------------ text ----

/** Primary text. */
val Chalk = Color(0xFFEDF2E6)

/** Secondary text, labels. */
val ChalkMuted = Color(0xFFA6B09B)

/** Tertiary text, hints, disabled. */
val ChalkFaint = Color(0xFF6C7663)

// ---------------------------------------------------------------- signal ----

/** The one accent: "you are protected". */
val Signal = Color(0xFFA9E34B)

/** Pressed / deep variant of the accent. */
val SignalDeep = Color(0xFF6F9C2C)

/** Accent-tinted surface for quiet accent fills. */
val SignalWash = Color(0xFF1B2610)

/** Text/icon colour on top of a solid Signal fill. */
val OnSignal = Color(0xFF0B1204)

// ----------------------------------------------------------------- states ---

/** "Working": connecting, scanning, verifying. */
val Ember = Color(0xFFE9B44C)
val EmberWash = Color(0xFF2A2010)
val OnEmber = Color(0xFF231903)

/** "Failed". */
val Clay = Color(0xFFE8674A)
val ClayWash = Color(0xFF2B1410)
val OnClay = Color(0xFF1A0803)

// --------------------------------------------------------------- aliases ----
// Kept so any older reference to the navy palette still compiles. New code
// should use the Carbon / Chalk / Signal tokens above.

val Navy900 = Carbon00
val Navy800 = Carbon10
val Navy700 = Carbon20
val Navy600 = Carbon30
val AetherBlue = Signal
val AetherCyan = Signal
val AetherError = Clay
val OnDark = Chalk
val OnDarkMuted = ChalkMuted
