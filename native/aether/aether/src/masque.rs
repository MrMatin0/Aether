//! MASQUE CONNECT-IP, in three focused pieces:
//!
//! * [`capsule`] - the HTTP capsule wire format (RFC 9297) and its parser.
//! * [`connect_ip`] - the CONNECT-IP request and the HTTP/3 datagram framing.
//! * [`probe`] - the synthetic IP packet used to prove a tunnel really
//!   forwards traffic instead of merely completing a handshake.
//!
//! Everything the rest of the crate imported from `crate::masque` is
//! re-exported here, so the split is source compatible.

mod capsule;
mod connect_ip;
mod probe;

pub use capsule::{
    encode_address_request, encode_capsule, encode_datagram_capsule, AssignedAddress, Capsule,
    CapsuleParser, RouteAdvertisement, CAPSULE_ADDRESS_ASSIGN, CAPSULE_ADDRESS_REQUEST,
    CAPSULE_DATAGRAM, CAPSULE_ROUTE_ADVERTISEMENT,
};
pub use connect_ip::{
    connect_ip_request, decode_ip_datagram, encode_ip_datagram, quarter_stream_id,
    strip_datagram_context,
};
pub use probe::build_dns_probe_packet;

/// A packet shorter than its own header cannot be an IP packet, and the two
/// versions do not share a minimum: v4 is 20 bytes, v6 is a fixed 40.
pub(crate) const IPV4_HEADER_LEN: usize = 20;
pub(crate) const IPV6_HEADER_LEN: usize = 40;

#[cfg(test)]
mod tests {
    use super::*;
    use octets::{Octets, OctetsMut};

    fn ip_packet() -> Vec<u8> {
        let mut pkt = vec![0u8; 24];
        pkt[0] = 0x45;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&[198, 18, 0, 1]);
        pkt[16..20].copy_from_slice(&[1, 1, 1, 1]);
        pkt
    }

    fn capsule_value(capsule: &[u8]) -> Vec<u8> {
        let mut b = Octets::with_slice(capsule);
        assert_eq!(b.get_varint().unwrap(), CAPSULE_DATAGRAM);
        let length = b.get_varint().unwrap() as usize;
        b.get_bytes(length).unwrap().to_vec()
    }

    #[test]
    fn the_h2_capsule_carries_the_bare_packet_that_the_cloudflare_edge_expects() {
        let packet = ip_packet();
        assert_eq!(capsule_value(&encode_datagram_capsule(&packet)), packet);
    }

    #[test]
    fn a_capsule_round_trips_through_the_receive_path() {
        let packet = ip_packet();
        let value = capsule_value(&encode_datagram_capsule(&packet));
        assert_eq!(strip_datagram_context(&value), Some(packet));
    }

    #[test]
    fn a_payload_that_does_carry_a_context_id_is_also_accepted() {
        let packet = ip_packet();
        let mut framed = vec![0u8];
        framed.extend_from_slice(&packet);
        assert_eq!(strip_datagram_context(&framed), Some(packet));
    }

    #[test]
    fn a_payload_that_is_not_an_ip_packet_is_rejected() {
        assert_eq!(strip_datagram_context(&[]), None);
        assert_eq!(strip_datagram_context(&[0x00, 0x01, 0x02]), None);
        assert_eq!(strip_datagram_context(&[0xff; 40]), None);
    }

    #[test]
    fn a_truncated_ipv6_packet_is_not_mistaken_for_a_datagram() {
        // Long enough to pass for IPv4, far short of the 40 byte IPv6 header.
        let mut truncated = vec![0u8; 24];
        truncated[0] = 0x60;
        assert_eq!(strip_datagram_context(&truncated), None);
    }

    #[test]
    fn the_h3_path_still_carries_the_context_id() {
        let packet = ip_packet();
        let h3 = encode_ip_datagram(8, &packet).expect("h3 encoding");
        let decoded = decode_ip_datagram(&h3, 8)
            .expect("h3 decoding")
            .expect("payload");
        assert_eq!(decoded, packet);
    }

    #[test]
    fn a_full_capsule_stream_parses_one_capsule_at_a_time() {
        let first = ip_packet();
        let mut second = ip_packet();
        second[16..20].copy_from_slice(&[8, 8, 8, 8]);

        let mut wire = encode_datagram_capsule(&first);
        wire.extend_from_slice(&encode_datagram_capsule(&second));

        let mut parser = CapsuleParser::new();
        // Byte at a time: a capsule that is not fully buffered yet must never
        // be reported as ready.
        let mut seen = Vec::new();
        for byte in wire {
            parser.push(&[byte]);
            while let Ok(Some(Capsule::Datagram(payload))) = parser.next() {
                seen.push(payload);
            }
        }

        assert_eq!(seen.len(), 2);
        assert_eq!(seen[0], first);
        assert_eq!(seen[1], second);
        assert!(!parser.is_desynced());
    }

    #[test]
    fn a_capsule_that_declares_an_impossible_length_desynchronises_the_parser() {
        let mut header = vec![0u8; 8];
        {
            let mut b = OctetsMut::with_slice(&mut header);
            b.put_varint(CAPSULE_DATAGRAM).unwrap();
            b.put_varint(500_000).unwrap();
        }

        let mut parser = CapsuleParser::new();
        parser.push(&header);

        assert!(matches!(parser.next(), Ok(None)));
        assert!(
            parser.is_desynced(),
            "an unsatisfiable length used to stall the parser forever"
        );
    }

    #[test]
    fn overflowing_the_parser_is_reported_instead_of_silently_resyncing() {
        let mut parser = CapsuleParser::new();
        parser.push(&vec![0u8; 300 * 1024]);
        assert!(parser.is_desynced());
        assert!(matches!(parser.next(), Ok(None)));
    }
}
