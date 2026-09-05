//! The HTTP/2 tunnel's configuration and every environment knob that tunes
//! it. Four near-identical "read an integer number of seconds" parsers used to
//! be spread through this transport; there is one now.

use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

/// Two confirmed round trips before a tunnel is trusted with real traffic: one
/// reply can be a middlebox answering on the edge's behalf.
pub(super) const DATA_PROBE_REQUIRED_SUCCESSES: u32 = 2;

/// How long to wait for a probe answer before assuming the probe was dropped.
pub(super) const PROBE_RESEND_AFTER: Duration = Duration::from_millis(700);

pub struct H2TunnelConfig {
    pub peer: SocketAddr,
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub local_ipv4: Ipv4Addr,
    /// Set while probing, so one scan does not narrate itself at info level.
    pub quiet: bool,
    pub pin_endpoint: bool,
    pub expected_pins: Vec<Vec<u8>>,
}

/// Seconds from `name`, ignoring empty, zero and unparseable values - zero
/// would mean "time out immediately", which is never what an operator means.
fn env_secs(name: &str, default: u64) -> Duration {
    let secs = std::env::var(name)
        .ok()
        .and_then(|raw| raw.trim().parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(default);
    Duration::from_secs(secs)
}

/// Whether the HTTP/2 transport is selected at all.
pub fn enabled() -> bool {
    let raw = std::env::var("AETHER_MASQUE_HTTP2").unwrap_or_default();
    matches!(
        raw.trim().to_lowercase().as_str(),
        "1" | "true" | "h2" | "yes" | "on"
    )
}

/// The HTTP/2 edge to dial. Same address as the QUIC peer unless it is
/// explicitly overridden.
pub fn h2_peer(quic_peer: SocketAddr) -> SocketAddr {
    std::env::var("AETHER_MASQUE_H2_PEER")
        .ok()
        .and_then(|raw| raw.trim().parse::<SocketAddr>().ok())
        .unwrap_or(quic_peer)
}

/// Data-plane validation is on unless it is explicitly switched off.
pub(super) fn data_check_enabled() -> bool {
    std::env::var("AETHER_MASQUE_NO_DATA_CHECK").is_err()
}

pub(super) fn validation_timeout() -> Duration {
    env_secs("AETHER_MASQUE_VALIDATE_SECS", 10)
}

pub(super) fn keepalive_interval() -> Duration {
    env_secs("AETHER_MASQUE_H2_KEEPALIVE_SECS", 15)
}

pub(super) fn keepalive_timeout() -> Duration {
    env_secs("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS", 20)
}

pub(super) fn log_or_debug(quiet: bool, msg: impl std::fmt::Display) {
    if quiet {
        log::debug!("{msg}");
    } else {
        log::info!("{msg}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_transport_is_off_unless_it_is_asked_for() {
        std::env::remove_var("AETHER_MASQUE_HTTP2");
        assert!(!enabled());

        for on in ["1", "true", "h2", "yes", "on", " ON ", "True"] {
            std::env::set_var("AETHER_MASQUE_HTTP2", on);
            assert!(enabled(), "{on:?} should enable http/2");
        }

        for off in ["", "0", "false", "h3", "quic"] {
            std::env::set_var("AETHER_MASQUE_HTTP2", off);
            assert!(!enabled(), "{off:?} should not enable http/2");
        }

        std::env::remove_var("AETHER_MASQUE_HTTP2");
    }

    #[test]
    fn a_nonsense_duration_falls_back_to_the_default() {
        let name = "AETHER_TEST_ONLY_SECS";
        std::env::remove_var(name);
        assert_eq!(env_secs(name, 9), Duration::from_secs(9));

        std::env::set_var(name, " 42 ");
        assert_eq!(env_secs(name, 9), Duration::from_secs(42));

        for bad in ["", "0", "-1", "soon", "12s"] {
            std::env::set_var(name, bad);
            assert_eq!(env_secs(name, 9), Duration::from_secs(9), "{bad:?}");
        }

        std::env::remove_var(name);
    }

    #[test]
    fn the_keepalive_timeout_outlives_the_keepalive_interval() {
        std::env::remove_var("AETHER_MASQUE_H2_KEEPALIVE_SECS");
        std::env::remove_var("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS");
        assert!(
            keepalive_interval() < keepalive_timeout(),
            "a ping must have time to be answered before the next one is due"
        );
    }

    #[test]
    fn an_override_that_is_not_an_address_is_ignored() {
        let quic: SocketAddr = "162.159.192.1:443".parse().unwrap();
        std::env::set_var("AETHER_MASQUE_H2_PEER", "not-an-address");
        assert_eq!(h2_peer(quic), quic);

        std::env::set_var("AETHER_MASQUE_H2_PEER", " 1.2.3.4:8443 ");
        assert_eq!(h2_peer(quic), "1.2.3.4:8443".parse().unwrap());

        std::env::remove_var("AETHER_MASQUE_H2_PEER");
    }
}
