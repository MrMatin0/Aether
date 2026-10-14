package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.core.DiagnosticsLog

/**
 * The user's Xray config (a share link, a subscription body or Xray JSON),
 * stored as PLAIN TEXT in app-private SharedPreferences.
 *
 * ### Why it is no longer sealed
 *
 * It used to be sealed with the Keystore key of [SecretStore]. That made every
 * save and every connect a Keystore operation, and a TEE / provider hiccup
 * meant a paste that was never saved ("could not be saved, paste it again") or
 * a connect that found no config. The config now lives in its own private
 * preferences file: readable only by this app (and by anyone with root, or
 * with a backup of the app's data), never encrypted.
 *
 * ### Why this is still not in the profile
 *
 * The connection profile is exported with a saved setup, travels inside the
 * service Intent and lands in DataStore - and a `vless://` link IS a
 * credential: its UUID is the only thing standing between a stranger and the
 * user's server. So the config still lives here, is read by the session at
 * connect time (studio.cluvex.aether.vpn.session.ChainStack), and is never
 * part of an export.
 *
 * ### Migration
 *
 * A config sealed by an earlier build is read once from [SecretStore], written
 * here in plain text, and the sealed copy is removed.
 *
 * Both calls touch the disk; call them off the main thread.
 */
class XrayConfigStore(context: Context) {

    private val app: Context = context.applicationContext ?: context

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored config, or "" when none was saved. */
    fun read(): String {
        prefs.getString(KEY, null)?.let { return it }
        return migrateSealed()
    }

    /** Stores [text]; a blank value clears it. Returns whether it is now persisted as asked. */
    fun write(text: String): Boolean {
        val value = text.trim()
        val editor = prefs.edit()
        if (value.isEmpty()) editor.remove(KEY) else editor.putString(KEY, value)
        val ok = runCatching { editor.commit() }.getOrDefault(false)
        if (ok) dropSealed()
        return ok
    }

    /** Moves a config sealed by an earlier build over to the plain store. */
    private fun migrateSealed(): String {
        val legacy = runCatching { SecretStore(app).read(KEY) }.getOrDefault("").trim()
        if (legacy.isEmpty()) return ""
        if (write(legacy)) {
            DiagnosticsLog.i(TAG, "Moved the Xray config out of the Keystore-sealed store.")
        }
        return legacy
    }

    /** Removes the sealed copy an earlier build left behind; no Keystore call. */
    private fun dropSealed() {
        runCatching { SecretStore(app).write(KEY, "") }
    }

    companion object {
        const val KEY = "xray_config"
        private const val PREFS = "aether_xray"
        private const val TAG = "xray"
    }
}
