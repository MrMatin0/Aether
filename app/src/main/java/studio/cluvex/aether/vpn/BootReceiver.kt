package studio.cluvex.aether.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected

/**
 * Reconnects after a reboot when the user asked for it (1.3.0).
 *
 * Two hard rules:
 *
 *  1. If VPN consent has NOT been granted yet we do nothing at all. A boot
 *     broadcast cannot legally show a dialog, and starting the service without
 *     consent would only produce a failure notification at 3am.
 *  2. Everything is wrapped: a foreground-service start from boot can be
 *     refused by the platform, and a refused auto-connect must never turn into
 *     a crash loop on every boot.
 *
 * Not exported: only the system delivers BOOT_COMPLETED, no other app can fake
 * it into starting the tunnel.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext
        AppPrefs.init(app)
        if (!AppPrefs.state.value.autoConnectOnBoot) return

        val needsConsent = runCatching { AetherController.prepare(app) != null }.getOrDefault(true)
        if (needsConsent) return

        val current = AetherController.state.value
        if (current.isConnected || current.isBusy) return

        // The profile lives in DataStore, so the read suspends: keep the
        // broadcast alive until the connect intent is actually out. The read
        // is guarded too — a corrupt or unreadable store must never turn the
        // boot broadcast into an unhandled-exception process kill.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val profile = runCatching { ProfileStore(app).profile.first() }.getOrNull()
                if (profile != null) runCatching { AetherController.connect(app, profile) }
            } finally {
                pending.finish()
            }
        }
    }
}
