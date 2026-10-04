package studio.cluvex.aether.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.Protocol

/** Device-local lab switches, deliberately not silently enabled by imported presets. */
data class WgExperimentConfig(
    val portHopping: Boolean = false,
    val dataPadding: Boolean = false,
) {
    fun toEnv(protocol: Protocol): Map<String, String> {
        // Explicit zeroes also override any inherited process environment.
        // AUTO is resolved to a concrete transport before AetherProcess.start.
        val supported = protocol == Protocol.WIREGUARD
        return mapOf(
            "AETHER_WG_PORT_HOP" to if (supported && portHopping) "1" else "0",
            "AETHER_WG_DATA_PADDING" to if (supported && dataPadding) "1" else "0",
        )
    }
}

object WgExperimentPrefs {
    private lateinit var prefs: SharedPreferences
    private val current = MutableStateFlow(WgExperimentConfig())
    val state: StateFlow<WgExperimentConfig> = current.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("wg_experiments", Context.MODE_PRIVATE)
        current.value = WgExperimentConfig(
            portHopping = prefs.getBoolean("portHopping", false),
            dataPadding = prefs.getBoolean("dataPadding", false),
        )
    }

    @Synchronized
    fun setPortHopping(value: Boolean) = save(current.value.copy(portHopping = value))

    @Synchronized
    fun setDataPadding(value: Boolean) = save(current.value.copy(dataPadding = value))

    private fun save(value: WgExperimentConfig) {
        check(::prefs.isInitialized) { "WgExperimentPrefs must be initialized by Application" }
        prefs.edit()
            .putBoolean("portHopping", value.portHopping)
            .putBoolean("dataPadding", value.dataPadding)
            .apply()
        current.value = value
    }
}
