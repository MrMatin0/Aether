package studio.cluvex.aether.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.ComponentCheck

/**
 * The self-test board (port -> handshake -> tcp -> dns+http) the panel renders.
 *
 * It has NOTHING to do with the log stream and now owns its own state and its
 * own lock. Previously both were @Synchronized members of DiagnosticsLog, so a
 * check update fired from a probe could queue behind init()'s file copy.
 */
internal object SelfTestChecks {

    private val _checks = MutableStateFlow<List<ComponentCheck>>(emptyList())
    val checks: StateFlow<List<ComponentCheck>> = _checks.asStateFlow()

    private val lock = Any()

    fun replace(checks: List<ComponentCheck>) = synchronized(lock) {
        _checks.value = checks
    }

    fun update(id: String, state: CheckState, detail: String?) = synchronized(lock) {
        _checks.value = _checks.value.map {
            if (it.id == id) it.copy(state = state, detail = detail ?: it.detail) else it
        }
    }
}
