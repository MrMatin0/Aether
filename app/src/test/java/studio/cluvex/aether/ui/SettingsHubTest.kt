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
