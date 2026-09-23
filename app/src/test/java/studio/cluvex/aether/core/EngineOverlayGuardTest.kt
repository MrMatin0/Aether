package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertTrue
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

/**
 * ONE TOR, ONE PSIPHON.
 *
 * Engine 2.0.0 can embed its own tor and engine 2.1.0 can spawn its own
 * psiphon-tunnel-core. This app already runs both - TorCore and PsiphonCore,
 * the latter built from source to close audit finding F-8 - and a second copy
 * of either would mean two configs, two egress settings and a second binary
 * from a second source to audit. See docs/CORE_V2.md and docs/CORE_V2_1.md.
 *
 * The engine's copies start only when asked, so the whole rule is: the engine
 * is never asked. This walks every protocol x scan mode x chain mode, scanned
 * and pinned, on both cores, with every chain-core field filled in, and checks
 * that nothing Tor- or Psiphon-shaped reaches the engine's argv or environment.
 */
class EngineOverlayGuardTest {

    private val cores = listOf("2.0.0", "2.1.0")

    private fun everyProfile(): Sequence<ConnectionProfile> = sequence {
        for (protocol in Protocol.entries) {
            for (scan in ScanMode.entries) {
                for (chain in ChainMode.entries) {
                    val scanned = ConnectionProfile(
                        protocol = protocol,
                        scanMode = scan,
                        chain = chain,
                        psiphonRegion = "DE",
                        torExitCountry = "DE",
                        torStrictNodes = true,
                    )
                    yield(scanned)
                    yield(
                        scanned.copy(
                            endpointMode = EndpointMode.MANUAL_PEER,
                            manualPeer = "162.159.192.1:443",
                        ),
                    )
                }
            }
        }
    }

    private fun assertNeverAsked(argPrefix: String, envPrefix: String) {
        for (profile in everyProfile()) {
            for (core in cores) {
                val args = profile.toArgs(core)
                assertTrue(
                    args.none { it.startsWith(argPrefix) },
                    "$argPrefix* in argv for ${profile.protocol}/${profile.scanMode}/${profile.chain} on $core: $args",
                )
            }
            val env = profile.toEnv()
            assertTrue(
                env.keys.none { it.startsWith(envPrefix) },
                "$envPrefix* in env for ${profile.protocol}/${profile.scanMode}/${profile.chain}: ${env.keys}",
            )
        }
    }

    @Test
    fun the_engine_is_never_asked_to_run_its_own_psiphon() {
        assertNeverAsked(argPrefix = "--psiphon", envPrefix = "AETHER_PSIPHON")
    }

    @Test
    fun the_engine_is_never_asked_to_run_its_own_tor() {
        assertNeverAsked(argPrefix = "--tor", envPrefix = "AETHER_TOR")
    }
}
