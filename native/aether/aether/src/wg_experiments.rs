//! Opt-in experiments, not a promise to bypass any particular filter.
//! Socket + detour have ONE lifetime. Never swap a naked UdpSocket and drop
//! its SOCKS UDP association while another task is still sending through it.
use arc_swap::ArcSwap;
use rand::RngExt;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::watch;

use crate::upstream::DetourGuard;
use crate::Result;

pub(super) fn enabled(name: &str) -> bool {
    std::env::var(name).is_ok_and(|v| v == "1")
}

pub(super) struct SocketPath {
    pub sock: Arc<UdpSocket>,
    pub peer: SocketAddr,
    // Kept until the LAST in-flight operation releases this path.
    _detour: DetourGuard,
    valid_rx: AtomicU64,
}

impl SocketPath {
    pub fn new(sock: Arc<UdpSocket>, peer: SocketAddr, detour: DetourGuard) -> Self {
        Self { sock, peer, _detour: detour, valid_rx: AtomicU64::new(0) }
    }

    pub fn authenticated(&self) {
        self.valid_rx.fetch_add(1, Ordering::Relaxed);
    }
}

pub(super) struct Paths {
    current: ArcSwap<SocketPath>,
    changed: watch::Sender<()>,
}

impl Paths {
    pub fn new(path: SocketPath) -> Self {
        let (changed, _) = watch::channel(());
        Self { current: ArcSwap::from_pointee(path), changed }
    }

    pub fn load(&self) -> Arc<SocketPath> {
        // An owned Arc, not an ArcSwap guard, crosses network awaits.
        self.current.load_full()
    }

    fn install(&self, path: Arc<SocketPath>) {
        self.current.store(path);
        self.changed.send_replace(());
    }

    pub fn subscribe(&self) -> watch::Receiver<()> {
        self.changed.subscribe()
    }

    pub async fn recv(
        &self,
        changed: &mut watch::Receiver<()>,
        buf: &mut [u8],
    ) -> std::io::Result<(usize, Arc<SocketPath>)> {
        loop {
            // Mark the generation before loading: a swap between these two
            // operations then either loads the new path or wakes changed().
            changed.borrow_and_update();
            let path = self.load();
            tokio::select! {
                biased;
                _ = changed.changed() => continue,
                read = path.sock.recv(buf) => return read.map(|n| (n, path)),
            }
        }
    }

    pub async fn send(&self, packet: &[u8]) -> std::io::Result<usize> {
        let path = self.load();
        path.sock.send(packet).await
    }

    /// A replacement is installed only after bind/connect/detour succeeded.
    /// It gets three seconds to return authenticated data, otherwise restore
    /// the old path. Health probes and ordinary traffic exercise the candidate.
    pub async fn rotate(&self) {
        let mut turn = 0usize;
        loop {
            tokio::time::sleep(Duration::from_secs(15)).await;
            let old = self.load();
            let peer = next_peer(old.peer, turn);
            turn = turn.wrapping_add(1);
            // Same egress/mark/upstream factory as initial connection. Android
            // launches the engine under the app UID excluded by TunFactory;
            // there is no per-fd protect() bridge in this subprocess design.
            let prepared = tokio::time::timeout(
                Duration::from_secs(3),
                crate::upstream::bind_via_upstream(peer),
            ).await;
            let (socket, _, detour) = match prepared {
                Ok(Ok(path)) => path,
                Ok(Err(e)) => {
                    log::debug!("[wg-experiment] hop preparation failed: {e}");
                    continue;
                }
                Err(_) => {
                    log::debug!("[wg-experiment] hop preparation timed out");
                    continue;
                }
            };
            super::tune_socket_buffers(&socket);
            let candidate = Arc::new(SocketPath::new(Arc::new(socket), peer, detour));
            self.install(candidate.clone());
            tokio::time::sleep(Duration::from_secs(3)).await;
            if candidate.valid_rx.load(Ordering::Relaxed) == 0 {
                self.install(old);
                log::info!("[wg-experiment] hop had no authenticated reply; rolled back");
            } else {
                log::info!("[wg-experiment] hop confirmed on port {}", peer.port());
            }
            // old/candidate guards drop only after all readers release them.
        }
    }
}

