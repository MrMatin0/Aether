package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ConnectionState

class HomePresentationTest {
    @Test
    fun engineShortcutSelectsSettingsAndBackReturnsToHub() {
        val engine = HomeRoute().open(SettingsPage.ENGINE)
        assertEquals(HomeTab.SETTINGS, engine.tab)
        assertEquals(SettingsPage.ENGINE, engine.page)
        assertTrue(engine.canGoBack)
        assertEquals(HomeRoute(HomeTab.SETTINGS), engine.back())
        assertEquals(HomeRoute(), engine.back().back())
        assertFalse(engine.back().back().canGoBack)
    }

    @Test
    fun diagnosticsNavigationClearsAnySettingsPage() {
        val route = HomeRoute().open(SettingsPage.ENGINE).select(HomeTab.DIAGNOSTICS)
        assertEquals(HomeTab.DIAGNOSTICS, route.tab)
        assertNull(route.page)
        assertEquals(HomeRoute(), route.back())
    }

    @Test
    fun selectingAnyTabClearsTheNestedPage() {
        HomeTab.entries.forEach { tab ->
            val route = HomeRoute().open(SettingsPage.ABOUT).select(tab)
            assertEquals(HomeRoute(tab), route)
        }
    }

    @Test
    fun allRoutesRoundTripThroughSavedState() {
        val routes = HomeTab.entries.map { HomeRoute(it) } +
            SettingsPage.entries.map { HomeRoute().open(it) }
        routes.forEach { assertEquals(it, HomeRoute.restore(it.savedValues())) }
    }

    @Test
    fun invalidSavedRoutesRecoverWithoutOpeningTheWrongPage() {
        assertEquals(HomeRoute(), HomeRoute.restore(emptyList()))
        assertEquals(HomeRoute(), HomeRoute.restore(listOf("removed-tab", "ENGINE")))
        assertEquals(HomeRoute(HomeTab.SETTINGS), HomeRoute.restore(listOf("SETTINGS", "removed-page")))
        assertEquals(HomeRoute(HomeTab.DIAGNOSTICS), HomeRoute.restore(listOf("DIAGNOSTICS", "ENGINE")))
    }

    @Test
    fun settingsPageCannotBelongToAnotherTab() {
        assertFailsWith<IllegalArgumentException> { HomeRoute(HomeTab.HOME, SettingsPage.ENGINE) }
    }

    @Test
    fun onlyForwardConnectionStatesShowPipeline() {
        assertEquals(0, connectionStep(ConnectionState.Launching))
        assertEquals(1, connectionStep(ConnectionState.Connecting))
        assertEquals(1, connectionStep(ConnectionState.Reconnecting(1, 3)))
        assertEquals(2, connectionStep(ConnectionState.Verifying))
        listOf(
            ConnectionState.Idle,
            ConnectionState.Connected("127.0.0.1:1080"),
            ConnectionState.Disconnecting,
            ConnectionState.Error("failed"),
        ).forEach { assertNull(connectionStep(it)) }
    }

    @Test
    fun uptimeHandlesMissingOrFutureStart() {
        assertEquals("…", formatSessionUptime(null, 1000))
        assertEquals("00:00:00", formatSessionUptime(2000, 1000))
        assertEquals("00:00:00", formatSessionUptime(1000, 1999))
    }

    @Test
    fun uptimeFormatsBoundariesAndLongSessions() {
        assertEquals("00:00:01", formatSessionUptime(1000, 2000))
        assertEquals("00:01:00", formatSessionUptime(1000, 61000))
        assertEquals("01:01:01", formatSessionUptime(1000, 3662000))
        assertEquals("100:00:00", formatSessionUptime(0, 360000000))
    }
}
