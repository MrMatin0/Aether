//! Capsules in and out of an established CONNECT-IP stream.

use std::net::IpAddr;

use bytes::Bytes;
use tokio::sync::mpsc;

use crate::error::{AetherError, Result};
use crate::masque::{self, Capsule, CapsuleParser};
use crate::quic::AssignedAddr;

/// Aborts the task it holds when dropped.
///
/// The connection driver has to die with the function that spawned it on EVERY
/// path, including the `?` ones, which is exactly what hand written abort calls
/// kept missing.
pub(super) struct AbortOnDrop(pub(super) tokio::task::JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// What one pass over the inbound capsule stream produced.
#[derive(Debug, Default, Clone, Copy)]
pub(super) struct Drained {
    /// At least one IP packet reached the tun side.
    pub(super) delivered: bool,
    /// Nothing is reading the tunnel any more, so there is no point running it.
    pub(super) inbound_closed: bool,
}

/// Wraps an IP packet in a datagram capsule and sends it.
pub(super) async fn send_ip_packet(
    send: &mut h2::SendStream<Bytes>,
    ip_packet: &[u8],
) -> Result<()> {
    send_capsule(
        send,
        Bytes::from(masque::encode_datagram_capsule(ip_packet)),
    )
    .await
}

/// Half-closes the stream. Best effort: every caller is already on its way out.
pub(super) fn close_stream(send: &mut h2::SendStream<Bytes>) {
    let _ = send.send_data(Bytes::new(), true);
}

async fn send_capsule(send: &mut h2::SendStream<Bytes>, data: Bytes) -> Result<()> {
    let len = data.len();
    if len == 0 {
        return Ok(());
    }

    // A capsule is sent whole or not at all: half a capsule desynchronises the
    // peer's parser.
    send.reserve_capacity(len);
    while send.capacity() < len {
        match futures::future::poll_fn(|cx| send.poll_capacity(cx)).await {
            Some(Ok(_)) => {},
            Some(Err(e)) => return Err(AetherError::Masque(format!("h2 capacity: {e}"))),
            None => return Err(AetherError::Masque("h2 stream closed".into())),
        }
    }

    send.send_data(data, false)
        .map_err(|e| AetherError::Masque(format!("h2 send_data: {e}")))?;
    Ok(())
}

/// Parses everything currently buffered and delivers it.
///
/// A parse error is not fatal on its own: the capsule was consumed whole, so
/// the stream is still aligned. The caller checks
/// [`CapsuleParser::is_desynced`] for the case that is.
pub(super) fn drain_capsules(
    capsules: &mut CapsuleParser,
    inbound_tx: &mpsc::Sender<Vec<u8>>,
    addr_tx: &Option<mpsc::Sender<AssignedAddr>>,
) -> Drained {
    let mut out = Drained::default();

    loop {
        match capsules.next() {
            Ok(Some(Capsule::Datagram(payload))) => {
                let Some(pkt) = masque::strip_datagram_context(&payload) else {
                    log::trace!("[h2] discarding a datagram that is not an ip packet");
                    continue;
                };
                out.delivered = true;
                match inbound_tx.try_send(pkt) {
                    Ok(()) => {},
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        log::trace!("[h2] inbound queue full, dropping datagram");
                    },
                    Err(mpsc::error::TrySendError::Closed(_)) => {
                        out.inbound_closed = true;
                        return out;
                    },
                }
            },
            Ok(Some(Capsule::AddressAssign(addrs))) => {
                for a in addrs {
                    let Some(ip) = bytes_to_ip(a.ip_version, &a.address) else {
                        continue;
                    };
                    log::info!("[h2] edge assigned {ip}/{}", a.prefix_len);
                    if let Some(tx) = addr_tx {
                        let _ = tx.try_send(AssignedAddr {
                            ip,
                            prefix: a.prefix_len,
                        });
                    }
                }
            },
            Ok(Some(Capsule::RouteAdvertisement(routes))) => {
                log::info!("[h2] received {} route advertisements", routes.len());
            },
            Ok(Some(_)) => {},
            Ok(None) => break,
            Err(e) => {
                log::trace!("[h2] capsule parse: {e}");
                break;
            },
        }
    }

    out
}

fn bytes_to_ip(version: u8, bytes: &[u8]) -> Option<IpAddr> {
    match version {
        4 if bytes.len() == 4 => Some(IpAddr::V4([bytes[0], bytes[1], bytes[2], bytes[3]].into())),
        6 if bytes.len() == 16 => {
            let mut b = [0u8; 16];
            b.copy_from_slice(bytes);
            Some(IpAddr::V6(b.into()))
        },
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_assigned_address_is_read_for_both_families() {
        assert_eq!(
            bytes_to_ip(4, &[10, 0, 0, 1]),
            Some("10.0.0.1".parse().unwrap())
        );
        assert_eq!(
            bytes_to_ip(6, &[0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]),
            Some("2606:4700::1".parse().unwrap())
        );
    }

    #[test]
    fn an_address_of_the_wrong_length_is_refused_instead_of_panicking() {
        assert_eq!(bytes_to_ip(4, &[10, 0, 0]), None);
        assert_eq!(bytes_to_ip(6, &[0; 4]), None);
        assert_eq!(bytes_to_ip(9, &[10, 0, 0, 1]), None);
        assert_eq!(bytes_to_ip(4, &[]), None);
    }

    #[tokio::test]
    async fn a_closed_inbound_channel_is_reported_instead_of_swallowed() {
        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(4);
        drop(inbound_rx);

        let mut capsules = CapsuleParser::new();
        capsules.push(&masque::encode_datagram_capsule(&[0x45u8; 24]));

        let drained = drain_capsules(&mut capsules, &inbound_tx, &None);
        assert!(drained.inbound_closed, "the tunnel has nowhere to deliver");
    }

    #[tokio::test]
    async fn a_delivered_packet_is_reported_as_a_data_plane_confirmation() {
        let (inbound_tx, mut inbound_rx) = mpsc::channel::<Vec<u8>>(4);

        let packet = [0x45u8; 24];
        let mut capsules = CapsuleParser::new();
        capsules.push(&masque::encode_datagram_capsule(&packet));

        let drained = drain_capsules(&mut capsules, &inbound_tx, &None);
        assert!(drained.delivered);
        assert!(!drained.inbound_closed);
        assert_eq!(inbound_rx.recv().await, Some(packet.to_vec()));
    }

    #[tokio::test]
    async fn a_capsule_that_is_not_an_ip_packet_is_dropped_not_delivered() {
        let (inbound_tx, _inbound_rx) = mpsc::channel::<Vec<u8>>(4);

        let mut capsules = CapsuleParser::new();
        capsules.push(&masque::encode_datagram_capsule(&[0xff, 0xee, 0xdd]));

        let drained = drain_capsules(&mut capsules, &inbound_tx, &None);
        assert!(!drained.delivered);
    }
}
