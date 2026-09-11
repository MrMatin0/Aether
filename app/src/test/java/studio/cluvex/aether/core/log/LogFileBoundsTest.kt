package studio.cluvex.aether.core.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file's two new guarantees: budgets counted in real bytes, and a trim that
 * a SIGKILL cannot catch half-done.
 */
class LogFileBoundsTest {

    /**
     * String.length is a UTF-16 unit count. This app's logs are routinely
     * Persian (two bytes per letter) and occasionally carry a flag emoji (four),
     * so sizing a file budget by length under-counts by 2-4x on exactly the
     * devices the app is built for.
     */
    @Test
    fun `utf8 length agrees with the real encoder across all three widths`() {
        listOf(
            "",
            "hello",
            "14:03:22.114 I/diag: port open = true",
            "\u062a\u0648\u0646\u0644 \u0628\u0627\u0644\u0627 \u0627\u0633\u062a",
            "\u2014\u2014 previous session restored \u2014\u2014",
            "\uD83C\uDDEE\uD83C\uDDF7 exit",
        ).forEach { text ->
            assertEquals(
                text.toByteArray(Charsets.UTF_8).size.toLong(),
                utf8ByteLength(text),
                "byte length of \"$text\"",
            )
        }
    }

    @Test
    fun `the byte budget keeps the newest lines`() {
        val lines = listOf("aaaa", "bbbb", "cccc")
        assertEquals(listOf("cccc"), boundToByteBudget(lines, 5))
        assertEquals(listOf("bbbb", "cccc"), boundToByteBudget(lines, 10))
        assertEquals(lines, boundToByteBudget(lines, 100))
    }

    /** A trim that returns nothing is a trim that deleted the log. */
    @Test
    fun `an oversized single line is kept rather than dropped`() {
        assertEquals(listOf("cccc"), boundToByteBudget(listOf("aaaa", "bbbb", "cccc"), 1))
        assertTrue(boundToByteBudget(emptyList(), 100).isEmpty())
    }

    /**
     * The trim stages, fsyncs and renames. If a staging file survives the call,
     * the swap was not atomic and a crash could have been caught mid-write.
     */
    @Test
    fun `a trim swaps atomically and leaves no staging file behind`() {
        val target = tempLog()
        val store = LogFile(maxBytes = 12L, keepLinesOnTrim = 2)
        store.attach(target)

        assertTrue(store.append(listOf("one", "two", "three", "four")))

        assertEquals(listOf("three", "four"), target.readLines())
        val parent = target.parentFile
        assertFalse(File(parent, target.name + ".tmp").exists(), "staging file left behind")
        assertFalse(File(parent, target.name + ".prev.tmp").exists(), "rotation staging left behind")
        assertTrue(File(parent, target.name + ".prev").exists(), "the trimmed content must survive")
    }

    /**
     * `.prev` is allowed to be as large as the cap, and the export path used to
     * read it whole. Streaming holds one line at a time.
     */
    @Test
    fun `the log reads as a stream, and reports an absent rotation`() {
        val target = tempLog()
        val store = LogFile(maxBytes = 4096L, keepLinesOnTrim = 8)
        store.attach(target)
        store.append(listOf("alpha", "beta"))

        val seen = ArrayList<String>()
        assertTrue(store.forEachLine { seen.add(it) })
        assertEquals(listOf("alpha", "beta"), seen)

        // Nothing has rotated yet, so there is no previous session to stream.
        assertFalse(store.forEachLine(previous = true) { seen.add(it) })
        assertEquals(2, seen.size)
    }

    private fun tempLog(): File =
        File.createTempFile("aether-bounds", ".log").apply { deleteOnExit() }
}
