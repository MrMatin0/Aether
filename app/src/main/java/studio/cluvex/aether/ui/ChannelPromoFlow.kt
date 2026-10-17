package studio.cluvex.aether.ui

/*
 * Presentation contract for the Telegram channel popup (ChannelPromoDialog).
 *
 * Kept free of Compose and resources so the rules that decide when the popup
 * shows, whether OK is locked and whether "don't show again" is offered are
 * unit-testable (see ChannelPromoFlowTest).
 */

internal const val TELEGRAM_CHANNEL_URL = "https://t.me/Aether_Plus"

/** What the user sees in the link chip: the scheme adds nothing but width. */
internal const val TELEGRAM_CHANNEL_LABEL = "t.me/Aether_Plus"

/** How long OK stays locked the very first time the popup is shown. */
internal const val CHANNEL_PROMO_FIRST_LOCK_SECONDS = 3

internal data class ChannelPromoRules(
    /** Whether the popup appears at all on this app open. */
    val show: Boolean,
    /** Seconds OK stays disabled; 0 means it is tappable right away. */
    val lockSeconds: Int,
    /** Whether the "don't show this message again" checkbox is offered. */
    val offersOptOut: Boolean,
)

/**
 * [timesSeen] counts acknowledged popups (OK taps), not displays: a first run
 * that is killed mid-countdown has not seen it, and gets the locked version
 * again.
 *
 * The first time is mandatory: always shown, locked for
 * [CHANNEL_PROMO_FIRST_LOCK_SECONDS], and without the opt-out. From the second
 * time on there is no lock and the opt-out is offered; once taken, the popup
 * is gone for good.
 */
internal fun channelPromoRules(timesSeen: Int, optedOut: Boolean): ChannelPromoRules {
    val first = timesSeen <= 0
    return ChannelPromoRules(
        show = first || !optedOut,
        lockSeconds = if (first) CHANNEL_PROMO_FIRST_LOCK_SECONDS else 0,
        offersOptOut = !first,
    )
}

internal fun channelPromoCanConfirm(remainingSeconds: Int): Boolean = remainingSeconds <= 0

/** How full the countdown ring is: 1 at the start of the lock, 0 when OK unlocks. */
internal fun channelPromoLockFraction(remainingSeconds: Int, lockSeconds: Int): Float =
    if (lockSeconds <= 0) 0f else remainingSeconds.coerceIn(0, lockSeconds).toFloat() / lockSeconds
