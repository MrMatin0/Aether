//! The endpoint scanner.
//!
//! ONE scanner, THREE modes, both transports.
//!
//! ROOT CAUSE THIS REPLACES: the scan used to exist twice. `prober.rs`
//! (MASQUE over QUIC/H2) and `wg_prober.rs` (WireGuard) each carried their own
//! `ScanMode` enum with five variants, their own tuning table, their own
//! candidate builder, their own CIDR sampler and their own copy of the
//! `select!` collection loop. That is ten tuning tables and two subtly
//! different scanners for one job, so:
//!
//!   * nobody could say what a mode DID - "thorough" meant a full /24 sweep on
//!     MASQUE and a sampled sweep with four port waves on WireGuard;
//!   * a fix to one path (port rotation, exclusions, custom ranges) silently
//!     did not apply to the other;
//!   * five modes in the UI described the same three intentions with two
//!     duplicates: "balanced" and "thorough" only differed in patience, and
//!     "stealth" was "balanced, but slower".
//!
//! So there is now one scanner and three modes, and a mode is nothing but a
//! [`ScanTuning`] row:
//!
//!   * [`ScanMode::Turbo`]  - first edge that answers wins. Seconds.
//!   * [`ScanMode::Precise`] - collect several working edges, keep the fastest.
//!   * [`ScanMode::Ultra`]  - only accept an edge that carries a REAL request
//!     end to end (what used to be called "ironclad"), and still rank by the
//!     round trip that proved it.
//!
//! The transports keep exactly what is transport specific: which addresses and
//! ports to consider, and how to verify one candidate. Everything else -
//! planning the target list, pacing the probes, the deadlines, picking a
//! winner - is here.

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::{Duration, Instant};

use futures::stream::StreamExt;
use rand::RngExt;

/// Which IP families a scan may consider.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpScan {
    V4,
    V6,
    Both,
}

impl IpScan {
    pub fn parse(s: &str) -> IpScan {
        match s.trim().to_lowercase().as_str() {
            "6" | "v6" | "ipv6" => IpScan::V6,
            "both" | "all" | "dual" => IpScan::Both,
            _ => IpScan::V4,
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            IpScan::V4 => "ipv4",
            IpScan::V6 => "ipv6",
            IpScan::Both => "dual-stack",
        }
    }

    pub fn want_v4(&self) -> bool {
        matches!(self, IpScan::V4 | IpScan::Both)
    }

    pub fn want_v6(&self) -> bool {
        matches!(self, IpScan::V6 | IpScan::Both)
    }
}

/// Which transport is being scanned.
///
/// The two probes cost very different amounts: a WireGuard handshake is two
/// UDP packets, while one MASQUE candidate is a full QUIC (or TLS/HTTP2)
/// handshake plus a CONNECT-IP exchange. The same mode therefore has to buy a
/// different amount of parallelism and patience per transport, which is the
/// ONLY reason [`ScanMode::tuning`] takes this.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    Masque,
    WireGuard,
}

/// How hard the scanner tries. Three modes, and that is the whole surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanMode {
    /// Take the first edge that answers.
    Turbo,
    /// Collect a handful of working edges and keep the fastest. The default.
    Precise,
    /// Only accept an edge that carries a real request end to end.
    Ultra,
}

impl ScanMode {
    /// Reads a mode from a flag value, a config file or an API payload.
    ///
    /// The five pre-1.4.6 names are still accepted and land on the closest of
    /// the three, so a saved profile, an old `--thorough` in someone's script
    /// and an `AETHER_SCAN=ironclad` in a service unit all keep working.
    pub fn parse(s: &str) -> ScanMode {
        match s.trim().to_lowercase().as_str() {
            "turbo" | "fast" | "quick" | "1" => ScanMode::Turbo,
            "ultra" | "ultra-precise" | "ultraprecise" | "very-precise" | "veryprecise"
            | "ironclad" | "real" | "verify" | "guaranteed" | "3" => ScanMode::Ultra,
            // precise / accurate and the legacy balanced, thorough, deep, pro,
            // stealth, quiet - plus anything unrecognised - are the default.
            _ => ScanMode::Precise,
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            ScanMode::Turbo => "turbo",
            ScanMode::Precise => "precise",
            ScanMode::Ultra => "ultra",
        }
    }

