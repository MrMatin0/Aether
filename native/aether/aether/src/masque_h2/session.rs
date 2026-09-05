//! The running HTTP/2 tunnel.

use std::time::Instant;

use tokio::sync::{mpsc, oneshot};

use crate::error::{AetherError, Result};
use crate::masque::{self, CapsuleParser};
use crate::quic::{AssignedAddr, Control, Internals};

use super::config::{
    data_check_enabled, keepalive_interval, keepalive_timeout, log_or_debug, validation_timeout,
    H2TunnelConfig, DATA_PROBE_REQUIRED_SUCCESSES, PROBE_RESEND_AFTER,
};
use super::connect::{open_connect_ip, ConnectIpStream};
use super::stream::{close_stream, drain_capsules, send_ip_packet};

/// Holds the socks5 readiness signal until the tunnel has proven itself.
///
/// Exposing socks5 before that means handing the user's requests to an edge
/// that may be dropping everything.
struct ReadyGate {
    tx: Option<oneshot::Sender<()>>,
    open: bool,
    quiet: bool,
    confirmations: u32,
}

impl ReadyGate {
    fn new(tx: Option<oneshot::Sender<()>>, quiet: bool) -> Self {
        Self {
            tx,
            open: false,
            quiet,
            confirmations: 0,
        }
    }

    fn is_open(&self) -> bool {
        self.open
    }

    fn open(&mut self) {
        if self.open {
            return;
        }
        self.open = true;
        if let Some(tx) = self.tx.take() {
            let _ = tx.send(());
        }
    }

    /// Records one confirmed end-to-end round trip. Returns true when that was
    /// the last one needed.
    fn confirm(&mut self) -> bool {
        if self.open {
            return false;
        }

        self.confirmations += 1;
        log::debug!(
            "[h2] data-plane round-trip {}/{DATA_PROBE_REQUIRED_SUCCESSES} confirmed",
            self.confirmations
        );

        if self.confirmations < DATA_PROBE_REQUIRED_SUCCESSES {
            return false;
        }

        self.open();
        log_or_debug(
            self.quiet,
            "[h2] tunnel validated (end-to-end data confirmed); exposing socks5",
        );
        true
    }
}

fn earliest(a: Option<Instant>, b: Option<Instant>) -> Option<Instant> {
    match (a, b) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (Some(a), None) => Some(a),
        (None, b) => b,
    }
}

/// Sleeps until `deadline`, or forever when there is none.
async fn sleep_until(deadline: Option<Instant>) {
    match deadline {
        Some(dl) => tokio::time::sleep(dl.saturating_duration_since(Instant::now())).await,
        None => std::future::pending::<()>().await,
    }
}

