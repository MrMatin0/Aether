package studio.cluvex.aether.core.tunnel.session

import android.net.VpnService
import studio.cluvex.aether.core.RoutingEngine
import studio.cluvex.aether.core.tunnel.dns.DnsInspector
import studio.cluvex.aether.core.tunnel.net.PacketFactory
import studio.cluvex.aether.core.tunnel.net.TunPipe
import studio.cluvex.aether.core.tunnel.net.UdpEmitter
import studio.cluvex.aether.core.tunnel.net.UnderlyingNetworks

/**
 * Everything a session needs from the bridge, as an explicit contract.
 *
 * The sessions used to be `inner class`es, so each one silently captured the
 * ENTIRE 1900-line bridge: its executor, its queues, its 20 packet helpers, its
 * mutable `isRunning` flag. Nothing could be reasoned about or tested in
 * isolation, and it was impossible to tell which state a session actually
 * touched. This is that dependency list, written down.
 */
internal class SessionEnv(
    val vpnService: VpnService,
    val socksHost: String,
    val socksPort: Int,
    val routingEngine: RoutingEngine,
    val packets: PacketFactory,
    val tun: TunPipe,
    val udpOut: UdpEmitter,
    val networks: UnderlyingNetworks,
    val dns: DnsInspector,
    /** False once the bridge is stopping; every session loop must respect it. */
    val isRunning: () -> Boolean,
    /** Runs a session leg on the bridge's unbounded pool. */
    val submit: (Runnable) -> Unit
)