    /// The whole definition of a mode, for one transport.
    pub fn tuning(&self, family: Family) -> ScanTuning {
        match (family, self) {
            // ---- MASQUE: one candidate is a full QUIC/TLS handshake --------
            (Family::Masque, ScanMode::Turbo) => ScanTuning {
                concurrency: 24,
                probe_timeout: Duration::from_millis(5_000),
                budget: Duration::from_secs(45),
                settle: Duration::ZERO,
                target_hits: 1,
                sample_per_cidr: 64,
                full_subnet: false,
                port_waves: 1,
                deep_verify: false,
            },
            (Family::Masque, ScanMode::Precise) => ScanTuning {
                concurrency: 16,
                probe_timeout: Duration::from_millis(6_000),
                budget: Duration::from_secs(150),
                settle: Duration::from_secs(15),
                target_hits: 6,
                sample_per_cidr: 140,
                full_subnet: false,
                port_waves: 2,
                deep_verify: false,
            },
            (Family::Masque, ScanMode::Ultra) => ScanTuning {
                // A real HTTP round trip per candidate is expensive AND loud,
                // so this mode goes wide-slow instead of wide-fast.
                concurrency: 6,
                probe_timeout: Duration::from_millis(12_000),
                budget: Duration::from_secs(300),
                settle: Duration::from_secs(20),
                target_hits: 3,
                sample_per_cidr: 160,
                full_subnet: false,
                port_waves: 2,
                deep_verify: true,
            },

            // ---- WireGuard: a handshake plus a data-plane check ------------
            (Family::WireGuard, ScanMode::Turbo) => ScanTuning {
                concurrency: 16,
                probe_timeout: Duration::from_millis(4_000),
                budget: Duration::from_secs(35),
                settle: Duration::ZERO,
                target_hits: 1,
                sample_per_cidr: 40,
                full_subnet: false,
                port_waves: 2,
                deep_verify: false,
            },
            (Family::WireGuard, ScanMode::Precise) => ScanTuning {
                concurrency: 10,
                probe_timeout: Duration::from_millis(7_000),
                budget: Duration::from_secs(120),
                settle: Duration::from_secs(12),
                target_hits: 5,
                sample_per_cidr: 120,
                full_subnet: false,
                port_waves: 3,
                deep_verify: false,
            },
            (Family::WireGuard, ScanMode::Ultra) => ScanTuning {
                concurrency: 5,
                probe_timeout: Duration::from_millis(12_000),
                budget: Duration::from_secs(240),
                settle: Duration::from_secs(18),
                target_hits: 3,
                sample_per_cidr: 120,
                full_subnet: false,
                port_waves: 3,
                deep_verify: true,
            },
        }
    }

    /// Legacy spellings, kept as aliases so older call sites keep compiling.
    /// They are not modes: each one IS one of the three above.
    #[allow(non_upper_case_globals)]
    pub const Balanced: ScanMode = ScanMode::Precise;
    #[allow(non_upper_case_globals)]
    pub const Thorough: ScanMode = ScanMode::Precise;
    #[allow(non_upper_case_globals)]
    pub const Stealth: ScanMode = ScanMode::Precise;
    #[allow(non_upper_case_globals)]
    pub const Ironclad: ScanMode = ScanMode::Ultra;
}

/// A mode, expanded into numbers. This is the only knob set in the scanner.
#[derive(Debug, Clone, Copy)]
pub struct ScanTuning {
    /// How many candidates are in flight at once.
    pub concurrency: usize,
    /// How long ONE candidate may take before it counts as silent.
    pub probe_timeout: Duration,
    /// Hard ceiling for the whole scan.
    pub budget: Duration,
    /// After enough endpoints are found, how much longer to listen for a
    /// faster one. Zero means "stop at the first sufficient result".
    pub settle: Duration,
    /// How many verified endpoints are "enough". 0 = spend the whole budget.
    pub target_hits: usize,
    /// Addresses sampled per range, when not sweeping the whole range.
    pub sample_per_cidr: usize,
    /// Enumerate every host in each range instead of sampling it.
    pub full_subnet: bool,
    /// How many passes over the sampled pool, rotating the fallback ports.
    pub port_waves: usize,
    /// Require a real end-to-end request per candidate, not just a handshake.
    pub deep_verify: bool,
}

/// One endpoint that answered, and how long it took to prove it.
#[derive(Debug, Clone, Copy)]
pub struct Hit {
    pub ip: IpAddr,
    pub port: u16,
    pub rtt: Duration,
}