fn next_peer(mut peer: SocketAddr, turn: usize) -> SocketAddr {
    // Only known WARP ingress addresses may change destination port. A custom
    // or local endpoint keeps its destination and hops the source port only.
    let warp = super::WG_PREFIXES_V4.iter().chain(super::WG_PREFIXES_V6.iter())
        .any(|cidr| cidr.parse::<ipnet::IpNet>().is_ok_and(|net| net.contains(&peer.ip())));
    if warp {
        let ports = [2408, 500, 1701, 4500];
        let mut index = turn % ports.len();
        if ports[index] == peer.port() { index = (index + 1) % ports.len(); }
        peer.set_port(ports[index]);
    }
    peer
}

/// Max plaintext after padding: outer IPv6(40) + UDP(8) + WG(32) fits
/// within 1280 bytes. Large packets are unchanged, never truncated here.
const PADDED_PLAINTEXT_LIMIT: usize = 1200;
const MAX_EXTRA: usize = 128;

fn ip_len(packet: &[u8]) -> Option<usize> {
    match packet.first().map(|b| b >> 4) {
        Some(4) if packet.len() >= 20 => {
            let header = (packet[0] as usize & 15) * 4;
            let len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            (header >= 20 && header <= len && len == packet.len()).then_some(len)
        }
        Some(6) if packet.len() >= 40 => {
            let payload = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            // No IPv6 jumbograms in this experiment.
            (payload > 0 && payload + 40 == packet.len()).then_some(payload + 40)
        }
        _ => None,
    }
}

pub(super) fn pad<'a>(packet: &'a [u8], scratch: &'a mut Vec<u8>, on: bool) -> &'a [u8] {
    if !on { return packet; }
    let Some(len) = ip_len(packet) else { return packet; };
    let aligned = (len + 15) & !15;
    let room = PADDED_PLAINTEXT_LIMIT.saturating_sub(aligned).min(MAX_EXTRA) / 16;
    if room == 0 { return packet; }
    let blocks = rand::rng().random_range(1..=room);
    pad_to(packet, scratch, aligned + blocks * 16)
}

fn pad_to<'a>(packet: &[u8], scratch: &'a mut Vec<u8>, length: usize) -> &'a [u8] {
    scratch.clear();
    scratch.extend_from_slice(packet);
    // Standard zero padding INSIDE the authenticated ciphertext. Neither IP
    // length/checksum fields nor WG headers/tags are rewritten.
    scratch.resize(length, 0);
    scratch
}

