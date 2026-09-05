//! The HTTP capsule wire format (RFC 9297) as Cloudflare's CONNECT-IP edges
//! speak it, plus an incremental parser for a capsule stream.

use octets::{Octets, OctetsMut};

use crate::error::{AetherError, Result};

pub const CAPSULE_ADDRESS_ASSIGN: u64 = 0x01;
pub const CAPSULE_ADDRESS_REQUEST: u64 = 0x02;
pub const CAPSULE_ROUTE_ADVERTISEMENT: u64 = 0x03;
pub const CAPSULE_DATAGRAM: u64 = 0x00;

/// A capsule carries a single IP packet, so nothing legitimate comes anywhere
/// near this. Crossing it does not mean "slow peer", it means the stream is no
/// longer capsule aligned - and a byte stream that lost its framing cannot be
/// resynchronised by guessing.
const MAX_CAPSULE_BUF: usize = 256 * 1024;

#[derive(Debug, Clone)]
pub struct AssignedAddress {
    pub request_id: u64,
    pub ip_version: u8,
    pub address: Vec<u8>,
    pub prefix_len: u8,
}

#[derive(Debug, Clone)]
pub struct RouteAdvertisement {
    pub ip_version: u8,
    pub start: Vec<u8>,
    pub end: Vec<u8>,
    pub protocol: u8,
}

#[derive(Debug, Clone)]
pub enum Capsule {
    AddressAssign(Vec<AssignedAddress>),
    AddressRequest,
    Datagram(Vec<u8>),
    RouteAdvertisement(Vec<RouteAdvertisement>),
    Unknown { kind: u64, payload: Vec<u8> },
}

/// Width of `v` encoded as an HTTP/QUIC variable length integer.
pub(super) fn varint_len(v: u64) -> usize {
    if v < 64 {
        1
    } else if v < 16_384 {
        2
    } else if v < 1_073_741_824 {
        4
    } else {
        8
    }
}

pub(super) fn oct(e: octets::BufferTooShortError) -> AetherError {
    AetherError::Capsule(e.to_string())
}

/// Address width for an IP version byte, rejecting anything that is neither.
fn address_len(ip_version: u8) -> Result<usize> {
    match ip_version {
        4 => Ok(4),
        6 => Ok(16),
        _ => Err(AetherError::Capsule(format!("bad ip version {ip_version}"))),
    }
}

pub fn encode_capsule(kind: u64, value: &[u8]) -> Vec<u8> {
    let cap = varint_len(kind) + varint_len(value.len() as u64) + value.len();
    let mut out = vec![0u8; cap];
    {
        let mut b = OctetsMut::with_slice(&mut out);
        let _ = b.put_varint(kind);
        let _ = b.put_varint(value.len() as u64);
        let _ = b.put_bytes(value);
    }
    out
}

pub fn encode_address_request(request_id: u64, ip_version: u8, prefix_len: u8) -> Vec<u8> {
    let value_len = varint_len(request_id) + 1 + 1;
    let mut value = vec![0u8; value_len];
    {
        let mut b = OctetsMut::with_slice(&mut value);
        let _ = b.put_varint(request_id);
        let _ = b.put_u8(ip_version);
        let _ = b.put_u8(prefix_len);
    }
    encode_capsule(CAPSULE_ADDRESS_REQUEST, &value)
}

pub fn encode_datagram_capsule(ip_packet: &[u8]) -> Vec<u8> {
    encode_capsule(CAPSULE_DATAGRAM, ip_packet)
}

/// Reassembles capsules from a stream that arrives in arbitrary chunks.
pub struct CapsuleParser {
    buf: Vec<u8>,
    desynced: bool,
}

impl CapsuleParser {
    pub fn new() -> Self {
        Self {
            buf: Vec::new(),
            desynced: false,
        }
    }

    pub fn push(&mut self, data: &[u8]) {
        if self.desynced {
            return;
        }
        if self.buf.len().saturating_add(data.len()) > MAX_CAPSULE_BUF {
            self.desync(format!(
                "{} buffered bytes without a complete capsule",
                self.buf.len().saturating_add(data.len())
            ));
            return;
        }
        self.buf.extend_from_slice(data);
    }

    /// True once the stream lost capsule alignment. Nothing further will ever
    /// be parsed, so the caller should tear the connection down rather than
    /// keep reading from it.
    pub fn is_desynced(&self) -> bool {
        self.desynced
    }

    fn desync(&mut self, reason: String) {
        if !self.desynced {
            log::warn!("capsule stream lost frame alignment: {reason}");
        }
        self.desynced = true;
        self.buf.clear();
    }

