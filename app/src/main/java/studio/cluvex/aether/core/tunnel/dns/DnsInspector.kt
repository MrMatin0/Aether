package studio.cluvex.aether.core.tunnel.dns

import studio.cluvex.aether.core.DnsMap
import studio.cluvex.aether.core.LogRepository
import studio.cluvex.aether.core.RoutingEngine
import studio.cluvex.aether.model.RoutingMode
import java.net.InetAddress

/**
 * The two DNS-aware behaviours of the bridge, in one place:
 *
 *  1. BLOCK enforcement — answer a query for a blocked domain with NXDOMAIN
 *     instead of letting it leave.
 *  2. IP -> domain learning — remember what each answer resolved to, so a later
 *     TCP/UDP flow to that IP can be routed by domain without sniffing.
 *
 * Both used to be private methods on the bridge, built on three separate
 * hand-rolled DNS label walkers. They share [DnsMessage] now.
 */
internal class DnsInspector(private val routingEngine: RoutingEngine) {

    /**
     * The synthesized NXDOMAIN answer for a blocked query, or null to let it
     * through.
     *
     * NOTE: deliberately does NOT short-circuit on `hasDomainRules()`. That looks
     * like a free fast path, but the verdict below is resolved against the
     * DESTINATION IP as well as the domain, so an IP rule covering the resolver
     * itself can return BLOCK with no domain rule in sight. Semantics are
     * identical to the original; only the parser underneath changed.
     */
    fun blockResponseFor(query: ByteArray, dstIp: ByteArray): ByteArray? {
        val domain = DnsMessage.firstQueryName(query) ?: return null
        val dstIpText = runCatching {
            InetAddress.getByAddress(dstIp).hostAddress
        }.getOrNull().orEmpty()

        val verdict = routingEngine.resolve(dstIpText, DNS_PORT, domain, null, null)
        if (verdict.mode != RoutingMode.BLOCK) return null

        LogRepository.i("[DnsGuard] [Block] domain=$domain", "DnsGuard")
        return DnsMessage.nxDomainResponse(query)
    }

    /** Records the A/AAAA answers of a response into the shared IP -> domain map. */
    fun learnFromResponse(data: ByteArray) {
        if (!DnsMessage.isResponse(data)) return
        if (DnsMessage.questionCount(data) == 0 || DnsMessage.answerCount(data) == 0) return

        val domain = DnsMessage.firstQueryName(data) ?: return

        DnsMessage.forEachAnswer(data) { record ->
            when {
                record.type == DnsMessage.TYPE_A && record.dataLength == 4 ->
                    DnsMap.put(ipv4Text(data, record.dataOffset), domain)

                record.type == DnsMessage.TYPE_AAAA && record.dataLength == 16 -> {
                    val text = runCatching {
                        InetAddress.getByAddress(
                            data.copyOfRange(record.dataOffset, record.dataOffset + 16)
                        ).hostAddress
                    }.getOrNull()
                    if (text != null) DnsMap.put(text, domain)
                }
            }
        }
    }

    private fun ipv4Text(data: ByteArray, at: Int): String = StringBuilder(15).apply {
        append(data[at].toInt() and 0xFF).append('.')
        append(data[at + 1].toInt() and 0xFF).append('.')
        append(data[at + 2].toInt() and 0xFF).append('.')
        append(data[at + 3].toInt() and 0xFF)
    }.toString()

    private companion object {
        const val DNS_PORT = 53
    }
}