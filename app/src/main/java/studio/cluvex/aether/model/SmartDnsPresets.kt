package studio.cluvex.aether.model

/** How the preset list is grouped on screen. */
enum class SmartDnsPresetGroup { ENCRYPTED, CLASSIC }

/**
 * One built-in Smart DNS server.
 *
 * The address is not in the source as text: it is [sealed] (see
 * [SmartDnsPresets.unseal]) and only opened, once, when the server is first
 * needed. A profile stores the preset's [token] (`preset:geohide-eu`), never
 * the address, so exports, saved setups and the diagnostics log carry the
 * token too.
 *
 * [region] is `EU`, `US`, `GLOBAL` or blank; [title] is a brand name for the
 * encrypted servers and a two-digit node number for the classic ones.
 */
class SmartDnsPreset internal constructor(
    val id: String,
    val title: String,
    val region: String,
    val group: SmartDnsPresetGroup,
    val recommended: Boolean,
    private val sealed: String,
) {
    /** What a profile stores for this preset. */
    val token: String
        get() = SmartDnsPresets.TOKEN_PREFIX + id

    /** Encrypted presets are DoH resolvers; classic ones are plain DNS on port 53. */
    val protocol: SmartDnsProtocol
        get() = if (group == SmartDnsPresetGroup.ENCRYPTED) SmartDnsProtocol.DOH else SmartDnsProtocol.PLAIN

    /** The server, labelled by [token]; null only if the sealed value were corrupt (unit-tested). */
    val server: SmartDnsServer? by lazy {
        SmartDnsServers.parseOne(SmartDnsPresets.unseal(sealed), protocol)?.copy(alias = token)
    }
}

/**
 * The ready-made Smart DNS servers offered on the connection tab, and the
 * helpers that keep "which presets are picked" and "what the user typed" in the
 * ONE stored field ([ConnectionProfile.smartDnsServers]).
 *
 * ### Order
 *
 * GeoHide EU is first and is the [DEFAULT]: it is what switching Smart DNS on
 * with an empty list selects.
 *
 * ### Sealing
 *
 * Each address is XOR-ed with a rolling key and hex-encoded, so the list is not
 * greppable plain text in the source, the APK's string pool or a screenshot.
 * This is obfuscation, not secrecy: the app must be able to open it, so anyone
 * with the APK can too.
 */
object SmartDnsPresets {

    /** Prefix of a stored preset entry. */
    const val TOKEN_PREFIX = "preset:"

    private val KEY: ByteArray = "aether::smartdns::seal::v1".toByteArray(Charsets.US_ASCII)

