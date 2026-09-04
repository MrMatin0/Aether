package studio.cluvex.aether.core

/**
 * Extracts the target domain from the first payload of a flow: TLS SNI on
 * port 443, HTTP Host header on port 80. Merged into Aether; used by the
 * userspace tunnel bridge for flow attribution.
 *
 * EVERYTHING THIS RETURNS IS PEER-CONTROLLED. The value goes on to be written
 * into a SOCKS5 ATYP=0x03 request (one-byte length field) and cached in
 * [DnsMap], so it is validated by [Hostname] before it leaves this class rather
 * than trusted by every consumer downstream. See Hostname's KDoc for what an
 * unvalidated name does to that request.
 */
object TrafficSniffer {
    fun sniffDomain(data: ByteArray, port: Int): String? {
        if (data.isEmpty()) return null
        val raw = when (port) {
            443 -> sniffSni(data)
            80 -> sniffHttpHost(data)
            else -> null
        }
        // Single choke point: a name that cannot be encoded faithfully is worth
        // exactly nothing to a router and is actively dangerous on the wire.
        return Hostname.sanitize(raw)
    }

    private fun sniffSni(data: ByteArray): String? {
        try {
            // 5 (record) + 4 (handshake) + 2 (version) + 32 (random) = 43, so
            // the session-id length byte lives at index 43 and needs 44 bytes.
            if (data.size < 44) return null
            if (data[0] != 0x16.toByte()) return null

            var pos = 5
            val handshakeType = data[pos]
            if (handshakeType != 0x01.toByte()) return null

            pos += 4
            pos += 2
            pos += 32

            val sessionIDLen = data[pos].toInt() and 0xFF
            pos += 1 + sessionIDLen

            if (pos + 2 > data.size) return null
            val cipherSuiteLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuiteLen

            if (pos + 1 > data.size) return null
            val compressionMethodLen = data[pos].toInt() and 0xFF
            pos += 1 + compressionMethodLen

            if (pos + 2 > data.size) return null
            val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2

            // Never walk past the ClientHello's own extension block OR past the
            // bytes we actually hold: the first TUN segment can carry a
            // truncated handshake, and the declared length is attacker/peer
            // controlled either way.
            val extensionsEnd = minOf(pos + extensionsLen, data.size)
            while (pos + 4 <= extensionsEnd) {
                val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)

                // BOUNDARY FIX (root cause of misparsed ClientHellos): where
                // THIS extension ends is computed before its body is touched.
                // The previous loop advanced `pos` by 5 bytes inside the
                // server_name branch and then added extLen to that ALREADY
                // moved cursor. So the moment a server_name extension did not
                // return early -- name_type != host_name, a truncated segment,
                // an empty list -- every following extension was read from a
                // bogus offset, which is how a flow could end up attributed to
                // a domain that was never in the handshake at all.
                val extEnd = pos + 4 + extLen
                if (extEnd > extensionsEnd) return null

                if (extType == 0x00) {
                    // server_name_list length (2) + name_type (1) + length (2).
                    // Every bound below is checked against extEnd, not against
                    // data.size, so a lying extension length cannot make the
                    // parser read a neighbouring extension's bytes as a name.
                    var p = pos + 4
                    if (p + 5 <= extEnd) {
                        val listLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                        p += 2
                        if (p + listLen <= extEnd) {
                            val nameType = data[p]
                            val nameLen = ((data[p + 1].toInt() and 0xFF) shl 8) or (data[p + 2].toInt() and 0xFF)
                            p += 3
                            if (nameType == 0x00.toByte() && nameLen > 0 && p + nameLen <= extEnd) {
                                // Returned raw; sniffDomain() normalises and
                                // validates it in one place for both sniffers.
                                return String(data, p, nameLen, Charsets.ISO_8859_1)
                            }
                        }
                    }
                }

                pos = extEnd
            }
        } catch (_: Exception) {}
        return null
    }

    private fun sniffHttpHost(data: ByteArray): String? {
        try {
            val text = String(data, 0, minOf(data.size, 2048), Charsets.ISO_8859_1)
            for (line in text.split("\r\n")) {
                // The header block ends at the first empty line. Scanning past
                // it meant a request BODY line that happens to start with
                // "Host:" (a proxied POST, an uploaded config, a pasted log)
                // was sniffed as the destination domain.
                if (line.isEmpty()) break
                if (line.startsWith("Host:", ignoreCase = true)) {
                    // A Host header may carry a port, and a bracketed IPv6
                    // literal carries colons of its own — strip a trailing
                    // ":port" only, and leave a bare literal for Hostname to
                    // reject rather than mangling it into a wrong host.
                    val value = line.substring(5).trim()
                    return HostPort.parse(value, 80)?.host ?: value
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
