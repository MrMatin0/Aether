package studio.cluvex.aether.ui

import java.util.Locale

/**
 * Country flags and names for ISO 3166-1 alpha-2 codes.
 *
 * Each ASCII letter A..Z maps to a regional indicator symbol
 * (U+1F1E6..U+1F1FF); two of them render as one flag glyph, so "JP" becomes
 * the Japanese flag. Names come from the platform's locale data, so a Persian
 * UI gets Persian country names with no table to maintain.
 */
object CountryFlags {

    private const val REGIONAL_INDICATOR_A = 0x1F1E6

    /** White flag, for codes that are not two ASCII letters. */
    private const val UNKNOWN_FLAG = "\uD83C\uDFF3\uFE0F"

    /** The flag emoji for [countryCode]. "USA" or "u1" get the white flag, not a wrong one. */
    fun flag(countryCode: String): String {
        val code = countryCode.trim().uppercase(Locale.ROOT)
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return UNKNOWN_FLAG
        return StringBuilder(4)
            .appendCodePoint(REGIONAL_INDICATOR_A + (code[0] - 'A'))
            .appendCodePoint(REGIONAL_INDICATOR_A + (code[1] - 'A'))
            .toString()
    }

    /** The country's name in [locale], or [fallback] when the platform has none. */
    fun displayName(countryCode: String, locale: Locale, fallback: String = countryCode): String {
        val code = countryCode.trim().uppercase(Locale.ROOT)
        val name = if (code.length == 2) {
            runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()
        } else {
            null
        }
        return when {
            !name.isNullOrBlank() && name != code -> name
            fallback.isNotBlank() -> fallback
            else -> code
        }
    }
}
