package studio.cluvex.aether.core

import studio.cluvex.aether.model.ConnectionState

/**
 * The session timer's rules, in one pure place (see SessionClockTest).
 *
 * WHAT WAS WRONG
 *
 * [AetherController] stamped the moment a session became Connected with
 * System.currentTimeMillis() - wall-clock time, about 1.8e12 today - while every
 * on-screen clock that reads the stamp (the "Connected for" tile, ConnectionMeta's
 * uptime tile, the session ledger) subtracts it from
 * SystemClock.elapsedRealtime(), which is time since boot: about 1e8 on a phone
 * that has been up for a day. The difference is hugely negative, every formatter
 * clamps a negative duration to zero, and the timer sat on 00:00:00 for the
 * entire session. The ticker itself was fine: it woke every second and computed
 * zero every second.
 *
 * The notification never showed it, because Notification.when IS wall-clock
 * time and the shade's chronometer was fed the stamp directly.
 *
 * THE RULE NOW
 *
 * The stamp is MONOTONIC (elapsedRealtime): that is what the UI measures against,
 * and a wall-clock stamp jumps with every NTP correction - routine on a phone that
 * has just left airplane mode, which is exactly when a VPN session tends to start.
 * The one consumer that needs wall-clock time converts at the edge, with
 * [toWallClock].
 *
 * A reconnect no longer restarts the clock either. setState() promised to "keep
 * the running timer during a transient reconnect", but the supervisor's reconnect
 * is Reconnecting -> Verifying -> Connected, and Verifying fell into the reset
 * branch. Verifying only follows Connected through a reconnect - a fresh attempt
 * passes through Launching first, which clears the stamp - so keeping the stamp
 * across it is safe.
 */
internal object SessionClock {

    /**
     * The session stamp after the controller enters [state], given the [current]
     * one.
     *
     * [now] is read only when a stamp is actually minted (the first Connected of
     * a session), so the caller can hand in a real clock without paying for it on
     * every transition.
     */
    fun next(current: Long?, state: ConnectionState, now: () -> Long): Long? = when (state) {
        is ConnectionState.Connected -> current ?: now()
        // A transient reconnect is the same session: Reconnecting, and then the
        // Verifying that gates its return to Connected.
        is ConnectionState.Reconnecting, is ConnectionState.Verifying -> current
        else -> null
    }

    /**
     * A monotonic [stamp] as wall-clock millis, for the one API that wants them:
     * Notification.when, which drives the shade's chronometer. A stamp from the
     * future (never minted, but cheap to rule out) reads as "just now".
     */
    fun toWallClock(stamp: Long, nowElapsed: Long, nowWall: Long): Long =
        nowWall - (nowElapsed - stamp).coerceAtLeast(0L)
}
