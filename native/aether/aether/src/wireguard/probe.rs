//! Pushing a real IP packet through a tunnel.
//!
//! A completed handshake proves nothing: plenty of edges answer the control
//! plane and then drop everything. Both the verifier and the running tunnel's
//! health check send this and wait for an answer.

use std::net::Ipv4Addr;

use boringtun::noise::{Tunn, TunnResult};
use tokio::net::UdpSocket;

use crate::error::{AetherError, Result};

use super::framing::inject_client_id;

/// The packet itself comes from [`crate::masque::build_dns_probe_packet`].
///
/// Both transports need the exact same synthetic DNS query, and keeping a
/// second IPv4 header builder plus checksum in this file is precisely how the
/// two drifted apart.
pub(super) fn dataplane_probe(src: Ipv4Addr) -> Vec<u8> {
    crate::masque::build_dns_probe_packet(src)
}

/// Encapsulates `probe` and puts it on the wire. A tunnel that is not ready to
/// carry data yet is not an error, it just produces nothing to send.
pub(super) async fn send_dataplane_probe(
    sock: &UdpSocket,
    tunn: &mut Tunn,
    client_id: &[u8; 3],
    probe: &[u8],
    out_buf: &mut [u8],
) -> Result<()> {
    match tunn.encapsulate(probe, out_buf) {
        TunnResult::WriteToNetwork(pkt) => {
            let mut framed = pkt.to_vec();
            inject_client_id(&mut framed, client_id);
            sock.send(&framed).await?;
        },
        TunnResult::Err(e) => {
            return Err(AetherError::Other(format!("dataplane encap: {e:?}")));
        },
        _ => {},
    }
    Ok(())
}
