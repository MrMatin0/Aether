package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The single source of truth for "how much is flowing right now".
 *
 * WHY THIS EXISTS
 *
 * The counter logic below is UNCHANGED from the old TrafficPanel and
 * deliberately so: the sum of hev's direction-corrected totals + the userspace
 * filter bridge (the only source when per-app blocking is on) + the share
 * bridge (the only source in proxy mode), polled once a second, with rates
 * derived from deltas against a monotonic clock and negative deltas clamped so
 * a core restart rebases instead of printing garbage.
 *
 * What changed is WHO owns it. It used to live inside a composable, which meant
 * the numbers only existed while the Connection tab was on screen — so the
 * notification could not show a speed at all, and the moment the user left the
 * app the meter stopped. A tunnel meter belongs to the SESSION, not to a
 * screen: the VPN service starts this monitor when the tunnel comes up and
 * stops it when the natives it polls are torn down, and both the notification
 * and the UI are read-only consumers of the same samples.
 *
 * One loop, one second, two consumers — instead of two loops racing each other
 * over the same JNI counters.
 */
object TrafficMonitor {

    /** The refresh cadence. The notification and the UI both live on this beat. */
    const val TICK_MS = 1_000L

    /**
     * One reading.
     *
     * [at] is the monotonic timestamp of the sample and is part of the state on
     * purpose: a StateFlow only emits on CHANGE, and two identical idle seconds
     * would otherwise be swallowed — which would freeze the notification's
     * "live" heartbeat exactly when the user is watching to see if anything is
     * still moving.
     */
    data class Sample(
        val downloadBytes: Long = 0L,
        val uploadBytes: Long = 0L,
        val downloadRate: Long = 0L,
        val uploadRate: Long = 0L,
        val live: Boolean = false,
        val at: Long = 0L,
    ) {
        val totalBytes: Long get() = downloadBytes + uploadBytes
    }

    private val _sample = MutableStateFlow(Sample())

    /** The latest reading. Never null; [Sample.live] is false before the first tick. */
    val sample: StateFlow<Sample> = _sample.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Starts polling. Idempotent: a second call while already running is a
     * no-op, so a reconnect cannot end up with two loops on the same counters.
     */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _sample.value = Sample()
        job = scope.launch {
            var lastDown = -1L
            var lastUp = -1L
            var lastAt = 0L
            while (isActive) {
                val hev = HevTunnel.traffic()
                val bridge = SocksTunBridge.active?.getStats()
                val share = ShareBridge.traffic()
                val hasSource = hev != null || bridge != null || ShareBridge.active.value
                if (hasSource) {
                    val down =
                        (hev?.downloadBytes ?: 0L) + (bridge?.rxBytes ?: 0L) + share.downloadBytes
                    val up =
                        (hev?.uploadBytes ?: 0L) + (bridge?.txBytes ?: 0L) + share.uploadBytes
                    val now = SystemClock.elapsedRealtime()
                    var downRate = _sample.value.downloadRate
                    var upRate = _sample.value.uploadRate
                    // REBASE FIX: a core/engine restart resets the native
                    // counters to zero. The old code only clamped the negative
                    // delta, so the meter printed 0 B/s for a full second and
                    // then a catch-up spike once the counters passed their
                    // pre-restart values. Re-anchor on the new baseline instead,
                    // so the NEXT tick measures a real interval.
                    if (lastDown >= 0L && (down < lastDown || up < lastUp)) {
                        downRate = 0L
                        upRate = 0L
                    } else if (lastAt > 0L && now > lastAt) {
                        val dtMs = now - lastAt
                        downRate = ((down - lastDown).coerceAtLeast(0L) * 1000L) / dtMs
                        upRate = ((up - lastUp).coerceAtLeast(0L) * 1000L) / dtMs
                    }
                    _sample.value = Sample(
                        downloadBytes = down,
                        uploadBytes = up,
                        downloadRate = downRate,
                        uploadRate = upRate,
                        live = true,
                        at = now,
                    )
                    lastDown = down
                    lastUp = up
                    lastAt = now
                } else if (_sample.value.live) {
                    // FROZEN-METER FIX: with every source gone (tunnel torn
                    // down, engine dead, sharing stopped) the loop used to just
                    // skip the tick, leaving the last live=true sample — and
                    // therefore a non-zero speed — on the notification and in
                    // the UI for as long as the monitor stayed up. Publish a
                    // dead reading and forget the deltas, so a fresh session's
                    // counters are never compared against the old one's.
                    _sample.value = Sample()
                    lastDown = -1L
                    lastUp = -1L
                    lastAt = 0L
                }
                delay(TICK_MS)
            }
        }
    }

    /** Stops polling and clears the reading, so a dead session shows nothing. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _sample.value = Sample()
    }

    /**
     * Instrument-style byte formatting. Locale.US on purpose: AppLocale sets
     * the JVM default to fa, and a meter that prints Persian-Indic digits in a
     * monospaced readout no longer lines up with itself. Prose keeps the
     * locale's digits; instrument readouts do not.
     */
    fun formatBytes(v: Long): String {
        if (v < 1024L) return "$v B"
        val kb = v / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** Per-second rate, e.g. `1.4 MB/s`. */
    fun formatRate(v: Long): String = formatBytes(v) + "/s"
}
