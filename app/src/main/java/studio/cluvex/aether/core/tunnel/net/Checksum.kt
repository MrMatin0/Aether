package studio.cluvex.aether.core.tunnel.net

/**
 * One's-complement Internet checksums (RFC 1071).
 *
 * Every function works over a SLICE of a caller-owned buffer, so nothing here
 * allocates. The original code built a throwaway `copyOfRange` of the whole
 * transport segment before checksumming it, which doubled the memory traffic of
 * every packet the bridge produced.
 */
internal object Checksum {

    /** IPv4 header checksum. Assumes a 20-byte header with the field zeroed. */
    fun ipv4Header(packet: ByteArray): Int = fold(sum(packet, 0, 20, 0L))

    fun transport4(
        srcIp: Int,
        dstIp: Int,
        packet: ByteArray,
        offset: Int,
        length: Int,
        protocol: Int
    ): Int {
        var acc = 0L
        acc += ((srcIp ushr 16) and 0xFFFF).toLong()
        acc += (srcIp and 0xFFFF).toLong()
        acc += ((dstIp ushr 16) and 0xFFFF).toLong()
        acc += (dstIp and 0xFFFF).toLong()
        acc += protocol.toLong()
        acc += length.toLong()
        return fold(sum(packet, offset, offset + length, acc))
    }

    fun transport6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        offset: Int,
        length: Int,
        protocol: Int
    ): Int {
        var acc = 0L
        acc = sum(srcIp, 0, 16, acc)
        acc = sum(dstIp, 0, 16, acc)
        acc += length.toLong()
        acc += protocol.toLong()
        return fold(sum(packet, offset, offset + length, acc))
    }

    /** A zero checksum means "not computed" on the wire, so it is sent as ~0. */
    fun nonZero(value: Int): Int = if (value == 0) 0xFFFF else value

    private fun sum(b: ByteArray, from: Int, to: Int, initial: Long): Long {
        var acc = initial
        var i = from
        val last = to - 1
        while (i < last) {
            acc += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < to) acc += (b[i].toInt() and 0xFF) shl 8
        return acc
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while ((s shr 16) > 0) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }
}