package studio.cluvex.aether.core

import studio.cluvex.aether.core.moat.JsonLite
import studio.cluvex.aether.core.moat.MoatPayloads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The moat wire format.
 *
 * Every interesting failure of the bridge feature is a parsing failure on a
 * network nobody can reproduce: a captive portal answering HTML with a 200, an
 * error object where bridges were expected, or Go's HTML escaping arriving inside
 * a snowflake URL. All three are testable, and only here.
 *
 * Payload shapes come from doc/moat.md in tpo/anti-censorship/rdsys.
 */
class MoatPayloadsTest {

    @Test
    fun `the built-in list is read as transport to lines`() {
        val body = """
            {
              "meek": ["meek_lite 192.0.2.20:80 url=https://x.invalid front=y.invalid"],
              "meek-azure": ["meek_lite 192.0.2.20:80 url=https://x.invalid front=y.invalid"],
              "obfs4": [
                "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=x iat-mode=0",
                "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=y iat-mode=0"
              ],
              "snowflake": ["snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72"]
            }
        """.trimIndent()
        val parsed = MoatPayloads.builtin(body)
        assertEquals(setOf("meek", "meek-azure", "obfs4", "snowflake"), parsed.keys)
        assertEquals(2, parsed.getValue("obfs4").size)
    }

    @Test
    fun `go html escaping is undone, or tor gets a broken url`() {
        // Go's encoding/json escapes & < > by default, so a snowflake or meek URL
        // arrives with \u0026 in it. Handing that to tor verbatim produces a
        // bridge that simply does not work.
        val body = """
            {"meek": ["meek_lite 192.0.2.20:80 url=https://x.invalid/?a=1\u0026b=2 front=y.invalid"]}
        """.trimIndent()
        val line = MoatPayloads.builtin(body).getValue("meek").first()
        assertTrue(line.contains("a=1&b=2"), "escaping survived into the bridge line: $line")
        assertFalse(line.contains("u0026"))
    }

    @Test
    fun `circumvention settings are read best first, with their source`() {
        val body = """
            {
              "settings": [
                {"bridges": {"type": "obfs4", "source": "builtin", "bridge_strings": [
                  "obfs4 1.2.3.4:443 AAAABBBBCCCCDDDDEEEEFFFF0000111122223333 cert=x iat-mode=0"
                ]}},
                {"bridges": {"type": "obfs4", "source": "bridgedb", "bridge_strings": [
                  "obfs4 5.6.7.8:443 AAAABBBBCCCCDDDDEEEEFFFF0000111144445555 cert=y iat-mode=0"
                ]}}
              ],
              "country": "ir"
            }
        """.trimIndent()
        val settings = MoatPayloads.settings(body)
        assertEquals("ir", settings.country)
        assertEquals(2, settings.bundles.size)
        // The distinction the UI depends on: a bridgedb bundle is a bridge handed
        // to this client alone and is kept like a personal request.
        assertFalse(settings.bundles[0].isPrivate)
        assertTrue(settings.bundles[1].isPrivate)
    }

    @Test
    fun `an empty settings list is an answer, not a failure`() {
        // It means the Tor Project believes plain Tor works from there.
        val settings = MoatPayloads.settings("""{"settings": [], "country": "se"}""")
        assertEquals("se", settings.country)
        assertTrue(settings.bundles.isEmpty())
    }

    @Test
    fun `an error object throws instead of reading as no bridges`() {
        // moat answers HTTP 200 for its own errors, so this object is the ONLY
        // signal. Reading it as "nothing found" would send the user to retry the
        // one thing that cannot work.
        val body = """
            {"errors": [{"code": 404, "detail": "No provided transport is available for this country"}]}
        """.trimIndent()
        val error = assertFailsWith<MoatPayloads.MoatError> { MoatPayloads.settings(body) }
        assertEquals(404, error.code)
        assertTrue(error.detail.contains("transport"))
    }

