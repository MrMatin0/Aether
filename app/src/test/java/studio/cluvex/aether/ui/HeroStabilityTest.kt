package studio.cluvex.aether.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The orb must keep ONE size whatever the connection state. Its slot is a
 * function of the window alone, so these pin that function: bounded, inside
 * the window, and leaving room for the tallest set of cards below it.
 */
class HeroStabilityTest {

    private val phones = listOf(
        320.dp to 420.dp,
        360.dp to 560.dp,
        393.dp to 700.dp,
        412.dp to 780.dp,
        800.dp to 1180.dp,
    )

    @Test
    fun portraitHeroIsBoundedAndLeavesRoomForTheTallestCards() {
        phones.forEach { (w, h) ->
            val density = homeDensity(h)
            val side = heroSlotHeight(w, h, density, twoPane = false)
            assertTrue(side <= HERO_ORBIT_MAX, "$w x $h side $side")
            assertTrue(side >= ORB_HOME_MIN, "$w x $h side $side")
            assertTrue(side <= w, "$w x $h side $side wider than the window")
            val reserve = heroReserve(density, twoPane = false)
            if (h - reserve >= ORB_HOME_MIN) {
                assertTrue(side + reserve <= h, "$w x $h: $side + $reserve overflows")
            }
        }
    }

    @Test
    fun theSlotDependsOnTheWindowOnly() {
        // Same window, same answer: nothing about the state can move it.
        phones.forEach { (w, h) ->
            val density = homeDensity(h)
            assertEquals(
                heroSlotHeight(w, h, density, twoPane = false),
                heroSlotHeight(w, h, density, twoPane = false),
            )
        }
    }

    @Test
    fun largeTextGivesTheCardsMoreRoom() {
        val density = homeDensity(780.dp)
        assertTrue(
            heroSlotHeight(412.dp, 780.dp, density, twoPane = false, fontScale = 1.3f) <=
                heroSlotHeight(412.dp, 780.dp, density, twoPane = false, fontScale = 1f),
        )
    }

    @Test
    fun twoPaneHeroFitsItsHalf() {
        listOf(640.dp to 300.dp, 880.dp to 700.dp, 1280.dp to 800.dp).forEach { (w, h) ->
            val density = homeDensity(h)
            val side = heroSlotHeight(w, h, density, twoPane = true)
            assertTrue(side <= HERO_ORBIT_MAX, "$w x $h side $side")
            assertTrue(side >= ORB_HOME_MIN, "$w x $h side $side")
            assertTrue(side <= (w - 72.dp) / 2 || side == ORB_HOME_MIN, "$w x $h side $side")
        }
    }
}
