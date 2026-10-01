package studio.cluvex.aether.ui

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.ui.components.widenForJoiningScripts

class SettingsHubTest {

    @Test fun everyPageLivesInExactlyOneGroup() {
        val placed = SettingsGroup.entries.flatMap { it.pages }
        assertEquals(SettingsPage.entries.toSet(), placed.toSet())
        assertEquals(placed.size, placed.toSet().size)
    }

    @Test fun summaryPagesBelongToTheirGroup() {
        SettingsGroup.entries.forEach { g -> assertTrue(g.pages.containsAll(g.summaryPages)) }
    }

    /** Bound the number of destinations, not the height: larger text must remain scrollable. */
    @Test fun noGroupGrowsPastFourRows() {
        SettingsGroup.entries.forEach { g -> assertTrue("${g.name} has ${g.pages.size} rows", g.pages.size <= 4) }
    }

    /** The tunnel is what people come to settings for, so it opens first. */
    @Test fun tunnelGroupComesFirst() {
        assertEquals(SettingsGroup.CORE_TUNNEL, SettingsGroup.entries.first())
        assertEquals(SettingsPage.CONNECTION, SettingsGroup.CORE_TUNNEL.pages.first())
    }

    /** Pages whose copy says "change only when a connection keeps failing" stay out of the everyday path. */
    @Test fun expertPagesLiveUnderAdvanced() {
        listOf(SettingsPage.TRANSPORT, SettingsPage.TUNING, SettingsPage.ORGANIZATION, SettingsPage.RESET)
            .forEach { assertEquals("${it.name} should be under Advanced", SettingsGroup.ADVANCED, it.group) }
    }

    /** The destructive row never sits between two harmless ones. */
    @Test fun resetIsTheLastRowOfTheHub() {
        assertEquals(SettingsPage.RESET, SettingsGroup.entries.last().pages.last())
    }

    @Test fun savedDestinationNamesAndOrderStayStable() {
        assertEquals(
            listOf(
                "CONNECTION", "CHAIN", "BRIDGES", "TRANSPORT", "ROUTING", "SHARING",
                "SECURITY", "ORGANIZATION", "APPEARANCE", "AUTOMATION", "HISTORY", "SETUPS",
                "TUNING", "RESET", "ABOUT",
            ),
            SettingsPage.entries.map { it.name },
        )
    }

    @Test fun legacyExpandedGroupRestoresBothReplacementSections() {
        assertEquals(
            setOf(SettingsGroup.CORE_TUNNEL, SettingsGroup.TRAFFIC, SettingsGroup.SAFETY),
            restoreSettingsGroups(listOf("CORE_TUNNEL", "ROUTING_SAFETY")),
        )
    }

    @Test fun currentExpandedGroupsRoundTrip() {
        val groups = SettingsGroup.entries.toSet()
        assertEquals(groups, restoreSettingsGroups(groups.map { it.name }))
    }

    @Test fun unknownAndDuplicateSavedGroupsAreSafe() {
        assertEquals(
            setOf(SettingsGroup.TRAFFIC, SettingsGroup.SAFETY),
            restoreSettingsGroups(listOf("REMOVED_GROUP", "TRAFFIC", "ROUTING_SAFETY", "SAFETY")),
        )
    }

    @Test fun explicitlyCollapsedHubStaysCollapsedAfterRestore() {
        assertTrue(restoreSettingsGroups(emptyList()).isEmpty())
    }

    @Test fun foldKeepsLengthSoRangesMapBack() {
        val s = "Kill\u200Cswitch \u064A\u0643 \u06F1\u06F2\u06F8\u06F0"
        assertEquals(s.length, foldSettingsText(s).length)
    }

    @Test fun rangesPointAtOriginalCharacters() {
        val m = SettingsFuzzy.matchTerm("switch", "Kill switch")!!
        assertEquals(MatchKind.EXACT_WORD, m.kind)
        assertEquals(listOf(5..10), m.ranges)
    }

    @Test fun oneTypoStillFinds() {
        assertEquals(MatchKind.TYPO, SettingsFuzzy.matchTerm("swich", "Kill switch")?.kind)
        assertTrue(SettingsFuzzy.withinOneEdit("swithc", "switch"))
    }

    @Test fun shortTermsNeverTypoMatch() {
        assertNull(SettingsFuzzy.matchTerm("dmz", "DNS"))
    }

    @Test fun arabicKeyboardLettersFindPersianCopy() {
        assertNotNull(SettingsFuzzy.matchTerm(normalizeSettingsText("امنيت"), "امنیت"))
    }

    @Test fun persianDigitsFindAsciiValues() {
        assertNotNull(SettingsFuzzy.matchTerm(normalizeSettingsText("۱۲۸۰"), "MTU 1280"))
    }

    @Test fun legacyMatcherStillWorks() {
        assertTrue(matchesSettingsQuery("kill switch", listOf("Kill switch", "Strict")))
        assertFalse(matchesSettingsQuery("kill tor", listOf("Kill switch")))
    }

    @Test fun persianHighlightWidensToWholeWord() {
        val text = "مسیریابی"
        assertEquals(text.indices, widenForJoiningScripts(text, 2..3))
    }
}
