//! Proving ONE endpoint is usable.
//!
//! Two stages, because they fail for different reasons: the handshake proves
//! the edge is a WARP edge and accepts these keys, and the data-plane check
//! proves it actually forwards packets afterwards.

use std::net::{Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use tokio::net::UdpSocket;

use crate::aethernoize::{self, AetherNoizeConfig};
use crate::error::{AetherError, Result};

use super::framing::{inject_client_id, strip_client_id, MAX_PACKET, TIMER_TICK};
use super::probe::{dataplane_probe, send_dataplane_probe};
use super::tunnel::EstablishedSession;

/// A handshake init is a single UDP datagram and filtering middleboxes eat
/// them, so it is retransmitted twice before an endpoint is written off.
const VERIFY_RETRY_DELAYS: [Duration; 2] = [
    Duration::from_millis(750),
    Duration::from_millis(2_000),
];

const DEFAULT_KEEPALIVE_SECS: u16 = 25;

/// Two confirmed round trips, because a single reply can come from a
/// middlebox answering on the edge's behalf.
const DATAPLANE_REQUIRED_SUCCESSES: u32 = 2;

/// Minimum spacing between two data-plane probes.
const DATAPLANE_PROBE_GAP: Duration = Duration::from_millis(600);

/// How long to wait for an answer before assuming the probe was dropped.
const DATAPLANE_RESEND_AFTER: Duration = Duration::from_millis(700);

fn dataplane_check_enabled() -> bool {
    std::env::var("AETHER_WG_NO_DATA_CHECK").is_err()
}

pub async fn verify_endpoint(
    peer: SocketAddr,
    private_key: [u8; 32],
    peer_public: [u8; 32],
    client_id: [u8; 3],
    local_ipv4: Ipv4Addr,
    aethernoize: &AetherNoizeConfig,
    timeout: Duration,
    keepalive: Option<u16>,
) -> Result<Duration> {
    let (elapsed, _session) = verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        client_id,
        local_ipv4,
        aethernoize,
        timeout,
        keepalive,
    )
    .await?;
    Ok(elapsed)
}

