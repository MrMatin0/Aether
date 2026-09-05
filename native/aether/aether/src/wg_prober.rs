//! WireGuard endpoint selection: WHAT to probe, and how to verify ONE
//! candidate.
//!
//! The scanner itself lives in [`crate::prober::scan`] and is shared with the
//! MASQUE prober. This file used to carry a second copy of it - its own
//! five-variant mode enum, its own tuning table, its own candidate builder and
//! its own CIDR sampler - which is why the two paths kept drifting apart.

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use crate::aethernoize::AetherNoizeConfig;
use crate::error::{AetherError, Result};
use crate::prober::scan::{self, Family, IpScan};
use crate::wireguard;

/// The scan modes are the SAME three modes for both transports; only the
/// tuning differs. The alias keeps older call sites (`wg_prober::WgScanMode`)
/// compiling.
pub use crate::prober::scan::ScanMode as WgScanMode;

/// How long a real HTTP round trip through a candidate may take in the
/// deep-verifying mode before that candidate is written off.
const DEEP_PING_TIMEOUT: Duration = Duration::from_secs(10);

/// The tunnel ping never sends IPv6 traffic, so loopback is all this needs to
/// be: the real address comes from the edge.
const PING_LOCAL_IPV6: &str = "::1";

#[derive(Debug, Clone, Copy)]
pub struct WgProbeResult {
    pub ip: IpAddr,
    pub port: u16,
    pub rtt: Duration,
}

#[derive(Clone)]
pub struct WgProbe {
    pub private_key: Arc<[u8; 32]>,
    pub peer_public_key: Arc<[u8; 32]>,
    pub client_id: [u8; 3],
    pub local_ipv4: Ipv4Addr,
    pub aethernoize: AetherNoizeConfig,
    pub ports: Vec<u16>,
    pub ip: IpScan,
    pub excluded: HashSet<SocketAddr>,
}

/// The address space one scan is allowed to touch.
///
/// Assembling this used to be forty lines inlined at the top of the hunt, which
/// buried the one decision it actually makes: pinned ranges replace the
/// built-in ones outright.
struct ScanSpace {
    /// Ranges the user pinned in Settings, if any.
    pinned: Option<Vec<String>>,
    cidrs_v4: Vec<String>,
    cidrs_v6: Vec<String>,
    anchors_v4: Vec<Ipv4Addr>,
    anchors_v6: Vec<Ipv6Addr>,
}

impl ScanSpace {
    fn resolve() -> Self {
        let pinned = pinned_cidrs_v4();

        let cidrs_v4: Vec<String> = match &pinned {
            Some(list) => list.clone(),
            None => wireguard::wg_prefixes_v4()
                .iter()
                .map(|c| c.to_string())
                .collect(),
        };
        let cidrs_v6: Vec<String> = wireguard::wg_prefixes_v6()
            .iter()
            .map(|c| c.to_string())
            .collect();

        // A pinned range means "scan exactly this", so the built-in seeds -
        // which live outside it - are dropped instead of being tried first.
        let mut anchors_v4: Vec<Ipv4Addr> = Vec::new();
        let mut anchors_v6: Vec<Ipv6Addr> = Vec::new();
        if pinned.is_none() {
            anchors_v4 = wireguard::wg_seeds_v4()
                .iter()
                .filter_map(|s| s.parse().ok())
                .collect();
            anchors_v6 = wireguard::WG_SEEDS_V6
                .iter()
                .filter_map(|s| s.parse().ok())
                .collect();
        }

        Self {
            pinned,
            cidrs_v4,
            cidrs_v6,
            anchors_v4,
            anchors_v6,
        }
    }

    fn ranges_label(&self) -> String {
        match &self.pinned {
            Some(list) => format!(" ranges={}", list.join(",")),
            None => String::new(),
        }
    }
}

pub async fn hunt_best_wg_endpoint(probe: &WgProbe, mode: WgScanMode) -> Result<WgProbeResult> {
    hunt_wg_endpoints(probe, mode, 1)
        .await?
        .into_iter()
        .next()
        .ok_or(AetherError::NoCleanEndpoint)
}

/// Finds up to [`want`] endpoints on DISTINCT addresses, best first.
///
/// Warp-in-warp asks for two, because using one address for both hops is not
/// warp-in-warp.
pub async fn hunt_wg_endpoints(
    probe: &WgProbe,
    mode: WgScanMode,
    want: usize,
) -> Result<Vec<WgProbeResult>> {
    let want = want.max(1);

    let mut tuning = mode.tuning(Family::WireGuard);
    tuning.concurrency = crate::sysprofile::cap_concurrency(tuning.concurrency);

    let Some(ip) = scan::usable_ip(probe.ip).await else {
        return Err(AetherError::NoCleanEndpoint);
    };

    let ports = scan::dedup_ports(&probe.ports, wireguard::WG_DEFAULT_PORT);
    let space = ScanSpace::resolve();

    let targets = scan::build_targets(
        &scan::TargetPlan {
            ip,
            ports: &ports,
            anchors_v4: &space.anchors_v4,
            anchors_v6: &space.anchors_v6,
            cidrs_v4: &space.cidrs_v4,
            cidrs_v6: &space.cidrs_v6,
            excluded: &probe.excluded,
        },
        &tuning,
    );

    log::info!(
        "[*] wireguard scan mode={} ip={} want={} candidates={} ports={:?} concurrency={} per_probe={:?} budget={:?}{}{}",
        mode.label(),
        ip.label(),
        want,
        targets.len(),
        ports,
        tuning.concurrency,
        tuning.probe_timeout,
        tuning.budget,
        match tuning.deep_verify {
            true => " verify=real-http",
            false => "",
        },
        space.ranges_label(),
    );

    let timeout = tuning.probe_timeout;
    let deep = tuning.deep_verify;
    let found = scan::sweep("wireguard", targets, &tuning, want, |ip, port| {
        verify_one_wg(probe, ip, port, timeout, deep)
    })
    .await;

    if found.is_empty() {
        return Err(AetherError::NoCleanEndpoint);
    }

    let picked: Vec<WgProbeResult> = found
        .into_iter()
        .take(want)
        .map(|hit| WgProbeResult {
            ip: hit.ip,
            port: hit.port,
            rtt: hit.rtt,
        })
        .collect();

    for result in &picked {
        log::info!(
            "[+] wg endpoint {}:{} rtt={:?}",
            result.ip,
            result.port,
            result.rtt,
        );
    }

    Ok(picked)
}

