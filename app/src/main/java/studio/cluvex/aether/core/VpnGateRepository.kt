package studio.cluvex.aether.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.model.ProbeState
import studio.cluvex.aether.model.SstpProbe
import studio.cluvex.aether.model.VpnGateServer

/**
 * Fetches the public VPN Gate relay list and tests relays for SSTP.
 *
 * ### API format
 *
 * `GET https://www.vpngate.net/api/iphone/` returns:
 *
 * ```
 * *vpn_servers
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
 *  Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 * public-vpn-227,219.100.37.1,...,<base64>
 * *
 * ```
 *
 * There is NO SSTP column, and HostName carries no domain. Every relay runs
 * SoftEther, whose SSTP endpoint is `<HostName>.opengw.net` on any of its TCP
 * listener ports. The OpenVPN config names one of those ports when it is a
 * TCP config, so that port is the best SSTP guess; 443 otherwise. Whether a
 * relay really speaks SSTP is only known after [probe].
 *
 * ### Threading
 *
 * Every public function suspends on [Dispatchers.IO].
 */
object VpnGateRepository {

    private const val TAG = "VpnGate"
    private const val API_URL = "https://www.vpngate.net/api/iphone/"
    private const val SSTP_DOMAIN = "opengw.net"
    private const val DEFAULT_SSTP_PORT = 443

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val PROBE_TIMEOUT_MS = 5_000

    /** HostName .. OpenVPN_ConfigData_Base64. */
    private const val MIN_COLUMNS = 15

    private val HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Downloads and parses the relay list. A failure (network, HTTP status, or
     * a body with no usable rows, e.g. a captive portal page) is returned as
     * [Result.failure] so the UI can offer a retry.
     */
    suspend fun fetchServers(): Result<List<VpnGateServer>> = withContext(Dispatchers.IO) {
        try {
            Result.success(download())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLog.w(TAG, "VPN Gate list failed: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    private fun download(): List<VpnGateServer> {
        val connection = URI(API_URL).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Aether+/1.0")
        connection.setRequestProperty("Accept", "text/plain, text/csv, */*")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("VPN Gate answered HTTP $code")
            val servers = connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { parseCsv(it) }
            if (servers.isEmpty()) throw IOException("VPN Gate returned no usable relays")
            DiagnosticsLog.i(TAG, "VPN Gate list: ${servers.size} relays")
            return servers
        } finally {
            connection.disconnect()
        }
    }

    /** Parses the whole body: skips `*` / `#` lines and drops duplicates. */
    internal fun parseCsv(lines: Sequence<String>): List<VpnGateServer> {
        val seen = HashSet<String>()
        val servers = ArrayList<VpnGateServer>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue
            val server = parseCsvRow(line) ?: continue
            if (seen.add(server.id)) servers += server
        }
        return servers
    }

    /**
     * Parses one CSV row, or null when it is malformed.
     *
     * Operator (12) and Message (13) are free text and the API does not quote
     * them, so a comma in a message used to shift every later column. The
     * base64 config never contains a comma, so it is always the LAST column,
     * and whatever sits between the fixed head and that tail is operator +
     * message.
     */
    internal fun parseCsvRow(row: String): VpnGateServer? {
        val cols = splitCsv(row)
        if (cols.size < MIN_COLUMNS) return null
        val hostname = cols[0].trim()
        val ip = cols[1].trim()
        if (!HOST.matches(hostname) || !IPV4.matches(ip)) return null

        val openVpn = parseOpenVpn(cols.last().trim())
        val middle = cols.subList(12, cols.size - 1)
        val tcpPort = if (openVpn.tcp) openVpn.port else null
        val udpPort = if (openVpn.udp) openVpn.port else null

        return VpnGateServer(
            hostname = hostname,
            ip = ip,
            score = cols[2].trim().toLongOrNull() ?: 0L,
            apiPingMs = cols[3].trim().toIntOrNull() ?: -1,
            speedBps = cols[4].trim().toLongOrNull() ?: 0L,
            countryName = cols[5].trim(),
            countryCode = cols[6].trim().uppercase(Locale.ROOT),
            numSessions = cols[7].trim().toIntOrNull() ?: 0,
            uptimeMs = cols[8].trim().toLongOrNull() ?: 0L,
            totalUsers = cols[9].trim().toLongOrNull() ?: 0L,
            totalTrafficBytes = cols[10].trim().toLongOrNull() ?: 0L,
            logPolicy = cols[11].trim(),
            operatorName = middle.firstOrNull()?.trim().orEmpty(),
            operatorMessage = middle.drop(1).joinToString(",").trim(),
            sstpHostname = if (hostname.contains('.')) hostname else "$hostname.$SSTP_DOMAIN",
            sstpPort = tcpPort ?: DEFAULT_SSTP_PORT,
            openVpnTcpPort = tcpPort,
            openVpnUdpPort = udpPort,
        )
    }

    /** Comma split that also honours double quotes, in case the API ever adds them. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(16)
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"') {
                current.append('"')
                i++
            } else if (c == '"') {
                quoted = !quoted
            } else if (c == ',' && !quoted) {
                out += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private class OpenVpnInfo(val tcp: Boolean, val udp: Boolean, val port: Int?)

    private fun parseOpenVpn(base64: String): OpenVpnInfo {
        if (base64.isEmpty()) return OpenVpnInfo(tcp = false, udp = false, port = null)
        val text = try {
            String(Base64.getMimeDecoder().decode(base64), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return OpenVpnInfo(tcp = false, udp = false, port = null)
        }
        var proto: String? = null
        var port: Int? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val parts = trimmed.split(WHITESPACE)
            val keyword = parts[0].lowercase(Locale.ROOT)
            if (keyword == "proto" && proto == null) {
                proto = parts.getOrNull(1)?.lowercase(Locale.ROOT)
            } else if (keyword == "remote" && port == null) {
                port = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..65535 }
            }
        }
        val protocol = proto.orEmpty()
        return OpenVpnInfo(tcp = protocol.startsWith("tcp"), udp = protocol.startsWith("udp"), port = port)
    }

    /**
     * Tests [server] for real: TCP connect (timed, that is the latency), then
     * TLS and the SSTP_DUPLEX_POST request. A `200` means the relay speaks
     * SSTP. No credentials are sent, so the unverified TLS here leaks nothing.
     */
    suspend fun probe(server: VpnGateServer): SstpProbe = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            val started = System.nanoTime()
            try {
                socket.connect(InetSocketAddress(server.ip, server.sstpPort), PROBE_TIMEOUT_MS)
            } catch (_: Exception) {
                return@withContext SstpProbe(ProbeState.UNREACHABLE)
            }
            val latency = ((System.nanoTime() - started) / 1_000_000L).toInt()
            val speaksSstp = try {
                socket.soTimeout = PROBE_TIMEOUT_MS
                val tls = SstpCore.insecureSocketFactory()
                    .createSocket(socket, server.sstpHostname, server.sstpPort, true) as SSLSocket
                tls.startHandshake()
                tls.outputStream.write(SstpCore.duplexPostRequest(server.sstpHostname))
                tls.outputStream.flush()
                SstpCore.isHttpOk(SstpCore.readHttpHeader(tls.inputStream))
            } catch (_: Exception) {
                false
            }
            SstpProbe(if (speaksSstp) ProbeState.SSTP_OK else ProbeState.REACHABLE_NO_SSTP, latency)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
