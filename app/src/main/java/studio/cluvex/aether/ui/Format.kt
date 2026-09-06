package studio.cluvex.aether.ui

import java.util.Locale

/** Monotonic timers use Latin digits; invalid negative durations display zero. */
internal fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = seconds % 3600L / 60L
    val rest = seconds % 60L
    return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
    else String.format(Locale.US, "%d:%02d", minutes, rest)
}
