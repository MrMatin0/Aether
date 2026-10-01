package studio.cluvex.aether.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The connection tab no longer scrolls, so its layout decisions ARE its
 * overflow protection. These pin them for the sizes people actually hold.
 */
class ConnectionLayoutTest {

    @Test
    fun densityFollowsAvailableHeight() {
        assertEquals(HomeDensity.ROOMY, homeDensity(690.dp))   // large phone, portrait
        assertEquals(HomeDensity.COMPACT, homeDensity(520.dp)) // mid phone
        assertEquals(HomeDensity.TIGHT, homeDensity(420.dp))   // 360x640 class
        assertEquals(HomeDensity.MINIMAL, homeDensity(300.dp)) // split screen / landscape
    }

    @Test
    fun largeTextSpendsTheHeightBudget() {
        assertEquals(HomeDensity.ROOMY, homeDensity(700.dp, fontScale = 1f))
        assertEquals(HomeDensity.COMPACT, homeDensity(700.dp, fontScale = 1.3f))
        // Shrinking the font never buys more than the real height.
        assertEquals(homeDensity(500.dp), homeDensity(500.dp, fontScale = 0.85f))
    }

    @Test
    fun orbStaysSmallerThanBeforeAndInsideItsOrbit() {
        listOf(
            320.dp to 120.dp,
            360.dp to 180.dp,
            412.dp to 360.dp,
            800.dp to 900.dp,
            1000.dp to 2000.dp,
        ).forEach { (w, h) ->
            val (orbit, orb) = heroGeometry(w, h)
            assertTrue(orb <= ORB_HOME_MAX, "$w x $h orb $orb")
            assertTrue(orb >= ORB_HOME_MIN, "$w x $h orb $orb")
            assertTrue(orbit >= orb, "$w x $h orbit $orbit < orb $orb")
            assertTrue(orbit <= HERO_ORBIT_MAX || orbit == orb)
        }
        assertTrue(ORB_HOME_MAX < 256.dp)
    }

    @Test
    fun onlyLandscapeAndWideWindowsSplit() {
        assertFalse(useTwoPane(360.dp, 640.dp))  // phone portrait
        assertFalse(useTwoPane(800.dp, 1180.dp)) // tablet portrait
        assertTrue(useTwoPane(640.dp, 300.dp))   // phone landscape
        assertTrue(useTwoPane(880.dp, 700.dp))   // tablet landscape (capped width)
        assertFalse(useTwoPane(420.dp, 300.dp))  // too narrow to split
    }
}
