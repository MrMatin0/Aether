package studio.cluvex.aether.model

/**
 * SSTP connection configuration.
 *
 * SSTP (Secure Socket Tunneling Protocol, MS-SSTP) carries PPP frames inside
 * one HTTPS connection, usually on port 443, so on the wire it looks like an
 * ordinary long-lived TLS session. The VPN Gate public relays accept the
 * shared [VPNGATE_USERNAME] / [VPNGATE_PASSWORD] credentials; private servers
 * bring their own.
 *
 * ### How SSTP fits the Aether+ architecture
 *
 * SSTP is a LAYER 3 tunnel: what comes out of it are IP packets, not TCP
 * streams. [studio.cluvex.aether.core.SstpCore] therefore exposes a packet
 * plane and a TUN bridge instead of a SOCKS5 listener, and the VpnService
 * builds the TUN from [studio.cluvex.aether.core.SstpCore.TunnelInfo].
 *
 * Unlike the other cores, SSTP needs no binary compiled from a separate
 * project: the protocol is pure Kotlin/JVM (TLS socket + PPP framing).
 */
data class SstpConfig(
    /** Server hostname (or IP) to dial. Also the default TLS SNI. */
    val hostname: String = "",
    /** Server TCP port. 443 for almost every server. */
    val port: Int = DEFAULT_PORT,
    /** PPP username. VPN Gate relays accept [VPNGATE_USERNAME]. */
    val username: String = VPNGATE_USERNAME,
    /** PPP password. VPN Gate relays accept [VPNGATE_PASSWORD]. */
    val password: String = VPNGATE_PASSWORD,
    /**
     * Verify the certificate chain AND that it matches [effectiveSni]. On by
     * default for hand-entered servers; picking a VPN Gate relay turns it off,
     * because those relays present self-signed certificates.
     */
    val verifyCert: Boolean = true,
    /** Custom SNI for the TLS ClientHello (blank = use [hostname]). */
    val customSni: String = "",
    /** PPP MRU we advertise. 0 = [DEFAULT_MTU]. */
    val mru: Int = 0,
    /** Tunnel MTU. 0 = [DEFAULT_MTU]. Always capped by the server's MRU. */
    val mtu: Int = 0,
    /** Where this configuration came from. */
    val source: SstpSource = SstpSource.MANUAL,
    /** ISO 3166-1 alpha-2 code of a VPN Gate relay. Display only. */
    val countryCode: String = "",
    /** Country name of a VPN Gate relay as the API reports it. Display only. */
    val countryName: String = "",
) {
    /** True when enough fields are set to attempt a connection. */
    val isUsable: Boolean
        get() = hostname.isNotBlank() && port in 1..65535

    /** The SNI to use for the TLS handshake. */
    val effectiveSni: String
        get() = customSni.trim().ifBlank { hostname.trim() }

    val effectiveMru: Int
        get() = if (mru in MIN_MTU..MAX_MTU) mru else DEFAULT_MTU

    val effectiveMtu: Int
        get() = if (mtu in MIN_MTU..MAX_MTU) mtu else DEFAULT_MTU

    /** Never print the password: configs end up in logs and crash reports. */
    override fun toString(): String =
        "SstpConfig(hostname=$hostname, port=$port, username=$username, password=***, " +
            "verifyCert=$verifyCert, sni=$effectiveSni, mru=$effectiveMru, mtu=$effectiveMtu, source=$source)"

    companion object {
        const val DEFAULT_PORT = 443
        const val VPNGATE_USERNAME = "vpn"
        const val VPNGATE_PASSWORD = "vpn"
        const val MIN_MTU = 576
        const val MAX_MTU = 1500

        /** Leaves room for TCP + TLS + SSTP + PPP overhead inside a 1500 byte path. */
        const val DEFAULT_MTU = 1400
    }
}

/** Where an SSTP configuration came from. */
enum class SstpSource {
    /** User typed the hostname and credentials manually. */
    MANUAL,

    /** Selected from the VPN Gate relay list. */
    VPNGATE,
}
