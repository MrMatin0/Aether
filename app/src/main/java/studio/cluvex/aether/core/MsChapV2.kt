package studio.cluvex.aether.core

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * MS-CHAPv2 (RFC 2759) for the SSTP client, plus the MPPE key derivation
 * (RFC 3079) that the SSTP crypto binding needs.
 *
 * ### Why it is its own object
 *
 * Everything here is a pure function of a few byte arrays, and every one of
 * them has an official test vector (RFC 2759 section 9.2, RFC 3079 section 3.5).
 * Keeping it apart from the PPP state machine is what lets MsChapV2Test pin it
 * on the JVM, where a wrong bit costs nothing - on the wire it costs an
 * "authentication failed" that is indistinguishable from a wrong password.
 *
 * ### Why there is a hand-written MD4
 *
 * The NT password hash is MD4, and MD4 is not a provider algorithm on Android
 * (nor on modern JREs without a legacy provider). It is 40 lines, it is only
 * ever fed a password, and the RFC 1320 vectors pin it.
 *
 * ### Why the HLAK is computed here
 *
 * SSTP's crypto binding (MS-SSTP 3.2.5.2) proves the PPP authentication and
 * the TLS channel were done by the same client. With PAP / CHAP the
 * higher-layer key is all zeros; with MS-CHAPv2 it is the two MPPE master keys,
 * client receive key first: `HLAK = MasterReceiveKey || MasterSendKey`.
 */
internal object MsChapV2 {

    const val PEER_CHALLENGE_LENGTH = 16
    const val AUTH_CHALLENGE_LENGTH = 16
    const val NT_RESPONSE_LENGTH = 24

    /** PeerChallenge(16) + Reserved(8) + NT-Response(24) + Flags(1). */
    const val RESPONSE_VALUE_LENGTH = 49

    private val MAGIC_SIGN = "Magic server to client signing constant".toByteArray(Charsets.US_ASCII)
    private val MAGIC_PAD = "Pad to make it do more than one iteration".toByteArray(Charsets.US_ASCII)
    private val MAGIC_MASTER = "This is the MPPE Master Key".toByteArray(Charsets.US_ASCII)
    private val MAGIC_CLIENT_SEND =
        "On the client side, this is the send key; on the server side, it is the receive key."
            .toByteArray(Charsets.US_ASCII)
    private val MAGIC_CLIENT_RECEIVE =
        "On the client side, this is the receive key; on the server side, it is the send key."
            .toByteArray(Charsets.US_ASCII)
    private val SHS_PAD1 = ByteArray(40)
    private val SHS_PAD2 = ByteArray(40) { 0xF2.toByte() }

    /** Everything one MS-CHAPv2 exchange produces on the client side. */
    class Exchange(
        /** The CHAP Response Value field, [RESPONSE_VALUE_LENGTH] bytes. */
        val responseValue: ByteArray,
        /** `S=<40 hex>` the server must send back in its Success packet. */
        val expectedAuthenticator: String,
        /** The SSTP higher-layer authentication key, 32 bytes. */
        val hlak: ByteArray,
    )

    /**
     * Builds the client's answer to a server challenge.
     *
     * [username] is what goes into the challenge hash: the bare user name, not
     * a `DOMAIN\\user` form (RFC 2759 section 4).
     */
    fun respond(
        username: String,
        password: String,
        authenticatorChallenge: ByteArray,
        peerChallenge: ByteArray,
    ): Exchange {
        require(authenticatorChallenge.size == AUTH_CHALLENGE_LENGTH) { "bad authenticator challenge" }
        require(peerChallenge.size == PEER_CHALLENGE_LENGTH) { "bad peer challenge" }
        val user = challengeName(username)
        val challenge = challengeHash(peerChallenge, authenticatorChallenge, user)
        val passwordHash = ntPasswordHash(password)
        val ntResponse = challengeResponse(challenge, passwordHash)

        val value = ByteArray(RESPONSE_VALUE_LENGTH)
        peerChallenge.copyInto(value, 0)
        ntResponse.copyInto(value, PEER_CHALLENGE_LENGTH + 8)
        // Flags (the last byte) stay zero.

        return Exchange(
            responseValue = value,
            expectedAuthenticator = authenticatorResponse(passwordHash, ntResponse, challenge),
            hlak = clientHlak(passwordHash, ntResponse),
        )
    }

    /** The bytes of the user name the challenge hash is computed over. */
    fun challengeName(username: String): ByteArray =
        username.substringAfterLast('\\').toByteArray(Charsets.UTF_8)

    /** RFC 2759 ChallengeHash(): the first 8 bytes of SHA1(peer | auth | user). */
    fun challengeHash(peerChallenge: ByteArray, authenticatorChallenge: ByteArray, user: ByteArray): ByteArray =
        sha1(peerChallenge, authenticatorChallenge, user).copyOf(8)

    /** RFC 2759 NtPasswordHash(): MD4 over the UTF-16LE password. */
    fun ntPasswordHash(password: String): ByteArray = md4(password.toByteArray(Charsets.UTF_16LE))

    /** RFC 2759 ChallengeResponse(): three DES encryptions of [challenge]. */
    fun challengeResponse(challenge: ByteArray, passwordHash: ByteArray): ByteArray {
        val zPasswordHash = passwordHash.copyOf(21)
        val out = ByteArray(NT_RESPONSE_LENGTH)
        for (i in 0 until 3) {
            des(desKey(zPasswordHash, i * 7), challenge).copyInto(out, i * 8)
        }
        return out
    }

