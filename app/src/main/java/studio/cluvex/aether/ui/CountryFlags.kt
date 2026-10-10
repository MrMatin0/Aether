package studio.cluvex.aether.ui

/**
 * Maps ISO 3166-1 alpha-2 country codes to their regional indicator emoji
 * flag sequences. Every two-letter code yields a flag on platforms that
 * render regional indicator symbols (Android 5+, all modern browsers).
 *
 * The trick: each ASCII letter A..Z maps to a Unicode regional indicator
 * symbol 🇦..🇿 (U+1F1E6..U+1F1FF). Two of those together form a flag
 * glyph. So "JP" → 🇯🇵, "US" → 🇺🇸, and so on.
 */
object CountryFlags {

    /** Returns the flag emoji for [countryCode], or 🏳 when invalid. */
    fun flag(countryCode: String): String {
        val code = countryCode.uppercase().take(2)
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "\uD83C\uDFF3\uFE0F"
        val first = 0x1F1E6 + (code[0] - 'A')
        val second = 0x1F1E6 + (code[1] - 'A')
        return String(intArrayOf(first, second), 0, 2)
    }

    /** Common country names for search matching, keyed by code. */
    val COUNTRY_NAMES = mapOf(
        "JP" to "Japan",
        "KR" to "South Korea",
        "US" to "United States",
        "CA" to "Canada",
        "GB" to "United Kingdom",
        "DE" to "Germany",
        "FR" to "France",
        "NL" to "Netherlands",
        "SG" to "Singapore",
        "AU" to "Australia",
        "TH" to "Thailand",
        "TW" to "Taiwan",
        "VN" to "Vietnam",
        "IN" to "India",
        "ID" to "Indonesia",
        "RU" to "Russia",
        "BR" to "Brazil",
        "HK" to "Hong Kong",
        "MY" to "Malaysia",
        "PH" to "Philippines",
        "RO" to "Romania",
        "BG" to "Bulgaria",
        "PL" to "Poland",
        "CZ" to "Czech Republic",
        "SE" to "Sweden",
        "NO" to "Norway",
        "FI" to "Finland",
        "DK" to "Denmark",
        "CH" to "Switzerland",
        "AT" to "Austria",
        "IT" to "Italy",
        "ES" to "Spain",
        "PT" to "Portugal",
        "MX" to "Mexico",
        "AR" to "Argentina",
        "CL" to "Chile",
        "CO" to "Colombia",
        "UA" to "Ukraine",
        "TR" to "Turkey",
        "ZA" to "South Africa",
        "EG" to "Egypt",
        "IL" to "Israel",
        "AE" to "UAE",
        "SA" to "Saudi Arabia",
        "IR" to "Iran",
        "PK" to "Pakistan",
        "BD" to "Bangladesh",
        "NZ" to "New Zealand",
    )
}
