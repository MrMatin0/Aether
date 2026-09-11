package studio.cluvex.aether.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import studio.cluvex.aether.core.CheckFact
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.ComponentCheck

/**
 * The self-test board (port -> handshake -> tcp -> dns+http) the panel renders.
 *
 * It has NOTHING to do with the log stream and owns its own state. Previously
 * both were @Synchronized members of DiagnosticsLog, so a check update fired
 * from a probe could queue behind init()'s file copy.
 *
 * The explicit monitor is gone too: MutableStateFlow.update() is an atomic
 * compare-and-set loop, which is exactly what a read-modify-write of an
 * immutable list needs, and it cannot be held by anything else.
 */
internal object SelfTestChecks {

    private val _checks = MutableStateFlow<List<ComponentCheck>>(emptyList())
    val checks: StateFlow<List<ComponentCheck>> = _checks.asStateFlow()

    fun replace(checks: List<ComponentCheck>) {
        _checks.value = checks
    }

    /**
     * Patches one row. A null [detail], [latencyMs] or [facts] means "leave what
     * is there", so a caller that only knows the new state does not have to
     * re-supply the measurements taken by the previous one.
     */
    fun update(
        id: String,
        state: CheckState,
        detail: String? = null,
        latencyMs: Long? = null,
        facts: List<CheckFact>? = null,
    ) {
        _checks.update { current ->
            current.map { check ->
                if (check.id != id) {
                    check
                } else {
                    check.copy(
                        state = state,
                        detail = detail ?: check.detail,
                        latencyMs = latencyMs ?: check.latencyMs,
                        facts = facts ?: check.facts,
                    )
                }
            }
        }
    }

    /** Clears the measurements of one row without touching the others. */
    fun reset(id: String) {
        _checks.update { current ->
            current.map { check ->
                if (check.id != id) {
                    check
                } else {
                    check.copy(
                        state = CheckState.PENDING,
                        detail = "",
                        latencyMs = null,
                        facts = emptyList(),
                    )
                }
            }
        }
    }
}
