package studio.cluvex.aether.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import studio.cluvex.aether.core.DiagnosticsLog
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM secret vault for the Zero Trust credentials introduced with engine
 * v1.5.0 (Access service-token client secret, enrolment JWT).
 *
 * WHY THIS EXISTS
 * ---------------
 * Everything else in the connection profile is a preference; losing it is
 * cosmetic. An Access service token is a long-lived credential for someone's
 * *organization*. Keeping it next to the MTU setting in a plain DataStore
 * protobuf would mean it lands in any backup of the app's data dir, is
 * readable with a file manager on a rooted device, and survives in plaintext
 * after the user clears the field.
 *
 * So the value is sealed with an AES-256-GCM key generated inside the Android
 * Keystore and marked non-exportable: the raw key never enters the app's
 * address space, and the ciphertext alone is useless on another device.
 *
 * Layout: Base64( iv(12) || ciphertext||tag(16) ).
 */
class SecretStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("aether_secrets", Context.MODE_PRIVATE)

    /** Reads and decrypts [name]; returns "" when unset or undecryptable. */
    fun read(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            // LENGTH GUARD: the old check was `blob.size <= IV_LEN`, which only
            // demanded the 12-byte IV. An AES-GCM sealed value is IV + at least
            // one ciphertext byte + a 16-byte tag, so anything shorter than
            // IV_LEN + TAG_LEN is structurally impossible and was previously
            // handed to doFinal() just to come back as an AEADBadTagException
            // — i.e. a truncated blob took the "key is permanently broken" path
            // instead of the "this is not a sealed value" path.
            if (blob.size <= IV_LEN + TAG_LEN) {
                DiagnosticsLog.w("secrets", "discarding malformed sealed value for $name")
                prefs.edit().remove(name).apply()
                return ""
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)),
            )
            String(cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size)), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            dropUndecryptable(name, e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Same category as a bad tag, different exception and thrown from
            // init() rather than doFinal(): the key is GONE (screen lock reset,
            // biometrics re-enrolled, restore onto another device). It used to
            // land in the generic "transient failure" branch below, so an
            // unreadable blob was retained forever and every read logged the
            // same error again.
            dropUndecryptable(name, e)
        } catch (e: Exception) {
            // Transient Keystore/TEE failure (early-boot contention, provider
            // hiccup): keep the ciphertext — a later read can still succeed.
            // Deleting here would silently destroy valid credentials because
            // of one bad cipher op.
            DiagnosticsLog.w("secrets", "decrypt failed for $name: ${e.message}")
            ""
        }
    }

    /**
     * The ciphertext is PERMANENTLY undecryptable for this key, so keeping it
     * only guarantees the same failure on every future read.
     */
    private fun dropUndecryptable(name: String, cause: Exception): String {
        DiagnosticsLog.w(
            "secrets",
            "$name is permanently undecryptable (${cause::class.java.simpleName}) — clearing it; " +
                "re-enter the credential in Settings.",
        )
        prefs.edit().remove(name).apply()
        return ""
    }

    /**
     * Encrypts and stores [value]; a blank value clears the entry. Returns
     * whether the value is now persisted as requested.
     *
     * SILENT-LOSS FIX: this used to be a bare `runCatching { … }` with no
     * logging and no return value. When the Keystore op failed — which does
     * happen: TEE contention during early boot, a provider hiccup, a device
     * whose keystore is in a bad state — the Access service-token secret was
     * simply never written, while Settings had already reported "saved". The
     * user then discovers it at the next connect, as an authentication failure
     * with no explanation. A write that did not happen has to say so.
     */
    fun write(name: String, value: String): Boolean {
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
            true
        } catch (e: Exception) {
            DiagnosticsLog.e(
                "secrets",
                "could not seal $name (${e::class.java.simpleName}: ${e.message}) — " +
                    "the credential was NOT saved.",
            )
            false
        }
    }

    /** Wipes every sealed secret. */
    fun clear() = prefs.edit().clear().apply()

    private fun key(): SecretKey = synchronized(keyLock) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT user-authentication bound: the VPN must be
                // able to reconnect unattended (boot, network change).
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    companion object {
        const val ACCESS_SECRET = "access_client_secret"
        const val ACCESS_TOKEN = "access_token"

        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "aether_secret_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val TAG_LEN = TAG_BITS / 8

        /**
         * Guards Keystore access across ALL SecretStore instances. Without it,
         * two concurrent first-time readers each see no key and both generate
         * one under the same alias — AndroidKeyStore silently replaces the
         * entry and everything sealed by the losing key becomes unreadable.
         */
        private val keyLock = Any()
    }
}
