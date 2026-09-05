package studio.cluvex.aether.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.cluvex.aether.AetherApp
import studio.cluvex.aether.MainActivity
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.widget.AetherWidgetProvider

/**
 * Everything the session puts on screen: the ongoing notification, the live
 * speed card, the Quick Settings tile and the home-screen widgets.
 *
 * Extracted from the service because none of it is VPN plumbing, and because
 * the ordering rules between the three surfaces (who is allowed to repaint the
 * shade, and how often) are far easier to keep straight when they live in one
 * file instead of being interleaved with TUN and engine lifecycle code.
 */
internal class VpnNotifications(
    private val service: AetherVpnService,
    private val scope: CoroutineScope,
) {

    /**
     * Repaints the ongoing notification with the live download/upload speed,
     * once a second, for as long as the tunnel is up. Owned by the SESSION and
     * not by the UI: the shade is exactly where the speed is needed while the
     * app is closed.
     */
    @Volatile
    private var trafficJob: Job? = null

    /**
     * The notification that belongs on screen RIGHT NOW.
     *
     * While the tunnel is up and the meter has a reading, the speed card IS the
     * status; rebuilding the plain text notification instead would blank the
     * numbers for a second. Shared by [update] and by the startForeground()
     * call in `onStartCommand`, so promoting the service can never wipe a live
     * reading either.
     */
    fun current(text: String): Notification {
        val sample = TrafficMonitor.sample.value
        return if (sample.live && AetherController.state.value.isConnected) {
            TrafficNotification.build(service, sample)
        } else {
            build(text)
        }
    }

    /**
     * Repaints the shade and both out-of-app surfaces for a state transition.
     *
     * The notify() itself is best-effort: a service that has just been demoted
     * can legitimately fail to post, and that used to surface to the user as a
     * CONNECTION error because the exception unwound the connect flow.
     */
    fun update(text: String) {
        runCatching { notificationManager()?.notify(NOTIF_ID, current(text)) }
        syncTileAndWidget()
    }

    /** Keeps the Quick Settings tile and any placed widget in sync. Cheap: both no-op when absent. */
    fun syncTileAndWidget() {
        AetherTileService.requestUpdate(service)
        AetherWidgetProvider.updateAllWidgets(service)
    }

    /**
     * Starts the 1-second speed meter and pipes every sample into the ongoing
     * notification. Idempotent, so a reconnect cannot end up with two writers:
     * the new collector waits for the previous one to actually finish, instead
     * of only asking it to stop and racing its last emission.
     *
     * Deliberately NOT routed through [update]: that also repaints the Quick
     * Settings tile and every placed home-screen widget, and doing that once a
     * second for the entire session would burn battery to redraw two things
     * whose content did not change.
     */
    fun startTrafficMeter() {
        val previous = trafficJob
        previous?.cancel()
        TrafficMonitor.start()
        trafficJob = scope.launch {
            previous?.join()
            TrafficMonitor.sample.collect { sample ->
                if (!sample.live) return@collect
                // A state transition owns the notification while it is busy:
                // "Connecting…" must never be replaced by a speed card.
                if (!AetherController.state.value.isConnected) return@collect
                runCatching {
                    notificationManager()?.notify(NOTIF_ID, TrafficNotification.build(service, sample))
                }
            }
        }
    }

    fun stopTrafficMeter() {
        trafficJob?.cancel()
        trafficJob = null
        TrafficMonitor.stop()
    }

    private fun notificationManager(): NotificationManager? =
        service.getSystemService(NotificationManager::class.java)

    private fun build(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            service,
            1,
            Intent(service, AetherVpnService::class.java).apply {
                action = AetherVpnService.ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(service, AetherApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(service.getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            // The action LABEL is what the button says, so it has to be the
            // verb ("Disconnect"), not the state it leads to ("Disconnecting…").
            .addAction(0, service.getString(R.string.action_disconnect), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        /** The one ongoing notification this service ever posts. */
        const val NOTIF_ID = 0x4145
    }
}
