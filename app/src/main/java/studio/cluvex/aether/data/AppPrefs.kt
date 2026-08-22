package studio.cluvex.aether.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App behaviour that is NOT part of the engine profile.
 *
 * These deliberately do NOT live in [ConnectionProfile]: that model is a
 * description of one tunnel, it travels to the VPN service through ProfileCodec
 * and it gets exported/imported as a config. "Connect when the app opens" is a
 * property of this installation, not of a tunnel, and it must be readable
 * SYNCHRONOUSLY from a boot broadcast.
 */
data class AppBehaviour(
    /** Start connecting as soon as the app is opened cold. */
    val autoConnectOnLaunch: Boolean = false,
    /** Reconnect after the device boots (requires VPN consent already granted). */
    val autoConnectOnBoot: Boolean = false,
    /** Record finished sessions (start, duration, bytes) on this device. */
    val keepHistory: Boolean = true,
)

/**
 * Process-wide store for [AppBehaviour]. Backed by a tiny SharedPreferences
 * file so the boot receiver can read it without a coroutine, and published as a
 * StateFlow so Compose reacts to changes with no plumbing.
 */
object AppPrefs {
    private const val FILE = "aether_behaviour"
    private const val KEY_LAUNCH = "auto_connect_launch"
    private const val KEY_BOOT = "auto_connect_boot"
    private const val KEY_HISTORY = "keep_history"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppBehaviour())
    val state: StateFlow<AppBehaviour> = _state.asStateFlow()

    /** Idempotent; called from Application.onCreate and defensively elsewhere. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = store
        _state.value = AppBehaviour(
            autoConnectOnLaunch = store.getBoolean(KEY_LAUNCH, false),
            autoConnectOnBoot = store.getBoolean(KEY_BOOT, false),
            keepHistory = store.getBoolean(KEY_HISTORY, true),
        )
    }

    @Synchronized
    fun update(context: Context, behaviour: AppBehaviour) {
        init(context)
        prefs?.edit()
            ?.putBoolean(KEY_LAUNCH, behaviour.autoConnectOnLaunch)
            ?.putBoolean(KEY_BOOT, behaviour.autoConnectOnBoot)
            ?.putBoolean(KEY_HISTORY, behaviour.keepHistory)
            ?.apply()
        _state.value = behaviour
    }
}
