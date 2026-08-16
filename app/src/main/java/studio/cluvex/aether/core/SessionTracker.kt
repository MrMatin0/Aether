package studio.cluvex.aether.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.model.ConnectionState

/** One finished session. Times are epoch millis, byte counts are for that session. */
data class SessionRecord(
    val startedAt: Long,
    val endedAt: Long,
    val downloaded: Long,
    val uploaded: Long,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
}

/**
 * Session history (1.3.0).
 *
 * WHY: "how much data did that cost me" and "how long does a session actually
 * survive on this network" were both unanswerable. The live traffic meter dies
 * with the session, so the numbers that matter -- did it hold for four hours or
 * four minutes -- were gone the moment the tunnel dropped.
 *
 * HOW: it OBSERVES, it does not intercept. Nothing in the connect path calls
 * into this object; it collects [AetherController.state] and samples the exact
 * same counters the traffic meter reads (hev + the userspace filter bridge + the
 * share bridge). A tunnel teardown zeroes those counters, so the totals are
 * sampled on a slow timer and the session is closed from the LAST sample rather
 * than from a read taken after the tunnel is already gone.
 *
 * PRIVACY: only start time, duration and byte totals, on this device, capped at
 * [MAX_RECORDS], no destinations, and the whole thing is switchable off in
 * Settings (AppPrefs.keepHistory). A censorship-circumvention tool has no
 * business keeping a browsing diary.
 */
object SessionTracker {
    private const val FILE = "aether_sessions"
    private const val KEY = "log"

    /** Deep enough to see a pattern, shallow enough to stay a single small write. */
    private const val MAX_RECORDS = 40

    /** Anything shorter is a failed connect, not a session worth logging. */
    private const val MIN_SESSION_MS = 5_000L
    private const val SAMPLE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var prefs: SharedPreferences? = null

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())

    /** Newest first. */
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    private var openedAt: Long? = null
    private var peakDown = 0L
    private var peakUp = 0L

    /** Idempotent; called once from Application.onCreate. */
    fun init(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = store
            _sessions.value = decode(store.getString(KEY, null))
        }
        scope.launch { AetherController.state.collect { onState(it) } }
        scope.launch { sampleLoop() }
    }

    fun clear(context: Context) {
        init(context)
        prefs?.edit()?.remove(KEY)?.apply()
        _sessions.value = emptyList()
    }

    private fun onState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> if (openedAt == null) {
                openedAt = System.currentTimeMillis()
                peakDown = 0L
                peakUp = 0L
            }
            // A transient reconnect is the SAME session, exactly like the
            // connected-since timer in AetherController.
            is ConnectionState.Reconnecting -> Unit
            else -> closeSession()
        }
    }

    private suspend fun sampleLoop() {
        while (true) {
            if (openedAt != null) sample()
            delay(SAMPLE_MS)
        }
    }

    /**
     * Reads the live counters. Monotonic maxima, so a core restart that rebases
     * a counter to zero can never make the session total go backwards.
     */
    private fun sample() {
        runCatching {
            val hev = HevTunnel.traffic()
            val bridge = SocksTunBridge.active?.getStats()
            val share = ShareBridge.traffic()
            val down = (hev?.downloadBytes ?: 0L) + (bridge?.rxBytes ?: 0L) + share.downloadBytes
            val up = (hev?.uploadBytes ?: 0L) + (bridge?.txBytes ?: 0L) + share.uploadBytes
            if (down > peakDown) peakDown = down
            if (up > peakUp) peakUp = up
        }
    }

    private fun closeSession() {
        val startedAt = openedAt ?: return
        openedAt = null
        // One last read: the tunnel may still be alive for a few more millis.
        sample()
        val record = SessionRecord(startedAt, System.currentTimeMillis(), peakDown, peakUp)
        peakDown = 0L
        peakUp = 0L
        if (!AppPrefs.state.value.keepHistory) return
        if (record.durationMs < MIN_SESSION_MS) return
        val store = prefs ?: return
        val updated = (listOf(record) + _sessions.value).take(MAX_RECORDS)
        store.edit().putString(KEY, encode(updated)).apply()
        _sessions.value = updated
    }

    private fun encode(records: List<SessionRecord>): String = records.joinToString("\n") {
        "${it.startedAt}|${it.endedAt}|${it.downloaded}|${it.uploaded}"
    }

    private fun decode(raw: String?): List<SessionRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            val startedAt = parts[0].toLongOrNull() ?: return@mapNotNull null
            val endedAt = parts[1].toLongOrNull() ?: return@mapNotNull null
            SessionRecord(
                startedAt = startedAt,
                endedAt = endedAt,
                downloaded = parts[2].toLongOrNull() ?: 0L,
                uploaded = parts[3].toLongOrNull() ?: 0L,
            )
        }.take(MAX_RECORDS).toList()
    }
}
