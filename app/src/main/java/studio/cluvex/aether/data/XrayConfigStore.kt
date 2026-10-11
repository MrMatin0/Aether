package studio.cluvex.aether.data

import android.content.Context

/**
 * The user's Xray config (a share link or Xray JSON), sealed with the same
 * Keystore key as the Zero Trust secrets.
 *
 * ### Why this is not in the profile
 *
 * A `vless://` link IS a credential: its UUID is the only thing standing
 * between a stranger and the user's server. The connection profile is exported
 * with a saved setup, travels inside the service Intent and lands in DataStore
 * in plaintext - none of which a credential may do. So the config lives here,
 * is read by the session at connect time
 * (studio.cluvex.aether.vpn.session.ChainStack), and is never part of an
 * export.
 *
 * Both calls touch the Keystore; call them off the main thread.
 */
class XrayConfigStore(context: Context) {

    private val secrets = SecretStore(context)

    /** The stored config, or "" when none was saved (or it could not be unsealed). */
    fun read(): String = secrets.read(KEY)

    /** Stores [text]; a blank value clears it. Returns whether it is now persisted as asked. */
    fun write(text: String): Boolean = secrets.write(KEY, text.trim())

    companion object {
        const val KEY = "xray_config"
    }
}
