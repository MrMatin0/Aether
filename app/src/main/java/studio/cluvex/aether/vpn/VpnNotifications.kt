package studio.cluvex.aether.vpn

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.widget.AetherWidgetProvider

/**
 * Everything the session puts on screen: the ongoing notification, the live
 * speed card, the Quick Settings tile and the home-screen widgets.
 *
 * STATE-AWARE: the notification is chosen from [AetherController.state], not
 * from the text a caller happens to pass. Each phase has its own shape:
 *
 *   BUSY       progress bar + elapsed time, route as subtext, Cancel
 *   CONNECTED  the live card ([TrafficNotification])
 *   ERROR      the actual failure reason, Retry + Disconnect
 *   CLOSING    progress bar, no actions
 *
 * The text argument is still honoured as the busy-phase line, because the
 * service knows finer steps ("Analyzing your network\u2026") than the state
 * machine models.
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
     * The chain the current session runs, for the route line. Set by the
     * service as soon as it knows (and again after hydration, which can change
     * it on a system restart).
     */
    @Volatile
    var chain: ChainMode? = null

    /** When the current busy stretch began, for the elapsed-time chronometer. 0 = not busy. */
    @Volatile
    private var busySince: Long = 0L

    /**
     * The notification that belongs on screen RIGHT NOW. Shared by [update]
     * and by the startForeground() call in `onStartCommand`, so promoting the
     * service can never wipe a live reading either.
     */
    fun current(text: String): Notification {
        val state = AetherController.state.value
        val phase = phaseOf(state)
        busySince = when {
            phase != NotifPhase.BUSY -> 0L
            busySince == 0L -> System.currentTimeMillis()
            else -> busySince
        }
        return when (phase) {
            NotifPhase.CONNECTED -> TrafficNotification.build(service, TrafficMonitor.sample.value, session())
            NotifPhase.ERROR -> buildError((state as? ConnectionState.Error)?.message ?: text)
            NotifPhase.CLOSING -> buildBusy(text, cancellable = false)
            NotifPhase.BUSY -> buildBusy(busyLine(text, state), cancellable = true)
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
                // "Connecting\u2026" must never be replaced by a speed card.
                if (!AetherController.state.value.isConnected) return@collect
                runCatching {
                    notificationManager()?.notify(
                        NOTIF_ID,
                        TrafficNotification.build(service, sample, session()),
                    )
                }
            }
        }
    }

    fun stopTrafficMeter() {
        trafficJob?.cancel()
        trafficJob = null
        TrafficMonitor.stop()
    }

    private fun session(): SessionInfo = SessionInfo(
        chain = chain,
        // Only a lookup made THROUGH the tunnel is the exit; the direct one is
        // the user's own operator and would be actively misleading here.
        countryCode = AetherController.ipInfo.value?.takeIf { it.viaTunnel }?.countryCode,
        connectedSince = AetherController.connectedSince.value,
    )

    private fun busyLine(text: String, state: ConnectionState): String =
        if (state is ConnectionState.Reconnecting) {
            text + " \u00B7 " +
                service.getString(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
        } else {
            text
        }

    /**
     * Busy and closing phases: plain platform styles only. A native progress
     * bar and chronometer look right on every OEM shade and scale with the
     * device by construction, which is the whole point of this redesign.
     *
     * The bar is indeterminate on purpose. Scan budgets exist per attempt, but
     * Smart Auto walks a ladder of attempts with different budgets and chains
     * add bootstrap time on top, so a determinate bar would reach 100% and
     * start over, which reads as "stuck". Elapsed time is the honest number.
     */
    private fun buildBusy(line: String, cancellable: Boolean): Notification {
        val builder = NotificationKit.base(service, line)
            .setContentTitle(service.getString(R.string.notif_title))
            .setContentText(line)
            .setProgress(0, 0, true)
        routeLabel(chain).takeIf { it.isNotEmpty() }?.let(builder::setSubText)
        if (busySince > 0L) {
            builder.setWhen(busySince).setUsesChronometer(true).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        if (cancellable) {
            // Same intent as Disconnect: the service decides what stopping means
            // (including the strict kill switch). The LABEL is what differs,
            // because nothing is connected yet.
            builder.addAction(
                0,
                service.getString(R.string.notif_action_cancel),
                NotificationKit.disconnectIntent(service),
            )
        }
        return builder.build()
    }

    /**
     * The error phase shows the REAL reason ("Tor did not bootstrap in time\u2026")
     * instead of a generic "Connection failed", expandable because those
     * reasons are written to be actionable and are rarely one line long.
     */
    private fun buildError(message: String): Notification {
        val killSwitch = message == service.getString(R.string.state_killswitch)
        val title = service.getString(
            if (killSwitch) R.string.kill_switch_title else R.string.notif_error_title,
        )
        return NotificationKit.base(service, title)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setShowWhen(false)
            .addAction(
                0,
                service.getString(R.string.notif_action_retry),
                NotificationKit.retryIntent(service),
            )
            .addAction(
                0,
                service.getString(R.string.action_disconnect),
                NotificationKit.disconnectIntent(service),
            )
            .build()
    }

    private fun notificationManager(): NotificationManager? =
        service.getSystemService(NotificationManager::class.java)

    companion object {
        /** The one ongoing notification this service ever posts. */
        const val NOTIF_ID = 0x4145
    }
}