/// What a transport wants scanned. Addresses and ports only - no policy.
pub struct TargetPlan<'a> {
    pub ip: IpScan,
    /// Fallback ports, MOST DOCUMENTED FIRST: `ports[0]` leads every pass.
    pub ports: &'a [u16],
    /// Known-good addresses, tried before anything is sampled.
    pub anchors_v4: &'a [Ipv4Addr],
    pub anchors_v6: &'a [Ipv6Addr],
    pub cidrs_v4: &'a [String],
    pub cidrs_v6: &'a [String],
    /// Endpoints on cooldown; never offered to the verifier.
    pub excluded: &'a HashSet<SocketAddr>,
}

/// Dedupes a port list and guarantees it is never empty.
pub fn dedup_ports(ports: &[u16], fallback: u16) -> Vec<u16> {
    let mut seen: HashSet<u16> = HashSet::new();
    let deduped: Vec<u16> = ports.iter().copied().filter(|p| seen.insert(*p)).collect();
    if deduped.is_empty() {
        vec![fallback]
    } else {
        deduped
    }
}

/// Confirms the requested IP families are usable on this host.
///
/// Returns the family set to actually scan, or `None` when the caller asked
/// for IPv6 ONLY on a host that has no IPv6 route (there is nothing to try).
pub async fn usable_ip(requested: IpScan) -> Option<IpScan> {
    if !requested.want_v6() || host_has_ipv6().await {
        return Some(requested);
    }
    if requested.want_v4() {
        log::warn!("[-] host has no IPv6 route; falling back to IPv4-only scan");
        return Some(IpScan::V4);
    }
    log::warn!("[-] host has no IPv6 route; IPv6 scan needs native IPv6 connectivity");
    None
}

/// True when this host can actually reach an IPv6 destination.
pub async fn host_has_ipv6() -> bool {
    match tokio::net::UdpSocket::bind("[::]:0").await {
        Ok(sock) => sock.connect("[2606:4700:d0::a29f:c001]:443").await.is_ok(),
        Err(_) => false,
    }
}

/// Builds the ordered candidate list.
///
/// The ORDER is the scanner's most valuable asset: every mode walks the same
/// list and simply stops at a different point, so the earliest candidates have
/// to be the likeliest to work.
///
///   1. anchors on the documented port - a known edge on the port that is
///      supposed to serve it is the cheapest possible win;
///   2. anchors on each fallback port - when the documented port is filtered
///      (routine on Iranian mobile), a known-good address on port 500 or 4500
///      beats a random address on the port that is already blocked. The old
///      MASQUE builder put this LAST, after tens of thousands of sampled
///      addresses, so a network that only filtered UDP/443 was never rescued
///      inside the budget;
///   3. the sampled pool, interleaved ACROSS ranges (one address from each
///      range, then the next) so a dead range costs one probe in turn instead
///      of a whole prefix of the budget;
///   4. further passes over the pool on rotating fallback ports, for the modes
///      that bought the time for them.
pub fn build_targets(plan: &TargetPlan<'_>, tuning: &ScanTuning) -> Vec<(IpAddr, u16)> {
    let ports = dedup_ports(plan.ports, 443);
    let primary = ports[0];
    let alternates: Vec<u16> = ports[1..].to_vec();

    let mut anchors: Vec<IpAddr> = Vec::new();
    if plan.ip.want_v4() {
        anchors.extend(plan.anchors_v4.iter().copied().map(IpAddr::V4));
    }
    if plan.ip.want_v6() {
        anchors.extend(plan.anchors_v6.iter().copied().map(IpAddr::V6));
    }

    let mut pool: Vec<IpAddr> = Vec::new();
    if plan.ip.want_v4() {
        let groups: Vec<Vec<Ipv4Addr>> = plan
            .cidrs_v4
            .iter()
            .map(|cidr| {
                if tuning.full_subnet {
                    enumerate_cidr_v4(cidr)
                } else {
                    sample_cidr_v4(cidr, tuning.sample_per_cidr)
                }
            })
            .collect();
        pool.extend(interleave(&groups).into_iter().map(IpAddr::V4));
    }
    if plan.ip.want_v6() {
        let per = if tuning.sample_per_cidr == 0 {
            96
        } else {
            tuning.sample_per_cidr
        };
        let groups: Vec<Vec<Ipv6Addr>> = plan
            .cidrs_v6
            .iter()
            .map(|cidr| sample_cidr_v6(cidr, per, plan.cidrs_v4))
            .collect();
        pool.extend(interleave(&groups).into_iter().map(IpAddr::V6));
    }

    let mut out: Vec<(IpAddr, u16)> = Vec::with_capacity(anchors.len() + pool.len());
    let mut seen: HashSet<(IpAddr, u16)> = HashSet::new();
    let excluded = plan.excluded;
    let mut push = |ip: IpAddr, port: u16| {
        if excluded.contains(&SocketAddr::new(ip, port)) {
            return;
        }
        if seen.insert((ip, port)) {
            out.push((ip, port));
        }
    };

    for ip in &anchors {
        push(*ip, primary);
    }
    for port in &alternates {
        for ip in &anchors {
            push(*ip, *port);
        }
    }
    for ip in &pool {
        push(*ip, primary);
    }
    if !alternates.is_empty() {
        for wave in 1..tuning.port_waves.max(1) {
            for (index, ip) in pool.iter().enumerate() {
                push(*ip, alternates[(index + wave - 1) % alternates.len()]);
            }
        }
    }

    out
}