    @Test
    fun `a refused captcha is reported as its own code`() {
        val body = """
            {"errors": [{"id": "4", "type": "moat-bridges", "version": "0.1.0",
              "code": 419, "status": "No You're A Teapot",
              "detail": "The CAPTCHA solution was incorrect."}]}
        """.trimIndent()
        val error = assertFailsWith<MoatPayloads.MoatError> { MoatPayloads.bridges(body) }
        assertEquals(419, error.code)
    }

    @Test
    fun `a challenge carries its token, and a transport in either shape`() {
        val asArray = """
            {"data": [{"id": "1", "type": "moat-challenge", "version": "0.1.0",
              "transport": ["obfs4", "vanilla"], "image": "aGk=", "challenge": "tok1"}]}
        """.trimIndent()
        MoatPayloads.challenge(asArray).let {
            assertEquals("tok1", it.challenge)
            assertEquals(listOf("obfs4", "vanilla"), it.transports)
            assertEquals("aGk=", it.image)
        }
        // doc/moat.md: `transport` is a bare string when the request named one.
        val asString = """
            {"data": [{"id": "1", "transport": "obfs4", "image": "aGk=", "challenge": "tok2"}]}
        """.trimIndent()
        assertEquals(listOf("obfs4"), MoatPayloads.challenge(asString).transports)
    }

    @Test
    fun `bridges are read out of a check response`() {
        val body = """
            {"data": [{"id": "3", "type": "moat-bridges", "version": "0.1.0", "bridges": [
              "obfs4 1.2.3.4:443 AAAABBBBCCCCDDDDEEEEFFFF0000111122223333 cert=x iat-mode=0",
              "obfs4 5.6.7.8:443 AAAABBBBCCCCDDDDEEEEFFFF0000111144445555 cert=y iat-mode=0"
            ], "qrcode": null}]}
        """.trimIndent()
        assertEquals(2, MoatPayloads.bridges(body).size)
    }

    @Test
    fun `a captive portal page is rejected rather than half-parsed`() {
        listOf(
            "<html><body>Blocked</body></html>",
            "",
            "   ",
            "{\"settings\": [",
            "not json at all",
        ).forEach { junk ->
            assertFailsWith<JsonLite.JsonException>("must not parse: $junk") {
                MoatPayloads.settings(junk)
            }
        }
    }

    @Test
    fun `requests are built with only values the server accepts`() {
        assertEquals(
            """{"country": "ir", "transports": ["obfs4", "snowflake"]}""",
            MoatPayloads.settingsRequest("IR", listOf("obfs4", "snowflake")),
        )
        // A country that is not two letters is dropped rather than sent: moat
        // answers 400 for a malformed body and that reads as "no bridges".
        assertEquals(
            """{"transports": ["obfs4"]}""",
            MoatPayloads.settingsRequest("Germany", listOf("obfs4", "NOT A TRANSPORT")),
        )
        assertEquals("{}", MoatPayloads.settingsRequest(null, emptyList()))
    }

    @Test
    fun `a captcha answer is escaped, whatever the user typed`() {
        val request = MoatPayloads.checkRequest("obfs4", "tok", "a\"b\\c\nd")
        assertTrue(request.contains("\\\""))
        assertTrue(request.contains("\\\\"))
        assertTrue(request.contains("\\n"))
        // Still one well-formed document.
        JsonLite.parseObject(request)
    }

    @Test
    fun `the json reader refuses what it cannot represent`() {
        assertFailsWith<JsonLite.JsonException> { JsonLite.parse("{\"a\": 1} trailing") }
        assertFailsWith<JsonLite.JsonException> { JsonLite.parse("{\"a\": \"unterminated\") }") }
        assertEquals("b", JsonLite.string(JsonLite.parseObject("{\"a\": \"b\"}")["a"]))
        assertEquals(7, JsonLite.int(JsonLite.parseObject("{\"a\": 7}")["a"]))
    }
}
