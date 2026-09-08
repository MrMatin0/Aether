package studio.cluvex.aether.core

/**
 * The exit countries Psiphon can be asked for, and how they are shown.
 *
 * ### Why this is a fixed list and not a free-text field
 *
 * `EgressRegion` is an ISO 3166-1 alpha-2 code and psiphon-tunnel-core treats
 * anything else as "no such region", silently. The Chain page used to take two
 * free-typed letters, which meant a user could type `EN`, `IR`, `UK` or their
 * own initials, watch the value persist, and get no exit country and no
 * explanation - a setting that looks like it works. A list cannot be typo'd.
 *
 * The codes are Psiphon's published egress regions. Availability still depends
 * on the live network (see [studio.cluvex.aether.vpn.session.ChainStack], which
 * falls back to an automatic exit rather than hanging on an empty region), so
 * this is the set of countries that CAN be asked for, not a promise.
 *
 * ### Why the flags are computed and not shipped
 *
 * A flag emoji is just the two regional-indicator code points for the country
 * code, rendered by the system emoji font. Deriving them from the code means the
 * list can never drift out of sync with itself and costs zero APK size, which a
 * 56-image drawable set would not.
 */
object PsiphonRegions {

    /**
     * Psiphon's own way of spelling "no region filter", and the default.
     *
     * Kept as the empty string rather than a sentinel like "AUTO" because that
     * is what goes into the config file: [PsiphonCore] omits `EgressRegion`
     * entirely for this value, which is the documented way to express it.
     */
    const val AUTOMATIC = ""

    /** Globe for the Automatic row, so it is never the visually odd one out. */
    const val GLOBE = "\uD83C\uDF10"

    /** [AUTOMATIC] first, so it stays the top row of the picker. */
    val codes: List<String> = listOf(
        AUTOMATIC,
        "AE", "AR", "AT", "AU", "BE", "BG", "BR", "CA", "CH", "CL", "CO", "CY",
        "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HK", "HR", "HU",
        "IE", "IL", "IN", "IS", "IT", "JP", "KR", "LT", "LU", "LV", "MD", "MX",
        "MY", "NL", "NO", "NZ", "PH", "PL", "PT", "RO", "RS", "SE", "SG", "SK",
        "TH", "TR", "TW", "UA", "US", "VN", "ZA",
    )

    /**
     * English country names, on purpose.
     *
     * These are not app copy: they are the labels of a foreign network's exit
     * locations, and the two-letter code next to the flag is the part that has
     * to survive a screenshot in a support thread. Fifty-six translated strings
     * per language would also be fifty-six more things for the string-resource
     * check to police for no user-visible gain.
     */
    private val names: Map<String, String> = mapOf(
        "AE" to "United Arab Emirates", "AR" to "Argentina", "AT" to "Austria",
        "AU" to "Australia", "BE" to "Belgium", "BG" to "Bulgaria",
        "BR" to "Brazil", "CA" to "Canada", "CH" to "Switzerland",
        "CL" to "Chile", "CO" to "Colombia", "CY" to "Cyprus",
        "CZ" to "Czechia", "DE" to "Germany", "DK" to "Denmark",
        "EE" to "Estonia", "ES" to "Spain", "FI" to "Finland",
        "FR" to "France", "GB" to "United Kingdom", "GR" to "Greece",
        "HK" to "Hong Kong", "HR" to "Croatia", "HU" to "Hungary",
        "IE" to "Ireland", "IL" to "Israel", "IN" to "India",
        "IS" to "Iceland", "IT" to "Italy", "JP" to "Japan",
        "KR" to "South Korea", "LT" to "Lithuania", "LU" to "Luxembourg",
        "LV" to "Latvia", "MD" to "Moldova", "MX" to "Mexico",
        "MY" to "Malaysia", "NL" to "Netherlands", "NO" to "Norway",
        "NZ" to "New Zealand", "PH" to "Philippines", "PL" to "Poland",
        "PT" to "Portugal", "RO" to "Romania", "RS" to "Serbia",
        "SE" to "Sweden", "SG" to "Singapore", "SK" to "Slovakia",
        "TH" to "Thailand", "TR" to "Turkey", "TW" to "Taiwan",
        "UA" to "Ukraine", "US" to "United States", "VN" to "Vietnam",
        "ZA" to "South Africa",
    )

    /**
     * The stored value, reduced to something the core will accept: a supported
     * code, or [AUTOMATIC].
     *
     * Deliberately drops codes that are well-formed but not in [codes]: a saved
     * profile from an older build (or an imported config) must not be able to
     * pin the session to a region Psiphon has no servers in, because that region
     * is a hard filter and the session would just never establish.
     */
    fun sanitize(raw: String): String {
        val code = raw.trim().uppercase()
        return if (code in names) code else AUTOMATIC
    }

    /** Country name with no flag - for logs, where emoji are noise. */
    fun name(code: String): String {
        val cc = code.trim().uppercase()
        return names[cc] ?: cc.ifEmpty { "Automatic" }
    }

    /**
     * The flag emoji for a country code: two regional-indicator code points.
     *
     * Returns [GLOBE] for anything that is not two ASCII letters, so a bad
     * stored value renders as the Automatic badge instead of two empty boxes.
     */
    fun flag(code: String): String {
        val cc = code.trim().uppercase()
        if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return GLOBE
        val builder = StringBuilder()
        cc.forEach { builder.appendCodePoint(REGIONAL_INDICATOR_A + (it - 'A')) }
        return builder.toString()
    }

    /** Flag + name, e.g. "\uD83C\uDDE9\uD83C\uDDEA  Germany". Two spaces: the flag is wide. */
    fun label(code: String): String = "${flag(code)}  ${name(code)}"

    /** U+1F1E6 REGIONAL INDICATOR SYMBOL LETTER A. */
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
