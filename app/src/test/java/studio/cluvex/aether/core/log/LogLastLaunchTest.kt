package studio.cluvex.aether.core.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The previous launch - the one a crash log lives in - has to outlive
 * everything the NEW session does to its own file.
 */
class LogLastLaunchTest {

    @Test
    fun `the previous launch survives a trim and a clear of the new session`() {
        val target = tempLog()
        target.writeText("crash line\n")
        val store = LogFile(maxBytes = 12L, keepLinesOnTrim = 2)
        store.attach(target)

        // Past the cap: trims, which rotates .prev over the attach-time copy.
        assertTrue(store.append(listOf("one", "two", "three", "four")))
        // And a clear on top, which rotates .prev again.
        store.reset()

        val seen = ArrayList<String>()
        assertTrue(store.forEachLastLaunchLine { seen.add(it) })
        assertEquals(listOf("crash line"), seen)
    }

    @Test
    fun `a launch with nothing before it reports no previous launch`() {
        val target = tempLog()
        val store = LogFile(maxBytes = 4096L, keepLinesOnTrim = 8)
        store.attach(target)
        assertFalse(store.forEachLastLaunchLine { })
    }

    @Test
    fun `a stale previous launch is not carried across an empty one`() {
        val target = tempLog()
        File(target.parentFile, target.name + ".last").writeText("two launches ago\n")
        val store = LogFile(maxBytes = 4096L, keepLinesOnTrim = 8)
        store.attach(target)
        assertFalse(store.forEachLastLaunchLine { })
    }

    @Test
    fun `public addresses are masked and tunnel addresses are not`() {
        assertEquals(
            "exit ip=104.28.x.x via 127.0.0.1:1080 tun 10.0.0.2 dns 8.8.x.x",
            IpMasker.mask("exit ip=104.28.1.2 via 127.0.0.1:1080 tun 10.0.0.2 dns 8.8.8.8"),
        )
        // A timestamp and a version string are not addresses.
        assertEquals("14:03:22.114 I/diag: v1.5.0", IpMasker.mask("14:03:22.114 I/diag: v1.5.0"))
    }

    private fun tempLog(): File =
        File.createTempFile("aether-last-launch", ".log").apply { deleteOnExit() }
}
