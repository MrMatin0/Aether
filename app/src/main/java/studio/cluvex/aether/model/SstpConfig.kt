package studio.cluvex.aether.model

/**
 * SSTP connection configuration.
 *
 * SSTP (Secure Socket Tunneling Protocol, MS-SSTP) tunnels PPP frames over
 * HTTPS on port 443. The VPNGate public relays accept anonymous connections
 * (no username/password required by default), but private servers may need
 * credentials.
 *
 * ### How SSTP fits the Aether+ architecture
 *
 * Like Psiphon and Tor, SSTP runs as a supervised process that exposes a
 * local SOCKS5 proxy. The chain model ([ChainMode]) wires it the same way:
 * SSTP can run alone, or as an overlay (SSTP over Aether, for networks that
 * block direct SSTP).
 *
 * Unlike the other cores, SSTP does not need a binary compiled from a
 * separate project: the protocol is pure Kotlin/JVM (TLS socket + PPP
 * framing), implemented in [studio.cluvex.aether.core.SstpCore].
 */
data class SstpConfig(
    /** Server hostname for TLS SNI and certificate verification. */
    val hostname: String = "",
    /** Server port, almost always 443. */
    val port: Int = 443,
    /** Username for PPP authentication (blank = anonymous/VPNGate). */
    val username: String = "vpn",
    /** Password for PPP authentication (blank = anonymous/VPNGate). */
    val password: String = "vpn",
    /** TLS certificate verification: true = verify, false = accept any cert. */
    val verifyCert: Boolean = false,
    /** Custom SNI for the TLS ClientHello (blank = use hostname). */
    val customSni: String = "",
    /** PPP MRU (Maximum Receive Unit). 0 = default (1500). */
    val mru: Int = 0,
    /** PPP MTU (Maximum Transmission Unit). 0 = default (1500). */
    val mtu: Int = 0,
    /** Enable PPP compression (CCP). Generally not useful over TLS. */
    val compression: Boolean = false,
    /** Source: where this config came from. */
    val source: SstpSource = SstpSource.MANUAL,
) {
    /** True when enough fields are set to attempt a connection. */
    val isUsable: Boolean
        get() = hostname.isNotBlank()

    /** The SNI to use for the TLS handshake. */
    val effectiveSni: String
        get() = customSni.ifBlank { hostname }
}

/** Where an SSTP configuration came from. */
enum class SstpSource {
    /** User typed the hostname and credentials manually. */
    MANUAL,
    /** Selected from the VPNGate server list. */
    VPNGATE,
}
