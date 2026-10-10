package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every function here has an official vector, and a single wrong bit on the
 * wire is reported by the server as "wrong password" - the least helpful
 * failure there is. So the RFCs pin it.
 *
 *  - RFC 1320 appendix A.5 (MD4)
 *  - RFC 2759 section 9.2 (MS-CHAPv2)
 *  - RFC 3079 section 3.5.3 (128-bit MPPE keys from MS-CHAPv2)
 */
class MsChapV2Test {

    private fun unhex(text: String): ByteArray {
        val clean = text.replace(" ", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private val user = "User"
    private val password = "clientPass"
    private val authChallenge = unhex("5B 5D 7C 7D 7B 3F 2F 3E 3C 2C 60 21 32 26 26 28")
    private val peerChallenge = unhex("21 40 23 24 25 5E 26 2A 28 29 5F 2B 3A 33 7C 7E")
    private val ntResponse = unhex("82 30 9E CD 8D 70 8B 5E A0 8F AA 39 81 CD 83 54 42 33 11 4A 3D 85 D6 DF")
    private val passwordHash = unhex("44 EB BA 8D 53 12 B8 D6 11 47 44 11 F5 69 89 AE")

    @Test
    fun md4MatchesRfc1320() {
        assertContentEquals(unhex("31d6cfe0d16ae931b73c59d7e0c089c0"), MsChapV2.md4(ByteArray(0)))
        assertContentEquals(unhex("bde52cb31de33e46245e05fbdbd6fb24"), MsChapV2.md4("a".toByteArray()))
        assertContentEquals(unhex("a448017aaf21d8525fc10ae87aa6729d"), MsChapV2.md4("abc".toByteArray()))
        assertContentEquals(
            unhex("e33b4ddc9c38f2199c3e7b164fcc0536"),
            MsChapV2.md4("1234567890".repeat(8).toByteArray()),
        )
    }

    @Test
    fun challengeHashMatchesRfc2759() {
        assertContentEquals(
            unhex("D0 2E 43 86 BC E9 12 26"),
            MsChapV2.challengeHash(peerChallenge, authChallenge, MsChapV2.challengeName(user)),
        )
    }

    @Test
    fun passwordHashAndResponseMatchRfc2759() {
        assertContentEquals(passwordHash, MsChapV2.ntPasswordHash(password))
        assertContentEquals(
            unhex("41 C0 0C 58 4B D2 D9 1C 40 17 A2 A1 2F A5 9F 3F"),
            MsChapV2.md4(passwordHash),
        )
        assertContentEquals(
            ntResponse,
            MsChapV2.challengeResponse(unhex("D0 2E 43 86 BC E9 12 26"), passwordHash),
        )
    }

    @Test
    fun respondBuildsTheWholeExchange() {
        val exchange = MsChapV2.respond(user, password, authChallenge, peerChallenge)
        assertEquals(MsChapV2.RESPONSE_VALUE_LENGTH, exchange.responseValue.size)
        assertContentEquals(peerChallenge, exchange.responseValue.copyOfRange(0, 16))
        assertContentEquals(ByteArray(8), exchange.responseValue.copyOfRange(16, 24))
        assertContentEquals(ntResponse, exchange.responseValue.copyOfRange(24, 48))
        assertEquals(0, exchange.responseValue[48].toInt())
        assertEquals("S=407A5589115FD0D6209F510FE9C04566932CDA56", exchange.expectedAuthenticator)
    }

    @Test
    fun aDomainPrefixIsNotPartOfTheChallengeHash() {
        val plain = MsChapV2.respond(user, password, authChallenge, peerChallenge)
        val withDomain = MsChapV2.respond("ACME\\User", password, authChallenge, peerChallenge)
        assertContentEquals(plain.responseValue, withDomain.responseValue)
    }

    @Test
    fun mppeKeysMatchRfc3079() {
        val master = MsChapV2.masterKey(MsChapV2.md4(passwordHash), ntResponse)
        assertContentEquals(unhex("FD EC E3 71 7A 8C 83 8C B3 88 E5 27 AE 3C DD 31"), master)
        // RFC 3079 lists the SERVER's send key, which is the client's receive key.
        assertContentEquals(
            unhex("8B 7C DC 14 9B 99 3A 1B A1 18 CB 15 3F 56 DC CB"),
            MsChapV2.clientStartKey(master, send = false),
        )
        val hlak = MsChapV2.clientHlak(passwordHash, ntResponse)
        assertEquals(32, hlak.size)
        assertContentEquals(MsChapV2.clientStartKey(master, send = false), hlak.copyOfRange(0, 16))
        assertContentEquals(MsChapV2.clientStartKey(master, send = true), hlak.copyOfRange(16, 32))
        assertFalse(hlak.copyOfRange(0, 16).contentEquals(hlak.copyOfRange(16, 32)))
    }

    @Test
    fun successIsOnlyAcceptedWithTheRightAuthenticator() {
        val expected = "S=407A5589115FD0D6209F510FE9C04566932CDA56"
        assertTrue(MsChapV2.verifySuccess("S=407A5589115FD0D6209F510FE9C04566932CDA56 M=Welcome", expected))
        assertTrue(MsChapV2.verifySuccess("s=407a5589115fd0d6209f510fe9c04566932cda56", expected))
        assertFalse(MsChapV2.verifySuccess("S=0000000000000000000000000000000000000000 M=Welcome", expected))
        assertFalse(MsChapV2.verifySuccess("M=Welcome", expected))
        assertFalse(MsChapV2.verifySuccess("S=407A", expected))
    }

    @Test
    fun failureMessagesAreParsed() {
        val message = "E=691 R=0 C=8F1A0E1B93D5A6B7C8D9E0F1A2B3C4D5 V=3 M=Authentication failure"
        assertEquals(691, MsChapV2.failureCode(message))
        assertEquals("Authentication failure", MsChapV2.messageText(message))
        assertNull(MsChapV2.failureCode("no code here"))
        assertEquals("", MsChapV2.messageText("E=649"))
    }

    @Test
    fun compoundMacKeyHasTheHashLength() {
        assertEquals(32, SstpCore.compoundMacKey(ByteArray(32), sha256 = true).size)
        assertEquals(20, SstpCore.compoundMacKey(ByteArray(32), sha256 = false).size)
        val zeros = SstpCore.compoundMacKey(ByteArray(32), sha256 = true)
        val keyed = SstpCore.compoundMacKey(MsChapV2.clientHlak(passwordHash, ntResponse), sha256 = true)
        assertFalse(zeros.contentEquals(keyed), "an MS-CHAPv2 session must not bind with the PAP key")
    }
}
