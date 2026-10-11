package studio.cluvex.aether.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import studio.cluvex.aether.model.ConnectionProfile

/**
 * WHEN Aether takes the device's VPN slot - and the fix for "pressing Connect
 * kills my other VPN".
 *
 * ### What was wrong
 *
 * Android has exactly one VPN slot, and `VpnService.prepare()` is not a
 * harmless question. Once the user has consented to Aether, it SILENTLY TAKES
 * THE SLOT, and whichever VPN held it is revoked on the spot. The connect tap
 * called it first thing, so:
 *
 *  - proxy mode, which never builds a TUN and therefore never needs the slot,
 *    killed the secondary VPN the instant Connect was pressed;
 *  - tunnel mode killed it at the tap too - minutes before Aether had a
 *    working tunnel of its own - leaving the device with nothing while the
 *    engine scanned, and often taking away the very network the engine was
 *    scanning over.
 *
 * ### What happens now
 *
 *  - Proxy mode never asks for the slot.
 *  - With another VPN up, the tap does not ask either ([deferToSession]). The
 *    session claims the slot itself, right before it establishes its TUN,
 *    i.e. once the chain has proven it carries traffic (see
 *    AetherVpnService.claimVpnSlot). Until that moment the other VPN keeps
 *    carrying the device.
 *  - If consent is still missing at that point, the session asks for the
 *    system dialog through [pending]; MainActivity shows it and reports back
 *    through [answer].
 *  - With no other VPN, the tap keeps asking up front, exactly as before:
 *    there is no one to take the slot from, and a first-time user still sees
 *    the dialog the moment they tap.
 */
object VpnConsent {

    /** A consent dialog the running session is waiting on. [id] tells two apart. */
    data class Request(val id: Long, val intent: Intent)

    private val _pending = MutableStateFlow<Request?>(null)

    /** The dialog to show, or null. Collected by MainActivity while it is started. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val lock = Any()
    private var reply: CompletableDeferred<Boolean>? = null
    private var nextId = 0L

    /**
     * True when ANY VPN network exists on the device right now.
     *
     * Callers ask this while Aether's own TUN is down (before a connect, or
     * inside a connect attempt after the previous natives were torn down), so
     * a VPN seen here belongs to another app. All networks, not just the
     * default one: a VPN that excludes this app is still the one a
     * prepare() would revoke.
     */
    fun anyVpnActive(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    /**
     * True when a connect tap must NOT call `VpnService.prepare()`: proxy mode
     * (it never needs the slot) or another VPN is up (it must keep the slot
     * until Aether's tunnel is really ready). The session then claims it.
     */
    fun deferToSession(context: Context, profile: ConnectionProfile): Boolean =
        profile.proxyMode || anyVpnActive(context)

    /**
     * Asks the activity to show [intent] (the system VPN consent dialog) and
     * suspends until it answers, or [timeoutMs] passes (no activity on
     * screen). Returns true only for a grant. A newer request supersedes an
     * older one, which is answered "no".
     */
    suspend fun request(intent: Intent, timeoutMs: Long): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val id = synchronized(lock) {
            reply?.complete(false)
            reply = answer
            ++nextId
        }
        _pending.value = Request(id, intent)
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() } ?: false
        } finally {
            synchronized(lock) { if (reply === answer) reply = null }
            if (_pending.value?.id == id) _pending.value = null
        }
    }

    /** The dialog's result, from the activity. A no-op when nothing waits. */
    fun answer(granted: Boolean) {
        val waiting = synchronized(lock) { reply.also { reply = null } }
        _pending.value = null
        waiting?.complete(granted)
    }
}
