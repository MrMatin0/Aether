package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import studio.cluvex.aether.ui.components.AppEntry
import studio.cluvex.aether.ui.components.filterApps
import studio.cluvex.aether.ui.components.normalizeAppSearchText
import studio.cluvex.aether.ui.components.orderedAppSelection
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
    @Test fun appSearchRequiresEveryTermButAllowsTermsAcrossFields() {
        assertEquals(listOf(apps[0]), filterApps(apps, "mail example"))
        assertEquals(listOf(apps[1]), filterApps(apps, "bank بانک"))
        assertEquals(emptyList(), filterApps(apps, "mail bank"))
    }
    @Test fun appSearchNormalizesPersianKeyboardVariants() {
        val persian = listOf(AppEntry("ir.example.routes", "مسیر‌یابی"), AppEntry("ir.example.keys", "کلید"))
        assertEquals(listOf(persian[0]), filterApps(persian, "مسیريابي"))
        assertEquals(listOf(persian[1]), filterApps(persian, "كليد"))
        assertEquals("مسیر یابی", normalizeAppSearchText("  مسیر   یابی  "))
    }
    @Test fun selectingVisibleResultsKeepsUnlistedPackages() {
        assertEquals(setOf("hidden.package", "com.example.mail"),
            selectVisibleApps(setOf("hidden.package"), listOf(apps[0])))
    }
    @Test fun selectingAgainDoesNotDuplicatePackages() {
        assertEquals(setOf("com.example.mail"), selectVisibleApps(setOf("com.example.mail"), listOf(apps[0])))
    }
    @Test fun confirmedSelectionFollowsVisibleOrderAndSortsMissingPackages() {
        assertEquals(
            listOf("com.example.mail", "com.example.bank", "a.hidden", "z.hidden"),
            orderedAppSelection(
                setOf("z.hidden", "com.example.bank", "a.hidden", "com.example.mail"),
                apps,
            ),
        )
    }
    @Test fun negativeDurationDoesNotShowNegativeClockFields() {
        assertEquals("0:00", formatDuration(-1000))
        assertEquals("0:00", formatDuration(999))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("1:00:00", formatDuration(3_600_000))
    }
}
