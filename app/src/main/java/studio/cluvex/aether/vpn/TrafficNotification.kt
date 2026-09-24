package studio.cluvex.aether.vpn

import android.app.Notification
import android.content.Context
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import studio.cluvex.aether.R
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.TrafficMonitor

/**
 * The CONNECTED notification: the one variant that needs a custom view.
 *
 * WHAT CHANGED (and why)
 *
 * The previous card painted its own dark, rounded, padded surface inside the
 * system's decorated area. That is where the "wrong proportions on some
 * devices" came from:
 *
 *  - a card inside a card: every OEM shade (One UI, MIUI/HyperOS, ColorOS,
 *    Pixel) already pads the custom area differently, and our 12dp padding +
 *    14dp corners were added on top of theirs;
 *  - the collapsed row asked for a 42dp minimum plus 12dp of padding, while
 *    Android 12+ gives a decorated custom view 48dp in total, so it clipped;
 *  - fixed 17sp readouts overflowed their cell on narrow screens and at large
 *    font scales, and looked lost on tablets;
 *  - a fixed dark slab looked foreign in every light shade.
 *
 * Now the layouts carry no background of their own, take their text colours
 * from TextAppearance.Compat.Notification (so they follow the shade, light or
 * dark), size themselves from smallest-width-qualified dimens, and the rates
 * auto-size inside a bounded line so a long reading shrinks instead of
 * clipping.
 *
 *   collapsed \u2014 one row: down, up, session total.
 *   expanded  \u2014 state + exit country, route + session total, two meter cells.
 *
 * The session timer is the system's own chronometer in the header
 * (setUsesChronometer), so it ticks without a single extra notify().
 */
internal object TrafficNotification {

    fun build(context: Context, sample: TrafficMonitor.Sample, session: SessionInfo): Notification {
        val downRate = TrafficMonitor.formatRate(sample.downloadRate)
        val upRate = TrafficMonitor.formatRate(sample.uploadRate)
        val total = TrafficMonitor.formatBytes(sample.totalBytes)
        val connected = context.getString(R.string.state_connected)

        val compact = RemoteViews(context.packageName, R.layout.notification_traffic_compact).apply {
            setTextViewText(R.id.notif_compact_down, downRate)
            setTextViewText(R.id.notif_compact_up, upRate)
            setTextViewText(R.id.notif_compact_total, total)
        }

        val country = session.countryCode?.trim()?.uppercase()?.takeIf { it.length == 2 }
        val expanded = RemoteViews(context.packageName, R.layout.notification_traffic).apply {
            setTextViewText(R.id.notif_state, connected)
            // Blank rather than GONE: the country and the route share their row
            // with fixed partners, and a view that disappears would let those
            // partners jump sideways the moment the exit lookup lands.
            setTextViewText(
                R.id.notif_country,
                country?.let { "${NetProbe.flagEmoji(it)} $it" }.orEmpty(),
            )
            setTextViewText(R.id.notif_route, routeLabel(session.chain))
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

        val builder = NotificationKit.base(context, connected)
            .setContentTitle(context.getString(R.string.notif_title))
            // Fallback line for anything that ignores custom views (wearables,
            // some launchers' heads-up shims, TalkBack's summary).
            .setContentText(context.getString(R.string.notif_speed_summary, downRate, upRate))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .addAction(
                0,
                context.getString(R.string.action_disconnect),
                NotificationKit.disconnectIntent(context),
            )

        val since = session.connectedSince
        if (since != null) {
            builder.setWhen(since).setUsesChronometer(true).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }
}