/// Same as [`verify_endpoint`], but hands back the live session so a caller
/// can keep using the tunnel it just paid for instead of handshaking twice.
pub async fn verify_endpoint_keep_session(
    peer: SocketAddr,
    private_key: [u8; 32],
    peer_public: [u8; 32],
    client_id: [u8; 3],
    local_ipv4: Ipv4Addr,
    aethernoize: &AetherNoizeConfig,
    timeout: Duration,
    keepalive: Option<u16>,
) -> Result<(Duration, EstablishedSession)> {
    let data_check = dataplane_check_enabled();
    log::trace!(
        "[wg] verify {peer} obf={} data_check={data_check}",
        aethernoize.is_enabled()
    );

    let (sock, _) = crate::upstream::bind_via_upstream(peer).await?;

    let start = Instant::now();
    let deadline = start + timeout;

    if aethernoize.is_enabled() {
        aethernoize::apply_obfuscation(&sock, peer, aethernoize).await;
    }

    let mut tunn = Tunn::new(
        StaticSecret::from(private_key),
        PublicKey::from(peer_public),
        None,
        Some(keepalive.unwrap_or(DEFAULT_KEEPALIVE_SECS)),
        0,
        None,
    );

    let mut out_buf = vec![0u8; MAX_PACKET];
    let mut recv_buf = vec![0u8; MAX_PACKET];
    let mut tmp_buf = vec![0u8; MAX_PACKET];

    let init_packet = match tunn.encapsulate(&[], &mut out_buf) {
        TunnResult::WriteToNetwork(pkt) => {
            let mut framed = pkt.to_vec();
            inject_client_id(&mut framed, &client_id);
            framed
        },
        other => {
            log::warn!("[wg] unexpected encap result: {other:?}");
            return Err(AetherError::Other("handshake init failed".into()));
        },
    };

    log::trace!("[wg] sending init {} bytes to {peer}", init_packet.len());
    sock.send(&init_packet).await?;

    let mut retry_index = 0usize;
    let mut timer = tokio::time::interval(TIMER_TICK);
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    timer.tick().await;

    let mut attempts = 0u32;
    let mut handshake_done = false;

    while !handshake_done {
        if Instant::now() >= deadline {
            log::trace!("[wg] timeout after {attempts} recv attempts");
            return Err(AetherError::Other("verify timeout".into()));
        }

        let remaining = deadline.saturating_duration_since(Instant::now());

        tokio::select! {
            r = sock.recv(&mut recv_buf) => {
                attempts += 1;
                let n = r?;
                log::trace!("[wg] recv {n} bytes (attempt {attempts})");
                strip_client_id(&mut recv_buf[..n]);

                // The reply is collected first and sent after the match, so
                // nothing borrows `tunn` once the session is handed on.
                let mut reply: Option<Vec<u8>> = None;
                match tunn.decapsulate(None, &recv_buf[..n], &mut tmp_buf) {
                    TunnResult::Done => handshake_done = true,
                    TunnResult::WriteToNetwork(pkt) => {
                        let mut framed = pkt.to_vec();
                        inject_client_id(&mut framed, &client_id);
                        reply = Some(framed);
                        handshake_done = true;
                    },
                    TunnResult::Err(e) => log::trace!("[wg] decap error: {e:?}"),
                    other => log::trace!("[wg] unexpected decap: {other:?}"),
                }

                if let Some(framed) = reply {
                    log::trace!("[wg] sending response {} bytes", framed.len());
                    sock.send(&framed).await?;
                }

                if handshake_done {
                    log::trace!("[wg] handshake done in {:?}", start.elapsed());
                }
            }

            _ = timer.tick() => {
                retransmit_init(&sock, &init_packet, peer, start, &mut retry_index).await?;

                match tunn.update_timers(&mut out_buf) {
                    TunnResult::WriteToNetwork(pkt) => {
                        let mut framed = pkt.to_vec();
                        inject_client_id(&mut framed, &client_id);
                        log::trace!("[wg] timer generated {} byte handshake packet", framed.len());
                        sock.send(&framed).await?;
                    },
                    TunnResult::Err(e) => {
                        return Err(AetherError::Other(format!("wireguard timer failed: {e:?}")));
                    },
                    _ => {},
                }
            }

            _ = tokio::time::sleep(remaining) => {
                log::trace!("[wg] sleep timeout");
                return Err(AetherError::Other("verify timeout".into()));
            }
        }
    }

    let elapsed = if data_check {
        verify_dataplane(&sock, &mut tunn, &client_id, local_ipv4, start, deadline).await?
    } else {
        start.elapsed()
    };

    Ok((
        elapsed,
        EstablishedSession::new(tunn, sock, peer, client_id),
    ))
}

/// Resends the handshake init once each retry delay has elapsed.
async fn retransmit_init(
    sock: &UdpSocket,
    init_packet: &[u8],
    peer: SocketAddr,
    start: Instant,
    retry_index: &mut usize,
) -> Result<()> {
    let Some(delay) = VERIFY_RETRY_DELAYS.get(*retry_index) else {
        return Ok(());
    };
    if start.elapsed() < *delay {
        return Ok(());
    }

    *retry_index += 1;
    log::trace!(
        "[wg] retransmitting init to {peer} after {delay:?} ({}/{})",
        *retry_index,
        VERIFY_RETRY_DELAYS.len()
    );
    sock.send(init_packet).await?;
    Ok(())
}

