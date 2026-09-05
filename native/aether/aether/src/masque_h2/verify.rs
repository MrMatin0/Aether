//! Proving ONE HTTP/2 endpoint is usable.
//!
//! An accepted CONNECT-IP request proves very little: an edge that answers the
//! control plane and drops every packet is the exact failure this catches.

use std::time::{Duration, Instant};

use crate::error::{AetherError, Result};
use crate::masque::{self, Capsule, CapsuleParser};

use super::config::{
    data_check_enabled, H2TunnelConfig, DATA_PROBE_REQUIRED_SUCCESSES, PROBE_RESEND_AFTER,
};
use super::connect::{open_connect_ip, ConnectIpStream};
use super::stream::{close_stream, send_ip_packet};

pub async fn verify_h2(cfg: &H2TunnelConfig, timeout: Duration) -> Result<Duration> {
    let start = Instant::now();

    match tokio::time::timeout(timeout, probe_data_plane(cfg)).await {
        Ok(Ok(())) => Ok(start.elapsed()),
        Ok(Err(e)) => Err(e),
        Err(_) => Err(AetherError::Other("h2 verify timeout".into())),
    }
}

async fn probe_data_plane(cfg: &H2TunnelConfig) -> Result<()> {
    let ConnectIpStream {
        mut send,
        mut recv,
        ping_pong: _,
        _driver,
    } = open_connect_ip(cfg).await?;

    if !data_check_enabled() {
        close_stream(&mut send);
        return Ok(());
    }

    let mut capsules = CapsuleParser::new();
    let probe = masque::build_dns_probe_packet(cfg.local_ipv4);
    send_ip_packet(&mut send, &probe).await?;

    // The edge can drop a probe. Retrying inside the caller's timeout is the
    // difference between "this endpoint is bad" and "one packet was unlucky".
    let mut resend = tokio::time::interval_at(
        tokio::time::Instant::now() + PROBE_RESEND_AFTER,
        PROBE_RESEND_AFTER,
    );
    resend.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let mut successes: u32 = 0;

    loop {
        let chunk = tokio::select! {
            _ = resend.tick() => {
                send_ip_packet(&mut send, &probe).await?;
                continue;
            }

            data = futures::future::poll_fn(|cx| recv.poll_data(cx)) => match data {
                Some(Ok(chunk)) => chunk,
                Some(Err(e)) => return Err(AetherError::Masque(format!("h2 body: {e}"))),
                None => return Err(AetherError::Masque("h2 stream closed before data".into())),
            },
        };

        let _ = recv.flow_control().release_capacity(chunk.len());
        capsules.push(&chunk);

        let mut datagrams: u32 = 0;
        loop {
            match capsules.next() {
                Ok(Some(Capsule::Datagram(_))) => datagrams += 1,
                Ok(Some(_)) => {},
                Ok(None) | Err(_) => break,
            }
        }

        if capsules.is_desynced() {
            return Err(AetherError::Masque(
                "h2 capsule stream lost frame alignment".into(),
            ));
        }

        successes += datagrams;
        if successes >= DATA_PROBE_REQUIRED_SUCCESSES {
            close_stream(&mut send);
            return Ok(());
        }
        if datagrams > 0 {
            send_ip_packet(&mut send, &probe).await?;
        }
    }
}