pub(super) async fn rebound(peer: SocketAddr) -> Result<(UdpSocket, SocketAddr, DetourGuard)> {
    crate::upstream::bind_via_upstream(peer).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ipv4(len: usize) -> Vec<u8> {
        let mut p = vec![0; len]; p[0] = 0x45;
        p[2..4].copy_from_slice(&(len as u16).to_be_bytes()); p
    }

    #[test]
    fn padding_is_opt_in_bounded_and_preserves_headers() {
        let mut scratch = Vec::new();
        for len in [20, 31, 64, 1000, 1180, 1199, 1200, 1280, 1500] {
            let packet = ipv4(len);
            assert_eq!(pad(&packet, &mut scratch, false), &packet);
            for _ in 0..32 {
                let padded = pad(&packet, &mut scratch, true);
                assert_eq!(&padded[..len], &packet);
                if padded.len() > len {
                    assert_eq!(padded.len() % 16, 0);
                    assert!(padded.len() <= 1200);
                    assert!(padded.len() - len <= 143);
                    assert!(padded[len..].iter().all(|b| *b == 0));
                }
            }
        }
        for p in [vec![], vec![0; 8], vec![0x45; 20]] {
            assert_eq!(pad(&p, &mut scratch, true), &p);
        }
    }

    #[test]
    fn ipv6_payload_length_stays_unchanged() {
        let mut p = vec![0; 56]; p[0] = 0x60; p[5] = 16;
        let mut scratch = Vec::new();
        let padded = pad(&p, &mut scratch, true);
        assert!(padded.len() > p.len());
        assert_eq!(&padded[..p.len()], &p);
    }

    #[test]
    fn only_warp_destinations_rotate_ports() {
        let custom: SocketAddr = "203.0.113.2:51820".parse().unwrap();
        assert_eq!(next_peer(custom, 0), custom);
        let warp: SocketAddr = "162.159.192.1:2408".parse().unwrap();
        let next = next_peer(warp, 0);
        assert_eq!(next.ip(), warp.ip());
        assert_ne!(next.port(), warp.port());
    }

    #[tokio::test]
    async fn a_pending_receive_wakes_on_socket_swap() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer = server.local_addr().unwrap();
        let old = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        old.connect(peer).await.unwrap();
        let paths = Arc::new(Paths::new(SocketPath::new(Arc::new(old), peer, DetourGuard::default())));
        let receiver = paths.clone();
        let task = tokio::spawn(async move {
            let mut changed = receiver.subscribe();
            let mut buf = [0; 16];
            let (n, _) = receiver.recv(&mut changed, &mut buf).await.unwrap();
            buf[..n].to_vec()
        });
        tokio::task::yield_now().await;
        let new = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        new.connect(peer).await.unwrap();
        let address = new.local_addr().unwrap();
        paths.install(Arc::new(SocketPath::new(Arc::new(new), peer, DetourGuard::default())));
        server.send_to(b"new", address).await.unwrap();
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), task).await.unwrap().unwrap(), b"new");
    }

    #[test]
    fn encrypted_padding_round_trips_for_ipv4_and_ipv6() {
        use boringtun::noise::{Tunn, TunnResult};
        use boringtun::x25519::{PublicKey, StaticSecret};
        let a_secret = StaticSecret::from([1u8; 32]);
        let b_secret = StaticSecret::from([2u8; 32]);
        let a_public = PublicKey::from(&a_secret);
        let b_public = PublicKey::from(&b_secret);
        let mut a = Tunn::new(a_secret, b_public, None, None, 0, None);
        let mut b = Tunn::new(b_secret, a_public, None, None, 1, None);
        let mut ab = vec![0; 2048]; let mut bb = vec![0; 2048];
        let init = match a.encapsulate(&[], &mut ab) {
            TunnResult::WriteToNetwork(p) => p.to_vec(), other => panic!("{other:?}"),
        };
        let response = match b.decapsulate(None, &init, &mut bb) {
            TunnResult::WriteToNetwork(p) => p.to_vec(), other => panic!("{other:?}"),
        };
        let keepalive = match a.decapsulate(None, &response, &mut ab) {
            TunnResult::WriteToNetwork(p) => p.to_vec(), other => panic!("{other:?}"),
        };
        assert!(matches!(b.decapsulate(None, &keepalive, &mut bb), TunnResult::Done));
        let v4 = super::super::build_dataplane_probe("172.16.0.2".parse().unwrap());
        let mut v6 = vec![0; 56]; v6[0] = 0x60; v6[5] = 16;
        v6[6] = 59; v6[7] = 64; v6[23] = 1; v6[39] = 2;
        let mut scratch = Vec::new();
        for packet in [v4, v6] {
            for _ in 0..16 {
                let padded = pad(&packet, &mut scratch, true);
                assert!(padded.len() > packet.len());
                let encrypted = match a.encapsulate(padded, &mut ab) {
                    TunnResult::WriteToNetwork(p) => p.to_vec(), other => panic!("{other:?}"),
                };
                match b.decapsulate(None, &encrypted, &mut bb) {
                    TunnResult::WriteToTunnelV4(p, _) | TunnResult::WriteToTunnelV6(p, _) => assert_eq!(p, packet),
                    other => panic!("{other:?}"),
                }
            }
        }
    }
}
