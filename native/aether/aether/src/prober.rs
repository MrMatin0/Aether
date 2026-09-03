//! MASQUE endpoint selection: WHAT to probe, and how to verify ONE candidate.
//!
//! Everything that used to make this file a scanner - the mode table, the
//! candidate ordering, the CIDR sampling, the collection loop - now lives in
//! [`scan`], shared with the WireGuard prober. What is left here is the part
//! that is genuinely about MASQUE: Cloudflare's documented ranges and ports,
//! and the three ways a candidate can be proven (QUIC, HTTP/2, or a real HTTP
//! round trip through the tunnel).

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use crate::error::{AetherError, Result};
use crate::noize::NoizeConfig;
use crate::quic;

pub mod scan;

// Re-exported so `prober::IpScan` / `prober::ScanMode` keep resolving for the
// CLI, the FFI layer and the api module.
pub use scan::{host_has_ipv6, Family, Hit, IpScan, ScanMode, ScanTuning};

pub const MASQUE_DOCUMENTED_CIDRS_V4: &[&str] = &["162.159.197.0/24", "162.159.198.0/24"];

pub const MASQUE_DOH_CIDRS_V4: &[&str] = &["162.159.36.0/24", "162.159.46.0/24"];

pub const MASQUE_CIDRS_V4: &[&str] = &[
    "162.159.196.0/24",
    "162.159.195.0/24",
    "162.159.192.0/24",
    "162.159.193.0/24",
    "162.159.204.0/24",
    "162.159.197.0/24",
    "162.159.198.0/24",
    "172.65.251.0/24",
    "188.114.96.0/24",
    "188.114.97.0/24",
    "188.114.98.0/24",
    "188.114.99.0/24",
    "162.159.36.0/24",
    "162.159.46.0/24",
];

pub const MASQUE_SEEDS: &[&str] = &[
    "162.159.196.1",
    "162.159.195.1",
    "162.159.192.1",
    "162.159.197.3",
    "162.159.197.1",
    "162.159.198.2",
    "162.159.198.1",
    "162.159.193.1",
];

pub const MASQUE_PORTS: &[u16] = &[443, 500, 1701, 4500, 4443, 8443, 8095];

pub const MASQUE_CIDRS_V6: &[&str] = &[
    "2606:4700:d0::/48",
    "2606:4700:102::/48",
    "2606:4700:d1::/48",
];

pub const MASQUE_ZT_CIDRS_V4: &[&str] = &["162.159.197.0/24"];

pub const MASQUE_ZT_CIDRS_V6: &[&str] = &["2606:4700:102::/48"];

pub const MASQUE_SEEDS_V6: &[&str] = &[
    "2606:4700:d0::a29f:c602",
    "2606:4700:d1::a29f:c602",
    "2606:4700:d0::a29f:c601",
    "2606:4700:d0::a29f:c001",
];

/// How long a real HTTP round trip through a candidate may take in the
/// deep-verifying mode before that candidate is written off.
const DEEP_PING_TIMEOUT: Duration = Duration::from_secs(10);

pub fn zero_trust_mode() -> bool {
    std::env::var("AETHER_TEAM")
        .map(|value| !value.trim().is_empty())
        .unwrap_or(false)
}

pub fn prioritize(all: &[&'static str], first: &[&'static str]) -> Vec<&'static str> {
    if !zero_trust_mode() {
        return all.to_vec();
    }

    let mut out: Vec<&'static str> = Vec::with_capacity(all.len());
    for entry in first {
        if all.contains(entry) {
            out.push(entry);
        }
    }
    for entry in all {
        if !out.contains(entry) {
            out.push(entry);
        }
    }
    out
}

pub fn masque_cidrs_v4() -> Vec<&'static str> {
    prioritize(MASQUE_CIDRS_V4, MASQUE_ZT_CIDRS_V4)
}

pub fn masque_cidrs_v6() -> Vec<&'static str> {
    prioritize(MASQUE_CIDRS_V6, MASQUE_ZT_CIDRS_V6)
}

/// The ranges the user pinned in Settings, if any.
///
/// ROOT-CAUSE FIX: the app has been exporting AETHER_MASQUE_CIDRS /
/// AETHER_SCAN_CIDRS since 1.2.0 and the comment in this file claimed they
/// were read here - but only wg_prober ever looked at them, so "scan only
/// these ranges" silently did nothing on MASQUE, which is the DEFAULT
/// transport. Both transports honour a pinned range now.
fn pinned_cidrs_v4() -> Option<Vec<String>> {
    let raw = std::env::var("AETHER_MASQUE_CIDRS")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| {
            std::env::var("AETHER_SCAN_CIDRS")
                .ok()
                .filter(|value| !value.trim().is_empty())
        })?;
    let list: Vec<String> = raw
        .split(',')
        .filter_map(scan::normalize_cidr_v4)
        .collect();
    if list.is_empty() {
        None
    } else {
        Some(list)
    }
}

