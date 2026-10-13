package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.Hop

/**
 * What the LIVE session's chain looks like: which cores are stacked, how far
 * each one has got, and — the part the rest of the app actually depends on —
 * which local port the device's traffic enters through.
 *
 * ### Why this is process-wide state
 *
 * The chain is decided by the VpnService, on an IO thread, per session. The
 * things that need to know about it are spread across processes' worth of
 * surface: the TUN forwarder's config file, the connectivity self-test, the
 * latency probe, the IP badge in the activity, the LAN share bridge. Passing a
 * port down all of those call chains would mean touching every one of them for
 * a value that is, by construction, singular: this app runs ONE tunnel at a
 * time. So it lives here, with a single writer ([publish] / [reset], both
 * called from the session state machine) and everybody else reading
 * [TunnelConfig.SOCKS_PORT].
 *
 * [entryPort] falls back to the engine's own port, so a probe that fires before
 * any chain has been published (or after teardown) behaves exactly as it did
 * before chaining existed.
 */
object ChainRuntime {

    /** How far one core in the chain has got. */
    enum class HopState { PENDING, STARTING, READY, FAILED }

    /** One row of the chain, for the diagnostics panel. */
    data class HopStatus(
        val hop: Hop,
        val state: HopState,
        /** Free-text progress, e.g. Tor's bootstrap percentage. */
        val detail: String? = null,
    )

    private val _mode = MutableStateFlow(ChainMode.AETHER)
    val mode: StateFlow<ChainMode> = _mode.asStateFlow()

    private val _entryPort = MutableStateFlow(TunnelConfig.ENGINE_SOCKS_PORT)

    /** The chain entry port, as a flow for the UI. */
    val entryPortFlow: StateFlow<Int> = _entryPort.asStateFlow()

    /** The chain entry port, for the many callers that just need the number. */
    val entryPort: Int get() = _entryPort.value

    /**
     * True while the live session is an SSTP packet tunnel.
     *
     * Every SOCKS session keeps the app's own package OUT of its TUN and
     * reaches the tunnel through the local SOCKS5 entry. SSTP has no such
     * entry, so its TUN carries the app's own sockets instead (see
     * TunFactory.establishSstp). For a probe that wants to go "through the
     * tunnel" a plain socket IS the tunnel path then, and dialling [entryPort]
     * would only reach a port nothing listens on - which is exactly why the
     * latency, the exit IP and the country used to fail on SSTP.
     */
    val appTrafficInTun: Boolean get() = _mode.value.usesSstp

    private val _hops = MutableStateFlow<List<HopStatus>>(emptyList())
    val hops: StateFlow<List<HopStatus>> = _hops.asStateFlow()

    /** `host:port` of the chain entry, for the Connected state label. */
    val endpoint: String get() = "${TunnelConfig.SOCKS_HOST}:$entryPort"

    /**
     * The local port the device's traffic enters for [mode], WITHOUT needing a
     * live session — the session state machine has to know where to point the
     * TUN forwarder before anything is running, and the port-release check has
     * to know which ports to wait on before anything is started.
     *
     * Aether's own SOCKS5 speaks UDP ASSOCIATE, so an Aether-entry chain needs
     * no front and the entry is the engine itself. So does Xray's SOCKS inbound,
     * and its generated config answers port-53 UDP itself (see [XrayConfig]),
     * so an Xray entry would be Xray's own port - though every Xray mode now
     * puts Xray at the BOTTOM of the chain, never at the entry. Psiphon and Tor
     * do not, so their traffic enters through [SocksFront] instead (see its
     * KDoc for the DNS argument).
     *
     * SSTP has no local port at all: its TUN is pumped packet by packet by
     * [SstpCore.bridgeTun]. The engine's port is returned so the many readers of
     * [entryPort] keep their pre-chain default, and nothing listens there
     * (see [appTrafficInTun] for what a probe does instead).
     */
    fun entryPortFor(mode: ChainMode): Int = when (mode.entryHop) {
        Hop.AETHER, Hop.SSTP -> TunnelConfig.ENGINE_SOCKS_PORT
        Hop.XRAY -> TunnelConfig.XRAY_SOCKS_PORT
        Hop.PSIPHON, Hop.TOR -> TunnelConfig.FRONT_SOCKS_PORT
    }

    /** True when [mode]'s entry is a core that needs the DNS-capable SOCKS front. */
    fun needsFront(mode: ChainMode): Boolean =
        mode.entryHop == Hop.PSIPHON || mode.entryHop == Hop.TOR

    /**
     * Every local port [mode] binds, so teardown can prove they were released.
     * Xray's relay port is only bound by a plaintext VLESS / Trojan config, and
     * waiting on a port nothing held returns at once.
     */
    fun portsFor(mode: ChainMode): List<Int> = buildList {
        if (mode.usesAether) add(TunnelConfig.ENGINE_SOCKS_PORT)
        if (mode.usesPsiphon) add(TunnelConfig.PSIPHON_SOCKS_PORT)
        if (mode.usesXray) {
            add(TunnelConfig.XRAY_SOCKS_PORT)
            add(TunnelConfig.XRAY_RELAY_PORT)
        }
        if (mode.usesTor) {
            add(TunnelConfig.TOR_SOCKS_PORT)
            add(TunnelConfig.TOR_DNS_PORT)
        }
        if (needsFront(mode)) add(TunnelConfig.FRONT_SOCKS_PORT)
    }

    /** Called when a session's chain is decided, before the cores are started. */
    fun begin(mode: ChainMode) {
        _mode.value = mode
        _hops.value = mode.hops.map { HopStatus(it, HopState.PENDING) }
        _entryPort.value = entryPortFor(mode)
    }

    fun update(hop: Hop, state: HopState, detail: String? = null) {
        _hops.value = _hops.value.map {
            if (it.hop == hop) it.copy(state = state, detail = detail) else it
        }
    }

    /** Called once the entry core is proven to be listening. */
    fun publish(mode: ChainMode, entryPort: Int) {
        _mode.value = mode
        _entryPort.value = entryPort
    }

    /** Back to "Aether on 1819", i.e. the pre-chain default. */
    fun reset() {
        _mode.value = ChainMode.AETHER
        _entryPort.value = TunnelConfig.ENGINE_SOCKS_PORT
        _hops.value = emptyList()
    }
}
