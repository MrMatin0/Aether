package studio.cluvex.aether.core.tunnel.net

/**
 * Big-endian read/write helpers for raw packet buffers.
 *
 * Extracted from `SocksTunBridge` verbatim: these were 8 private methods on a
 * 1900-line class, which meant every other packet helper had to live in that
 * same class just to reach them.
 */
internal object Bytes {

    fun int(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    fun setInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    fun short(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun setShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    /** Reads a 32-bit field as an UNSIGNED value (TCP sequence / ack numbers). */
    fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    fun setU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    fun ofInt(v: Int): ByteArray = byteArrayOf(
        (v shr 24).toByte(),
        (v shr 16).toByte(),
        (v shr 8).toByte(),
        (v and 0xFF).toByte()
    )

    fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
}