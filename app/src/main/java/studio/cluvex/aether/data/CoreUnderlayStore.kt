package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.Underlay

/**
 * The underlay each core was last used with, so a tap on the core selector
 * returns to "Tor via Psiphon" rather than to bare Tor.
 *
 * ### Why not in the profile
 *
 * The profile holds exactly one [studio.cluvex.aether.model.ChainMode] and its
 * persisted format is a contract (exports, the service Intent, DataStore). This
 * is a UI convenience: losing it costs a fallback to Direct, never a wrong
 * route, so it lives in its own private preferences file and is never part of
 * an export.
 *
 * Stored by enum NAME, like every other persisted enum in the app. A name this
 * build does not know (a downgrade, a hand edit) reads as "nothing remembered".
 *
 * [read] touches the disk; call it off the main thread. [write] uses `apply()`,
 * which is safe to call from the main thread.
 */
class CoreUnderlayStore(context: Context) {

    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every remembered (core, underlay) pair. */
    fun read(): Map<Hop, Underlay> = buildMap {
        for (core in Hop.entries) {
            val name = prefs.getString(core.name, null) ?: continue
            Underlay.entries.firstOrNull { it.name == name }?.let { put(core, it) }
        }
    }

    /** Remembers [underlay] for [core]. */
    fun write(core: Hop, underlay: Underlay) {
        prefs.edit().putString(core.name, underlay.name).apply()
    }

    private companion object {
        const val PREFS = "aether_core_selector"
    }
}