    /** RFC 2759 GenerateAuthenticatorResponse(), as the `S=` string. */
    fun authenticatorResponse(passwordHash: ByteArray, ntResponse: ByteArray, challenge: ByteArray): String {
        val passwordHashHash = md4(passwordHash)
        val first = sha1(passwordHashHash, ntResponse, MAGIC_SIGN)
        val digest = sha1(first, challenge, MAGIC_PAD)
        return "S=" + hex(digest)
    }

    /**
     * True when the server's Success message carries the authenticator we
     * expected, i.e. the server proved it knows the password too.
     */
    fun verifySuccess(message: String, expected: String): Boolean {
        val start = message.indexOf("S=")
        if (start < 0 || start + 42 > message.length) return false
        return message.substring(start, start + 42).equals(expected, ignoreCase = true)
    }

    /** RFC 3079 GetMasterKey(). */
    fun masterKey(passwordHashHash: ByteArray, ntResponse: ByteArray): ByteArray =
        sha1(passwordHashHash, ntResponse, MAGIC_MASTER).copyOf(16)

    /** RFC 3079 GetAsymmetricStartKey() for the CLIENT, 128-bit. */
    fun clientStartKey(masterKey: ByteArray, send: Boolean): ByteArray =
        sha1(masterKey, SHS_PAD1, if (send) MAGIC_CLIENT_SEND else MAGIC_CLIENT_RECEIVE, SHS_PAD2).copyOf(16)

    /** MS-SSTP 3.2.5.2.1: HLAK = MasterReceiveKey || MasterSendKey, client view. */
    fun clientHlak(passwordHash: ByteArray, ntResponse: ByteArray): ByteArray {
        val master = masterKey(md4(passwordHash), ntResponse)
        return clientStartKey(master, send = false) + clientStartKey(master, send = true)
    }

    /**
     * The error code of a Failure message (`E=691 R=0 C=... V=3 M=...`), or
     * null. 691 is "wrong user name or password", 649 "no dial-in permission",
     * 648 "password expired".
     */
    fun failureCode(message: String): Int? =
        Regex("E=(\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()

    /** The free-text `M=` part of a Success / Failure message, or "". */
    fun messageText(message: String): String {
        val at = message.indexOf("M=")
        return if (at < 0) "" else message.substring(at + 2).trim()
    }

    // ------------------------------------------------------------ primitives

    /**
     * Expands 7 key bytes at [offset] into an 8-byte DES key. The low bit of
     * every byte is a parity bit DES ignores, so it is simply left clear.
     */
    private fun desKey(source: ByteArray, offset: Int): ByteArray {
        fun b(i: Int): Int = source[offset + i].toInt() and 0xFF
        val key = ByteArray(8)
        key[0] = (b(0) and 0xFE).toByte()
        key[1] = (((b(0) shl 7) or (b(1) ushr 1)) and 0xFE).toByte()
        key[2] = (((b(1) shl 6) or (b(2) ushr 2)) and 0xFE).toByte()
        key[3] = (((b(2) shl 5) or (b(3) ushr 3)) and 0xFE).toByte()
        key[4] = (((b(3) shl 4) or (b(4) ushr 4)) and 0xFE).toByte()
        key[5] = (((b(4) shl 3) or (b(5) ushr 5)) and 0xFE).toByte()
        key[6] = (((b(5) shl 2) or (b(6) ushr 6)) and 0xFE).toByte()
        key[7] = ((b(6) shl 1) and 0xFE).toByte()
        return key
    }

    private fun des(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(block)
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

    /** RFC 1320 MD4. */
    fun md4(message: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xEFCDAB89.toInt()
        var c0 = 0x98BADCFE.toInt()
        var d0 = 0x10325476

        val bitLength = message.size.toLong() * 8L
        val paddedLength = ((message.size + 8) / 64 + 1) * 64
        val buffer = ByteArray(paddedLength)
        message.copyInto(buffer)
        buffer[message.size] = 0x80.toByte()
        for (i in 0 until 8) buffer[paddedLength - 8 + i] = (bitLength ushr (8 * i)).toByte()

        val x = IntArray(16)
        var offset = 0
        while (offset < paddedLength) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                x[i] = (buffer[p].toInt() and 0xFF) or
                    ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                    ((buffer[p + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 16) {
                val f = (b and c) or (b.inv() and d)
                val t = Integer.rotateLeft(a + f + x[i], ROUND1_SHIFTS[i % 4])
                a = d; d = c; c = b; b = t
            }
            for (i in 0 until 16) {
                val g = (b and c) or (b and d) or (c and d)
                val t = Integer.rotateLeft(a + g + x[ROUND2_ORDER[i]] + 0x5A827999, ROUND2_SHIFTS[i % 4])
                a = d; d = c; c = b; b = t
            }
            for (i in 0 until 16) {
                val h = b xor c xor d
                val t = Integer.rotateLeft(a + h + x[ROUND3_ORDER[i]] + 0x6ED9EBA1, ROUND3_SHIFTS[i % 4])
                a = d; d = c; c = b; b = t
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        val out = ByteArray(16)
        intArrayOf(a0, b0, c0, d0).forEachIndexed { i, word ->
            for (j in 0 until 4) out[i * 4 + j] = (word ushr (8 * j)).toByte()
        }
        return out
    }

    private val ROUND1_SHIFTS = intArrayOf(3, 7, 11, 19)
    private val ROUND2_SHIFTS = intArrayOf(3, 5, 9, 13)
    private val ROUND3_SHIFTS = intArrayOf(3, 9, 11, 15)
    private val ROUND2_ORDER = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val ROUND3_ORDER = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
}