    pub fn next(&mut self) -> Result<Option<Capsule>> {
        if self.desynced {
            return Ok(None);
        }

        // Read the header without holding a borrow on the buffer, so the
        // desync and drain paths below can take it mutably.
        let header = {
            let mut b = Octets::with_slice(&self.buf);
            let kind = b.get_varint().ok();
            let len = b.get_varint().ok();
            match (kind, len) {
                (Some(kind), Some(len)) => Some((kind, len as usize, b.off())),
                _ => None,
            }
        };

        let Some((kind, len, header_len)) = header else {
            return Ok(None);
        };

        if len > MAX_CAPSULE_BUF {
            // This can never be satisfied, so waiting for it would stall the
            // parser for the lifetime of the connection.
            self.desync(format!(
                "capsule kind {kind} declares {len} bytes, over the {MAX_CAPSULE_BUF} byte limit"
            ));
            return Ok(None);
        }

        let total = header_len + len;
        if self.buf.len() < total {
            return Ok(None);
        }

        let value = self.buf[header_len..total].to_vec();
        self.buf.drain(0..total);

        // A malformed capsule BODY is not a framing failure: the capsule was
        // consumed whole, so the stream stays aligned and the caller may keep
        // going after logging it.
        let capsule = match kind {
            CAPSULE_ADDRESS_ASSIGN => Capsule::AddressAssign(parse_address_assign(&value)?),
            CAPSULE_ADDRESS_REQUEST => Capsule::AddressRequest,
            CAPSULE_ROUTE_ADVERTISEMENT => {
                Capsule::RouteAdvertisement(parse_route_advertisement(&value)?)
            },
            CAPSULE_DATAGRAM => Capsule::Datagram(value),
            other => Capsule::Unknown {
                kind: other,
                payload: value,
            },
        };

        Ok(Some(capsule))
    }
}

impl Default for CapsuleParser {
    fn default() -> Self {
        Self::new()
    }
}

fn parse_address_assign(value: &[u8]) -> Result<Vec<AssignedAddress>> {
    let mut b = Octets::with_slice(value);
    let mut out = Vec::new();

    while b.cap() > 0 {
        let request_id = b.get_varint().map_err(oct)?;
        let ip_version = b.get_u8().map_err(oct)?;
        let address = b.get_bytes(address_len(ip_version)?).map_err(oct)?.to_vec();
        let prefix_len = b.get_u8().map_err(oct)?;

        out.push(AssignedAddress {
            request_id,
            ip_version,
            address,
            prefix_len,
        });
    }

    Ok(out)
}

fn parse_route_advertisement(value: &[u8]) -> Result<Vec<RouteAdvertisement>> {
    let mut b = Octets::with_slice(value);
    let mut out = Vec::new();

    while b.cap() > 0 {
        let ip_version = b.get_u8().map_err(oct)?;
        let addr_len = address_len(ip_version)?;
        let start = b.get_bytes(addr_len).map_err(oct)?.to_vec();
        let end = b.get_bytes(addr_len).map_err(oct)?.to_vec();
        let protocol = b.get_u8().map_err(oct)?;

        out.push(RouteAdvertisement {
            ip_version,
            start,
            end,
            protocol,
        });
    }

    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_widths_match_the_quic_encoding() {
        assert_eq!(varint_len(0), 1);
        assert_eq!(varint_len(63), 1);
        assert_eq!(varint_len(64), 2);
        assert_eq!(varint_len(16_383), 2);
        assert_eq!(varint_len(16_384), 4);
        assert_eq!(varint_len(1_073_741_823), 4);
        assert_eq!(varint_len(1_073_741_824), 8);
    }

    #[test]
    fn an_unknown_ip_version_is_rejected_instead_of_being_read_as_bytes() {
        assert!(address_len(4).is_ok());
        assert!(address_len(6).is_ok());
        assert!(address_len(0).is_err());
        assert!(address_len(5).is_err());
    }

    #[test]
    fn an_address_assign_capsule_survives_a_round_trip() {
        let mut value = Vec::new();
        value.push(0x01); // request id 1, single byte varint
        value.push(4);
        value.extend_from_slice(&[10, 0, 0, 2]);
        value.push(32);

        let wire = encode_capsule(CAPSULE_ADDRESS_ASSIGN, &value);
        let mut parser = CapsuleParser::new();
        parser.push(&wire);

        match parser.next().expect("parse").expect("capsule") {
            Capsule::AddressAssign(addrs) => {
                assert_eq!(addrs.len(), 1);
                assert_eq!(addrs[0].request_id, 1);
                assert_eq!(addrs[0].address, vec![10, 0, 0, 2]);
                assert_eq!(addrs[0].prefix_len, 32);
            },
            other => panic!("unexpected capsule {other:?}"),
        }
    }

    #[test]
    fn a_malformed_body_does_not_desynchronise_the_stream() {
        let mut wire = encode_capsule(CAPSULE_ADDRESS_ASSIGN, &[0x01, 9, 1, 2, 3]);
        wire.extend_from_slice(&encode_datagram_capsule(&[0x45; 24]));

        let mut parser = CapsuleParser::new();
        parser.push(&wire);

        assert!(parser.next().is_err(), "ip version 9 is not a thing");
        assert!(!parser.is_desynced());
        assert!(matches!(
            parser.next().expect("parse"),
            Some(Capsule::Datagram(_))
        ));
    }

    #[test]
    fn an_unknown_capsule_kind_is_handed_back_untouched() {
        let wire = encode_capsule(0x7f, &[1, 2, 3]);
        let mut parser = CapsuleParser::new();
        parser.push(&wire);

        match parser.next().expect("parse").expect("capsule") {
            Capsule::Unknown { kind, payload } => {
                assert_eq!(kind, 0x7f);
                assert_eq!(payload, vec![1, 2, 3]);
            },
            other => panic!("unexpected capsule {other:?}"),
        }
    }
}