#[derive(Debug, Clone, Copy)]
pub struct ProbeResult {
    pub ip: IpAddr,
    pub port: u16,
    pub rtt: Duration,
}

#[derive(Clone)]
pub struct MasqueProbe {
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Arc<[u8]>,
    pub key_pem: Arc<[u8]>,
    pub ech_config_list: Option<Arc<[u8]>>,
    pub noize: NoizeConfig,
    pub ports: Vec<u16>,
    pub ip: IpScan,
    pub local_ipv4: Ipv4Addr,
}

/// Picks the best MASQUE gateway this network will give us, in [`mode`]'s
/// budget.
pub async fn hunt_best_gateway(probe: &MasqueProbe, mode: ScanMode) -> Result<ProbeResult> {
    let mut tuning = mode.tuning(Family::Masque);
    tuning.concurrency = crate::sysprofile::cap_concurrency(tuning.concurrency);

    let ip = match scan::usable_ip(probe.ip).await {
        Some(ip) => ip,
        None => return Err(AetherError::NoCleanEndpoint),
    };

    let ports = scan::dedup_ports(&probe.ports, 443);
    let pinned = pinned_cidrs_v4();
    let cidrs_v4: Vec<String> = match &pinned {
        Some(list) => list.clone(),
        None => masque_cidrs_v4().iter().map(|c| c.to_string()).collect(),
    };
    let cidrs_v6: Vec<String> = masque_cidrs_v6().iter().map(|c| c.to_string()).collect();
    // A pinned range means "scan exactly this", so the built-in seeds - which
    // live outside it - are dropped instead of being tried first.
    let anchors_v4: Vec<Ipv4Addr> = match pinned.is_some() {
        true => Vec::new(),
        false => MASQUE_SEEDS.iter().filter_map(|s| s.parse().ok()).collect(),
    };
    let anchors_v6: Vec<Ipv6Addr> = match pinned.is_some() {
        true => Vec::new(),
        false => MASQUE_SEEDS_V6
            .iter()
            .filter_map(|s| s.parse().ok())
            .collect(),
    };
    let excluded: HashSet<SocketAddr> = HashSet::new();

    let targets = scan::build_targets(
        &scan::TargetPlan {
            ip,
            ports: &ports,
            anchors_v4: &anchors_v4,
            anchors_v6: &anchors_v6,
            cidrs_v4: &cidrs_v4,
            cidrs_v6: &cidrs_v6,
            excluded: &excluded,
        },
        &tuning,
    );

    log::info!(
        "[*] masque scan mode={} ip={} candidates={} ports={:?} concurrency={} per_probe={:?} budget={:?}{}{}",
        mode.label(),
        ip.label(),
        targets.len(),
        ports,
        tuning.concurrency,
        tuning.probe_timeout,
        tuning.budget,
        if tuning.deep_verify {
            " verify=real-http"
        } else {
            ""
        },
        match &pinned {
            Some(list) => format!(" ranges={}", list.join(",")),
            None => String::new(),
        },
    );

    let timeout = tuning.probe_timeout;
    let deep = tuning.deep_verify;
    let found = scan::sweep("masque", targets, &tuning, 1, |ip, port| {
        verify_one(probe, ip, port, timeout, deep)
    })
    .await;

    match found.first() {
        Some(hit) => {
            log::info!(
                "[+] best gateway {}:{} rtt={:?}",
                hit.ip,
                hit.port,
                hit.rtt,
            );
            Ok(ProbeResult {
                ip: hit.ip,
                port: hit.port,
                rtt: hit.rtt,
            })
        }
        None => Err(AetherError::NoCleanEndpoint),
    }
}

