package studio.cluvex.aether.vpn.session

import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.TunnelConfig
import java.io.File

private const val TAG = "vpn"

/**
 * The hev-socks5-tunnel config file, in the exact shape v2rayNG uses.
 *
 * The critical fields are `tunnel.ipv4` / `tunnel.ipv6`: hev configures its
 * internal lwIP netif from them, and without them packets are pulled off the
 * TUN fd but have nowhere to be routed - so the tunnel "connects" but no site
 * ever loads. They MUST equal the VpnService addAddress values, which is why
 * both read [TunnelConfig].
 *
 * The SOCKS5 port is a PARAMETER and not a constant: it is the chain entry, so
 * it is the engine's own listener for an Aether-only session and the DNS-capable
 * front ([studio.cluvex.aether.core.SocksFront]) for a Psiphon or Tor entry.
 * Hard-coding it here would silently forward every packet to whichever core
 * happened to own 1819.
 */
internal object HevConfig {

    fun write(filesDir: File, mtu: Int, socksPort: Int): File {
        val file = File(filesDir, "hev.yaml")
        val yaml = """
            tunnel:
              mtu: $mtu
              ipv4: ${TunnelConfig.TUN_IPV4}
              ipv6: '${TunnelConfig.TUN_IPV6}'
            socks5:
              address: ${TunnelConfig.SOCKS_HOST}
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 86016
              connect-timeout: 5000
              # 1.2.4 stability: the old 60s idle timeout killed long-lived
              # sessions ("works 1-2 minutes, then no site opens").
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 120000
              log-level: warn
        """.trimIndent()
        file.writeText(yaml)
        DiagnosticsLog.i(TAG, "hev.yaml written:\n$yaml")
        return file
    }
}
