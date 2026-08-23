package studio.cluvex.aether.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import studio.cluvex.aether.AetherApp
import studio.cluvex.aether.MainActivity
import studio.cluvex.aether.R
import studio.cluvex.aether.core.TrafficMonitor

/**
 * The connected notification, as an instrument panel.
 *
 * WHY A CUSTOM VIEW
 *
 * The ongoing notification used to say "Aether / Connected" and nothing else,
 * which is the one place the answer to "is it actually doing anything?" is
 * needed most: the shade is where the user looks WITHOUT opening the app.
 * A single line of text cannot carry two numbers plus their totals with any
 * hierarchy, so the content view is built by hand:
 *
 *   collapsed — one dense row: down speed, up speed, session total.
 *   expanded  — a card with a live dot, the state, and one cell per direction
 *               (big monospaced rate + cumulative volume underneath).
 *
 * [NotificationCompat.DecoratedCustomViewStyle] keeps the system header and the
 * Disconnect action, so only the content area is ours.
 *
 * Colours are explicit (the Carbon & Signal tokens from colors.xml) and the
 * card paints its own dark background: notification shades come in both light
 * and dark, and a lime readout on white would be unreadable half the time.
 * Rates are pinned to LTR because they are instrument readouts, not prose.
 */
object TrafficNotification {

    fun build(context: Context, sample: TrafficMonitor.Sample): Notification {
        val downRate = TrafficMonitor.formatRate(sample.downloadRate)
        val upRate = TrafficMonitor.formatRate(sample.uploadRate)
        val total = TrafficMonitor.formatBytes(sample.totalBytes)

        val compact = RemoteViews(context.packageName, R.layout.notification_traffic_compact).apply {
            setTextViewText(R.id.notif_compact_down, downRate)
            setTextViewText(R.id.notif_compact_up, upRate)
            setTextViewText(R.id.notif_compact_total, total)
        }

        val expanded = RemoteViews(context.packageName, R.layout.notification_traffic).apply {
            setTextViewText(R.id.notif_state, context.getString(R.string.state_connected))
            setTextViewText(
                R.id.notif_session,
                context.getString(R.string.traffic_usage_total, total),
            )
            setTextViewText(R.id.notif_down_rate, downRate)
            setTextViewText(R.id.notif_up_rate, upRate)
            setTextViewText(
                R.id.notif_down_total,
                context.getString(
                    R.string.traffic_total,
                    TrafficMonitor.formatBytes(sample.downloadBytes),
                ),
            )
            setTextViewText(
                R.id.notif_up_total,
                context.getString(
                    R.string.traffic_total,
                    TrafficMonitor.formatBytes(sample.uploadBytes),
                ),
            )
        }

        return NotificationCompat.Builder(context, AetherApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            // Fallback line for anything that ignores custom views (wearables,
            // some launchers' heads-up shims, TalkBack's summary).
            .setContentText(context.getString(R.string.notif_speed_summary, downRate, upRate))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setOngoing(true)
            // Once a second is a lot of notify() calls; without this the shade
            // would try to re-alert on every single one.
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(ContextCompat.getColor(context, R.color.aether_primary))
            .setContentIntent(openIntent(context))
            .addAction(0, context.getString(R.string.action_disconnect), disconnectIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Same request codes as the plain notification's intents on purpose: the
     * two variants replace each other in the shade, and reusing the codes means
     * the system reuses the PendingIntents instead of minting a new pair every
     * second.
     */
    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun disconnectIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_DISCONNECT
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