/// Runs the scan and returns the endpoints that passed, fastest first, one per
/// address.
///
/// `want` is how many DISTINCT addresses the caller needs (warp-in-warp needs
/// two, everything else needs one). The sweep ends on the first of:
///
///   * enough distinct addresses AND enough total hits for the mode - then, if
///     the mode has a settle window, it keeps listening for that long so a
///     faster edge that is still in flight can still win;
///   * the candidate list is exhausted;
///   * the mode's budget runs out.
pub async fn sweep<F, Fut>(
    label: &str,
    targets: Vec<(IpAddr, u16)>,
    tuning: &ScanTuning,
    want: usize,
    verify: F,
) -> Vec<Hit>
where
    F: Fn(IpAddr, u16) -> Fut,
    Fut: std::future::Future<Output = Option<Duration>>,
{
    let want = want.max(1);
    // 0 means "never finish early", so nothing can reach the bar.
    let need_hits = if tuning.target_hits == 0 {
        usize::MAX
    } else {
        tuning.target_hits.max(want)
    };

    let probe = &verify;
    let stream = futures::stream::iter(targets)
        .map(move |(ip, port)| async move {
            probe(ip, port).await.map(|rtt| Hit { ip, port, rtt })
        })
        .buffer_unordered(tuning.concurrency.max(1));
    tokio::pin!(stream);

    let deadline = Instant::now() + tuning.budget;
    let mut hits: Vec<Hit> = Vec::new();
    let mut settle_until: Option<Instant> = None;

    loop {
        let horizon = match settle_until {
            Some(settle) => settle.min(deadline),
            None => deadline,
        };
        let remaining = horizon.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            log_stop(label, &hits, settle_until.is_some());
            break;
        }

        tokio::select! {
            item = stream.next() => match item {
                None => {
                    log::debug!("[*] {label} scan tried every candidate it had");
                    break;
                }
                Some(None) => continue,
                Some(Some(hit)) => {
                    log::info!(
                        "[+] {label} candidate ok {}:{} rtt={:?}",
                        hit.ip,
                        hit.port,
                        hit.rtt,
                    );
                    hits.push(hit);
                    let distinct = rank(&hits).len();
                    if distinct >= want && hits.len() >= need_hits {
                        if tuning.settle.is_zero() {
                            break;
                        }
                        if settle_until.is_none() {
                            log::info!(
                                "[+] {label} scan has {} endpoint(s); listening {:?} longer for a faster one",
                                distinct,
                                tuning.settle,
                            );
                            settle_until = Some(Instant::now() + tuning.settle);
                        }
                    }
                }
            },
            _ = tokio::time::sleep(remaining) => {
                log_stop(label, &hits, settle_until.is_some());
                break;
            }
        }
    }

    rank(&hits)
}

fn log_stop(label: &str, hits: &[Hit], settling: bool) {
    if hits.is_empty() {
        log::warn!("[-] {label} scan budget spent with no endpoint");
    } else if settling {
        log::info!("[+] {label} scan saw nothing faster; finalizing the selection");
    } else {
        log::warn!("[-] {label} scan budget spent; keeping the best of what answered");
    }
}

