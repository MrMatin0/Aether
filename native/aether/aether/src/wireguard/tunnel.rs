//! The long lived WireGuard tunnel.
//!
//! Four tasks run it, and all four need the same handful of things. They share
//! one [`Shared`] instead of the pile of per-task clones this used to carry
//! (`sock_r`, `sock_w`, `sock_t`, `sock_h`, `tunn_r`, `tunn_w`, ...).

use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use parking_lot::Mutex as StdMutex;
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, Mutex};

use crate::aethernoize::{self, AetherNoizeConfig};
use crate::error::{AetherError, Result};

use super::framing::{
    inject_client_id, is_transient_socket_error, strip_client_id, MAX_PACKET, TIMER_TICK,
};
use super::probe::{dataplane_probe, send_dataplane_probe};

/// How often the tunnel checks that the peer is still answering.
const WG_HEALTHCHECK_INTERVAL: Duration = Duration::from_secs(3);

/// A run of per-datagram errors this long means the socket is not coming back.
const MAX_TRANSIENT_RECV_ERRORS: u32 = 64;
const TRANSIENT_RECV_BACKOFF: Duration = Duration::from_millis(50);

fn wg_stale_timeout() -> Duration {
    let secs = std::env::var("AETHER_WG_STALE_SECS")
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(10);
    Duration::from_secs(secs)
}

/// Aborts everything it holds when it goes out of scope, so one task ending
/// cannot leave the other three running against a dead socket.
struct TaskGuard(Vec<tokio::task::AbortHandle>);

impl Drop for TaskGuard {
    fn drop(&mut self) {
        for handle in self.0.drain(..) {
            handle.abort();
        }
    }
}

#[derive(Clone)]
pub struct WgConfig {
    pub local_private_key: [u8; 32],
    pub peer_public_key: [u8; 32],
    pub peer_endpoint: SocketAddr,
    pub local_ipv4: Ipv4Addr,
    pub local_ipv6: Ipv6Addr,
    pub client_id: [u8; 3],
    pub preshared_key: Option<[u8; 32]>,
    pub persistent_keepalive: Option<u16>,
    pub aethernoize: Arc<AetherNoizeConfig>,
}

/// A handshake that already succeeded, handed over so the caller does not pay
/// for a second one.
pub struct EstablishedSession {
    tunn: Arc<Mutex<Box<Tunn>>>,
    sock: Arc<UdpSocket>,
    peer: SocketAddr,
    client_id: [u8; 3],
}

impl EstablishedSession {
    pub(super) fn new(
        tunn: Tunn,
        sock: UdpSocket,
        peer: SocketAddr,
        client_id: [u8; 3],
    ) -> Self {
        Self {
            tunn: Arc::new(Mutex::new(Box::new(tunn))),
            sock: Arc::new(sock),
            peer,
            client_id,
        }
    }
}

pub struct WgTunnel {
    tunn: Arc<Mutex<Box<Tunn>>>,
    sock: Arc<UdpSocket>,
    peer: SocketAddr,
    inbound_tx: mpsc::Sender<Vec<u8>>,
    pub obf_sent: Arc<Mutex<bool>>,
    pub aethernoize: Arc<AetherNoizeConfig>,
    pub client_id: [u8; 3],
    pub local_ipv4: Ipv4Addr,
}

/// Everything the four tasks share.
struct Shared {
    tunn: Arc<Mutex<Box<Tunn>>>,
    sock: Arc<UdpSocket>,
    peer: SocketAddr,
    client_id: [u8; 3],
    local_ipv4: Ipv4Addr,
    aethernoize: Arc<AetherNoizeConfig>,
    /// When the peer last sent something that decrypted. This is the only
    /// evidence the tunnel is alive that cannot be faked by a middlebox.
    last_valid_rx: StdMutex<Instant>,
}

impl Shared {
    /// Stamps the client id into the reserved bytes and sends. Every outbound
    /// packet in this module goes through here.
    async fn send_wg(&self, pkt: &[u8]) {
        let mut framed = pkt.to_vec();
        inject_client_id(&mut framed, &self.client_id);
        if let Err(e) = self.sock.send(&framed).await {
            log::trace!("[wg] send failed: {e}");
        }
    }

    fn touch_rx(&self) {
        *self.last_valid_rx.lock() = Instant::now();
    }

    fn idle_for(&self) -> Duration {
        self.last_valid_rx.lock().elapsed()
    }
}

