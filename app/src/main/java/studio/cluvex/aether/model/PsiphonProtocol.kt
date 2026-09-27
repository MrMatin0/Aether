package studio.cluvex.aether.model

/**
 * Which tunnel protocols the Psiphon hop is allowed to use.
 *
 * Only meaningful with a Psiphon core built from shirokhorshid's fork of
 * psiphon-tunnel-core (the default, see scripts/build-overlay-cores.sh): that
 * fork adds the FRONTED-MEEK-CDN-* protocols and the config keys that point
 * fronted meek at CDN edges chosen here. See docs/CDN_FRONTING.md.
 *
 *  - [AUTO]         Psiphon picks, exactly as before. The CDN edge overrides
 *                   are still offered to the core so a fronted protocol it
 *                   happens to choose can use them, but nothing is restricted
 *                   and server-issued tactics stay on.
 *  - [DIRECT]       the ordinary Psiphon protocols (SSH/OSSH/TLS/QUIC/meek...)
 *                   including the CDN-fronted ones, tactics off.
 *  - [CDN_FRONTING] ONLY the FRONTED-MEEK-CDN-* protocols: every byte goes
 *                   through a CDN edge (Akamai / Fastly) with an innocuous SNI.
 *                   For networks where nothing but big CDNs gets through.
 *
 * [fromStored] is the single place a persisted / imported name becomes one of
 * these, so a hand-written config can say `cdn` or `fronting`.
 */
enum class PsiphonProtocol {
    AUTO, DIRECT, CDN_FRONTING,
    ;

    companion object {
        fun fromStored(raw: String?): PsiphonProtocol? {
            val name = raw?.trim()?.uppercase()
                ?.replace('-', '_')?.replace(' ', '_')
                ?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.name == name }?.let { return it }
            return when (name) {
                "CDN", "FRONTING", "CDNFRONTING", "CDN_FRONTED", "FRONTED" -> CDN_FRONTING
                "DEFAULT" -> AUTO
                "NORMAL" -> DIRECT
                else -> null
            }
        }
    }
}
