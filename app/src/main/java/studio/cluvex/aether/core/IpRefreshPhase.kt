package studio.cluvex.aether.core

import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected

/**
 * Describes which IP source the shell should refresh after a state change.
 * Keeping this decision outside the activity makes the networking effect
 * declarative and gives the state mapping a small, deterministic test surface.
 */
enum class IpRefreshPhase {
    CONNECTED,
    BUSY,
    IDLE;

    companion object {
        fun from(state: ConnectionState): IpRefreshPhase = when {
            state.isConnected -> CONNECTED
            state.isBusy -> BUSY
            else -> IDLE
        }
    }
}