impl WgTunnel {
    pub async fn new(cfg: WgConfig, inbound_tx: mpsc::Sender<Vec<u8>>) -> Result<Self> {
        let (sock, _) = crate::upstream::bind_via_upstream(cfg.peer_endpoint).await?;

        let tunn = Tunn::new(
            StaticSecret::from(cfg.local_private_key),
            PublicKey::from(cfg.peer_public_key),
            cfg.preshared_key,
            cfg.persistent_keepalive,
            0,
            None,
        );

        Ok(Self {
            tunn: Arc::new(Mutex::new(Box::new(tunn))),
            sock: Arc::new(sock),
            peer: cfg.peer_endpoint,
            inbound_tx,
            obf_sent: Arc::new(Mutex::new(false)),
            aethernoize: cfg.aethernoize.clone(),
            client_id: cfg.client_id,
            local_ipv4: cfg.local_ipv4,
        })
    }

    /// Adopts a session that was already proven by the prober. The obfuscation
    /// preamble is marked as sent, because it was.
    pub fn from_established(
        session: EstablishedSession,
        aethernoize: Arc<AetherNoizeConfig>,
        inbound_tx: mpsc::Sender<Vec<u8>>,
        local_ipv4: Ipv4Addr,
    ) -> Self {
        Self {
            tunn: session.tunn,
            sock: session.sock,
            peer: session.peer,
            inbound_tx,
            obf_sent: Arc::new(Mutex::new(true)),
            aethernoize,
            client_id: session.client_id,
            local_ipv4,
        }
    }

    pub async fn run(self, outbound_rx: mpsc::Receiver<Vec<u8>>) -> Result<()> {
        let shared = Arc::new(Shared {
            tunn: self.tunn.clone(),
            sock: self.sock.clone(),
            peer: self.peer,
            client_id: self.client_id,
            local_ipv4: self.local_ipv4,
            aethernoize: self.aethernoize.clone(),
            last_valid_rx: StdMutex::new(Instant::now()),
        });

        let recv_task = tokio::spawn(recv_loop(shared.clone(), self.inbound_tx.clone()));
        let send_task = tokio::spawn(send_loop(
            shared.clone(),
            outbound_rx,
            self.obf_sent.clone(),
        ));
        let timer_task = tokio::spawn(timer_loop(shared.clone()));
        let health_task = tokio::spawn(health_loop(shared.clone()));

        // Whichever task ends first, the other three go with it.
        let _guard = TaskGuard(vec![
            recv_task.abort_handle(),
            send_task.abort_handle(),
            timer_task.abort_handle(),
            health_task.abort_handle(),
        ]);

        tokio::select! {
            _ = recv_task => {
                log::info!("wireguard recv task ended");
                Ok(())
            }
            _ = send_task => {
                log::info!("wireguard send task ended");
                Ok(())
            }
            _ = timer_task => {
                log::info!("wireguard timer task ended");
                Ok(())
            }
            r = health_task => match r {
                Ok(Ok(())) => Ok(()),
                Ok(Err(e)) => Err(e),
                Err(e) => Err(AetherError::Other(format!("health task panicked: {e}"))),
            },
        }
    }
}

/// Peer -> tun. Decrypts what arrives and hands IP packets up the stack.
async fn recv_loop(shared: Arc<Shared>, inbound_tx: mpsc::Sender<Vec<u8>>) {
    let mut buf = vec![0u8; MAX_PACKET];
    let mut tmp = vec![0u8; MAX_PACKET];
    let mut transient_errors = 0u32;

    loop {
        let n = match shared.sock.recv(&mut buf).await {
            Ok(n) => {
                transient_errors = 0;
                n
            },
            Err(e) => {
                if !is_transient_socket_error(&e) {
                    log::error!("recv error: {e}");
                    return;
                }
                transient_errors += 1;
                if transient_errors > MAX_TRANSIENT_RECV_ERRORS {
                    log::error!(
                        "recv error: {e}; giving up after {transient_errors} consecutive transient failures"
                    );
                    return;
                }
                log::debug!("transient recv error: {e}; keeping the tunnel and retrying");
                tokio::time::sleep(TRANSIENT_RECV_BACKOFF).await;
                continue;
            },
        };

        strip_client_id(&mut buf[..n]);

        let mut tunn = shared.tunn.lock().await;
        match tunn.decapsulate(None, &buf[..n], &mut tmp) {
            TunnResult::Done => {
                shared.touch_rx();
            },
            TunnResult::Err(e) => {
                log::trace!("decapsulate error: {e:?}");
            },
            TunnResult::WriteToNetwork(pkt) => {
                let pkt = pkt.to_vec();
                drop(tunn);
                shared.touch_rx();
                shared.send_wg(&pkt).await;
            },
            TunnResult::WriteToTunnelV4(pkt, _) | TunnResult::WriteToTunnelV6(pkt, _) => {
                let pkt = pkt.to_vec();
                drop(tunn);
                shared.touch_rx();
                // A closed inbound channel means nothing is reading the tunnel
                // any more. This used to be ignored, which left the tunnel
                // looking healthy while it decrypted packets into the void.
                if inbound_tx.send(pkt).await.is_err() {
                    log::info!("[wg] inbound consumer is gone; ending the recv loop");
                    return;
                }
            },
        }
    }
}

