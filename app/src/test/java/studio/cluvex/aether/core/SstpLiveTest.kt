package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import studio.cluvex.aether.model.ProbeState

/**
 * End-to-end check against the REAL VPN Gate network: list, probe, then a full
 * SSTP session (TLS, SSTP call, LCP, sign-in, crypto binding, IPCP) until the
 * relay hands out an address.
 *
 * OPT-IN, because it needs the internet and depends on volunteers' servers:
 *
 * ```
 * AETHER_SSTP_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*SstpLiveTest*'
 * ```
 *
 * Without the variable it returns immediately, so CI stays hermetic.
 * `AETHER_SSTP_LIVE_TRIES` caps how many relays are tried (default 15).
 */
class SstpLiveTest {

    @Test
    fun completesAnSstpSessionWithALiveVpnGateRelay(): Unit = runBlocking<Unit> {
        if (System.getenv("AETHER_SSTP_LIVE") != "1") return@runBlocking
        val tries = System.getenv("AETHER_SSTP_LIVE_TRIES")?.toIntOrNull() ?: 15

        val servers = VpnGateRepository.fetchServers().getOrElse { fail("VPN Gate list failed: ${it.message}") }
        assertTrue(servers.isNotEmpty(), "VPN Gate returned no relays")

        var lastError = "no relay answered the SSTP probe"
        var attempted = 0
        for (server in servers.sortedByDescending { it.score }) {
            if (attempted >= tries) break
            if (VpnGateRepository.probe(server).state != ProbeState.SSTP_OK) continue
            attempted++
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                SstpCore.start(server.toSstpConfig(), scope)
                val end = withTimeoutOrNull(LIVE_TIMEOUT_MS) {
                    SstpCore.state.first {
                        it == SstpCore.SstpState.CONNECTED || it == SstpCore.SstpState.ERROR
                    }
                }
                if (end == SstpCore.SstpState.CONNECTED) {
                    val info = assertNotNull(SstpCore.tunnelInfo.value, "connected without tunnel info")
                    assertNotEquals("0.0.0.0", info.localIp)
                    assertTrue(info.mtu in 576..1500, "implausible MTU ${info.mtu}")
                    return@runBlocking
                }
                lastError = "${server.sstpHostname}:${server.sstpPort}: " +
                    SstpCore.statusMessage.value.ifBlank { "timed out" }
            } finally {
                SstpCore.stop()
                scope.cancel()
            }
        }
        fail("No VPN Gate relay completed an SSTP session after $attempted attempt(s); last error: $lastError")
    }

    private companion object {
        const val LIVE_TIMEOUT_MS = 60_000L
    }
}
