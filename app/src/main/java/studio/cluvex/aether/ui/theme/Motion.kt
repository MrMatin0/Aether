package studio.cluvex.aether.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/*
 * Shared motion vocabulary.
 *
 * One family of curves for the whole app: exponential ease-out. Things arrive
 * fast and settle, which reads as "responsive". No bounce, no overshoot, no
 * elastic — a VPN client is an instrument, not a toy, and springy motion makes
 * a security state look uncertain.
 *
 * Names are prefixed so they can never be confused with (or shadow) the
 * built-in androidx easing constants.
 */

/** Default: quick departure, long soft landing. */
val AetherEaseOut: Easing = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)

/** For large travel (sheets, tab swaps). */
val AetherEaseOutExpo: Easing = CubicBezierEasing(0.19f, 1f, 0.22f, 1f)

/** For state that leaves and comes back (cross-fades of the same element). */
val AetherEaseInOut: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/** Durations, in milliseconds. */
object AetherDur {
    /** Hover/press feedback. */
    const val Snap = 120

    /** Colour and small position changes. */
    const val Quick = 200

    /** Content swaps. */
    const val Base = 320

    /** Full-screen or hero transitions. */
    const val Slow = 520

    /** One turn of an ambient, looping indicator. */
    const val Loop = 2400
}
