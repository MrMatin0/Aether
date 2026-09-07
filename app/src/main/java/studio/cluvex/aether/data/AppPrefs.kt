package studio.cluvex.aether.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app is painted.
 *
 * SYSTEM is the default because it is what people expect, but the override is
 * not decoration: this app is often run on a borrowed, second-hand or oddly
 * configured phone where the system setting is not what the person holding it
 * wants, and forcing dark also matters for anyone using it somewhere they would
 * rather a bright screen did not announce them.
 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * App behaviour that is NOT part of the engine profile.
 *
 * These deliberately do NOT live in [studio.cluvex.aether.model.ConnectionProfile]:
 * that model is a description of one tunnel, it travels to the VPN service
 * through ProfileCodec and it gets exported/imported as a config. "Connect when
 * the app opens" is a property of this installation, not of a tunnel, and it
 * must be readable SYNCHRONOUSLY from a boot broadcast.
 */
data class AppBehaviour(
    /** Start connecting as soon as the app is opened cold. */
    val autoConnectOnLaunch: Boolean = false,
    /** Reconnect after the device boots (requires VPN consent already granted). */
    val autoConnectOnBoot: Boolean = false,
    /** Record finished sessions (start, duration, bytes) on this device. */
    val keepHistory: Boolean = true,
    /** Light / dark / follow the system. Read by the Compose theme. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * Process-wide store for [AppBehaviour]. Backed by a tiny SharedPreferences
 * file so the boot receiver can read it without a coroutine, and published as a
 * StateFlow so Compose reacts to changes with no plumbing.
 *
 * The theme lives here rather than in DataStore for the same reason: the very
 * first frame of the very first Activity needs it, and an async read means a
 * guaranteed flash of the wrong scheme on every cold start.
 *
 * WRITING: prefer [mutate] over [update]. A settings row only ever changes ONE
 * field, and [mutate] rebases that change on the CURRENT state instead of on
 * whatever snapshot the caller happened to be holding - see its KDoc for the
 * bug that motivated it.
 */
object AppPrefs {
    private const val FILE = "aether_behaviour"
    private const val KEY_LAUNCH = "auto_connect_launch"
    private const val KEY_BOOT = "auto_connect_boot"
    private const val KEY_HISTORY = "keep_history"
    private const val KEY_THEME = "theme_mode"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppBehaviour())
    val state: StateFlow<AppBehaviour> = _state.asStateFlow()

    /**
     * Idempotent AND thread-safe, and it has to be both: this is called from
     * [studio.cluvex.aether.AetherApp.onCreate], from
     * [studio.cluvex.aether.vpn.BootReceiver] (a binder thread) and from
     * MainActivity's auto-connect check. The `prefs != null` guard makes every
     * call after the first a no-op, and @Synchronized is what keeps two callers
     * from both deciding they are the first one and publishing [_state] twice.
     *
     * Every read of and write to [prefs] in this object happens under this same
     * monitor, which is why the field needs no @Volatile.
     */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = store
        _state.value = AppBehaviour(
            autoConnectOnLaunch = store.getBoolean(KEY_LAUNCH, false),
            autoConnectOnBoot = store.getBoolean(KEY_BOOT, false),
            keepHistory = store.getBoolean(KEY_HISTORY, true),
            themeMode = readTheme(store),
        )
    }

    /**
     * Changes ONE part of the behaviour, rebased on the CURRENT state.
     *
     * WHAT WAS WRONG: every settings row called
     * `update(context, behaviour.copy(field = new))`, where `behaviour` is the
     * value the composable captured when it was COMPOSED. That turns a
     * single-field edit into a whole-object overwrite carrying a stale copy of
     * every other field, and the snapshot only refreshes after the
     * StateFlow -> collectAsState round trip has recomposed the panel. Any
     * second tap that lands before that - two switches in Automation flipped in
     * quick succession, a theme change while a toggle was still settling -
     * wrote the old value of the first field back over the new one. The setting
     * silently reverted, on disk as well as on screen, and it looked like the
     * toggle "did not stick".
     *
     * Doing the read-modify-write inside the lock that already guards the store
     * removes the window entirely: [transform] always receives the state as it
     * is right now, never as some caller last saw it.
     */
    @Synchronized
    fun mutate(context: Context, transform: (AppBehaviour) -> AppBehaviour) {
        init(context)
        write(transform(_state.value))
    }

    /**
     * Replaces the WHOLE behaviour. Correct for a reset or an import, which
     * genuinely own every field; for a single setting use [mutate].
     */
    @Synchronized
    fun update(context: Context, behaviour: AppBehaviour) {
        init(context)
        write(behaviour)
    }

    /** Only call while holding this object's monitor (see [mutate]). */
    private fun write(behaviour: AppBehaviour) {
        // Non-null unless init() threw, in which case it propagated and we are
        // not here. Named so a future edit cannot quietly turn a failed write
        // into a published in-memory value that never reached the disk.
        val store = prefs ?: return
        store.edit()
            .putBoolean(KEY_LAUNCH, behaviour.autoConnectOnLaunch)
            .putBoolean(KEY_BOOT, behaviour.autoConnectOnBoot)
            .putBoolean(KEY_HISTORY, behaviour.keepHistory)
            .putString(KEY_THEME, behaviour.themeMode.name)
            .apply()
        _state.value = behaviour
    }

    /**
     * Stored as the enum NAME, not its ordinal: an ordinal silently remaps every
     * saved preference the moment a value is inserted into the enum.
     */
    private fun readTheme(store: SharedPreferences): ThemeMode {
        val raw = store.getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }
}