    val ALL: List<SmartDnsPreset> = listOf(
        SmartDnsPreset("geohide-eu", "GeoHide", "EU", SmartDnsPresetGroup.ENCRYPTED, true,
            "0e37457c95ead4f5e90672496a91bfc2a949684367d1ef84ea123d5c7a90b5"),
        SmartDnsPreset("geohide-us", "GeoHide", "US", SmartDnsPresetGroup.ENCRYPTED, false,
            "0e37457c95ead4f5f90072496a91bfc2a949684367d1ef84ea123d5c7a90b5"),
        SmartDnsPreset("geohide", "GeoHide", "GLOBAL", SmartDnsPresetGroup.ENCRYPTED, false,
            "0e37457c95ead4f5eb163346669ab285bf5969557c8da69bec5a3e50"),
        SmartDnsPreset("xbox-dns", "Xbox DNS", "", SmartDnsPresetGroup.ENCRYPTED, false,
            "0e37457c95ead4f5f4113356229ab9d8e35e331e7690f8c7e84a295b66"),
        SmartDnsPreset("mafioznik", "Mafioznik", "", SmartDnsPresetGroup.ENCRYPTED, false,
            "0e37457c95ead4f5e81d2f00629fb1c2a256285879d0f393e31028476ccfbdcfb84e2f"),
        SmartDnsPreset("udp-01", "01", "", SmartDnsPresetGroup.CLASSIC, false, "57720022dee8d5e3ba5d691e"),
        SmartDnsPreset("udp-02", "02", "", SmartDnsPresetGroup.CLASSIC, false, "57720022dee8d5e3ba5d691f"),
        SmartDnsPreset("udp-03", "03", "", SmartDnsPresetGroup.CLASSIC, false, "57720022dee8d5e3ba5d691a"),
        SmartDnsPreset("udp-04", "04", "", SmartDnsPresetGroup.CLASSIC, false, "57720022dee8d5e3ba5d691b"),
        SmartDnsPreset("udp-05", "05", "", SmartDnsPresetGroup.CLASSIC, false, "57720022dee8d5e3ba5d6918"),
        SmartDnsPreset("udp-06", "06", "", SmartDnsPresetGroup.CLASSIC, false, "57720022dee8d5e3ba5d6919"),
        SmartDnsPreset("udp-07", "07", "", SmartDnsPresetGroup.CLASSIC, false, "5e741f3ed4e8d5eebb5d6e1e3f"),
        SmartDnsPreset("udp-08", "08", "", SmartDnsPresetGroup.CLASSIC, false, "5e741f3ed4e8d5eebb5d6e1e3e"),
        SmartDnsPreset("udp-09", "09", "", SmartDnsPresetGroup.CLASSIC, false, "577a0222d4e3c8f4bd426e0039c9"),
        SmartDnsPreset("udp-10", "10", "", SmartDnsPresetGroup.CLASSIC, false, "577a0222d4e3c8f4bd426e0039c6"),
        SmartDnsPreset("udp-11", "11", "", SmartDnsPresetGroup.CLASSIC, false, "577a0222d4e3c8f4bd426e0037c6"),
        SmartDnsPreset("udp-12", "12", "", SmartDnsPresetGroup.CLASSIC, false, "52761f3dd3e5d5e8bc47721f36ce"),
        SmartDnsPreset("udp-13", "13", "", SmartDnsPresetGroup.CLASSIC, false, "55741f3ed5e0d5ebb541721b3e"),
        SmartDnsPreset("udp-14", "14", "", SmartDnsPresetGroup.CLASSIC, false, "52751f34c8e1cee2a245"),
    )

    /** The priority preset: first in the list, and what an empty list is seeded with. */
    val DEFAULT: SmartDnsPreset
        get() = ALL.first()

    fun byId(id: String): SmartDnsPreset? {
        val key = id.trim().lowercase()
        return ALL.firstOrNull { it.id == key }
    }

    /** The preset a `preset:<id>` entry names, or null (not a token, or an unknown id). */
    fun fromToken(text: String): SmartDnsPreset? {
        val entry = text.trim()
        if (!entry.lowercase().startsWith(TOKEN_PREFIX)) return null
        return byId(entry.substring(TOKEN_PREFIX.length))
    }

    /** Opens a sealed value; an empty string for anything malformed. */
    internal fun unseal(hex: String): String {
        if (hex.isEmpty() || hex.length % 2 != 0) return ""
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val b = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return ""
            val mask = (KEY[i % KEY.size].toInt() and 0xFF) xor ((i * 31 + 7) and 0xFF)
            out[i] = (b xor mask).toByte()
        }
        return String(out, Charsets.US_ASCII)
    }

    // ------------------------------------------------- the stored selection

    /** The ids of the presets in [raw], in the order they are tried. */
    fun selectedIds(raw: String): List<String> =
        SmartDnsServers.entries(raw).mapNotNull { fromToken(it)?.id }.distinct()

    /**
     * Everything in [raw] that is NOT a preset, as the user's own text.
     *
     * A profile saved while the protocol was a setting ([hint] other than
     * plain) has its bare entries written out with their scheme here, so they
     * keep their meaning once the list is edited and stored with no hint.
     */
    fun customPart(raw: String, hint: SmartDnsProtocol): String {
        val own = SmartDnsServers.entries(raw).filterNot { it.lowercase().startsWith(TOKEN_PREFIX) }
        if (hint == SmartDnsProtocol.PLAIN) return own.joinToString(", ")
        return own.joinToString(", ") { entry ->
            SmartDnsServers.parseAuto(entry, hint)?.let { it.alias.ifEmpty { it.endpoint } } ?: entry
        }
    }

    /** The stored text for [ids] (tried first, in that order) followed by the user's [custom] text. */
    fun compose(ids: List<String>, custom: String): String =
        (ids.distinct().map { TOKEN_PREFIX + it } + custom.trim())
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    /** [ids] with [id] added at the end, or removed when it was already there. */
    fun toggle(ids: List<String>, id: String): List<String> =
        if (id in ids) ids.filterNot { it == id } else ids + id
}
