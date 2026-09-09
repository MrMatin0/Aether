package studio.cluvex.aether.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.core.BridgeLine

private val Context.bridgeDataStore by preferencesDataStore(name = "aether_bridges")

/**
 * Everything about bridges that is NOT a setting.
 *
 * ### Why this is not in the profile
 *
 * [studio.cluvex.aether.model.ConnectionProfile.torBridgeLines] is the set of
 * bridges tor will use, and it is a SETTING: exported with a saved setup, reset
 * by "Reset settings", replaced when the user switches from built-in bridges to
 * a pasted line. None of that may be true of the two things kept here.
 *
 *  - **Personal bridges.** A requested bridge is handed out ONCE, from a pool
 *    that is rate-limited per subnet, and can only be requested while something
 *    still reaches bridges.torproject.org. Storing it in the profile would mean
 *    "let me try the built-in ones again" silently destroys a bridge the user
 *    may not be able to get back.
 *  - **The refreshed built-in list.** A catalogue, not a choice. It belongs next
 *    to the payload it overrides (see
 *    [studio.cluvex.aether.core.BridgeCatalog]), and it must survive a settings
 *    reset because nothing about it is user configuration.
 *
 * A separate DataStore file rather than more keys in the profile store, for
 * exactly that reason: the two have different lifetimes.
 */
class BridgeStore(private val context: Context) {

    private object Keys {
        /** The raw body of the last successful moat `/circumvention/builtin`. */
        val catalog = stringPreferencesKey("catalog")
        val catalogAt = longPreferencesKey("catalogAt")

        /** Bridges moat handed to this device: newline separated, canonical. */
        val personal = stringPreferencesKey("personal")
        val personalAt = longPreferencesKey("personalAt")
    }

    data class Snapshot(
        /** Unparsed, on purpose: see [studio.cluvex.aether.core.BridgeCatalog.current]. */
        val catalogJson: String = "",
        val catalogUpdatedAt: Long = 0L,
        val personalLines: List<String> = emptyList(),
        val personalUpdatedAt: Long = 0L,
    )

    val state: Flow<Snapshot> = context.bridgeDataStore.data.map { prefs ->
        Snapshot(
            catalogJson = prefs[Keys.catalog] ?: "",
            catalogUpdatedAt = prefs[Keys.catalogAt] ?: 0L,
            personalLines = BridgeLine.parseAll(prefs[Keys.personal] ?: "").map { it.line },
            personalUpdatedAt = prefs[Keys.personalAt] ?: 0L,
        )
    }

    /** Stores a refreshed built-in list. A blank or unusable body is ignored. */
    suspend fun saveCatalog(json: String, now: Long = System.currentTimeMillis()) {
        if (json.isBlank()) return
        context.bridgeDataStore.edit { prefs ->
            prefs[Keys.catalog] = json
            prefs[Keys.catalogAt] = now
        }
    }

    /**
     * Adds bridges received from moat, keeping what was already there.
     *
     * ADDS rather than replaces: two requests a week apart are two sets of
     * bridges, and the older one is not invalidated by the newer. Canonicalised
     * and de-duplicated by [BridgeLine], so the same bridge arriving twice does
     * not become two rows in the picker.
     */
    suspend fun addPersonal(lines: List<String>, now: Long = System.currentTimeMillis()) {
        val incoming = BridgeLine.parseAll(lines).map { it.line }
        if (incoming.isEmpty()) return
        context.bridgeDataStore.edit { prefs ->
            val existing = BridgeLine.parseAll(prefs[Keys.personal] ?: "").map { it.line }
            val merged = (existing + incoming).distinct().takeLast(BridgeLine.MAX_LINES)
            prefs[Keys.personal] = merged.joinToString("\n")
            prefs[Keys.personalAt] = now
        }
    }

    /** Forgets one personal bridge (the row's delete action). */
    suspend fun removePersonal(line: String) {
        val target = BridgeLine.parse(line)?.line ?: return
        context.bridgeDataStore.edit { prefs ->
            val kept = BridgeLine.parseAll(prefs[Keys.personal] ?: "")
                .map { it.line }
                .filterNot { it == target }
            prefs[Keys.personal] = kept.joinToString("\n")
        }
    }
}
