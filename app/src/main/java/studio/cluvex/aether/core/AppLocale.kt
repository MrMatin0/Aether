package studio.cluvex.aether.core

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/*
 * IN-APP LANGUAGE (1.3.0)
 *
 * WHY THIS EXISTS
 *
 * The app already shipped a complete Persian translation (res/values-fa), but
 * the only way to reach it was to change the language of the whole PHONE. That
 * is backwards for this audience: plenty of people run an English Android and
 * want the Persian copy of THIS app, and plenty run a Persian phone but prefer
 * the English technical wording. The choice now lives in the app, one tap from
 * the home screen.
 *
 * HOW IT IS APPLIED
 *
 *  - API 33+ : the platform's per-app language API (LocaleManager). The system
 *    owns the override, so EVERY context follows it -- activities, the VPN
 *    service notification, the Quick Settings tile -- and the choice also shows
 *    up in Android's own app-info screen. The system repaints the app itself.
 *  - API 26..32 : no platform API exists, so the selection is persisted here
 *    and layered onto the activity's resources in attachBaseContext(). The
 *    activity is recreated to repaint, which also flips layoutDirection for
 *    Persian.
 *
 * WHY SHAREDPREFERENCES AND NOT DATASTORE: attachBaseContext() runs before
 * anything can suspend, so the read must be synchronous. It also runs inside
 * Application.attachBaseContext(), where getApplicationContext() is still
 * null -- which is why every read below goes through the context handed in.
 */

/** The three states of the language setting. */
enum class AppLanguage(val tag: String) {
    /** Follow the phone. */
    SYSTEM(""),
    ENGLISH("en"),
    PERSIAN("fa"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = values().firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

object AppLocale {
    private const val PREFS = "aether_locale"
    private const val KEY_TAG = "language_tag"

    /** What the user picked; SYSTEM when they never picked anything. */
    fun stored(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY_TAG, null))

    /** The language actually on screen right now (SYSTEM resolved to a real one). */
    fun effective(context: Context): AppLanguage {
        val stored = stored(context)
        if (stored != AppLanguage.SYSTEM) return stored
        val language = runCatching { context.resources.configuration.locales[0].language }.getOrNull()
        return if (language.equals(AppLanguage.PERSIAN.tag, ignoreCase = true)) {
            AppLanguage.PERSIAN
        } else {
            AppLanguage.ENGLISH
        }
    }

    /** True when the platform repaints the app itself and we must NOT recreate(). */
    val systemHandlesSwitch: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Persists [language] and, on API 33+, hands it to the platform. */
    fun apply(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_TAG, language.tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val manager = context.getSystemService(LocaleManager::class.java)
                manager?.applicationLocales = if (language == AppLanguage.SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(language.tag)
                }
            }
        }
    }

    /**
     * Wraps a base context so its resources resolve in the selected language.
     * Returns [base] untouched when the user follows the system, so the default
     * path costs nothing.
     */
    fun wrap(base: Context): Context {
        val language = stored(base)
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        // Also set the JVM default so date/number formatting agrees with the UI.
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Walks a Compose context chain up to the hosting Activity, if there is one. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
