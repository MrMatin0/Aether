package studio.cluvex.aether.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.model.ConnectionProfile

/** One named, saved [ConnectionProfile]. */
data class Preset(val name: String, val payload: String) {
    fun toProfile(): ConnectionProfile = ProfileCodec.decode(payload)
}

/**
 * Saved setups (1.3.0).
 *
 * WHY: on a filtered network the settings that work change with the network,
 * the time of day and whichever DPI rule shipped that week. Before this, finding
 * a working combination again meant re-deriving eight fields from memory. A
 * setup is just an encoded profile, so this reuses [ProfileCodec] -- which
 * already refuses to serialise the Zero Trust secrets, so neither a saved setup
 * nor an exported config can ever carry a long-lived organization credential.
 */
class PresetStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _presets = MutableStateFlow(read())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    /** Saves (or replaces) a setup. False when the name is empty or the list is full. */
    fun save(name: String, profile: ConnectionProfile): Boolean {
        val clean = sanitize(name)
        if (clean.isEmpty()) return false
        val others = _presets.value.filterNot { it.name.equals(clean, ignoreCase = true) }
        if (others.size >= MAX_PRESETS) return false
        // Strip the record/field framing characters from the payload too:
        // route rules and free-text fields can carry anything pasted into
        // them, and one stray control character would split records mid-
        // payload on read, dropping neighbouring presets with it.
        val payload = sanitizePayload(ProfileCodec.encode(profile))
        write(others + Preset(clean, payload))
        return true
    }

    fun delete(name: String) {
        // Same identity rule as save(): names are matched case-insensitively.
        write(_presets.value.filterNot { it.name.equals(name, ignoreCase = true) })
    }

    private fun read(): List<Preset> = (prefs.getString(KEY, null) ?: "")
        .split(RECORD)
        .mapNotNull { record ->
            val idx = record.indexOf(FIELD)
            if (idx <= 0) return@mapNotNull null
            val name = record.substring(0, idx)
            val payload = record.substring(idx + 1)
            if (name.isBlank() || payload.isBlank()) null else Preset(name, payload)
        }
        .take(MAX_PRESETS)

    private fun write(list: List<Preset>) {
        val trimmed = list.take(MAX_PRESETS)
        prefs.edit()
            .putString(KEY, trimmed.joinToString(RECORD) { it.name + FIELD + it.payload })
            .apply()
        _presets.value = trimmed
    }

    companion object {
        /** Enough to cover "home / mobile data / work / desperate" without becoming a list to scroll. */
        const val MAX_PRESETS = 12

        private const val FILE = "aether_presets"
        private const val KEY = "presets"

        // Control characters: a preset name can contain anything printable, and
        // the payload itself is newline separated, so neither can collide.
        private const val RECORD = "\u0002"
        private const val FIELD = "\u0001"

        private const val HEADER = "# aether-config v1"

        /**
         * The shareable text form of a config. The header line has no '=' in it,
         * and [ProfileCodec.decode] ignores lines without one, so it survives a
         * round trip without any special casing.
         */
        fun exportText(profile: ConnectionProfile): String =
            HEADER + "\n" + ProfileCodec.encode(profile)

        /** Parses pasted text, or null when it clearly is not an Aether config. */
        fun importText(raw: String?): ConnectionProfile? {
            if (raw.isNullOrBlank()) return null
            // The key=value form carries "protocol="; the 1.0/1.1 legacy pipe
            // format does not — but [ProfileCodec.decode] still understands it,
            // so rejecting it here would refuse configs the app can decode.
            val looksCurrent = raw.contains("protocol=")
            val looksLegacy = !raw.contains('=') && raw.contains('|')
            if (!looksCurrent && !looksLegacy) return null
            return ProfileCodec.decode(sanitizePayload(raw))
        }

        private fun sanitize(name: String): String =
            name.trim().replace(Regex("[\\r\\n\\u0001\\u0002]"), " ").take(40)

        private fun sanitizePayload(payload: String): String =
            payload.replace(Regex("[\\u0001\\u0002]"), " ")
    }
}
