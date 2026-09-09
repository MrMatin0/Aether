package studio.cluvex.aether.core

import android.content.Context
import studio.cluvex.aether.core.moat.MoatPayloads
import studio.cluvex.aether.model.BridgeTransport

/**
 * The BUILT-IN bridges: the public list Tor Browser ships, per transport.
 *
 * ### Three sources, one answer
 *
 * 1. [FALLBACK] - compiled into the app. Same reasoning as
 *    `PsiphonCore.builtInConfig`: a feature whose first use needs a successful
 *    network request on a filtered network is a feature that does not work when
 *    it is needed. These lines are public by definition (they are in Tor
 *    Browser, on the Tor Project's wiki, and served unauthenticated by moat),
 *    so shipping them costs nothing and makes bridges usable offline.
 * 2. `assets/tor/bridges.json` - refreshed at build time by
 *    `scripts/fetch-tor-bridges.sh`, so a release is never more stale than its
 *    own build date.
 * 3. A live refresh from moat, stored by
 *    [studio.cluvex.aether.data.BridgeStore].
 *
 * Later sources WIN when they parse and contain something, and are ignored when
 * they do not. That ordering is the whole robustness story: a truncated asset or
 * an HTML captive-portal page cannot empty the picker.
 *
 * ### Why the built-ins are offered at all, given a censor has them too
 *
 * Because they are the only bridges available with no network access, they work
 * in most places most of the time, and the alternative - making the user request
 * a personal bridge before they can try anything - needs a working connection to
 * bridges.torproject.org, which is exactly what a censored network does not
 * have. The UI says plainly that these are public and that a personal bridge is
 * the next step when they fail.
 */
object BridgeCatalog {

    /**
     * The built-in list as served by
     * `https://bridges.torproject.org/moat/circumvention/builtin`.
     *
     * Kept in the SAME shape as that endpoint (moat key -> lines) so the
     * fallback, the asset and a live refresh all go through one parser and
     * nothing has a second format of its own.
     *
     * Vanilla is absent on purpose: there is no public list of plain bridges,
     * and there never will be - an unobfuscated address that everybody has is
     * an address that is already blocked.
     */
    val FALLBACK: Map<BridgeTransport, List<String>> = mapOf(
        BridgeTransport.OBFS4 to listOf(
            "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0",
            "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1",
            "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0",
            "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1",
            "obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq/mtAjp1BIC9vHKJ2FAEpGssTPw iat-mode=0",
            "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
            "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0",
        ),
        BridgeTransport.SNOWFLAKE to listOf(
            "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn",
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn",
        ),
        BridgeTransport.MEEK to listOf(
            "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN",
        ),
    )

    /** Where `scripts/fetch-tor-bridges.sh` puts its copy. */
    const val ASSET = "tor/bridges.json"

    @Volatile
    private var bundled: Map<BridgeTransport, List<String>>? = null

    /**
     * The built-in bridges from the build's own payload: the asset when it is
     * usable, the compiled-in list otherwise.
     *
     * Cached, because an APK asset cannot change while the process lives.
     */
    fun bundled(context: Context): Map<BridgeTransport, List<String>> =
        bundled ?: readAsset(context).let { fromAsset ->
            val merged = if (fromAsset.isEmpty()) FALLBACK else merge(FALLBACK, fromAsset)
            merged.also { bundled = it }
        }

    /**
     * The list the picker shows: [bundled], with a live refresh layered on top
     * when there is one.
     *
     * @param refreshed the JSON body of the last successful moat refresh, or
     *   blank. Parsed here rather than at write time so a payload that stops
     *   parsing after an app update degrades to the bundled list instead of to
     *   nothing.
     */
    fun current(context: Context, refreshed: String?): Map<BridgeTransport, List<String>> {
        val base = bundled(context)
        val live = parse(refreshed)
        return if (live.isEmpty()) base else merge(base, live)
    }

    /** Parses a moat `/circumvention/builtin` body. Empty on anything unusable. */
    fun parse(json: String?): Map<BridgeTransport, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        val byMoatName = runCatching { MoatPayloads.builtin(json) }.getOrDefault(emptyMap())
        if (byMoatName.isEmpty()) return emptyMap()
        val result = LinkedHashMap<BridgeTransport, MutableList<String>>()
        for ((name, lines) in byMoatName) {
            val transport = BridgeTransport.fromMoatName(name) ?: continue
            val usable = BridgeLine.parseAll(lines).map { it.line }
            if (usable.isEmpty()) continue
            // moat serves meek twice (as `meek` and `meek-azure`), so the same
            // transport can arrive under two keys with identical contents.
            val bucket = result.getOrPut(transport) { mutableListOf() }
            usable.forEach { if (it !in bucket) bucket.add(it) }
        }
        return result.mapValues { it.value.toList() }
    }

    /** [override] replaces a transport wholesale; transports it omits are kept. */
    private fun merge(
        base: Map<BridgeTransport, List<String>>,
        override: Map<BridgeTransport, List<String>>,
    ): Map<BridgeTransport, List<String>> {
        val merged = LinkedHashMap<BridgeTransport, List<String>>(base)
        for ((transport, lines) in override) {
            if (lines.isNotEmpty()) merged[transport] = lines
        }
        return merged
    }

    private fun readAsset(context: Context): Map<BridgeTransport, List<String>> = runCatching {
        context.assets.open(ASSET).use { input ->
            parse(input.readBytes().toString(Charsets.UTF_8))
        }
    }.getOrDefault(emptyMap())
}