pub async fn run(
    cfg: H2TunnelConfig,
    internals: Internals,
    addr_tx: Option<mpsc::Sender<AssignedAddr>>,
    ready_tx: Option<oneshot::Sender<()>>,
) -> Result<()> {
    let (mut outbound_rx, inbound_tx, mut ctrl_rx) = internals.into_parts();
    let quiet = cfg.quiet;
    let data_check = data_check_enabled();
    let probe_packet = masque::build_dns_probe_packet(cfg.local_ipv4);

    let ConnectIpStream {
        mut send,
        mut recv,
        ping_pong,
        _driver,
    } = open_connect_ip(&cfg).await?;

    let mut ping_pong = ping_pong
        .ok_or_else(|| AetherError::Masque("h2 connection does not support ping".into()))?;

    let mut capsules = CapsuleParser::new();
    let mut gate = ReadyGate::new(ready_tx, quiet);
    let mut validate_deadline: Option<Instant> = None;

    if data_check {
        if let Err(e) = send_ip_packet(&mut send, &probe_packet).await {
            log::debug!("[h2] initial data-plane probe: {e}");
        }
        validate_deadline = Some(Instant::now() + validation_timeout());
        log_or_debug(
            quiet,
            "[h2] validating data-plane (end-to-end probe) before exposing socks5",
        );
    } else {
        gate.open();
    }

    // First tick one full period from now: the probe above just went out, and
    // an interval that fires immediately sent two back to back.
    let mut probe_interval = tokio::time::interval_at(
        tokio::time::Instant::now() + PROBE_RESEND_AFTER,
        PROBE_RESEND_AFTER,
    );
    probe_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let pong_grace = keepalive_timeout();
    let mut keepalive = tokio::time::interval(keepalive_interval());
    keepalive.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut pong_deadline: Option<Instant> = None;

    loop {
        // Deadlines are enforced here and armed as a select branch below. They
        // used to be enforced here only, which meant an idle stalled tunnel
        // never woke up to notice them.
        if !gate.is_open() {
            if let Some(dl) = validate_deadline {
                if Instant::now() >= dl {
                    log::warn!(
                        "[h2] data-plane validation timed out; edge accepts control but drops traffic"
                    );
                    close_stream(&mut send);
                    return Err(AetherError::Masque(
                        "h2 data-plane validation timeout (handshake ok, no traffic)".into(),
                    ));
                }
            }
        }
        if let Some(dl) = pong_deadline {
            if Instant::now() >= dl {
                log::warn!(
                    "[h2] no PING response from edge within {pong_grace:?}; connection is stalled"
                );
                close_stream(&mut send);
                return Err(AetherError::Masque("h2 keepalive timeout".into()));
            }
        }

        let watchdog = earliest(
            match gate.is_open() {
                true => None,
                false => validate_deadline,
            },
            pong_deadline,
        );
        let awaiting_pong = pong_deadline.is_some();

        tokio::select! {
            biased;

            // Without this branch, a silently dead edge parks the select
            // forever: while a pong is outstanding the keepalive branch is
            // disabled, and a stalled connection produces neither data nor a
            // pong to wake anything up.
            _ = sleep_until(watchdog), if watchdog.is_some() => {}

            _ = keepalive.tick(), if gate.is_open() && !awaiting_pong => {
                match ping_pong.send_ping(h2::Ping::opaque()) {
                    Ok(()) => {
                        pong_deadline = Some(Instant::now() + pong_grace);
                        log::debug!("[h2] keepalive ping sent");
                    }
                    Err(e) => log::debug!("[h2] keepalive ping send failed: {e}"),
                }
            }

            pong = std::future::poll_fn(|cx| ping_pong.poll_pong(cx)), if awaiting_pong => {
                match pong {
                    Ok(_) => {
                        pong_deadline = None;
                        log::debug!("[h2] keepalive pong received");
                    }
                    Err(e) => {
                        log::warn!("[h2] keepalive ping failed: {e}");
                        close_stream(&mut send);
                        return Err(AetherError::Masque(format!("h2 keepalive: {e}")));
                    }
                }
            }

            _ = probe_interval.tick(), if data_check && !gate.is_open() => {
                if let Err(e) = send_ip_packet(&mut send, &probe_packet).await {
                    log::trace!("[h2] data-plane probe resend: {e}");
                }
            }

            ctrl = ctrl_rx.recv() => {
                match ctrl {
                    Some(Control::Close) | None => {
                        close_stream(&mut send);
                        log_or_debug(quiet, "[h2] closing tunnel");
                        return Ok(());
                    }
                    Some(Control::Migrate) => {}
                }
            }

            pkt = outbound_rx.recv() => {
                match pkt {
                    Some(ip_packet) => {
                        if let Err(e) = send_ip_packet(&mut send, &ip_packet).await {
                            log::debug!("[h2] send: {e}");
                            return Err(e);
                        }
                    }
                    None => {
                        close_stream(&mut send);
                        return Ok(());
                    }
                }
            }

            data = futures::future::poll_fn(|cx| recv.poll_data(cx)) => {
                let chunk = match data {
                    Some(Ok(chunk)) => chunk,
                    Some(Err(e)) => {
                        log::warn!("[h2] recv body error: {e}");
                        return Err(AetherError::Masque(format!("h2 body: {e}")));
                    }
                    None => {
                        log_or_debug(quiet, "[h2] server closed stream");
                        return Ok(());
                    }
                };

                let _ = recv.flow_control().release_capacity(chunk.len());
                capsules.push(&chunk);
                let drained = drain_capsules(&mut capsules, &inbound_tx, &addr_tx);

                // A stream that lost capsule alignment cannot be recovered by
                // reading more of it. This used to be logged at trace and then
                // read forever.
                if capsules.is_desynced() {
                    close_stream(&mut send);
                    return Err(AetherError::Masque(
                        "h2 capsule stream lost frame alignment".into(),
                    ));
                }

                if drained.inbound_closed {
                    log_or_debug(quiet, "[h2] nothing is reading the tunnel; closing");
                    close_stream(&mut send);
                    return Ok(());
                }

                if drained.delivered && !gate.is_open() && !gate.confirm() {
                    // More confirmations wanted: probe again now instead of
                    // waiting out the resend interval.
                    if let Err(e) = send_ip_packet(&mut send, &probe_packet).await {
                        log::trace!("[h2] follow-up data-plane probe: {e}");
                    }
                }

                if gate.is_open() {
                    validate_deadline = None;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn the_gate_stays_shut_until_enough_round_trips_are_confirmed() {
        let (tx, mut rx) = oneshot::channel();
        let mut gate = ReadyGate::new(Some(tx), true);

        for _ in 1..DATA_PROBE_REQUIRED_SUCCESSES {
            assert!(!gate.confirm());
            assert!(!gate.is_open());
            assert!(rx.try_recv().is_err(), "socks5 must not be exposed yet");
        }

        assert!(gate.confirm(), "the last confirmation opens the gate");
        assert!(gate.is_open());
        assert_eq!(rx.try_recv(), Ok(()));

        // Further data must not re-signal a gate that is already open.
        assert!(!gate.confirm());
    }

    #[test]
    fn a_gate_with_the_data_check_off_opens_immediately() {
        let (tx, mut rx) = oneshot::channel();
        let mut gate = ReadyGate::new(Some(tx), false);
        gate.open();
        assert!(gate.is_open());
        assert_eq!(rx.try_recv(), Ok(()));
    }

    #[test]
    fn the_watchdog_wakes_for_whichever_deadline_comes_first() {
        let now = Instant::now();
        let soon = now + Duration::from_secs(1);
        let later = now + Duration::from_secs(5);

        assert_eq!(earliest(Some(soon), Some(later)), Some(soon));
        assert_eq!(earliest(Some(later), Some(soon)), Some(soon));
        assert_eq!(earliest(Some(soon), None), Some(soon));
        assert_eq!(earliest(None, Some(later)), Some(later));
        assert_eq!(earliest(None, None), None);
    }

    #[tokio::test]
    async fn a_deadline_in_the_past_wakes_the_loop_at_once() {
        let past = Instant::now() - Duration::from_secs(1);
        tokio::time::timeout(Duration::from_millis(50), sleep_until(Some(past)))
            .await
            .expect("an elapsed deadline must not block");
    }
}
