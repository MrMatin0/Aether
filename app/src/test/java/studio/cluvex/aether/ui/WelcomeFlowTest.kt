package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WelcomeFlowTest {
    /** The story order is part of the copy: the privacy page must come last, right before the prompts. */
    @Test fun storyOrderIsFixed() {
        assertEquals(
            listOf(WelcomePage.HELLO, WelcomePage.SMART, WelcomePage.CHAIN, WelcomePage.PRIVACY),
            WelcomePages,
        )
    }

    @Test fun pageLookupClampsInsteadOfThrowing() {
        assertEquals(WelcomePage.HELLO, welcomePageAt(-3))
        assertEquals(WelcomePage.PRIVACY, welcomePageAt(99))
        assertEquals(WelcomePage.CHAIN, welcomePageAt(2))
    }

    @Test fun skipIsHiddenOnlyOnTheLastPage() {
        WelcomePages.indices.forEach { i ->
            assertEquals(i != WelcomePages.lastIndex, welcomeShowsSkip(i), "page $i")
        }
    }

    @Test fun backIsHiddenOnlyOnTheFirstPage() {
        assertFalse(welcomeShowsBack(0))
        (1..WelcomePages.lastIndex).forEach { assertTrue(welcomeShowsBack(it), "page $it") }
    }

    @Test fun lastPageDetection() {
        assertFalse(isLastWelcomePage(0))
        assertTrue(isLastWelcomePage(WelcomePages.lastIndex))
        assertTrue(isLastWelcomePage(WelcomePages.lastIndex + 1))
    }

    @Test fun progressSegmentsFollowTheSwipe() {
        assertEquals(1f, welcomeSegmentFill(0f, 0))
        assertEquals(0f, welcomeSegmentFill(0f, 1))
        assertEquals(0.5f, welcomeSegmentFill(0.5f, 1), 1e-6f)
        assertEquals(1f, welcomeSegmentFill(3f, 3))
        assertEquals(0f, welcomeSegmentFill(1.9f, 3))
    }
}
