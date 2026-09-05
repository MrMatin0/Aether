package studio.cluvex.aether.core.tunnel.net

/**
 * Identity of a 5-tuple flow, used as the session-map key.
 *
 * IPv4 keeps its addresses as packed ints so the hot path never allocates a
 * byte array just to look a session up.
 */
internal sealed interface FlowKey {
    val proto: Int

    companion object {
        fun of(
            proto: Int,
            l3: IpPacketReader.L3Header,
            srcPort: Int,
            dstPort: Int
        ): FlowKey = if (l3.version == 4) {
            FlowKey4(proto, l3.srcIp4, srcPort, l3.dstIp4, dstPort)
        } else {
            FlowKey6(proto, l3.srcIp, srcPort, l3.dstIp, dstPort)
        }
    }
}

internal data class FlowKey4(
    override val proto: Int,
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int
) : FlowKey

/**
 * `data class` cannot be used here: its generated `equals`/`hashCode` compare
 * ByteArrays by REFERENCE, so every packet of the same flow would hash to a
 * different bucket and each one would open a brand new session.
 */
internal class FlowKey6(
    override val proto: Int,
    val srcIp: ByteArray,
    val srcPort: Int,
    val dstIp: ByteArray,
    val dstPort: Int
) : FlowKey {

    private val hash: Int = run {
        var result = proto
        result = 31 * result + srcIp.contentHashCode()
        result = 31 * result + srcPort
        result = 31 * result + dstIp.contentHashCode()
        result = 31 * result + dstPort
        result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlowKey6) return false
        return proto == other.proto &&
            srcPort == other.srcPort &&
            dstPort == other.dstPort &&
            srcIp.contentEquals(other.srcIp) &&
            dstIp.contentEquals(other.dstIp)
    }

    /** Cached: this is recomputed on every single map lookup otherwise. */
    override fun hashCode(): Int = hash
}