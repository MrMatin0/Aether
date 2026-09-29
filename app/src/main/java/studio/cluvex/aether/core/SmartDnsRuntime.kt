package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the LIVE session did with Smart DNS that the UI has to know about.
 *
 * Today that is one thing: whether the ROM refused the IPv6-free TUN Smart DNS
 * asks for (see `TunFactory.establishSession`). The session then runs on the
 * addressed interface, so apps are handed AAAA answers again and a covered
 * site can be reached over IPv6 straight out of the WARP exit - the feature is
 * quietly bypassed. That used to reach the diagnostics log only; the Routing
 * page now shows it next to the setting it defeats.
 *
 * Same shape as [ChainRuntime]: one writer (TunFactory when a session TUN is
 * built, NativeStack when it is closed), everybody else reads.
 */
object SmartDnsRuntime {

    private val _ipv6Fallback = MutableStateFlow(false)

    /** True while the live session's TUN carries an IPv6 address DESPITE Smart DNS. */
    val ipv6Fallback: StateFlow<Boolean> = _ipv6Fallback.asStateFlow()

    fun setIpv6Fallback(value: Boolean) {
        _ipv6Fallback.value = value
    }

    /** No session TUN, nothing to report. */
    fun reset() {
        _ipv6Fallback.value = false
    }
}
