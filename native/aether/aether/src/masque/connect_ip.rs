//! The CONNECT-IP request itself and the HTTP/3 datagram framing that carries
//! IP packets over it.

use octets::{Octets, OctetsMut};
use quiche::h3;

use crate::consts;
use crate::error::Result;

use super::capsule::{oct, varint_len};
use super::{IPV4_HEADER_LEN, IPV6_HEADER_LEN};

pub fn connect_ip_request(authority: &str, path: &str) -> Vec<h3::Header> {
    vec![
        h3::Header::new(b":method", b"CONNECT"),
        h3::Header::new(b":protocol", consts::CF_CONNECT_PROTOCOL.as_bytes()),
        h3::Header::new(b":scheme", b"https"),
        h3::Header::new(b":authority", authority.as_bytes()),
        h3::Header::new(b":path", path.as_bytes()),
        h3::Header::new(b"user-agent", b""),
        h3::Header::new(b"capsule-protocol", b"?1"),
    ]
}

/// HTTP/3 datagrams are addressed by quarter stream id (RFC 9297).
pub fn quarter_stream_id(stream_id: u64) -> u64 {
    stream_id / 4
}

pub fn encode_ip_datagram(stream_id: u64, ip_packet: &[u8]) -> Result<Vec<u8>> {
    let qsid = quarter_stream_id(stream_id);
    let ctx = consts::CONNECT_IP_CONTEXT_ID;

    let cap = varint_len(qsid) + varint_len(ctx) + ip_packet.len();
    let mut out = vec![0u8; cap];

    {
        let mut b = OctetsMut::with_slice(&mut out);
        b.put_varint(qsid).map_err(oct)?;
        b.put_varint(ctx).map_err(oct)?;
        b.put_bytes(ip_packet).map_err(oct)?;
    }

    Ok(out)
}

pub fn decode_ip_datagram(datagram: &[u8], expect_stream_id: u64) -> Result<Option<Vec<u8>>> {
    let mut b = Octets::with_slice(datagram);

    let qsid = b.get_varint().map_err(oct)?;
    if qsid != quarter_stream_id(expect_stream_id) {
        return Ok(None);
    }

    let ctx = b.get_varint().map_err(oct)?;
    if ctx != consts::CONNECT_IP_CONTEXT_ID {
        return Ok(None);
    }

    let rest = b.cap();
    let payload = b.get_bytes(rest).map_err(oct)?;
    Ok(Some(payload.to_vec()))
}

/// Unwraps a datagram capsule payload into the IP packet it carries.
///
/// Cloudflare's HTTP/2 edges send the bare packet while the HTTP/3 path
/// prefixes a context id, so both shapes are accepted - but only when what
/// comes out actually looks like an IP packet.
pub fn strip_datagram_context(payload: &[u8]) -> Option<Vec<u8>> {
    if payload.is_empty() {
        return None;
    }

    let mut b = Octets::with_slice(payload);
    if let Ok(ctx) = b.get_varint() {
        if ctx == consts::CONNECT_IP_CONTEXT_ID {
            let consumed = payload.len() - b.cap();
            let inner = &payload[consumed..];
            if looks_like_ip_packet(inner) {
                return Some(inner.to_vec());
            }
        }
    }

    if looks_like_ip_packet(payload) {
        return Some(payload.to_vec());
    }

    None
}

/// A cheap plausibility check: the version nibble plus enough bytes for that
/// version's fixed header.
fn looks_like_ip_packet(data: &[u8]) -> bool {
    match data.first().map(|first| first >> 4) {
        Some(4) => data.len() >= IPV4_HEADER_LEN,
        Some(6) => data.len() >= IPV6_HEADER_LEN,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_datagram_is_addressed_by_quarter_stream_id() {
        assert_eq!(quarter_stream_id(0), 0);
        assert_eq!(quarter_stream_id(4), 1);
        assert_eq!(quarter_stream_id(8), 2);
    }

    #[test]
    fn a_datagram_for_another_stream_is_ignored() {
        let packet = [0x45u8; 24];
        let encoded = encode_ip_datagram(8, &packet).expect("encode");
        assert_eq!(decode_ip_datagram(&encoded, 12).expect("decode"), None);
    }

    #[test]
    fn the_version_nibble_alone_is_not_enough() {
        assert!(!looks_like_ip_packet(&[0x45; 19]));
        assert!(looks_like_ip_packet(&[0x45; 20]));
        assert!(!looks_like_ip_packet(&[0x60; 39]));
        assert!(looks_like_ip_packet(&[0x60; 40]));
        assert!(!looks_like_ip_packet(&[0x35; 64]));
    }
}
