package studio.cluvex.aether.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import studio.cluvex.aether.AetherApp
import studio.cluvex.aether.MainActivity
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionState

/**
 * What the shade should be showing right now.
 *
 * Derived from [ConnectionState] and nothing else, so the notification can
 * never disagree with the in-app UI about where the session is. Pure on
 * purpose: see NotificationPhaseTest.
 */
internal enum class NotifPhase {
    /** Starting, scanning, verifying or reconnecting: progress + Cancel. */
    BUSY,

    /** Tunnel verified: the live card. */
    CONNECTED,

    /** Failed connect, or the kill switch holding traffic: Retry + Disconnect. */
    ERROR,

    /** Teardown in flight: progress, no actions (there is nothing left to cancel). */
    CLOSING,
}

internal fun phaseOf(state: ConnectionState): NotifPhase = when (state) {
    is ConnectionState.Connected -> NotifPhase.CONNECTED
    is ConnectionState.Error -> NotifPhase.ERROR
    ConnectionState.Disconnecting -> NotifPhase.CLOSING
    // Idle only ever reaches the shade through the startForeground() call at the
    // very top of onStartCommand, i.e. a session that is about to start.
    ConnectionState.Idle,
    ConnectionState.Launching,
    ConnectionState.Connecting,
    ConnectionState.Verifying,
    is ConnectionState.Reconnecting,
    -> NotifPhase.BUSY
}

/**
 * The route as the user thinks about it: where their traffic ENTERS first.
 * "Tor \u2192 Psiphon \u2192 Aether". Unlike [ChainMode.pathLabel] it drops the
 * trailing "internet", which costs a third of a notification line and says
 * nothing.
 */
internal fun routeLabel(chain: ChainMode?): String =
    chain?.hops?.asReversed()?.joinToString(" \u2192 ") { it.label }.orEmpty()

/** Everything about the session the connected card shows besides the meter. */
internal data class SessionInfo(
    val chain: ChainMode?,
    val countryCode: String?,
    val connectedSince: Long?,
)

/**
 * Shared plumbing for every notification variant, so the four phases differ
 * only in what they SAY and never in how they behave (channel, alerting,
 * visibility, tap target, PendingIntent identity).
 */
internal object NotificationKit {

    private const val REQ_OPEN = 0
    private const val REQ_DISCONNECT = 1
    private const val REQ_RETRY = 2

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun accent(context: Context): Int = ContextCompat.getColor(context, R.color.notif_accent)

    /**
     * The common base.
     *
     * - FOREGROUND_SERVICE_IMMEDIATE: from Android 12 the system may hold a
     *   foreground-service notification back for up to 10 s. For a VPN that is
     *   exactly the window in which the user wants to see "Connecting\u2026".
     * - setSilent + setOnlyAlertOnce: the connected card repaints every second.
     * - VISIBILITY_PRIVATE + a public version: route and exit country are
     *   nobody's business on a locked screen.
     */
    fun base(context: Context, publicText: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, AetherApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, publicText))
            .setContentIntent(openIntent(context))

    private fun publicVersion(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, AetherApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setSilent(true)
            .build()

    /**
     * Request codes are fixed per purpose: the variants replace each other in
     * the shade, and stable codes let the system reuse the PendingIntents
     * instead of minting a new set on every one-second repaint.
     */
    fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN,
        Intent(context, MainActivity::class.java),
        FLAGS,
    )

    fun disconnectIntent(context: Context): PendingIntent =
        serviceIntent(context, AetherVpnService.ACTION_DISCONNECT, REQ_DISCONNECT)

    fun retryIntent(context: Context): PendingIntent =
        serviceIntent(context, AetherVpnService.ACTION_RETRY, REQ_RETRY)

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AetherVpnService::class.java).setAction(action),
            FLAGS,
        )
}
