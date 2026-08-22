package studio.cluvex.aether.ui

import java.util.Locale

/**
 * Shared duration readout for instrument-style labels (elapsed timer, session
 * history): `m:ss` under an hour, `h:mm:ss` beyond it. Locale.US on purpose —
 * timers and meters always render Latin digits; prose keeps the locale's own.
 */
internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val rest = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, rest)
    }
}
