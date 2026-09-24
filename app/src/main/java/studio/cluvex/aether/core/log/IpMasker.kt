package studio.cluvex.aether.core.log

/**
 * Optional second pass for a SHARED log: masks the last two octets of every
 * public IPv4 address.
 *
 * WHY IT IS OPT-IN AND NOT PART OF [LogSanitizer]: addresses are most of the
 * diagnostic value of a log, and the sanitizer is contractually forbidden from
 * touching them. But a log exported while disconnected carries the user's own
 * ISP address, and for the people this app is built for that is not a harmless
 * detail to paste into a public issue. So the reader decides, per export.
 *
 * Loopback, private, link-local, CGNAT and multicast ranges are left alone:
 * they identify nobody and they are exactly what a tunnel bug is about
 * (127.0.0.1:1080, 10.x TUN addresses).
 */
object IpMasker {

    private val ipv4 = Regex(
        "(?<![0-9.])([0-9]{1,3})[.]([0-9]{1,3})[.]([0-9]{1,3})[.]([0-9]{1,3})(?![0-9]|[.][0-9])",
    )

    /** One line with public IPv4 addresses reduced to `a.b.x.x`. Never throws. */
    fun mask(line: String): String {
        if (line.isEmpty()) return line
        return ipv4.replace(line) { match ->
            val octets = match.groupValues.drop(1).map { it.toIntOrNull() ?: 256 }
            if (octets.any { it > 255 } || isNonPublic(octets)) {
                match.value
            } else {
                "${octets[0]}.${octets[1]}.x.x"
            }
        }
    }

    private fun isNonPublic(o: List<Int>): Boolean = when {
        o[0] == 0 || o[0] == 10 || o[0] == 127 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        o[0] >= 224 -> true
        else -> false
    }
}
