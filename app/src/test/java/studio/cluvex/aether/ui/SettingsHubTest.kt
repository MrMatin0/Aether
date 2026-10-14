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

    /**
     * Groups are always-visible flat cards now, so bound the number of
     * destinations per card, not the height: larger text must remain scrollable.
     */
    @Test fun noGroupGrowsPastFiveRows() {
        SettingsGroup.entries.forEach { g -> assertTrue("${g.name} has ${g.pages.size} rows", g.pages.size <= 5) }
    }

    @Test fun noGroupIsEmpty() {
        SettingsGroup.entries.forEach { g -> assertTrue("${g.name} is empty", g.pages.isNotEmpty()) }
    }

    /** The tunnel is what people come to settings for, so it opens first. */
    @Test fun tunnelGroupComesFirst() {
        assertEquals(SettingsGroup.CORE_TUNNEL, SettingsGroup.entries.first())
        assertEquals(SettingsPage.CONNECTION, SettingsGroup.CORE_TUNNEL.pages.first())
    }

    /** Anti-DPI is the second thing people on filtered networks reach for: right under Connection. */
    @Test fun transportIsPromotedIntoCoreTunnel() {
        assertEquals(SettingsGroup.CORE_TUNNEL, SettingsPage.TRANSPORT.group)
        assertEquals(SettingsPage.TRANSPORT, SettingsGroup.CORE_TUNNEL.pages[1])
        assertFalse(SettingsPage.TRANSPORT in SettingsGroup.ADVANCED.pages)
    }

    @Test fun coreTunnelRowsAreInTheAgreedOrder() {
        assertEquals(
            listOf(
                SettingsPage.CONNECTION, SettingsPage.TRANSPORT, SettingsPage.CHAIN,
                SettingsPage.BRIDGES, SettingsPage.SETUPS,
            ),
            SettingsGroup.CORE_TUNNEL.pages,
        )
    }

    @Test fun hubGroupsAndRowsMatchTheFlatLayout() {
        assertEquals(
            listOf(
                SettingsGroup.CORE_TUNNEL, SettingsGroup.TRAFFIC, SettingsGroup.SAFETY,
                SettingsGroup.APP, SettingsGroup.ADVANCED,
            ),
            SettingsGroup.entries,
        )
        assertEquals(listOf(SettingsPage.ROUTING, SettingsPage.SHARING), SettingsGroup.TRAFFIC.pages)
        assertEquals(listOf(SettingsPage.SECURITY, SettingsPage.AUTOMATION), SettingsGroup.SAFETY.pages)
        assertEquals(
            listOf(SettingsPage.APPEARANCE, SettingsPage.HISTORY, SettingsPage.ABOUT),
            SettingsGroup.APP.pages,
        )
        assertEquals(
            listOf(SettingsPage.TUNING, SettingsPage.ORGANIZATION, SettingsPage.RESET),
            SettingsGroup.ADVANCED.pages,
        )
    }

    /** Engine tuning, organization-only Zero Trust and Reset stay out of the everyday path. */
    @Test fun expertPagesLiveUnderAdvanced() {
        listOf(SettingsPage.TUNING, SettingsPage.ORGANIZATION, SettingsPage.RESET)
            .forEach { assertEquals("${it.name} should be under Advanced", SettingsGroup.ADVANCED, it.group) }
    }

    /** Tor bridges keep their place in the tunnel group. */
    @Test fun bridgesStayInCoreTunnel() {
        assertEquals(SettingsGroup.CORE_TUNNEL, SettingsPage.BRIDGES.group)
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

    @Test fun transportIsFoundByAntiDpiTerms() {
        listOf("DPI", "SNI", "fragment", "ECH").forEach {
            assertTrue("$it should point at Transport", it in SettingsPage.TRANSPORT.tags)
        }
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
        assertNotNull(SettingsFuzzy.matchTerm(normalizeSettingsText("\u0627\u0645\u0646\u064a\u062a"), "\u0627\u0645\u0646\u06cc\u062a"))
    }

    @Test fun persianDigitsFindAsciiValues() {
        assertNotNull(SettingsFuzzy.matchTerm(normalizeSettingsText("\u06f1\u06f2\u06f8\u06f0"), "MTU 1280"))
    }

    @Test fun legacyMatcherStillWorks() {
        assertTrue(matchesSettingsQuery("kill switch", listOf("Kill switch", "Strict")))
        assertFalse(matchesSettingsQuery("kill tor", listOf("Kill switch")))
    }

    @Test fun persianHighlightWidensToWholeWord() {
        val text = "\u0645\u0633\u06cc\u0631\u06cc\u0627\u0628\u06cc"
        assertEquals(text.indices, widenForJoiningScripts(text, 2..3))
    }
}
