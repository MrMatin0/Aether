package studio.cluvex.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine
import studio.cluvex.aether.core.parseRestoredLine
import studio.cluvex.aether.ui.components.ConsoleFilter
import studio.cluvex.aether.ui.components.ConsoleQuery
import studio.cluvex.aether.ui.components.filterConsoleGuarded
import studio.cluvex.aether.ui.components.matchesConsoleFilter
import studio.cluvex.aether.ui.components.newerThan

/** The console redesign's rules, asserted without a composition. */
class DiagnosticsRedesignTest {

    private fun line(tag: String, message: String, level: LogLevel = LogLevel.INFO, seq: Long = 0L) =
        LogLine(0L, tag, level, message, seq = seq)

    @Test
    fun `the tunnel filter matches words, not fragments of words`() {
        assertFalse(matchesConsoleFilter(line("log", "—— previous session restored (3 lines) ——"), ConsoleFilter.TUNNEL))
        assertFalse(matchesConsoleFilter(line("TrafficMonitor", "tuned the sampler"), ConsoleFilter.TUNNEL))
        assertFalse(matchesConsoleFilter(line("ui", "factory reset requested"), ConsoleFilter.TUNNEL))

        assertTrue(matchesConsoleFilter(line("core/HevTunnel", "up"), ConsoleFilter.TUNNEL))
        assertTrue(matchesConsoleFilter(line("svc", "tun0 established"), ConsoleFilter.TUNNEL))
        assertTrue(matchesConsoleFilter(line("svc", "SOCKS5 greeting ok"), ConsoleFilter.TUNNEL))
        assertTrue(matchesConsoleFilter(line("core/TorCore", "bootstrapped 100%"), ConsoleFilter.TUNNEL))
    }

    @Test
    fun `the dns filter reads camelCase tags`() {
        assertTrue(matchesConsoleFilter(line("core/DnsMap", "mapped"), ConsoleFilter.DNS))
        assertFalse(matchesConsoleFilter(line("core/HevTunnel", "up"), ConsoleFilter.DNS))
    }

    @Test
    fun `a restored line keeps its severity and tag`() {
        val text = "14:03:22.114 E/core/HevTunnel: native fault"
        val restored = parseRestoredLine(text, seq = 7L)
        assertEquals(LogLevel.ERROR, restored.level)
        assertEquals("core/HevTunnel", restored.tag)
        assertEquals(7L, restored.seq)
        assertTrue(restored.raw)
        assertEquals(text, restored.format())
        assertTrue(matchesConsoleFilter(restored, ConsoleFilter.ERRORS))
    }

    @Test
    fun `an unparseable restored line stays a quiet debug line`() {
        val restored = parseRestoredLine("[... 12 lines dropped ...]")
        assertEquals(LogLevel.DEBUG, restored.level)
        assertEquals("prev", restored.tag)
    }

    @Test
    fun `a catastrophic pattern cannot hang the console`() {
        val evil = ConsoleQuery("(a+)+$", regex = true)
        val lines = List(50) { line("t", "a".repeat(40) + "!", seq = it.toLong()) }
        val started = System.nanoTime()
        val slice = filterConsoleGuarded(lines, ConsoleFilter.ALL, evil)
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(slice.regexTimedOut)
        assertTrue(slice.lines.isEmpty())
        assertTrue(tookMs < 2_000L, "took $tookMs ms")
    }

    @Test
    fun `a fast pattern is not reported as slow`() {
        val lines = listOf(line("socks", "CONNECT refused rep=1", seq = 1L))
        val slice = filterConsoleGuarded(lines, ConsoleFilter.ALL, ConsoleQuery("rep=[0-9]", regex = true))
        assertEquals(1, slice.lines.size)
        assertFalse(slice.regexTimedOut)
    }

    @Test
    fun `new lines are counted from the tail`() {
        val shown = (1L..10L).map { line("t", "m$it", seq = it) }
        assertEquals(3, newerThan(shown, 7L))
        assertEquals(0, newerThan(shown, 10L))
        assertEquals(0, newerThan(emptyList(), 0L))
    }
}
