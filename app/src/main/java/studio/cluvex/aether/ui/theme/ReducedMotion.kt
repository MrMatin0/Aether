package studio.cluvex.aether.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/*
 * REDUCED MOTION.
 *
 * THE PROBLEM THIS FIXES
 *
 * Every animation in this app was unconditional. The two worst offenders were
 * the connect orb's loops: a 1.6s highlight lap while the engine works, and a
 * 5.6s halo rotation that runs for the ENTIRE lifetime of a connected session.
 * A VPN session is measured in hours, so that is a 256dp Canvas being redrawn at
 * the display's refresh rate for as long as the screen is on, whether or not the
 * person wants motion at all.
 *
 * Android has had the answer to "do not animate" since API 17 and the whole
 * platform honours it: Settings > Accessibility > Remove animations sets the
 * animator duration scale to 0. It exists for people who get motion sickness
 * from it, and it is also the first thing anyone toggles on a phone whose
 * battery is a decade old. Compose's own animation APIs do NOT read it.
 *
 * So we read it once and publish it. Two rules follow from it, applied
 * everywhere in the UI:
 *
 *   1. Durations collapse to 0 ([aetherDuration]). The end state still arrives —
 *      nothing is skipped, nothing is left half-transitioned — it just arrives
 *      on the next frame instead of over 320ms.
 *   2. Anything that loops FOREVER is not shortened, it is not started at all.
 *      A 0ms infinite repeat is a spin at infinite speed, which is worse than
 *      the thing we were asked to remove.
 *
 * Read once per composition of the theme, not observed live: the value can only
 * change from the system Settings app, which means leaving Aether, and the
 * Activity is recreated on the way back.
 */

/** True when the system has been told to stop animating. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The system's animator duration scale, as a boolean.
 *
 * Wrapped in runCatching because Settings.Global is a content provider read:
 * on a hardened or half-built ROM it can throw, and a decoration preference must
 * never be able to take the UI down. LocalInspectionMode short-circuits it so
 * previews do not touch a resolver they do not have.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    return remember(context, inspecting) {
        if (inspecting) {
            false
        } else {
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) == 0f
            }.getOrDefault(false)
        }
    }
}

/**
 * A duration, honoured or collapsed.
 *
 * Wrap every tween length in this instead of passing an [AetherDur] constant
 * straight through: `tween(aetherDuration(AetherDur.Base))`. Zero is a legal
 * tween length in Compose (the spec special-cases it to "already finished"), so
 * this costs one branch and no behavioural risk.
 */
@Composable
@ReadOnlyComposable
fun aetherDuration(millis: Int): Int = if (LocalReducedMotion.current) 0 else millis