/// tun -> peer. Encrypts what the stack produces, with the obfuscation
/// preamble in front of the very first packet.
async fn send_loop(
    shared: Arc<Shared>,
    mut outbound_rx: mpsc::Receiver<Vec<u8>>,
    obf_sent: Arc<Mutex<bool>>,
) {
    let mut out_buf = vec![0u8; MAX_PACKET];
    let mut post_hs_junk_sent = false;

    while let Some(ip_packet) = outbound_rx.recv().await {
        let mut tunn = shared.tunn.lock().await;
        let pkt = match tunn.encapsulate(&ip_packet, &mut out_buf) {
            TunnResult::WriteToNetwork(pkt) => pkt.to_vec(),
            TunnResult::Err(e) => {
                log::trace!("encapsulate error: {e:?}");
                continue;
            },
            _ => continue,
        };
        drop(tunn);

        {
            let mut sent = obf_sent.lock().await;
            if !*sent && shared.aethernoize.is_enabled() {
                *sent = true;
                drop(sent);
                aethernoize::apply_obfuscation(&shared.sock, shared.peer, &shared.aethernoize)
                    .await;
            }
        }

        shared.send_wg(&pkt).await;

        // Post-handshake junk goes out once, not on every data packet.
        if shared.aethernoize.jc_after_hs > 0 && !post_hs_junk_sent {
            post_hs_junk_sent = true;
            aethernoize::send_post_handshake_junk(&shared.sock, shared.peer, &shared.aethernoize)
                .await;
        }
    }
}

/// Gives boringtun a chance to run its own timers (rekey, keepalive).
async fn timer_loop(shared: Arc<Shared>) {
    let mut interval = tokio::time::interval(TIMER_TICK);
    let mut tmp = vec![0u8; MAX_PACKET];

    loop {
        interval.tick().await;

        let mut tunn = shared.tunn.lock().await;
        let pkt = match tunn.update_timers(&mut tmp) {
            TunnResult::WriteToNetwork(pkt) => pkt.to_vec(),
            _ => continue,
        };
        drop(tunn);

        if shared.aethernoize.is_enabled() {
            aethernoize::send_keepalive_junk(&shared.sock, &shared.aethernoize).await;
        }
        shared.send_wg(&pkt).await;
    }
}

/// Watches the data plane. A handshake that stays up while traffic stops is
/// the failure mode this exists for.
async fn health_loop(shared: Arc<Shared>) -> Result<()> {
    let stale_after = wg_stale_timeout();
    let probe = dataplane_probe(shared.local_ipv4);
    let mut out_buf = vec![0u8; MAX_PACKET];
    let mut interval = tokio::time::interval(WG_HEALTHCHECK_INTERVAL);

    loop {
        interval.tick().await;

        let idle = shared.idle_for();
        if idle >= stale_after {
            log::warn!(
                "[wg] no valid data from peer {} in {idle:?}; tunnel considered dead",
                shared.peer
            );
            return Err(AetherError::Other(
                "wireguard tunnel stale: no valid data from peer".into(),
            ));
        }

        let mut tunn = shared.tunn.lock().await;
        if let Err(e) = send_dataplane_probe(
            &shared.sock,
            &mut tunn,
            &shared.client_id,
            &probe,
            &mut out_buf,
        )
        .await
        {
            log::trace!("[wg] health probe send failed: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_stale_timeout_falls_back_to_ten_seconds() {
        std::env::remove_var("AETHER_WG_STALE_SECS");
        assert_eq!(wg_stale_timeout(), Duration::from_secs(10));

        std::env::set_var("AETHER_WG_STALE_SECS", " 25 ");
        assert_eq!(wg_stale_timeout(), Duration::from_secs(25));

        // Zero would mean "declare the tunnel dead immediately", and garbage
        // must not silently do the same.
        std::env::set_var("AETHER_WG_STALE_SECS", "0");
        assert_eq!(wg_stale_timeout(), Duration::from_secs(10));
        std::env::set_var("AETHER_WG_STALE_SECS", "soon");
        assert_eq!(wg_stale_timeout(), Duration::from_secs(10));
        std::env::remove_var("AETHER_WG_STALE_SECS");
    }

    #[test]
    fn the_health_check_runs_several_times_before_a_tunnel_is_declared_stale() {
        std::env::remove_var("AETHER_WG_STALE_SECS");
        assert!(
            WG_HEALTHCHECK_INTERVAL * 3 <= wg_stale_timeout(),
            "one dropped probe must not be enough to kill a working tunnel"
        );
    }
}
