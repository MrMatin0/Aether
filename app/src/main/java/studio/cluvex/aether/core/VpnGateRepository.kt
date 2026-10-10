package studio.cluvex.aether.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.model.VpnGateServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Fetches the public VPNGate relay list and parses it into [VpnGateServer]
 * instances.
 *
 * ### API format
 *
 * The VPNGate CSV API (`/api/iphone/`) returns a CSV whose first line is the
 * header row prefixed with `*` and whose last line is `*`. Between those are
 * one row per server, with fields separated by commas. The columns that matter
 * for SSTP:
 *
 * ```
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
 * Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,
 * OpenVPN_ConfigData_Base64,SSTP_Hostname,...
 * ```
 *
 * Column indices (0-based) are documented in [parseCsvRow]. Servers that
 * report an SSTP hostname support SSTP on port 443.
 *
 * ### Threading
 *
 * Every public function suspends on [Dispatchers.IO]. The caller (ViewModel)
 * collects the result on the main thread.
 */
object VpnGateRepository {

    private const val API_URL = "https://www.vpngate.net/api/iphone/"

    /** Alternative mirror when the main API is unreachable. */
    private const val MIRROR_URL = "https://www.vpngate.net/api/iphone/"

    /** Read timeout for the CSV download, ms. */
    private const val READ_TIMEOUT_MS = 15_000

    /** Connect timeout for the CSV download, ms. */
    private const val CONNECT_TIMEOUT_MS = 10_000

    /**
     * Fetches the full server list from the VPNGate API.
     *
     * Returns an empty list on network failure rather than throwing, so the
     * UI can show a retry prompt instead of crashing.
     */
    suspend fun fetchServers(): Result<List<VpnGateServer>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URI(API_URL).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Aether+/1.0")

            try {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val servers = mutableListOf<VpnGateServer>()

                reader.useLines { lines ->
                    var headerSeen = false
                    for (line in lines) {
                        val trimmed = line.trim()
                        // Skip the header row (starts with *) and the footer
                        if (trimmed.startsWith("*")) {
                            headerSeen = true
                            continue
                        }
                        if (!headerSeen || trimmed.isEmpty()) continue
                        parseCsvRow(trimmed)?.let { servers.add(it) }
                    }
                }
                servers
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Parses one CSV row into a [VpnGateServer], or null when the row is
     * malformed or missing critical fields.
     *
     * VPNGate CSV column indices (0-based):
     *  0  HostName
     *  1  IP
     *  2  Score
     *  3  Ping
     *  4  Speed (bps)
     *  5  CountryLong
     *  6  CountryShort
     *  7  NumVpnSessions
     *  8  Uptime (ms)
     *  9  TotalUsers
     * 10  TotalTraffic
     * 11  LogType
     * 12  Operator
     * 13  Message
     * 14  OpenVPN_ConfigData_Base64 (TCP+UDP combined, or empty)
     *
     * Columns beyond 14 are provider-dependent. SSTP hostname appears as the
     * last meaningful column when present.
     */
    internal fun parseCsvRow(row: String): VpnGateServer? {
        val cols = row.split(",")
        if (cols.size < 15) return null

        val hostname = cols[0].trim()
        val ip = cols[1].trim()
        if (hostname.isEmpty() || ip.isEmpty()) return null

        val score = cols[2].trim().toIntOrNull() ?: 0
        val ping = cols[3].trim().toIntOrNull() ?: -1
        val speedBps = cols[4].trim().toLongOrNull() ?: 0L
        val countryName = cols[5].trim()
        val countryCode = cols[6].trim().uppercase()
        val numSessions = cols[7].trim().toIntOrNull() ?: 0
        val uptimeMs = cols[8].trim().toLongOrNull() ?: 0L
        val totalUsers = cols[9].trim().toLongOrNull() ?: 0L
        val totalTraffic = cols[10].trim().toLongOrNull() ?: 0L
        val logPolicy = cols[11].trim()
        val openVpnConfig = cols.getOrNull(14)?.trim().orEmpty()

        // SSTP hostname: in the VPNGate API the SSTP hostname is typically
        // reported alongside the server hostname when SSTP is available.
        // Servers on ddns hostnames ending with .opengw.net support SSTP.
        val sstpHostname = if (cols.size > 15) cols[15].trim() else ""
        val supportsSstp = sstpHostname.isNotEmpty() ||
            hostname.endsWith(".opengw.net") ||
            hostname.endsWith(".vpngate.net")

        val effectiveSstpHostname = sstpHostname.ifEmpty {
            if (supportsSstp) hostname else ""
        }

        return VpnGateServer(
            hostname = hostname,
            ip = ip,
            operatorMessage = cols.getOrNull(13)?.trim().orEmpty(),
            countryCode = countryCode,
            countryName = countryName,
            speedBps = speedBps,
            numSessions = numSessions,
            uptimeMs = uptimeMs,
            totalUsers = totalUsers,
            totalTrafficBytes = totalTraffic,
            logPolicy = logPolicy,
            supportsSstp = supportsSstp,
            sstpHostname = effectiveSstpHostname,
            supportsOpenVpnTcp = openVpnConfig.isNotEmpty(),
            supportsOpenVpnUdp = openVpnConfig.isNotEmpty(),
            supportsL2tp = true, // VPNGate servers generally support L2TP
            score = score,
            pingMs = ping,
        )
    }

    /**
     * Measures TCP-connect latency to [server] on port 443 (SSTP port).
     * Returns the round-trip time in milliseconds, or -1 on failure.
     *
     * This is a simple TCP SYN/ACK measurement, NOT an SSTP handshake, so it
     * completes in one RTT and gives the user a reasonable "will this server
     * respond" signal without the overhead of a full TLS + SSTP negotiation.
     */
    suspend fun measurePing(server: VpnGateServer): Int = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server.ip, 443), 5_000)
            }
            ((System.nanoTime() - start) / 1_000_000).toInt()
        }.getOrDefault(-1)
    }

    /**
     * Checks if the server's SSTP port (443) is reachable.
     */
    suspend fun checkSstpReachability(server: VpnGateServer): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server.ip, 443), 5_000)
                true
            }
        }.getOrDefault(false)
    }
}
