package studio.cluvex.aether.core.tunnel.session

import android.net.Network
import studio.cluvex.aether.core.LogRepository
import studio.cluvex.aether.model.RoutingMode

/**
 * Decides how one flow should be carried, and logs that decision.
 *
 * TCP and UDP both ran the same seven-step dance inline — resolve the rule, look
 * for an underlying network, check the v6 route, log DIRECT_REJECTED vs the
 * mode, then bail on BLOCK — written out twice with slightly different variable
 * names (`useDirect` in one, `isDirect` in the other). One copy now.
 */
internal class FlowRouter(private val env: SessionEnv) {

    /** What to do with a flow. [directNetwork] is non-null only when [direct]. */
    class Plan(
        val mode: RoutingMode,
        val direct: Boolean,
        val directNetwork: Network?,
        val resolvedDomain: String?,
        /** True when DIRECT was asked for but no usable physical route exists. */
        val directUnavailable: Boolean
    ) {
        val blocked: Boolean get() = mode == RoutingMode.BLOCK
        /** DIRECT with nowhere to go must FAIL, not silently fall back to the tunnel. */
        val unroutable: Boolean get() = directUnavailable
    }

    fun plan(
        targetIp: String,
        port: Int,
        ipVersion: Int,
        knownDomain: String?,
        sniHost: String? = null,
        httpHost: String? = null,
        protocolLabel: String,
        /**
         * The TCP sniff path resolves twice (once before the SYN/ACK, once with the
         * sniffed host). Only the final verdict should reach the log, or every
         * sniffed flow would emit two contradictory routing lines.
         */
        log: Boolean = true
    ): Plan {
        val decision = env.routingEngine.resolve(targetIp, port, knownDomain, sniHost, httpHost)
        val wantsDirect = decision.mode == RoutingMode.DIRECT
        val network = if (wantsDirect) env.networks.best() else null
        val canGoDirect = wantsDirect &&
            network != null &&
            (ipVersion == 4 || env.networks.supportsIpv6(network))

        if (log && decision.matchedRule != null) {
            val domain = decision.resolvedDomain ?: "unknown"
            if (wantsDirect && !canGoDirect) {
                LogRepository.i(
                    "[Routing] DIRECT_REJECTED domain=$domain ip=$targetIp " +
                        "protocol=$protocolLabel reason=no_underlying_route"
                )
            } else {
                LogRepository.i(
                    "[Routing] ${decision.mode.name} domain=$domain ip=$targetIp protocol=$protocolLabel"
                )
            }
        }

        return Plan(
            mode = decision.mode,
            direct = canGoDirect,
            directNetwork = if (canGoDirect) network else null,
            resolvedDomain = decision.resolvedDomain,
            directUnavailable = wantsDirect && !canGoDirect
        )
    }
}