/// Proves ONE candidate: a real WireGuard handshake, and in the deep mode a
/// real HTTP round trip through the session that handshake produced.
async fn verify_one_wg(
    probe: &WgProbe,
    ip: IpAddr,
    port: u16,
    timeout: Duration,
    deep: bool,
) -> Option<Duration> {
    let peer = SocketAddr::new(ip, port);

    let (rtt, session) = match wireguard::verify_endpoint_keep_session(
        peer,
        *probe.private_key,
        *probe.peer_public_key,
        probe.client_id,
        probe.local_ipv4,
        &probe.aethernoize,
        timeout,
        None,
    )
    .await
    {
        Ok(value) => value,
        Err(e) => {
            log::trace!("wg probe {ip}:{port} -> {e}");
            return None;
        },
    };

    if !deep {
        return Some(rtt);
    }

    let params = crate::tunnelping::WgPingParams {
        local_ipv4: probe.local_ipv4,
        local_ipv6: PING_LOCAL_IPV6.parse().expect("::1 is a valid address"),
        aethernoize: probe.aethernoize.clone(),
    };

    match crate::tunnelping::wg_http_ping_established(session, &params, DEEP_PING_TIMEOUT).await {
        Ok(http_rtt) => {
            log::info!("[+] {ip}:{port} carried a real http round trip in {http_rtt:?}");
            Some(http_rtt)
        },
        Err(e) => {
            log::trace!("[-] {ip}:{port} failed the real http check: {e}");
            None
        },
    }
}

/// The ranges the user pinned in Settings, if any. Core 1.6.0 stopped
/// exposing the old upstream helper, so the app owns this.
fn pinned_cidrs_v4() -> Option<Vec<String>> {
    let raw = std::env::var("AETHER_WG_CIDRS")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| {
            std::env::var("AETHER_SCAN_CIDRS")
                .ok()
                .filter(|value| !value.trim().is_empty())
        })?;

    let list: Vec<String> = raw.split(',').filter_map(scan::normalize_cidr_v4).collect();

    match list.is_empty() {
        true => None,
        false => Some(list),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_wireguard_modes_are_the_same_three_modes() {
        assert_eq!(WgScanMode::parse("turbo"), WgScanMode::Turbo);
        assert_eq!(WgScanMode::parse("balanced"), WgScanMode::Precise);
        assert_eq!(WgScanMode::parse("ironclad"), WgScanMode::Ultra);
    }

    #[test]
    fn a_wireguard_scan_leans_on_more_ports_than_a_masque_scan() {
        // WireGuard edges answer on a wide set of udp ports and 2408 is the
        // one operators filter first, so every mode gets more than one pass.
        for mode in [WgScanMode::Turbo, WgScanMode::Precise, WgScanMode::Ultra] {
            assert!(mode.tuning(Family::WireGuard).port_waves >= 2);
        }
    }

    #[test]
    fn a_pinned_range_is_read_from_either_variable_and_normalized() {
        std::env::set_var("AETHER_WG_CIDRS", "188.114.96.x , 162.159.192.0/24");
        let pinned = pinned_cidrs_v4().expect("a pinned range");
        std::env::remove_var("AETHER_WG_CIDRS");
        assert_eq!(pinned, vec!["188.114.96.0/24", "162.159.192.0/24"]);
    }

    #[test]
    fn a_pinned_range_replaces_the_built_in_seeds_instead_of_joining_them() {
        std::env::remove_var("AETHER_SCAN_CIDRS");
        std::env::set_var("AETHER_WG_CIDRS", "188.114.96.0/24");
        let space = ScanSpace::resolve();
        std::env::remove_var("AETHER_WG_CIDRS");

        assert_eq!(space.cidrs_v4, vec!["188.114.96.0/24"]);
        assert!(
            space.anchors_v4.is_empty(),
            "a seed outside the pinned range would be scanned first"
        );
        assert_eq!(space.ranges_label(), " ranges=188.114.96.0/24");
    }

    #[test]
    fn without_a_pinned_range_the_built_in_catalog_is_scanned() {
        std::env::remove_var("AETHER_WG_CIDRS");
        std::env::remove_var("AETHER_SCAN_CIDRS");
        let space = ScanSpace::resolve();

        assert!(!space.cidrs_v4.is_empty());
        assert!(!space.cidrs_v6.is_empty());
        assert!(!space.anchors_v4.is_empty());
        assert!(!space.anchors_v6.is_empty());
        assert_eq!(space.ranges_label(), "");
    }

    #[test]
    fn the_default_wireguard_port_leads_the_scan() {
        assert_eq!(
            scan::dedup_ports(&wireguard::WG_PORTS.to_vec(), wireguard::WG_DEFAULT_PORT)[0],
            wireguard::WG_PORTS[0]
        );
    }
}
