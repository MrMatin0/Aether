//! The bytes on the wire, and which failures are worth surviving.

use std::io::ErrorKind;
use std::time::Duration;

/// How often the tunnel gives boringtun a chance to run its own timers.
pub(super) const TIMER_TICK: Duration = Duration::from_millis(250);

/// Every receive buffer in this module. A UDP datagram cannot exceed it.
pub(super) const MAX_PACKET: usize = 65536;

/// WireGuard message types 1..=4 (init, response, cookie reply, transport).
const WG_MSG_TYPE_MIN: u8 = 1;
const WG_MSG_TYPE_MAX: u8 = 4;

/// Type byte plus the three reserved bytes.
const WG_HEADER_LEN: usize = 4;

/// A UDP error that says something about ONE datagram, not about the socket.
///
/// The common one is an ICMP port-unreachable arriving as ConnectionRefused:
/// treating that as fatal used to drop a working tunnel because a single probe
/// bounced.
pub fn is_transient_socket_error(error: &std::io::Error) -> bool {
    matches!(
        error.kind(),
        ErrorKind::ConnectionRefused
            | ErrorKind::ConnectionReset
            | ErrorKind::ConnectionAborted
            | ErrorKind::HostUnreachable
            | ErrorKind::NetworkUnreachable
            | ErrorKind::Interrupted
            | ErrorKind::WouldBlock
            | ErrorKind::TimedOut
    )
}

/// True for a packet whose three reserved bytes we are allowed to rewrite.
fn is_wg_message(pkt: &[u8]) -> bool {
    pkt.len() >= WG_HEADER_LEN && matches!(pkt[0], WG_MSG_TYPE_MIN..=WG_MSG_TYPE_MAX)
}

/// WARP edges expect the account's client id in the three reserved bytes that
/// stock WireGuard leaves zero. Anything else is dropped by the edge.
pub(super) fn inject_client_id(pkt: &mut [u8], client_id: &[u8; 3]) {
    if is_wg_message(pkt) {
        pkt[1..WG_HEADER_LEN].copy_from_slice(client_id);
    }
}

/// Puts the reserved bytes back to zero before handing a packet to boringtun,
/// which validates them against the spec.
pub(super) fn strip_client_id(pkt: &mut [u8]) {
    if is_wg_message(pkt) {
        pkt[1..WG_HEADER_LEN].fill(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Error;

    #[test]
    fn an_icmp_port_unreachable_is_treated_as_transient() {
        assert!(is_transient_socket_error(&Error::from(
            ErrorKind::ConnectionRefused
        )));
    }

    #[test]
    fn the_usual_transient_udp_errors_do_not_end_the_tunnel() {
        for kind in [
            ErrorKind::ConnectionReset,
            ErrorKind::ConnectionAborted,
            ErrorKind::HostUnreachable,
            ErrorKind::NetworkUnreachable,
            ErrorKind::Interrupted,
            ErrorKind::WouldBlock,
            ErrorKind::TimedOut,
        ] {
            assert!(
                is_transient_socket_error(&Error::from(kind)),
                "{kind:?} should be transient"
            );
        }
    }

    #[test]
    fn a_broken_socket_is_still_fatal() {
        for kind in [
            ErrorKind::NotConnected,
            ErrorKind::AddrNotAvailable,
            ErrorKind::PermissionDenied,
            ErrorKind::InvalidInput,
        ] {
            assert!(
                !is_transient_socket_error(&Error::from(kind)),
                "{kind:?} should be fatal"
            );
        }
    }

    #[test]
    fn the_client_id_round_trips_through_the_reserved_bytes() {
        let mut pkt = vec![1u8, 0, 0, 0, 0xaa, 0xbb];
        inject_client_id(&mut pkt, &[7, 8, 9]);
        assert_eq!(&pkt[1..4], &[7, 8, 9]);
        assert_eq!(&pkt[4..], &[0xaa, 0xbb], "the payload is untouched");

        strip_client_id(&mut pkt);
        assert_eq!(&pkt[1..4], &[0, 0, 0]);
    }

    #[test]
    fn a_packet_that_is_not_a_wireguard_message_is_left_alone() {
        // Type 9 is not a WireGuard message: this is somebody else's traffic,
        // or junk, and rewriting bytes 1..4 of it would corrupt it.
        let original = vec![9u8, 1, 2, 3, 4];
        let mut pkt = original.clone();
        inject_client_id(&mut pkt, &[7, 8, 9]);
        assert_eq!(pkt, original);

        // Too short to hold the reserved bytes at all.
        let mut runt = vec![1u8, 5, 6];
        inject_client_id(&mut runt, &[7, 8, 9]);
        assert_eq!(runt, vec![1u8, 5, 6]);
        strip_client_id(&mut runt);
        assert_eq!(runt, vec![1u8, 5, 6]);
    }

    #[test]
    fn all_four_wireguard_message_types_are_stamped() {
        for kind in WG_MSG_TYPE_MIN..=WG_MSG_TYPE_MAX {
            let mut pkt = vec![kind, 0, 0, 0];
            inject_client_id(&mut pkt, &[1, 2, 3]);
            assert_eq!(&pkt[1..4], &[1, 2, 3], "message type {kind}");
        }
    }
}
