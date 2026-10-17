package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelPromoFlowTest {
    @Test fun firstTimeIsMandatoryLockedAndHasNoOptOut() {
        val rules = channelPromoRules(timesSeen = 0, optedOut = false)
        assertTrue(rules.show)
        assertEquals(CHANNEL_PROMO_FIRST_LOCK_SECONDS, rules.lockSeconds)
        assertEquals(3, rules.lockSeconds)
        assertFalse(rules.offersOptOut)
    }

    /** A stray opt-out flag can never skip the first, mandatory showing. */
    @Test fun firstTimeIgnoresOptOut() {
        assertTrue(channelPromoRules(timesSeen = 0, optedOut = true).show)
    }

    @Test fun laterTimesAreUnlockedAndOfferOptOut() {
        listOf(1, 2, 10).forEach { seen ->
            val rules = channelPromoRules(timesSeen = seen, optedOut = false)
            assertTrue(rules.show, "seen $seen")
            assertEquals(0, rules.lockSeconds, "seen $seen")
            assertTrue(rules.offersOptOut, "seen $seen")
        }
    }

    @Test fun optOutHidesItForGood() {
        listOf(1, 5).forEach { seen -> assertFalse(channelPromoRules(seen, optedOut = true).show, "seen $seen") }
    }

    @Test fun okUnlocksOnlyWhenTheCountdownEnds() {
        assertFalse(channelPromoCanConfirm(3))
        assertFalse(channelPromoCanConfirm(1))
        assertTrue(channelPromoCanConfirm(0))
    }

    @Test fun countdownRingDrains() {
        assertEquals(1f, channelPromoLockFraction(3, 3))
        assertEquals(0f, channelPromoLockFraction(0, 3))
        assertEquals(1f / 3f, channelPromoLockFraction(1, 3), 1e-6f)
        assertEquals(0f, channelPromoLockFraction(5, 0))
        assertEquals(1f, channelPromoLockFraction(9, 3))
    }
}
