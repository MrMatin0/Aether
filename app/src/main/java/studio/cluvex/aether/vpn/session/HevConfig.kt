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
 * TUN fd but have nowhere to be routed — so the tunnel "connects" but no site
 * ever loads. They MUST equal the VpnService addAddress values, which is why
 * both read [TunnelConfig].
 */
internal object HevConfig {

    fun write(filesDir: File, mtu: Int): File {
        val file = File(filesDir, "hev.yaml")
        val yaml = """
            tunnel:
              mtu: $mtu
              ipv4: ${TunnelConfig.TUN_IPV4}
              ipv6: '${TunnelConfig.TUN_IPV6}'
            socks5:
              address: ${VpnTunables.SOCKS_HOST}
              port: ${VpnTunables.SOCKS_PORT}
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