/// Proves ONE candidate.
///
/// [`deep`] is what the slowest mode buys: instead of accepting a completed
/// handshake, the candidate has to carry a real HTTP request through the
/// tunnel and bring the answer back. That is the difference between "the edge
/// is up" and "the edge will actually serve you".
async fn verify_one(
    probe: &MasqueProbe,
    ip: IpAddr,
    port: u16,
    timeout: Duration,
    deep: bool,
) -> Option<Duration> {
    if deep {
        let params = crate::tunnelping::MasquePingParams {
            peer: SocketAddr::new(ip, port),
            sni: probe.sni.clone(),
            authority: probe.authority.clone(),
            path: probe.path.clone(),
            cert_pem: probe.cert_pem.to_vec(),
            key_pem: probe.key_pem.to_vec(),
            noize: probe.noize.clone(),
            local_ipv4: probe.local_ipv4,
            local_ipv4_str: probe.local_ipv4.to_string(),
            local_ipv6_str: String::new(),
        };
        return match crate::tunnelping::masque_http_ping(&params, DEEP_PING_TIMEOUT).await {
            Ok(rtt) => {
                log::info!("[+] {ip}:{port} carried a real http round trip in {rtt:?}");
                Some(rtt)
            }
            Err(e) => {
                log::trace!("[-] {ip}:{port} failed the real http check: {e}");
                None
            }
        };
    }

    if crate::masque_h2::enabled() {
        let cfg = crate::masque_h2::H2TunnelConfig {
            peer: SocketAddr::new(ip, port),
            sni: probe.sni.clone(),
            authority: probe.authority.clone(),
            path: probe.path.clone(),
            cert_pem: probe.cert_pem.to_vec(),
            key_pem: probe.key_pem.to_vec(),
            local_ipv4: probe.local_ipv4,
            quiet: true,
            pin_endpoint: true,
            expected_pins: crate::consts::MASQUE_PINS.iter().map(|p| p.to_vec()).collect(),
        };
        return match crate::masque_h2::verify_h2(&cfg, timeout).await {
            Ok(rtt) => Some(rtt),
            Err(e) => {
                log::trace!("h2 probe {ip}:{port} -> {e}");
                None
            }
        };
    }

    let vp = quic::VerifyParams {
        peer: SocketAddr::new(ip, port),
        sni: probe.sni.clone(),
        authority: probe.authority.clone(),
        path: probe.path.clone(),
        cert_pem: probe.cert_pem.to_vec(),
        key_pem: probe.key_pem.to_vec(),
        ech_config_list: probe.ech_config_list.as_ref().map(|a| a.to_vec()),
        noize: probe.noize.clone(),
        timeout,
        local_ipv4: probe.local_ipv4,
    };

    match quic::verify_masque(&vp).await {
        Ok(rtt) => Some(rtt),
        Err(e) => {
            log::trace!("probe {ip}:{port} -> {e}");
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;
    use std::time::Instant;

    use futures::stream::StreamExt;
    use rand::RngExt;

    #[test]
    fn the_documented_zero_trust_masque_ingress_range_is_scanned() {
        assert!(MASQUE_CIDRS_V4.contains(&"162.159.197.0/24"));
        assert!(MASQUE_CIDRS_V6.contains(&"2606:4700:102::/48"));
    }

    #[test]
    fn the_dns_over_https_ranges_are_swept_last_because_they_never_serve_masque() {
        let tail = &MASQUE_CIDRS_V4[MASQUE_CIDRS_V4.len() - MASQUE_DOH_CIDRS_V4.len()..];
        for entry in MASQUE_DOH_CIDRS_V4 {
            assert!(tail.contains(entry), "{entry} should be at the end");
        }
    }

    #[test]
    fn the_documented_default_masque_port_leads_the_sweep() {
        assert_eq!(MASQUE_PORTS.first(), Some(&443));
    }

    #[test]
    fn the_documented_masque_fallback_ports_keep_their_documented_order() {
        assert_eq!(MASQUE_PORTS, &[443, 500, 1701, 4500, 4443, 8443, 8095]);
    }

    #[test]
    fn without_a_team_the_range_order_is_left_alone() {
        std::env::remove_var("AETHER_TEAM");
        assert_eq!(
            prioritize(MASQUE_CIDRS_V4, MASQUE_ZT_CIDRS_V4),
            MASQUE_CIDRS_V4.to_vec(),
        );
    }

    #[test]
    fn a_pinned_range_replaces_the_built_in_list_and_accepts_the_documented_shapes() {
        std::env::set_var("AETHER_MASQUE_CIDRS", "8.6.112.x, 188.114.96.0/24 , junk");
        let pinned = pinned_cidrs_v4().expect("a pinned range");
        std::env::remove_var("AETHER_MASQUE_CIDRS");
        assert_eq!(pinned, vec!["8.6.112.0/24", "188.114.96.0/24"]);
    }

    #[test]
    fn nothing_pinned_means_nothing_overridden() {
        std::env::remove_var("AETHER_MASQUE_CIDRS");
        std::env::remove_var("AETHER_SCAN_CIDRS");
        assert!(pinned_cidrs_v4().is_none());
    }

    #[test]
    fn the_documented_masque_fallback_ports_are_all_covered() {
        for port in [443u16, 500, 1701, 4443, 4500, 8443, 8095] {
            assert!(
                MASQUE_PORTS.contains(&port),
                "documented fallback port {port} should be scanned",
            );
        }
    }

    #[test]
    fn every_masque_prefix_and_seed_parses() {
        for entry in MASQUE_CIDRS_V4 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<Ipv4Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
        for entry in MASQUE_CIDRS_V6 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<Ipv6Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
        for seed in MASQUE_SEEDS {
            assert!(seed.parse::<Ipv4Addr>().is_ok(), "{seed}");
        }
        for seed in MASQUE_SEEDS_V6 {
            assert!(seed.parse::<Ipv6Addr>().is_ok(), "{seed}");
        }
    }

    // ---- live-network report (never runs in CI) -------------------------
    //
    // A hand-run tool, not a test: it says which WARP ranges answer from the
    // network you are sitting on, which is the only way to tell "these ranges
    // are filtered here" apart from "udp is filtered here".

    async fn quic_answers(peer: SocketAddr, timeout: Duration) -> Option<Duration> {
        let bind = if peer.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" };
        let sock = tokio::net::UdpSocket::bind(bind).await.ok()?;
        sock.connect(peer).await.ok()?;
        let local = sock.local_addr().ok()?;

        let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION).ok()?;
        config.set_application_protos(&[b"h3"]).ok()?;
        config.verify_peer(false);
        config.set_max_idle_timeout(timeout.as_millis() as u64);
        config.set_initial_max_data(1_000_000);
        config.set_initial_max_stream_data_bidi_local(100_000);
        config.set_initial_max_streams_bidi(4);

        let mut scid = [0u8; 16];
        rand::rng().fill(&mut scid[..]);
        let scid = quiche::ConnectionId::from_ref(&scid);

        let sni = crate::consts::CONNECT_SNI;
        let mut conn = quiche::connect(Some(sni), &scid, local, peer, &mut config).ok()?;

        let mut out = [0u8; 1350];
        let (written, _) = conn.send(&mut out).ok()?;

        let started = Instant::now();
        sock.send(&out[..written]).await.ok()?;

        let mut buf = [0u8; 1500];
        match tokio::time::timeout(timeout, sock.recv(&mut buf)).await {
            Ok(Ok(read)) if read > 0 => Some(started.elapsed()),
            _ => None,
        }
    }

    async fn tcp_answers(peer: SocketAddr, timeout: Duration) -> Option<Duration> {
        let started = Instant::now();
        match tokio::time::timeout(timeout, tokio::net::TcpStream::connect(peer)).await {
            Ok(Ok(_)) => Some(started.elapsed()),
            _ => None,
        }
    }

    async fn first_answer(
        targets: &[SocketAddr],
        timeout: Duration,
        attempts: u32,
        udp: bool,
    ) -> Option<(SocketAddr, Duration)> {
        for _ in 0..attempts {
            let probes = targets.iter().copied().map(|peer| async move {
                let rtt = match udp {
                    true => quic_answers(peer, timeout).await,
                    false => tcp_answers(peer, timeout).await,
                };
                (peer, rtt)
            });

            let results: Vec<(SocketAddr, Option<Duration>)> = futures::stream::iter(probes)
                .buffer_unordered(targets.len().max(1))
                .collect()
                .await;

            if let Some((peer, Some(rtt))) = results.into_iter().find(|(_, rtt)| rtt.is_some()) {
                return Some((peer, rtt));
            }
        }
        None
    }

    fn hosts_of(cidr: &str, tails: &[u8]) -> Vec<Ipv4Addr> {
        let base: Ipv4Addr = cidr.split('/').next().unwrap().parse().expect("cidr base");
        let octets = base.octets();
        tails
            .iter()
            .map(|tail| Ipv4Addr::new(octets[0], octets[1], octets[2], *tail))
            .collect()
    }

    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "probes the live cloudflare edge from this network to see which masque ranges answer"]
    async fn report_which_masque_ranges_answer_on_this_network() {
        const TAILS: &[u8] = &[1, 2, 3];

        let timeout = Duration::from_millis(
            std::env::var("AETHER_PROBE_TIMEOUT_MS")
                .ok()
                .and_then(|raw| raw.trim().parse::<u64>().ok())
                .filter(|ms| *ms > 0)
                .unwrap_or(10_000),
        );

        let attempts = std::env::var("AETHER_PROBE_ATTEMPTS")
            .ok()
            .and_then(|raw| raw.trim().parse::<u32>().ok())
            .filter(|n| *n > 0)
            .unwrap_or(2);

        let ports: Vec<u16> = match std::env::var("AETHER_PROBE_PORTS") {
            Ok(raw) => raw
                .split(',')
                .filter_map(|p| p.trim().parse::<u16>().ok())
                .collect(),
            Err(_) => vec![443],
        };
        let ports = if ports.is_empty() { vec![443] } else { ports };

        println!();
        println!("probing the masque ranges from this network");
        println!("  udp timeout {timeout:?}, {attempts} attempt(s), hosts {TAILS:?}");
        println!("  udp ports {ports:?}");
        println!("  override: AETHER_PROBE_TIMEOUT_MS, AETHER_PROBE_ATTEMPTS, AETHER_PROBE_PORTS");
        println!();

        let control: Vec<SocketAddr> = vec![
            "1.1.1.1:443".parse().unwrap(),
            "8.8.8.8:443".parse().unwrap(),
        ];

        let control_quic = first_answer(&control, timeout, attempts, true).await;
        let control_tcp = first_answer(&control, timeout, attempts, false).await;

        println!("control targets (public resolvers, not cloudflare warp edges)");
        match control_quic {
            Some((peer, rtt)) => println!("  quic/udp 443 works: {peer} in {}ms", rtt.as_millis()),
            None => println!("  quic/udp 443 got no answer at all"),
        }
        match control_tcp {
            Some((peer, rtt)) => println!("  tcp 443 works:      {peer} in {}ms", rtt.as_millis()),
            None => println!("  tcp 443 got no answer at all"),
        }
        println!();

        let mut udp_ok = Vec::new();
        let mut tcp_only = Vec::new();
        let mut silent = Vec::new();

        for cidr in MASQUE_CIDRS_V4 {
            let note = if MASQUE_DOCUMENTED_CIDRS_V4.contains(cidr) {
                " [documented]"
            } else if MASQUE_DOH_CIDRS_V4.contains(cidr) {
                " [dns-over-https]"
            } else {
                ""
            };

            let hosts = hosts_of(cidr, TAILS);

            let mut udp_hit: Option<(SocketAddr, Duration)> = None;
            for port in &ports {
                let targets: Vec<SocketAddr> = hosts
                    .iter()
                    .map(|ip| SocketAddr::new(IpAddr::V4(*ip), *port))
                    .collect();
                udp_hit = first_answer(&targets, timeout, attempts, true).await;
                if udp_hit.is_some() {
                    break;
                }
            }

            let tcp_targets: Vec<SocketAddr> = hosts
                .iter()
                .map(|ip| SocketAddr::new(IpAddr::V4(*ip), 443))
                .collect();
            let tcp_hit = first_answer(&tcp_targets, timeout, attempts, false).await;

            match (udp_hit, tcp_hit) {
                (Some((peer, rtt)), _) => {
                    println!("  UDP OK    {cidr}{note}  {peer} in {}ms", rtt.as_millis());
                    udp_ok.push(*cidr);
                }
                (None, Some((peer, rtt))) => {
                    println!(
                        "  TCP ONLY  {cidr}{note}  {peer} in {}ms, udp stayed silent",
                        rtt.as_millis(),
                    );
                    tcp_only.push(*cidr);
                }
                (None, None) => {
                    println!("  SILENT    {cidr}{note}");
                    silent.push(*cidr);
                }
            }
        }

        println!();
        println!("masque over quic works on ({}):", udp_ok.len());
        for cidr in &udp_ok {
            println!("  {cidr}");
        }
        println!();
        println!("reachable over tcp only ({}):", tcp_only.len());
        for cidr in &tcp_only {
            println!("  {cidr}");
        }
        println!();
        println!("no answer on either ({}):", silent.len());
        for cidr in &silent {
            println!("  {cidr}");
        }

        println!();
        println!("verdict");
        if !udp_ok.is_empty() {
            println!("  put these ranges first in MASQUE_CIDRS_V4: {udp_ok:?}");
        } else if control_quic.is_none() {
            println!("  this network answers no quic at all, not even a public resolver,");
            println!("  so udp 443 is blocked here rather than these ranges being blocked.");
            println!("  reordering MASQUE_CIDRS_V4 cannot help; masque needs its http/2");
            println!("  fallback over tcp 443 (--masque-http2) on this network.");
        } else {
            println!("  quic works to other hosts but every warp range stayed silent,");
            println!("  so these ranges really are filtered here.");
            if !tcp_only.is_empty() {
                println!("  the tcp-only ranges above can still carry masque over http/2.");
            }
        }
        println!();
    }
}
