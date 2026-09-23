package studio.cluvex.aether.ui

/*
 * Presentation contract for the first-run welcome (OnboardingScreen).
 *
 * Kept free of Compose and resources so the rules that decide what the user
 * can do on each page are unit-testable (see WelcomeFlowTest).
 */

/** The four beats of the welcome, in story order. */
internal enum class WelcomePage { HELLO, SMART, CHAIN, PRIVACY }

internal val WelcomePages: List<WelcomePage> = WelcomePage.entries

/** Out-of-range indices clamp instead of throwing: a pager can overshoot. */
internal fun welcomePageAt(index: Int): WelcomePage =
    WelcomePages[index.coerceIn(0, WelcomePages.lastIndex)]

internal fun isLastWelcomePage(index: Int): Boolean = index >= WelcomePages.lastIndex

/** On the last page the primary button already finishes, so Skip would be a duplicate. */
internal fun welcomeShowsSkip(index: Int): Boolean = !isLastWelcomePage(index)

internal fun welcomeShowsBack(index: Int): Boolean = index > 0

/**
 * How full segment [index] of the progress bar is, for a pager [position]
 * (currentPage + currentPageOffsetFraction). Segment 0 is full on the first
 * page, and each next segment fills continuously while the user swipes.
 */
internal fun welcomeSegmentFill(position: Float, index: Int): Float =
    (position - index + 1f).coerceIn(0f, 1f)
