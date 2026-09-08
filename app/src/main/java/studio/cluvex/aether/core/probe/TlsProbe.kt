package studio.cluvex.aether.core.probe

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS for the probes: wrap a live socket, and fingerprint SNI-based filtering.
 *
 * Both callers used to own a copy of this. NetProbe wrapped the geolocation
 * socket and SmartAuto handshook against 1.1.1.1 with a fake SNI, and each had
 * its own hostname-verification call - so a fix to one was a fix to one.
 */
internal object TlsProbe {

    /** Outcome of a fingerprint handshake, keeping WHY it failed for the log. */
    class Outcome(val verified: Boolean, val failure: String?)

    /**
     * Wraps an already-connected [socket] in TLS with SNI = [host] and verifies
     * the certificate against that name.
     *
     * SECURITY: [SSLSocket.startHandshake] validates the chain against the
     * system trust store but does NOT check that the certificate belongs to
     * [host]. Without the verifier below, anyone holding a valid certificate
     * for ANY domain could intercept the TLS geolocation probe.
     */
    fun wrap(socket: Socket, host: String, port: Int, timeoutMs: Int): SSLSocket {
        // getDefault() is statically typed as the plain SocketFactory, which
        // lacks the (socket, host, port, autoClose) overload - cast first.
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            runCatching { ssl.close() }
            throw SSLPeerUnverifiedException("Certificate does not match host $host (possible MitM)")
        }
        return ssl
    }

    /**
     * Completes a full TLS handshake to [ip] while presenting [sniHost], with
     * hostname verification and no payload sent.
     *
     * SNI-based DPI middleboxes kill exactly this step, so a failure here while
     * plain TCP to the same host succeeds is a useful filtering signal.
     */
    fun fingerprint(ip: String, sniHost: String, port: Int, timeoutMs: Int): Outcome =
        try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(ip, port), timeoutMs)
                raw.soTimeout = timeoutMs
                // autoClose = true, so closing either socket closes both; the
                // outer `use` closing an already-closed socket is a no-op.
                wrap(raw, sniHost, port, timeoutMs).use { Outcome(true, null) }
            }
        } catch (e: Exception) {
            Outcome(false, e.message ?: e.javaClass.simpleName)
        }
}