/// Fastest first, one entry per address.
///
/// Two ports on ONE edge are one choice, not two: warp-in-warp needs two
/// genuinely different addresses for its hops, and the fastest port on an
/// address is the only one worth keeping anyway.
pub fn rank(hits: &[Hit]) -> Vec<Hit> {
    let mut sorted = hits.to_vec();
    sorted.sort_by_key(|hit| hit.rtt);
    let mut seen: HashSet<IpAddr> = HashSet::new();
    sorted
        .into_iter()
        .filter(|hit| seen.insert(hit.ip))
        .collect()
}

/// Round-robins across groups: one item from each, then the next.
fn interleave<T: Copy>(groups: &[Vec<T>]) -> Vec<T> {
    let longest = groups.iter().map(|group| group.len()).max().unwrap_or(0);
    let total: usize = groups.iter().map(|group| group.len()).sum();
    let mut out: Vec<T> = Vec::with_capacity(total);
    for index in 0..longest {
        for group in groups {
            if let Some(value) = group.get(index) {
                out.push(*value);
            }
        }
    }
    out
}

// ---- range helpers ------------------------------------------------------
//
// One copy. These were duplicated verbatim in both probers, which is how the
// two of them drifted apart in the first place.

/// Accepts a range in any shape the settings screen says it accepts:
/// `188.114.96.0/24`, `8.6.112.x`, or a single address.
///
/// The old parser took CIDR only and silently dropped everything else, so a
/// range typed exactly as the hint suggested (`8.6.112.x`) left the scan
/// quietly running on the built-in list instead.
pub fn normalize_cidr_v4(raw: &str) -> Option<String> {
    let value = raw.trim();
    if value.is_empty() {
        return None;
    }
    if let Some((ip, prefix)) = value.split_once('/') {
        let addr = ip.trim().parse::<Ipv4Addr>().ok()?;
        let bits: u8 = prefix.trim().parse().ok()?;
        if bits > 32 {
            return None;
        }
        return Some(format!("{addr}/{bits}"));
    }
    let lowered = value.to_lowercase();
    if let Some(head) = lowered.strip_suffix(".x") {
        if head.split('.').count() != 3 {
            return None;
        }
        let addr = format!("{head}.0").parse::<Ipv4Addr>().ok()?;
        return Some(format!("{addr}/24"));
    }
    let addr = value.parse::<Ipv4Addr>().ok()?;
    Some(format!("{addr}/32"))
}

pub fn parse_cidr_v4(cidr: &str) -> Option<(u32, u8)> {
    let (ip, prefix) = cidr.split_once('/')?;
    Some((
        u32::from(ip.trim().parse::<Ipv4Addr>().ok()?),
        prefix.trim().parse().ok()?,
    ))
}

/// Every usable host in a range, network and broadcast excluded. Refuses
/// anything wider than a /20 so a typo cannot ask for a million probes.
pub fn enumerate_cidr_v4(cidr: &str) -> Vec<Ipv4Addr> {
    let (base, prefix) = match parse_cidr_v4(cidr) {
        Some(value) => value,
        None => return Vec::new(),
    };
    let host_bits = 32u32.saturating_sub(prefix as u32);
    if host_bits == 0 {
        return vec![Ipv4Addr::from(base)];
    }
    if host_bits > 12 {
        return Vec::new();
    }
    let size = 1u32 << host_bits;
    (1..size.saturating_sub(1))
        .map(|offset| Ipv4Addr::from(base + offset))
        .collect()
}

/// `n` random distinct hosts from a range.
pub fn sample_cidr_v4(cidr: &str, n: usize) -> Vec<Ipv4Addr> {
    let (base, prefix) = match parse_cidr_v4(cidr) {
        Some(value) => value,
        None => return Vec::new(),
    };
    let host_bits = 32u32.saturating_sub(prefix as u32);
    let size = if host_bits >= 32 {
        u32::MAX
    } else {
        1u32 << host_bits
    };
    if size <= 2 {
        return vec![Ipv4Addr::from(base)];
    }

    let usable = size - 2;
    let want = (n as u32).min(usable);
    let mut rng = rand::rng();
    let mut chosen: HashSet<u32> = HashSet::with_capacity(want as usize);
    let mut out = Vec::with_capacity(want as usize);

    while (out.len() as u32) < want {
        let offset = 1 + rng.random_range(0..usable);
        if chosen.insert(offset) {
            out.push(Ipv4Addr::from(base + offset));
        }
    }

    out
}

