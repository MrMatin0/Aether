package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.SmartDnsProtocol

/**
 * What the LIVE session's Smart DNS front is doing, for the Routing page.
 *
 * The one thing a user cannot see from the settings is which path the front
 * settled on: through the tunnel, or straight out of the phone because the
 * provider only answers local addresses - and, on the direct path, how many of
 * the provider's proxy ranges it detected. That is shown next to the setting.
 *
 * Same shape as [ChainRuntime]: one writer ([SmartDnsFront]; NativeStack
 * resets it when the session TUN is closed), everybody else reads.
 */
object SmartDnsRuntime {

    data class Status(
        val protocol: SmartDnsProtocol,
        val path: SmartDnsPath,
        val proxyRanges: Int,
    )

    private val _status = MutableStateFlow<Status?>(null)

    /** The live session's Smart DNS state, or null when no front is running. */
    val status: StateFlow<Status?> = _status.asStateFlow()

    fun begin(protocol: SmartDnsProtocol) {
        _status.value = Status(protocol, SmartDnsPath.TUNNEL, 0)
    }

    fun setPath(path: SmartDnsPath) {
        val current = _status.value ?: return
        _status.value = current.copy(
            path = path,
            proxyRanges = if (path == SmartDnsPath.TUNNEL) 0 else current.proxyRanges,
        )
    }

    fun setProxyRanges(count: Int) {
        val current = _status.value ?: return
        _status.value = current.copy(proxyRanges = count)
    }

    /** No session, nothing to report. */
    fun reset() {
        _status.value = null
    }
}
