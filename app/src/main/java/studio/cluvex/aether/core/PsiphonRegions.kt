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
 * ### Why THIS list
 *
 * These are the Psiphon exits that are actually reachable, not every region
 * Psiphon has ever published. `EgressRegion` is a HARD filter, so offering a
 * country with no reachable server only buys a session that sits on
 * "Connecting" until the budget runs out (see
 * [studio.cluvex.aether.vpn.session.ChainStack], which then falls back to an
 * automatic exit). Availability still depends on the live network, so this is
 * the set of countries that CAN be asked for, not a promise.
 *
 * ### Why the flags are computed and not shipped
 *
 * A flag emoji is just the two regional-indicator code points for the country
 * code, rendered by the system emoji font. Deriving them from the code means the
 * list can never drift out of sync with itself and costs zero APK size, which a
 * drawable per country would not.
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
        "AT", "AU", "BE", "CA", "CH", "CZ", "DE", "DK", "ES", "FI", "FR", "GB",
        "ID", "IE", "IN", "IT", "JP", "NL", "NO", "PL", "RO", "RS", "SE", "SG",
        "US",
    )

    /**
     * English country names, on purpose.
     *
     * These are not app copy: they are the labels of a foreign network's exit
     * locations, and the two-letter code next to the flag is the part that has
     * to survive a screenshot in a support thread. A translated string per
     * country per language would also be that many more things for the
     * string-resource check to police for no user-visible gain.
     */
    private val names: Map<String, String> = mapOf(
        "AT" to "Austria", "AU" to "Australia", "BE" to "Belgium",
        "CA" to "Canada", "CH" to "Switzerland", "CZ" to "Czechia",
        "DE" to "Germany", "DK" to "Denmark", "ES" to "Spain",
        "FI" to "Finland", "FR" to "France", "GB" to "United Kingdom",
        "ID" to "Indonesia", "IE" to "Ireland", "IN" to "India",
        "IT" to "Italy", "JP" to "Japan", "NL" to "Netherlands",
        "NO" to "Norway", "PL" to "Poland", "RO" to "Romania",
        "RS" to "Serbia", "SE" to "Sweden", "SG" to "Singapore",
        "US" to "United States",
    )

    /**
     * The stored value, reduced to something the core will accept: a supported
     * code, or [AUTOMATIC].
     *
     * Deliberately drops codes that are well-formed but not in [codes]: a saved
     * profile from an older build (or an imported config) must not be able to
     * pin the session to a region Psiphon has no reachable servers in, because
     * that region is a hard filter and the session would just never establish.
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
