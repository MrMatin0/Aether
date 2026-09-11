package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine
import studio.cluvex.aether.ui.components.ConsoleFilter
import studio.cluvex.aether.ui.components.ConsoleQuery
import studio.cluvex.aether.ui.components.consoleCounts
import studio.cluvex.aether.ui.components.filterConsole

/**
 * What the log inspector shows.
 *
 * These are the decisions that can hide the line a reader came for, so they live
 * outside the composable and are asserted directly.
 */
class DiagnosticsConsoleTest {

    private val everything = ConsoleQuery("", regex = false)

    private val log = listOf(
        LogLine(0L, "diag", LogLevel.DEBUG, "starting self-check"),
        LogLine(0L, "hev", LogLevel.INFO, "up on 127.0.0.1:1080"),
        LogLine(0L, "diag", LogLevel.WARN, "dns+http failed, will repeat"),
        LogLine(0L, "socks", LogLevel.ERROR, "CONNECT refused rep=1"),
    )

    private fun messages(filter: ConsoleFilter, query: ConsoleQuery = everything) =
        filterConsole(log, filter, query).map { it.message }

    @Test
    fun `everything means everything`() {
        assertEquals(log.map { it.message }, messages(ConsoleFilter.ALL))
    }

    @Test
    fun `the severity filters keep the same shape they always had`() {
        assertEquals(listOf("CONNECT refused rep=1"), messages(ConsoleFilter.ERRORS))
        assertEquals(
            listOf("dns+http failed, will repeat", "CONNECT refused rep=1"),
            messages(ConsoleFilter.WARNINGS),
        )
    }

    /**
     * A tunnel event is INFO and a DNS failure is WARN, so neither question can
     * be answered by reading levels. The subject filters read the tag first and
     * the message second, because the tags were never normalised across the
     * dozen call sites that write them.
     */
    @Test
    fun `the subject filters read the tag and the message, not the level`() {
        assertEquals(
            listOf("up on 127.0.0.1:1080", "CONNECT refused rep=1"),
            messages(ConsoleFilter.TUNNEL),
        )
        assertEquals(listOf("dns+http failed, will repeat"), messages(ConsoleFilter.DNS))
    }

    /** A badge reading 0 answers "are there errors" without changing the view. */
    @Test
    fun `every badge count agrees with its own filter`() {
        val counts = consoleCounts(log)
        ConsoleFilter.entries.forEach { filter ->
            assertEquals(messages(filter).size, counts[filter], "count for $filter")
        }
        assertEquals(log.size, counts[ConsoleFilter.ALL])
        assertEquals(1, counts[ConsoleFilter.ERRORS])
    }

    @Test
    fun `plain search matches the rendered line, ignoring case`() {
        assertEquals(
            listOf("CONNECT refused rep=1"),
            messages(ConsoleFilter.ALL, ConsoleQuery("REFUSED", regex = false)),
        )
        // The tag is part of the rendered line, so it is searchable too.
        assertEquals(2, messages(ConsoleFilter.ALL, ConsoleQuery("/diag:", regex = false)).size)
    }

    @Test
    fun `regex is only a regex when regex is on`() {
        assertEquals(
            listOf("CONNECT refused rep=1"),
            messages(ConsoleFilter.ALL, ConsoleQuery("rep=\\d", regex = true)),
        )
        // With regex off the same text is a literal, and no line contains it.
        assertTrue(messages(ConsoleFilter.ALL, ConsoleQuery("rep=\\d", regex = false)).isEmpty())
        assertTrue(messages(ConsoleFilter.ALL, ConsoleQuery(".*", regex = false)).isEmpty())
    }

    /**
     * A pattern is invalid for most of the time it is being typed. Treating that
     * as "matches nothing" empties the console under the reader's hands, so it
     * falls back to a substring match and says so.
     */
    @Test
    fun `a half-typed pattern falls back to plain text instead of throwing`() {
        val broken = ConsoleQuery("rep=[", regex = true)
        assertTrue(broken.malformed)
        assertTrue(messages(ConsoleFilter.ALL, broken).isEmpty())

        val brokenButPresent = ConsoleQuery("refused(", regex = true)
        assertTrue(brokenButPresent.malformed)
        assertTrue(messages(ConsoleFilter.ALL, brokenButPresent).isEmpty())
    }

    @Test
    fun `a blank query is not a filter`() {
        assertTrue(everything.blank)
        assertFalse(everything.malformed)
        assertFalse(ConsoleQuery("   ", regex = true).malformed)
        assertEquals(log.size, messages(ConsoleFilter.ALL, ConsoleQuery("   ", regex = true)).size)
    }
}