pub fn parse_cidr_v6(cidr: &str) -> Option<(u128, u8)> {
    let (ip, prefix) = cidr.split_once('/')?;
    Some((
        u128::from(ip.trim().parse::<Ipv6Addr>().ok()?),
        prefix.trim().parse().ok()?,
    ))
}

/// `n` addresses from an IPv6 range, each carrying an address from the v4
/// ranges in its low bits - which is how Cloudflare's edges are numbered, so
/// this hits real listeners instead of random darkness.
pub fn sample_cidr_v6(cidr: &str, n: usize, v4_cidrs: &[String]) -> Vec<Ipv6Addr> {
    let (base, prefix) = match parse_cidr_v6(cidr) {
        Some(value) => value,
        None => return Vec::new(),
    };
    if 128u32.saturating_sub(prefix as u32) == 0 {
        return vec![Ipv6Addr::from(base)];
    }

    let v4: Vec<(u32, u8)> = v4_cidrs
        .iter()
        .filter_map(|cidr| parse_cidr_v4(cidr))
        .collect();
    let mut rng = rand::rng();
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let embedded = if v4.is_empty() {
            rng.random::<u32>() as u128
        } else {
            let (network, bits) = v4[rng.random_range(0..v4.len())];
            let host_bits = 32u32.saturating_sub(bits as u32);
            let host = if host_bits == 0 {
                0
            } else {
                rng.random::<u32>() & ((1u32 << host_bits) - 1)
            };
            (network | host) as u128
        };
        out.push(Ipv6Addr::from(base | embedded));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tuning() -> ScanTuning {
        ScanMode::Precise.tuning(Family::WireGuard)
    }

    fn plan<'a>(
        ports: &'a [u16],
        anchors: &'a [Ipv4Addr],
        cidrs: &'a [String],
        excluded: &'a HashSet<SocketAddr>,
    ) -> TargetPlan<'a> {
        TargetPlan {
            ip: IpScan::V4,
            ports,
            anchors_v4: anchors,
            anchors_v6: &[],
            cidrs_v4: cidrs,
            cidrs_v6: &[],
            excluded,
        }
    }

    #[test]
    fn the_ui_and_the_engine_agree_on_exactly_three_modes() {
        assert_eq!(ScanMode::Turbo.label(), "turbo");
        assert_eq!(ScanMode::Precise.label(), "precise");
        assert_eq!(ScanMode::Ultra.label(), "ultra");
    }

    #[test]
    fn the_five_legacy_mode_names_still_resolve() {
        assert_eq!(ScanMode::parse("turbo"), ScanMode::Turbo);
        assert_eq!(ScanMode::parse("balanced"), ScanMode::Precise);
        assert_eq!(ScanMode::parse("thorough"), ScanMode::Precise);
        assert_eq!(ScanMode::parse("stealth"), ScanMode::Precise);
        assert_eq!(ScanMode::parse("ironclad"), ScanMode::Ultra);
        assert_eq!(ScanMode::parse("ULTRA"), ScanMode::Ultra);
        assert_eq!(ScanMode::parse("nonsense"), ScanMode::Precise);
        assert_eq!(ScanMode::Balanced, ScanMode::Precise);
        assert_eq!(ScanMode::Ironclad, ScanMode::Ultra);
    }

    #[test]
    fn only_the_slowest_mode_pays_for_a_real_request_per_candidate() {
        for family in [Family::Masque, Family::WireGuard] {
            assert!(!ScanMode::Turbo.tuning(family).deep_verify);
            assert!(!ScanMode::Precise.tuning(family).deep_verify);
            assert!(ScanMode::Ultra.tuning(family).deep_verify);
        }
    }

    #[test]
    fn turbo_stops_at_the_first_answer_and_the_others_do_not() {
        for family in [Family::Masque, Family::WireGuard] {
            let turbo = ScanMode::Turbo.tuning(family);
            assert_eq!(turbo.target_hits, 1);
            assert!(turbo.settle.is_zero());
            assert!(ScanMode::Precise.tuning(family).target_hits > 1);
            assert!(!ScanMode::Precise.tuning(family).settle.is_zero());
        }
    }

    #[test]
    fn a_slower_mode_never_gets_a_smaller_budget() {
        for family in [Family::Masque, Family::WireGuard] {
            let turbo = ScanMode::Turbo.tuning(family).budget;
            let precise = ScanMode::Precise.tuning(family).budget;
            let ultra = ScanMode::Ultra.tuning(family).budget;
            assert!(turbo < precise, "turbo must be the quick one");
            assert!(precise < ultra, "ultra must be the patient one");
        }
    }

    #[test]
    fn anchors_lead_on_the_documented_port_then_on_every_fallback_port() {
        let ports = [2408u16, 500, 1701];
        let anchors: Vec<Ipv4Addr> = vec![
            "162.159.192.1".parse().unwrap(),
            "162.159.193.1".parse().unwrap(),
        ];
        let cidrs = vec!["162.159.195.0/24".to_string()];
        let excluded = HashSet::new();
        let targets = build_targets(&plan(&ports, &anchors, &cidrs, &excluded), &tuning());

        assert_eq!(targets[0], (IpAddr::V4(anchors[0]), 2408));
        assert_eq!(targets[1], (IpAddr::V4(anchors[1]), 2408));
        assert_eq!(targets[2], (IpAddr::V4(anchors[0]), 500));
        assert_eq!(targets[3], (IpAddr::V4(anchors[1]), 500));
        assert_eq!(targets[4], (IpAddr::V4(anchors[0]), 1701));
        assert_eq!(targets[5], (IpAddr::V4(anchors[1]), 1701));
        // Everything after the anchors is sampled, and the first pass over the
        // pool is on the documented port.
        assert_eq!(targets[6].1, 2408);
        assert!(targets[6].0.to_string().starts_with("162.159.195."));
    }

    #[test]
    fn the_pool_is_interleaved_across_ranges_so_a_dead_range_cannot_stall_the_scan() {
        let ports = [443u16];
        let cidrs = vec![
            "162.159.192.0/24".to_string(),
            "188.114.96.0/24".to_string(),
        ];
        let excluded = HashSet::new();
        let mut tuning = tuning();
        tuning.sample_per_cidr = 4;
        let targets = build_targets(&plan(&ports, &[], &cidrs, &excluded), &tuning);

        let first = targets[0].0.to_string();
        let second = targets[1].0.to_string();
        assert!(first.starts_with("162.159.192."), "got {first}");
        assert!(second.starts_with("188.114.96."), "got {second}");
    }

    #[test]
    fn extra_port_waves_retry_the_pool_without_repeating_a_pair() {
        let ports = [2408u16, 500];
        let cidrs = vec!["162.159.192.0/24".to_string()];
        let excluded = HashSet::new();
        let mut tuning = tuning();
        tuning.sample_per_cidr = 3;
        tuning.port_waves = 2;
        let targets = build_targets(&plan(&ports, &[], &cidrs, &excluded), &tuning);

        assert_eq!(targets.len(), 6, "three addresses over two port waves");
        let unique: HashSet<(IpAddr, u16)> = targets.iter().copied().collect();
        assert_eq!(unique.len(), targets.len(), "no pair may be probed twice");
        assert_eq!(
            targets[3..].iter().filter(|(_, port)| *port == 500).count(),
            3,
        );
    }

    #[test]
    fn a_cooled_down_endpoint_is_never_offered_to_the_verifier() {
        let ports = [2408u16];
        let anchors: Vec<Ipv4Addr> = vec!["162.159.192.1".parse().unwrap()];
        let cidrs: Vec<String> = Vec::new();
        let mut excluded = HashSet::new();
        excluded.insert("162.159.192.1:2408".parse::<SocketAddr>().unwrap());
        let targets = build_targets(&plan(&ports, &anchors, &cidrs, &excluded), &tuning());

        assert!(targets.is_empty(), "the only candidate was on cooldown");
    }

    #[test]
    fn two_ports_on_one_edge_count_as_a_single_choice() {
        let hits = vec![
            Hit {
                ip: "162.159.192.1".parse().unwrap(),
                port: 2408,
                rtt: Duration::from_millis(40),
            },
            Hit {
                ip: "162.159.192.1".parse().unwrap(),
                port: 500,
                rtt: Duration::from_millis(30),
            },
            Hit {
                ip: "162.159.195.4".parse().unwrap(),
                port: 4500,
                rtt: Duration::from_millis(55),
            },
        ];
        let ranked = rank(&hits);
        assert_eq!(ranked.len(), 2);
        assert_eq!(ranked[0].port, 500, "the quickest port on an address wins");
        assert_eq!(ranked[1].ip.to_string(), "162.159.195.4");
        assert!(rank(&[]).is_empty());
    }

    #[tokio::test]
    async fn turbo_returns_the_first_endpoint_that_answers() {
        let targets: Vec<(IpAddr, u16)> = vec![
            ("1.1.1.1".parse().unwrap(), 443),
            ("1.0.0.1".parse().unwrap(), 443),
        ];
        let tuning = ScanMode::Turbo.tuning(Family::Masque);
        let hits = sweep("test", targets, &tuning, 1, |ip, _port| async move {
            match ip.to_string().as_str() {
                "1.1.1.1" => Some(Duration::from_millis(12)),
                _ => None,
            }
        })
        .await;

        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].ip.to_string(), "1.1.1.1");
    }

    #[tokio::test]
    async fn a_precise_scan_keeps_the_fastest_of_what_answered() {
        let targets: Vec<(IpAddr, u16)> = vec![
            ("1.1.1.1".parse().unwrap(), 443),
            ("1.0.0.1".parse().unwrap(), 443),
            ("9.9.9.9".parse().unwrap(), 443),
        ];
        let mut tuning = ScanMode::Precise.tuning(Family::Masque);
        tuning.target_hits = 3;
        let hits = sweep("test", targets, &tuning, 1, |ip, _port| async move {
            match ip.to_string().as_str() {
                "1.1.1.1" => Some(Duration::from_millis(90)),
                "1.0.0.1" => Some(Duration::from_millis(20)),
                _ => Some(Duration::from_millis(50)),
            }
        })
        .await;

        assert_eq!(hits.len(), 3);
        assert_eq!(hits[0].ip.to_string(), "1.0.0.1");
    }

    #[tokio::test]
    async fn nothing_answering_is_reported_as_nothing_found() {
        let targets: Vec<(IpAddr, u16)> = vec![("1.1.1.1".parse().unwrap(), 443)];
        let tuning = ScanMode::Turbo.tuning(Family::Masque);
        let hits = sweep("test", targets, &tuning, 1, |_ip, _port| async { None }).await;
        assert!(hits.is_empty());
    }

    #[test]
    fn a_port_list_is_deduped_and_never_empty() {
        assert_eq!(dedup_ports(&[443, 443, 500], 443), vec![443, 500]);
        assert_eq!(dedup_ports(&[], 2408), vec![2408]);
    }

    #[test]
    fn a_range_may_be_written_the_way_the_settings_screen_says_it_can() {
        assert_eq!(
            normalize_cidr_v4(" 188.114.96.0/24 ").as_deref(),
            Some("188.114.96.0/24"),
        );
        assert_eq!(normalize_cidr_v4("8.6.112.x").as_deref(), Some("8.6.112.0/24"));
        assert_eq!(normalize_cidr_v4("8.6.112.X").as_deref(), Some("8.6.112.0/24"));
        assert_eq!(normalize_cidr_v4("1.2.3.4").as_deref(), Some("1.2.3.4/32"));
        assert!(normalize_cidr_v4("188.114.96.0/64").is_none());
        assert!(normalize_cidr_v4("nonsense").is_none());
        assert!(normalize_cidr_v4("").is_none());
    }

    #[test]
    fn a_sampled_range_stays_inside_the_range() {
        let hosts = sample_cidr_v4("188.114.96.0/24", 32);
        assert_eq!(hosts.len(), 32);
        for host in hosts {
            assert!(
                host.to_string().starts_with("188.114.96."),
                "{host} escaped its range",
            );
        }
        assert!(sample_cidr_v4("not a range", 4).is_empty());
    }

    #[test]
    fn a_full_sweep_covers_the_usable_hosts_and_refuses_absurd_ranges() {
        assert_eq!(enumerate_cidr_v4("162.159.192.0/24").len(), 254);
        assert!(enumerate_cidr_v4("10.0.0.0/8").is_empty());
    }

    #[test]
    fn sampled_ipv6_addresses_carry_a_v4_edge_in_their_low_bits() {
        let v4 = vec!["162.159.192.0/24".to_string()];
        let hosts = sample_cidr_v6("2606:4700:d0::/48", 8, &v4);
        assert_eq!(hosts.len(), 8);
        for host in hosts {
            assert!(
                host.to_string().starts_with("2606:4700:d0:"),
                "{host} escaped its range",
            );
        }
    }
}