/// Waits for [`DATAPLANE_REQUIRED_SUCCESSES`] probe replies, resending while
/// the deadline allows.
async fn verify_dataplane(
    sock: &UdpSocket,
    tunn: &mut Tunn,
    client_id: &[u8; 3],
    local_ipv4: Ipv4Addr,
    start: Instant,
    deadline: Instant,
) -> Result<Duration> {
    let probe = dataplane_probe(local_ipv4);
    let mut out_buf = vec![0u8; MAX_PACKET];
    let mut recv_buf = vec![0u8; MAX_PACKET];
    let mut tmp_buf = vec![0u8; MAX_PACKET];

    let mut successes: u32 = 0;
    send_dataplane_probe(sock, tunn, client_id, &probe, &mut out_buf).await?;

    // One instant owns the whole schedule. The previous version tracked a
    // "last sent" instant AND a resend instant, and wrote a future value into
    // the first, which quietly pushed the resend out and made the gap below a
    // no-op.
    let mut next_probe_at = Instant::now() + DATAPLANE_RESEND_AFTER;

    loop {
        let now = Instant::now();
        if now >= deadline {
            log::debug!(
                "[wg] dataplane verify timed out ({successes}/{DATAPLANE_REQUIRED_SUCCESSES} confirmations)"
            );
            return Err(AetherError::Other("dataplane timeout".into()));
        }
        if now >= next_probe_at {
            let _ = send_dataplane_probe(sock, tunn, client_id, &probe, &mut out_buf).await;
            next_probe_at = now + DATAPLANE_RESEND_AFTER;
        }

        let wait = deadline
            .saturating_duration_since(now)
            .min(next_probe_at.saturating_duration_since(now));

        tokio::select! {
            r = sock.recv(&mut recv_buf) => {
                let n = r?;
                strip_client_id(&mut recv_buf[..n]);

                let mut reply: Option<Vec<u8>> = None;
                let mut confirmed = false;
                match tunn.decapsulate(None, &recv_buf[..n], &mut tmp_buf) {
                    TunnResult::WriteToTunnelV4(..) | TunnResult::WriteToTunnelV6(..) => {
                        confirmed = true;
                    },
                    TunnResult::WriteToNetwork(pkt) => {
                        let mut framed = pkt.to_vec();
                        inject_client_id(&mut framed, client_id);
                        reply = Some(framed);
                    },
                    _ => {},
                }

                if let Some(framed) = reply {
                    let _ = sock.send(&framed).await;
                }

                if confirmed {
                    successes += 1;
                    log::debug!(
                        "[wg] dataplane round-trip {successes}/{DATAPLANE_REQUIRED_SUCCESSES} confirmed in {:?}",
                        start.elapsed()
                    );
                    if successes >= DATAPLANE_REQUIRED_SUCCESSES {
                        let elapsed = start.elapsed();
                        log::debug!("[wg] dataplane ok in {elapsed:?}");
                        return Ok(elapsed);
                    }
                    next_probe_at = Instant::now() + DATAPLANE_PROBE_GAP;
                }
            }

            _ = tokio::time::sleep(wait) => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_probe_schedule_leaves_room_for_a_reply() {
        assert!(
            DATAPLANE_PROBE_GAP < DATAPLANE_RESEND_AFTER,
            "a confirmed round trip should re-probe sooner than a lost one"
        );
        assert!(DATAPLANE_REQUIRED_SUCCESSES >= 2);
    }

    #[test]
    fn the_retry_delays_grow() {
        assert!(VERIFY_RETRY_DELAYS.windows(2).all(|w| w[0] < w[1]));
    }

    #[tokio::test]
    async fn endpoint_verification_retransmits_a_lost_initial_handshake() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer = server.local_addr().unwrap();
        let profile = aethernoize::from_profile("off");
        let verifier = tokio::spawn(async move {
            verify_endpoint(
                peer,
                [7u8; 32],
                [9u8; 32],
                [1u8, 2, 3],
                "172.16.0.2".parse().unwrap(),
                &profile,
                Duration::from_secs(4),
                None,
            )
            .await
        });

        let mut received = Vec::new();
        let mut buf = [0u8; 2048];
        for _ in 0..3 {
            let n = tokio::time::timeout(Duration::from_secs(3), server.recv(&mut buf))
                .await
                .expect("handshake packet deadline")
                .expect("handshake packet");
            received.push(buf[..n].to_vec());
        }

        verifier.abort();
        let _ = verifier.await;

        assert_eq!(received.len(), 3);
        assert_eq!(received[0], received[1]);
        assert_eq!(received[1], received[2]);
    }
}
