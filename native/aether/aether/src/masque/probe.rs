//! The data-plane probe packet.
//!
//! Both transports need the same thing to prove a tunnel forwards traffic: a
//! real IP packet with a real answer. WireGuard's health check and MASQUE's
//! validation used to carry byte-identical copies of this builder, an IPv4
//! header writer and a checksum each; this is the only copy.

use std::net::Ipv4Addr;

use rand::RngExt;

use super::IPV4_HEADER_LEN;

const UDP_HEADER_LEN: usize = 8;
const IPV4_CHECKSUM_AT: usize = 10;
const PROTO_UDP: u8 = 17;
const PROBE_TTL: u8 = 64;
const DNS_PORT: u16 = 53;
/// A resolver that is reachable from anywhere the tunnel could plausibly land,
/// and that is not the tunnel provider's own.
const PROBE_RESOLVER: Ipv4Addr = Ipv4Addr::new(8, 8, 8, 8);
const PROBE_LABELS: [&str; 2] = ["cloudflare", "com"];
const EPHEMERAL_PORTS: std::ops::Range<u16> = 20000..60000;

/// An IPv4/UDP DNS query for `cloudflare.com`, sourced from `src`.
///
/// The UDP checksum is left zero, which IPv4 explicitly permits and every
/// resolver on the path accepts.
pub fn build_dns_probe_packet(src: Ipv4Addr) -> Vec<u8> {
    let dns = dns_query();
    let udp_len = UDP_HEADER_LEN + dns.len();
    let total_len = IPV4_HEADER_LEN + udp_len;

    let mut pkt = Vec::with_capacity(total_len);

    // IPv4 header.
    pkt.push(0x45); // version 4, five word header
    pkt.push(0x00); // dscp / ecn
    pkt.extend_from_slice(&(total_len as u16).to_be_bytes());
    pkt.extend_from_slice(&rand::random::<u16>().to_be_bytes()); // identification
    pkt.extend_from_slice(&[0x00, 0x00]); // flags, fragment offset
    pkt.push(PROBE_TTL);
    pkt.push(PROTO_UDP);
    pkt.extend_from_slice(&[0x00, 0x00]); // checksum, filled in below
    pkt.extend_from_slice(&src.octets());
    pkt.extend_from_slice(&PROBE_RESOLVER.octets());

    let checksum = ipv4_header_checksum(&pkt[..IPV4_HEADER_LEN]);
    pkt[IPV4_CHECKSUM_AT..IPV4_CHECKSUM_AT + 2].copy_from_slice(&checksum.to_be_bytes());

    // UDP header.
    let sport: u16 = rand::rng().random_range(EPHEMERAL_PORTS);
    pkt.extend_from_slice(&sport.to_be_bytes());
    pkt.extend_from_slice(&DNS_PORT.to_be_bytes());
    pkt.extend_from_slice(&(udp_len as u16).to_be_bytes());
    pkt.extend_from_slice(&[0x00, 0x00]); // checksum, optional over IPv4

    pkt.extend_from_slice(&dns);
    pkt
}

/// A single recursive A query, with a random transaction id so two probes are
/// never identical on the wire.
fn dns_query() -> Vec<u8> {
    let mut q = Vec::with_capacity(32);
    q.extend_from_slice(&rand::random::<u16>().to_be_bytes()); // transaction id
    q.extend_from_slice(&[0x01, 0x00]); // standard query, recursion desired
    q.extend_from_slice(&[0x00, 0x01]); // one question
    q.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]); // no answers, no ns, no additional
    for label in PROBE_LABELS {
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0x00); // root label
    q.extend_from_slice(&[0x00, 0x01]); // type A
    q.extend_from_slice(&[0x00, 0x01]); // class IN
    q
}

/// One's complement sum over an IPv4 header, per RFC 1071.
fn ipv4_header_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i + 1 < header.len() {
        sum += u16::from_be_bytes([header[i], header[i + 1]]) as u32;
        i += 2;
    }
    if i < header.len() {
        sum += (header[i] as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_probe_is_a_well_formed_ipv4_udp_packet() {
        let src: Ipv4Addr = "172.16.0.2".parse().unwrap();
        let pkt = build_dns_probe_packet(src);

        assert_eq!(pkt[0], 0x45);
        assert_eq!(pkt[9], PROTO_UDP);
        assert_eq!(
            u16::from_be_bytes([pkt[2], pkt[3]]) as usize,
            pkt.len(),
            "total length must match the packet"
        );
        assert_eq!(&pkt[12..16], &src.octets());
        assert_eq!(&pkt[16..20], &PROBE_RESOLVER.octets());
        assert_eq!(
            u16::from_be_bytes([pkt[22], pkt[23]]),
            DNS_PORT,
            "destination port"
        );
        assert_eq!(
            u16::from_be_bytes([pkt[24], pkt[25]]) as usize,
            pkt.len() - IPV4_HEADER_LEN,
            "udp length must cover header plus payload"
        );
    }

    #[test]
    fn a_receiver_validating_the_header_checksum_accepts_the_probe() {
        let pkt = build_dns_probe_packet("10.0.0.1".parse().unwrap());
        // Summing a header that already carries its checksum yields zero.
        assert_eq!(ipv4_header_checksum(&pkt[..IPV4_HEADER_LEN]), 0);
    }

    #[test]
    fn two_probes_do_not_look_alike_on_the_wire() {
        let src: Ipv4Addr = "10.0.0.1".parse().unwrap();
        let a = build_dns_probe_packet(src);
        let b = build_dns_probe_packet(src);
        assert_ne!(a, b, "the transaction id and source port are randomised");
    }
}
