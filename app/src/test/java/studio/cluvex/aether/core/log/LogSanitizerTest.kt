package studio.cluvex.aether.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What must not leave the device, and what must.
 *
 * BOTH DIRECTIONS MATTER. A redaction that misses a client secret publishes it;
 * a redaction that eats the timestamp, the exit IP and the port destroys the bug
 * report the export exists to produce. The second failure is the easy one to
 * write by accident, so it gets as many assertions as the first.
 */
class LogSanitizerTest {

    @Test
    fun `a keyed value is redacted and the rest of the line survives`() {
        assertEquals(
            "token=${LogSanitizer.REDACTED} next",
            LogSanitizer.sanitize("token=abc123 next"),
        )
        assertEquals(
            "client_secret=${LogSanitizer.REDACTED}",
            LogSanitizer.sanitize("client_secret: hunter2"),
        )
    }

    /** A quoted JSON pair is the shape a rejected enrolment response comes in. */
    @Test
    fun `a quoted json credential loses its value`() {
        val sanitized = LogSanitizer.sanitize("{\"client_secret\":\"s3cr3tV4lue\"}")
        assertFalse(sanitized.contains("s3cr3tV4lue"), "secret survived: $sanitized")
        assertTrue(sanitized.contains(LogSanitizer.REDACTED))
    }

    /**
     * `Authorization: Bearer <jwt>` is two tokens, and redacting only the first
     * publishes the one that matters. The scheme word has to be swallowed with
     * the value.
     */
    @Test
    fun `a bearer header loses the whole credential, not just the scheme`() {
        assertEquals(
            "Authorization=${LogSanitizer.REDACTED}",
            LogSanitizer.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9"),
        )
        assertEquals(
            "sending Bearer ${LogSanitizer.REDACTED} upstream",
            LogSanitizer.sanitize("sending Bearer eyJhbGciOiJIUzI1NiJ9 upstream"),
        )
    }

    /** The Zero Trust enrolment flow logs the address it sent a code to. */
    @Test
    fun `an e-mail address is redacted`() {
        assertEquals(
            "enrolment code sent to ${LogSanitizer.REDACTED}",
            LogSanitizer.sanitize("enrolment code sent to person@example.com"),
        )
    }

    /** A long mixed-case-plus-digits run is a key, whatever it is called. */
    @Test
    fun `an unlabelled opaque blob is redacted`() {
        assertEquals(
            "wg peer ${LogSanitizer.REDACTED}",
            LogSanitizer.sanitize("wg peer aB3dEfGhIjKlMnOpQrStUvWxYz01"),
        )
    }

    /**
     * The diagnosis has to survive. Timestamps, levels, tags, IPs, ports, engine
     * arguments and file paths are the whole value of the export.
     */
    @Test
    fun `ordinary diagnostic lines are left completely alone`() {
        listOf(
            "14:03:22.114 I/diag: port open = true (3 ms)",
            "14:03:22.640 E/socks: CONNECT refused rep=1",
            "14:03:23.001 I/diag: dns+http OK, exit ip=104.28.11.5 cc=DE (812 ms)",
            "/data/user/0/io.github.mrmatin0.aether/files/diagnostics.log",
            "MTU 1280, keepalive 25, TLS groups X25519:P-256",
        ).forEach { line ->
            assertEquals(line, LogSanitizer.sanitize(line), "needlessly redacted")
            assertFalse(LogSanitizer.isSensitive(line), "wrongly flagged: $line")
        }
    }

    /**
     * The in-memory export and the streamed disk log are separate paths, so a
     * line can pass through this twice. The second pass must be a no-op.
     */
    @Test
    fun `sanitizing is idempotent and safe on an empty line`() {
        listOf(
            "token=abc123",
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9",
            "enrolment code sent to person@example.com",
            "wg peer aB3dEfGhIjKlMnOpQrStUvWxYz01",
        ).forEach { line ->
            val once = LogSanitizer.sanitize(line)
            assertEquals(once, LogSanitizer.sanitize(once), "not a fixed point: $once")
        }
        assertEquals("", LogSanitizer.sanitize(""))
    }

    @Test
    fun `a batch is sanitized line by line and flagged correctly`() {
        val batch = listOf("token=abc123", "port open = true")
        assertEquals(
            listOf("token=${LogSanitizer.REDACTED}", "port open = true"),
            LogSanitizer.sanitize(batch),
        )
        assertTrue(LogSanitizer.isSensitive(batch[0]))
        assertFalse(LogSanitizer.isSensitive(batch[1]))
    }
}
