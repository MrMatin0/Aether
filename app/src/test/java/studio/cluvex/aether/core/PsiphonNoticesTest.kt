package studio.cluvex.aether.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pinned to the exact Psiphon lines of a real Xray -> Psiphon session log. */
class PsiphonNoticesTest {

    @Test
    fun ipv6_route_permission_is_harmless() {
        assertNotNull(
            PsiphonNotices.harmless(
                "ResolveIP: resolver.(*Resolver).updateNetworkState#1219: unable to determine IPv6 route: " +
                    "resolver.<redacted>#1475: route ip+net: netlinkrib: permission denied",
            ),
        )
    }

    @Test
    fun socks_accept_eof_is_harmless() {
        assertNotNull(PsiphonNotices.harmless("SOCKS proxy accept error: AcceptSocks: socksPeekByte() failed: EOF"))
    }

    @Test
    fun untunneled_dsl_fetch_through_upstream_is_harmless() {
        assertNotNull(
            PsiphonNotices.harmless(
                "untunneled DSL fetch failed: psiphon.runUntunneledDSLFetcher#96: " +
                    "psiphon.runUntunneledDSLFetcher.func1#60: " +
                    "psiphon.(*InproxyBrokerClientManager).GetBrokerClient#152: " +
                    "psiphon.(*InproxyBrokerClientManager).reset#297: " +
                    "psiphon.NewInproxyBrokerClientInstance#533: " +
                    "psiphon.MakeInproxyBrokerDialParameters#1211: upstream proxy unsupported",
            ),
        )
    }

    @Test
    fun meek_eof_is_a_real_warning() {
        // This one is the symptom: the upstream (Xray) dropped the connection.
        assertNull(PsiphonNotices.harmless("meek round trip failed: EOF"))
        assertNull(
            PsiphonNotices.harmless(
                "psiphon.(*MeekConn).relay.func1#1310: psiphon.(*MeekConn).relay#1438: " +
                    "psiphon.(*MeekConn).relayRoundTrip#1839: EOF",
            ),
        )
    }

    @Test
    fun unrelated_messages_stay_warnings() {
        assertNull(PsiphonNotices.harmless(""))
        assertNull(PsiphonNotices.harmless("establish tunnel timeout"))
        assertNull(PsiphonNotices.harmless("permission denied"))
    }
}
