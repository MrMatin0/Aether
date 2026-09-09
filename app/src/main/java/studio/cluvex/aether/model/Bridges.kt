package studio.cluvex.aether.model

/**
 * A pluggable-transport BINARY: the executable tor spawns and speaks the
 * pluggable-transport protocol to.
 *
 * These are packaged exactly like the three cores - an executable under a `.so`
 * name in `jniLibs`, extracted into `nativeLibraryDir` with the exec bit set,
 * which is one of the few places Android still allows exec from. tor launches
 * them itself through `ClientTransportPlugin ... exec <path>`; nothing in this
 * app supervises them.
 *
 * [binaries] is a candidate list, not one name. `lyrebird` is what the Tor
 * Project calls obfs4proxy since 2023 and both names are still in circulation,
 * so a build that carries either one works instead of reporting the transport
 * as absent.
 */
enum class PtPlugin(val id: String, val binaries: List<String>) {
    /** obfs4 + meek_lite (+ obfs2/obfs3/scramblesuit, which nothing offers any more). */
    LYREBIRD("lyrebird", listOf("liblyrebird.so", "libobfs4proxy.so")),

    /** snowflake-client: WebRTC to volunteer proxies, brokered over a CDN. */
    SNOWFLAKE("snowflake", listOf("libsnowflake.so")),

    /** webtunnel: looks like HTTPS traffic to an ordinary website. */
    WEBTUNNEL("webtunnel", listOf("libwebtunnel.so")),
}

/**
 * One kind of Tor bridge.
 *
 * ### Why this is an enum and not the raw string from a bridge line
 *
 * Three different names exist for the same thing and all three are in the wild:
 * what tor writes in a torrc ([torName]), what moat answers with ([moatNames]),
 * and what a human calls it. `meek` is the worst case - moat serves it under
 * both `meek` and `meek-azure` (rdsys#272) while the transport tor actually
 * runs is called `meek_lite`. Mapping those in one place is the difference
 * between a working picker and a build that offers a transport it cannot name.
 *
 * ### Order
 *
 * Declaration order IS the order of the picker, cheapest and most likely to
 * work first. [VANILLA] is last on purpose: a plain bridge hides nothing about
 * the traffic, it only moves the address, so it is the fallback rather than the
 * suggestion.
 */
enum class BridgeTransport(
    /** The first token of a `Bridge` line, or "" for a bridge with no transport. */
    val torName: String,
    /** Every key moat has used for it. The first one is what we send back. */
    val moatNames: List<String>,
    /** The binary that speaks it, or null when tor itself does. */
    val plugin: PtPlugin?,
) {
    OBFS4("obfs4", listOf("obfs4"), PtPlugin.LYREBIRD),
    SNOWFLAKE("snowflake", listOf("snowflake"), PtPlugin.SNOWFLAKE),
    WEBTUNNEL("webtunnel", listOf("webtunnel"), PtPlugin.WEBTUNNEL),
    MEEK("meek_lite", listOf("meek", "meek-azure"), PtPlugin.LYREBIRD),

    /**
     * A bridge with no obfuscation: `<ip>:<port> <fingerprint>`.
     *
     * Still useful - it is an address a censor's relay list does not contain -
     * and it is the ONLY kind that works in a build with no PT binaries, which
     * is why it is a first-class option here rather than an afterthought.
     */
    VANILLA("", listOf("vanilla"), null),
    ;

    /** True when tor can run this without any extra executable. */
    val needsPlugin: Boolean get() = plugin != null

    /** What we ask moat for. */
    val moatName: String get() = moatNames.first()

    companion object {
        /** The token at the start of a bridge line, blank/absent meaning vanilla. */
        fun fromTorName(raw: String?): BridgeTransport? {
            val name = raw?.trim()?.lowercase().orEmpty()
            if (name.isEmpty()) return VANILLA
            entries.firstOrNull { it.torName == name }?.let { return it }
            // obfs4proxy speaks these under one binary and old configs still
            // carry them; they are obfs4 as far as the picker is concerned.
            return when (name) {
                "meek", "meek-azure", "meek_azure" -> MEEK
                else -> null
            }
        }

        /** A key from a moat payload. */
        fun fromMoatName(raw: String?): BridgeTransport? {
            val name = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { name in it.moatNames }
        }

        /** A persisted / transported name, or null to keep the caller's default. */
        fun fromStored(raw: String?): BridgeTransport? {
            val name = raw?.trim()?.uppercase()?.replace('-', '_')?.takeIf { it.isNotEmpty() }
                ?: return null
            entries.firstOrNull { it.name == name }?.let { return it }
            return when (name) {
                "MEEK_LITE", "MEEK_AZURE" -> MEEK
                "NONE", "PLAIN" -> VANILLA
                else -> null
            }
        }
    }
}

/**
 * WHERE the bridges in a profile came from.
 *
 * Deliberately not a boolean plus a list: the three sources have completely
 * different failure modes and the user has to be told which one they are in.
 * Built-in bridges are public and therefore the first thing a censor blocks; a
 * requested bridge is handed out to one person and can only be obtained while
 * something still reaches the bridge server; a pasted line is only as good as
 * wherever it came from.
 *
 * The MODE does not decide what tor runs - [ConnectionProfile.torBridgeLines]
 * does, always. This is provenance, and it is what the page reopens on.
 */
enum class TorBridgeMode {
    /** No bridges: tor connects to the public relays, as it always did. */
    OFF,

    /** The public list Tor Browser ships, per transport. */
    BUILTIN,

    /** Bridges the Tor Project handed to this device alone. */
    REQUESTED,

    /** Lines the user pasted from email, Telegram or a friend. */
    CUSTOM,
    ;

    val isOn: Boolean get() = this != OFF

    companion object {
        fun fromStored(raw: String?): TorBridgeMode? {
            val name = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.name == name }?.let { return it }
            return when (name) {
                "NONE", "FALSE" -> OFF
                "DEFAULT", "PUBLIC" -> BUILTIN
                "PERSONAL", "MOAT", "PRIVATE" -> REQUESTED
                "MANUAL", "PASTED", "TRUE" -> CUSTOM
                else -> null
            }
        }
    }
}
