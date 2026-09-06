package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import studio.cluvex.aether.ui.components.AppEntry
import studio.cluvex.aether.ui.components.filterApps
import studio.cluvex.aether.ui.components.selectVisibleApps

class UiUxRegressionTest {
    private val apps = listOf(AppEntry("com.example.mail", "Mail"), AppEntry("com.example.bank", "بانک"))

    @Test fun searchTrimsAndMatchesLabelsOrPackages() {
        assertEquals(listOf(apps[0]), filterApps(apps, " MAIL "))
        assertEquals(listOf(apps[1]), filterApps(apps, "بانک"))
        assertEquals(listOf(apps[1]), filterApps(apps, "EXAMPLE.BANK"))
        assertEquals(apps, filterApps(apps, "  "))
        assertEquals(emptyList(), filterApps(apps, "missing"))
    }
    @Test fun selectingVisibleResultsKeepsUnlistedPackages() {
        assertEquals(setOf("hidden.package", "com.example.mail"),
            selectVisibleApps(setOf("hidden.package"), listOf(apps[0])))
    }
    @Test fun selectingAgainDoesNotDuplicatePackages() {
        assertEquals(setOf("com.example.mail"), selectVisibleApps(setOf("com.example.mail"), listOf(apps[0])))
    }
    @Test fun negativeDurationDoesNotShowNegativeClockFields() {
        assertEquals("0:00", formatDuration(-1000))
        assertEquals("0:00", formatDuration(999))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("1:00:00", formatDuration(3_600_000))
    }
}
