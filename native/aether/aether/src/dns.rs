//! Where the core gets its ECH key.
//!
//! Aether Mobile patch (fix/ech-from-core, 2026-10-08). One ECHConfigList per
//! process, looked up once and shared by everything that offers ECH: the
//! camouflaged route to the WARP API (apifront.rs) on every protocol, and the
//! MASQUE tunnel (lib.rs resolve_ech). The lookup is configured with upstream
//! 2.3.0's variables, so the next core sync lands on the same settings:
//!
//!   AETHER_ECH         --ech: auto, or a base64 ECHConfigList
//!   AETHER_ECH_DOMAIN  the name whose HTTPS record holds the key
//!                      (upstream's --ech-domain)
//!   AETHER_ECH_DNS     the resolver it is asked of, udp://ip[:port] or
//!                      tcp://ip[:port] (upstream's --ech-dns)
//!
//! With neither of the last two set the lookup is the one every core before
//! this made: cloudflare-ech.com and crypto.cloudflare.com, each asked of
//! 1.1.1.1, 1.0.0.1 and 8.8.8.8 over UDP. DNS-over-HTTPS needs upstream's
//! https.rs (core 2.3.0) and is refused here with a message that says so.

use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::timeout_at;

use crate::error::{AetherError, Result};

pub const BOOTSTRAP_DNS: &[&str] = &["1.1.1.1:53", "1.0.0.1:53", "8.8.8.8:53"];
pub const ECH_HOSTS: &[&str] = &["cloudflare-ech.com", "crypto.cloudflare.com"];

/// --ech: `auto`, or a base64 ECHConfigList.
pub const ECH_VARIABLE: &str = "AETHER_ECH";
/// The resolver the ECHConfigList is asked of (upstream's --ech-dns).
pub const ECH_DNS_VARIABLE: &str = "AETHER_ECH_DNS";
/// The domain whose ECHConfigList is offered (upstream's --ech-domain).
pub const ECH_DOMAIN_VARIABLE: &str = "AETHER_ECH_DOMAIN";

/// The resolver when only the domain is configured.
pub const DEFAULT_ECH_DNS: &str = "udp://1.1.1.1";
/// The domain when only the resolver is configured.
pub const DEFAULT_ECH_DOMAIN: &str = "cloudflare-ech.com";

const RR_HTTPS: u16 = 65;
const SVCPARAM_ECH: u16 = 5;

/// How long a configured lookup may take, over either transport.
const ECH_LOOKUP_TIMEOUT: Duration = Duration::from_secs(8);
/// How long each step of the unconfigured cascade may take.
const BOOTSTRAP_LOOKUP_TIMEOUT: Duration = Duration::from_secs(3);
/// Over UDP the question goes out again after this long without an answer.
const UDP_RESEND_AFTER: Duration = Duration::from_secs(2);
/// A lookup that failed is not repeated for this long: the WARP API and the
/// MASQUE tunnel both ask, seconds apart, and the second would only wait for
/// the same silence again.
const FAILURE_HOLD: Duration = Duration::from_secs(60);

/// The smallest ECHConfig contents worth considering: config id, kem, an x25519
/// public key, one cipher suite, max name length, a public name and extensions
/// come to well over this.
const MIN_ECH_CONFIG: usize = 32;

/// The key every ECH handshake of this process offers.
static SESSION_KEY: Mutex<Option<Vec<u8>>> = Mutex::new(None);
/// When the last lookup failed, and why.
static LAST_FAILURE: Mutex<Option<(Instant, String)>> = Mutex::new(None);

/// The resolver the ECHConfigList is asked of.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EchDns {
    Udp(SocketAddr),
    Tcp(SocketAddr),
}

impl EchDns {
    /// `udp://ip[:port]` or `tcp://ip[:port]`, on port 53 unless one is given,
    /// an IPv6 address in brackets.
    pub fn parse(value: &str) -> std::result::Result<Self, String> {
        let value = value.trim();
        if strip_scheme(value, "https://").is_some() {
            return Err(format!(
                "{value}: DNS-over-HTTPS for the ECH key needs core 2.3.0; use udp:// or tcp://"
            ));
        }
        let (rest, tcp) = if let Some(rest) = strip_scheme(value, "udp://") {
            (rest, false)
        } else if let Some(rest) = strip_scheme(value, "tcp://") {
            (rest, true)
        } else {
            return Err(format!("{value} is no udp:// or tcp:// address"));
        };
        let address = socket_address(rest.trim_end_matches('/'), 53)
            .ok_or_else(|| format!("{value} names no IP address"))?;
        Ok(if tcp {
            EchDns::Tcp(address)
        } else {
            EchDns::Udp(address)
        })
    }
}

impl std::fmt::Display for EchDns {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EchDns::Udp(address) => write!(f, "udp://{address}"),
            EchDns::Tcp(address) => write!(f, "tcp://{address}"),
        }
    }
}

/// One lookup: the HTTPS record of `domain`, asked of `dns`, within `budget`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EchLookup {
    pub dns: EchDns,
    pub domain: String,
    pub budget: Duration,
}

impl std::fmt::Display for EchLookup {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} via {}", self.domain, self.dns)
    }
}

/// `value` after `scheme`, which it starts with in any case; None when it does not.
fn strip_scheme<'a>(value: &'a str, scheme: &str) -> Option<&'a str> {
    let head = value.get(..scheme.len())?;
    if head.eq_ignore_ascii_case(scheme) {
        Some(&value[scheme.len()..])
    } else {
        None
    }
}

/// `text` as an address: `ip:port`, `[ipv6]:port`, or an IP address alone, on
/// `default_port`.
fn socket_address(text: &str, default_port: u16) -> Option<SocketAddr> {
    if let Ok(address) = text.parse::<SocketAddr>() {
        return Some(address);
    }
    if let Ok(ip) = text.parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, default_port));
    }
    let inner = text.strip_prefix('[')?.strip_suffix(']')?;
    inner
        .parse::<Ipv6Addr>()
        .ok()
        .map(|ip| SocketAddr::new(IpAddr::V6(ip), default_port))
}

/// Whether `name` is a domain whose HTTPS record can be asked for: labels of letters,
/// digits, '-' and '_', of 1 to 63 bytes each and 253 in all, a trailing dot allowed.
pub fn valid_domain(name: &str) -> bool {
    let name = name.strip_suffix('.').unwrap_or(name);
    !name.is_empty()
        && name.len() <= 253
        && name.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
}

fn configured(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// The lookups to make, in order, as the environment configures them.
pub fn lookup_plan() -> Result<Vec<EchLookup>> {
    plan_for(
        configured(ECH_DNS_VARIABLE).as_deref(),
        configured(ECH_DOMAIN_VARIABLE).as_deref(),
    )
}

/// A configured resolver or domain means exactly that one lookup, the other
/// half taking its default. Neither means the cascade older cores made.
fn plan_for(dns: Option<&str>, domain: Option<&str>) -> Result<Vec<EchLookup>> {
    if dns.is_none() && domain.is_none() {
        let mut plan = Vec::with_capacity(ECH_HOSTS.len() * BOOTSTRAP_DNS.len());
        for host in ECH_HOSTS {
            for server in BOOTSTRAP_DNS {
                if let Ok(address) = server.parse::<SocketAddr>() {
                    plan.push(EchLookup {
                        dns: EchDns::Udp(address),
                        domain: (*host).to_string(),
                        budget: BOOTSTRAP_LOOKUP_TIMEOUT,
                    });
                }
            }
        }
        return Ok(plan);
    }

    let dns = EchDns::parse(dns.unwrap_or(DEFAULT_ECH_DNS))
        .map_err(|e| AetherError::Ech(format!("{ECH_DNS_VARIABLE}: {e}")))?;
    let name = domain.unwrap_or(DEFAULT_ECH_DOMAIN);
    if !valid_domain(name) {
        return Err(AetherError::Ech(format!(
            "{ECH_DOMAIN_VARIABLE}: {name} is no domain name"
        )));
    }
    Ok(vec![EchLookup {
        dns,
        domain: name.trim_end_matches('.').to_ascii_lowercase(),
        budget: ECH_LOOKUP_TIMEOUT,
    }])
}

/// What the session asked for with --ech.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EchRequest {
    Off,
    Auto,
    Given(String),
}

/// The session's --ech, read from AETHER_ECH.
pub fn ech_request() -> EchRequest {
    request_from(configured(ECH_VARIABLE).as_deref())
}

fn request_from(value: Option<&str>) -> EchRequest {
    match value {
        None => EchRequest::Off,
        Some(value) if value.eq_ignore_ascii_case("auto") => EchRequest::Auto,
        Some(value)
            if matches!(
                value.to_ascii_lowercase().as_str(),
                "off" | "0" | "false" | "no" | "none"
            ) =>
        {
            EchRequest::Off
        }
        Some(value) => EchRequest::Given(value.to_string()),
    }
}

/// True when the session asked for ECH (--ech auto or --ech <base64>).
pub fn ech_requested() -> bool {
    ech_request() != EchRequest::Off
}

/// An ECHConfigList is a 2 byte length followed by at least one ECHConfig
/// (2 byte version, 2 byte length, then the contents). A list whose prefix does
/// not match its size, or that is too short to hold a real config, is refused
/// by boring anyway; handing it out would poison every later attempt.
pub fn plausible_ech_list(list: &[u8]) -> bool {
    if list.len() < 2 + 4 + MIN_ECH_CONFIG {
        return false;
    }

    let declared = u16::from_be_bytes([list[0], list[1]]) as usize;
    if declared != list.len() - 2 {
        return false;
    }

    let first_config = u16::from_be_bytes([list[4], list[5]]) as usize;
    first_config >= MIN_ECH_CONFIG && 4 + first_config <= declared
}

/// The key this process already has, if any.
pub fn cached_key() -> Option<Vec<u8>> {
    SESSION_KEY.lock().ok().and_then(|guard| guard.clone())
}

/// Makes `list` the key of every later ECH handshake, but only one that looks
/// like a real ECHConfigList: a key an edge handed back as retry config takes
/// over from the one that was looked up. Returns whether it was kept.
pub fn remember(list: Vec<u8>) -> bool {
    if !plausible_ech_list(&list) {
        return false;
    }
    if let Ok(mut guard) = SESSION_KEY.lock() {
        *guard = Some(list);
    }
    if let Ok(mut guard) = LAST_FAILURE.lock() {
        *guard = None;
    }
    true
}

fn recent_failure() -> Option<String> {
    let guard = LAST_FAILURE.lock().ok()?;
    match guard.as_ref() {
        Some((at, reason)) if at.elapsed() < FAILURE_HOLD => Some(reason.clone()),
        _ => None,
    }
}

fn note_failure(reason: &str) {
    if let Ok(mut guard) = LAST_FAILURE.lock() {
        *guard = Some((Instant::now(), reason.to_string()));
    }
}

fn reason_of(error: AetherError) -> String {
    match error {
        AetherError::Ech(reason) => reason,
        other => other.to_string(),
    }
}

/// The ECHConfigList of this process: the one it already has, else the one the
/// configured lookup finds (see the module doc). Through the upstream proxy
/// when there is one.
pub async fn fetch_ech_config() -> Result<Vec<u8>> {
    if let Some(list) = cached_key() {
        return Ok(list);
    }
    if let Some(reason) = recent_failure() {
        return Err(AetherError::Ech(format!(
            "{reason} (not asked again for a minute)"
        )));
    }

    let plan = match lookup_plan() {
        Ok(plan) => plan,
        Err(error) => {
            let reason = reason_of(error);
            note_failure(&reason);
            return Err(AetherError::Ech(reason));
        }
    };

    let mut last = String::from("no lookup to make");
    for lookup in &plan {
        match run_lookup(lookup).await {
            Ok(list) if remember(list.clone()) => {
                log::info!(
                    "fetched ECHConfigList ({} bytes) for {} via {}",
                    list.len(),
                    lookup.domain,
                    lookup.dns
                );
                return Ok(list);
            }
            Ok(list) => {
                last = format!(
                    "{lookup} answered {} bytes that are no ECHConfigList",
                    list.len()
                );
                log::debug!("ech lookup: {last}");
            }
            Err(error) => {
                last = reason_of(error);
                log::debug!("ech lookup: {last}");
            }
        }
    }

    let reason = if plan.len() > 1 {
        format!(
            "no ECHConfigList resolved in {} lookups (last: {last})",
            plan.len()
        )
    } else {
        last
    };
    note_failure(&reason);
    Err(AetherError::Ech(reason))
}

/// The key every ECH handshake of the session offers: the one it already has
/// (looked up, or handed back by an edge), else the base64 list --ech was
/// given, else the configured lookup. None when there is no usable key.
pub async fn session_key() -> Option<Vec<u8>> {
    if let Some(list) = cached_key() {
        return Some(list);
    }

    if let EchRequest::Given(raw) = ech_request() {
        match crate::tls::decode_ech_config_list(&raw) {
            Ok(list) if remember(list.clone()) => {
                log::info!("[+] using the ECHConfigList from {ECH_VARIABLE}");
                return Some(list);
            }
            Ok(list) => log::warn!(
                "[-] {ECH_VARIABLE} holds {} bytes that are no ECHConfigList; looking one up instead",
                list.len()
            ),
            Err(e) => log::warn!(
                "[-] {ECH_VARIABLE} is not valid base64 ({e}); looking one up instead"
            ),
        }
    }

    match fetch_ech_config().await {
        Ok(list) => Some(list),
        Err(e) => {
            log::info!("[-] no ECHConfigList: {e}");
            None
        }
    }
}

async fn run_lookup(lookup: &EchLookup) -> Result<Vec<u8>> {
    let work = async {
        match lookup.dns {
            EchDns::Udp(server) => query_udp(server, &lookup.domain).await,
            EchDns::Tcp(server) => query_tcp(server, &lookup.domain).await,
        }
    };
    match tokio::time::timeout(lookup.budget, work).await {
        Ok(Ok(list)) => Ok(list),
        Ok(Err(error)) => Err(AetherError::Ech(format!(
            "{lookup} failed: {}",
            reason_of(error)
        ))),
        Err(_) => Err(AetherError::Ech(format!(
            "{} did not answer for {} within {:?}",
            lookup.dns, lookup.domain, lookup.budget
        ))),
    }
}

async fn query_udp(server: SocketAddr, domain: &str) -> Result<Vec<u8>> {
    let (sock, _, _detour) = crate::upstream::bind_via_upstream(server).await?;
    let (query, id) = build_query(domain, RR_HTTPS);
    let mut buf = [0u8; 4096];

    // Asked again and again until an answer comes or run_lookup gives up.
    loop {
        sock.send(&query).await?;
        let resend_at = tokio::time::Instant::now() + UDP_RESEND_AFTER;
        while let Ok(received) = timeout_at(resend_at, sock.recv(&mut buf)).await {
            let n = received?;
            if response_matches(&buf[..n], id, domain, RR_HTTPS) {
                return answer_ech(&buf[..n], domain);
            }
            log::debug!("discarding an ech dns reply that does not match the query");
        }
    }
}

async fn query_tcp(server: SocketAddr, domain: &str) -> Result<Vec<u8>> {
    let mut stream = match crate::upstream::configured() {
        Some(proxy) => proxy.connect(server).await.map_err(|e| {
            AetherError::Ech(format!("connect to {server} through the proxy: {e}"))
        })?,
        None => crate::egress::tcp_connect(server)
            .await
            .map_err(|e| AetherError::Ech(format!("connect to {server}: {e}")))?,
    };
    let (query, id) = build_query(domain, RR_HTTPS);
    stream.write_all(&tcp_message(&query)).await?;

    let mut length = [0u8; 2];
    stream.read_exact(&mut length).await?;
    let mut msg = vec![0u8; u16::from_be_bytes(length) as usize];
    stream.read_exact(&mut msg).await?;

    if !response_matches(&msg, id, domain, RR_HTTPS) {
        return Err(AetherError::Ech(
            "the reply does not match the query".into(),
        ));
    }
    answer_ech(&msg, domain)
}

/// `msg` as it goes over TCP: behind its length, in two bytes in network order (RFC
/// 1035, 4.2.2).
fn tcp_message(msg: &[u8]) -> Vec<u8> {
    let mut framed = Vec::with_capacity(msg.len() + 2);
    framed.extend_from_slice(&(msg.len() as u16).to_be_bytes());
    framed.extend_from_slice(msg);
    framed
}

/// The ech parameter of the HTTPS record in `msg`, a reply about `domain`.
fn answer_ech(msg: &[u8], domain: &str) -> Result<Vec<u8>> {
    match parse_https_ech(msg) {
        Some(ech) if !ech.is_empty() => Ok(ech),
        _ => Err(AetherError::Ech(format!(
            "{domain} has no HTTPS record with an ech parameter"
        ))),
    }
}

pub fn response_matches(
    msg: &[u8],
    expected_id: u16,
    expected_name: &str,
    expected_qtype: u16,
) -> bool {
    if msg.len() < 12 {
        return false;
    }
    if u16::from_be_bytes([msg[0], msg[1]]) != expected_id {
        return false;
    }
    if msg[2] & 0x80 == 0 {
        return false;
    }
    if u16::from_be_bytes([msg[4], msg[5]]) != 1 {
        return false;
    }

    let mut pos = 12;
    for label in expected_name.split('.') {
        if label.is_empty() {
            continue;
        }
        let len = match msg.get(pos) {
            Some(value) => *value as usize,
            None => return false,
        };
        if len != label.len() {
            return false;
        }
        pos += 1;
        let end = match pos.checked_add(len) {
            Some(value) if value <= msg.len() => value,
            _ => return false,
        };
        if !msg[pos..end].eq_ignore_ascii_case(label.as_bytes()) {
            return false;
        }
        pos = end;
    }

    if msg.get(pos) != Some(&0) {
        return false;
    }
    pos += 1;

    if pos + 4 > msg.len() {
        return false;
    }

    u16::from_be_bytes([msg[pos], msg[pos + 1]]) == expected_qtype
}

fn build_query(name: &str, qtype: u16) -> (Vec<u8>, u16) {
    let mut q = Vec::with_capacity(32 + name.len());
    let id: u16 = rand::random();
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00]);
    q.extend_from_slice(&[0x00, 0x01]);
    q.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in name.split('.') {
        if label.is_empty() {
            continue;
        }
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0x00);
    q.extend_from_slice(&qtype.to_be_bytes());
    q.extend_from_slice(&[0x00, 0x01]);
    (q, id)
}

fn parse_https_ech(msg: &[u8]) -> Option<Vec<u8>> {
    if msg.len() < 12 {
        return None;
    }
    let qd = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let an = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut pos = 12;

    for _ in 0..qd {
        pos = skip_name(msg, pos)?;
        pos = pos.checked_add(4)?;
    }

    for _ in 0..an {
        pos = skip_name(msg, pos)?;
        if pos + 10 > msg.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([msg[pos], msg[pos + 1]]);
        let rdlen = u16::from_be_bytes([msg[pos + 8], msg[pos + 9]]) as usize;
        pos += 10;
        if pos + rdlen > msg.len() {
            return None;
        }
        if rtype == RR_HTTPS {
            if let Some(ech) = parse_svcparams_ech(msg, pos, rdlen) {
                return Some(ech);
            }
        }
        pos += rdlen;
    }
    None
}

fn parse_svcparams_ech(msg: &[u8], rdata_start: usize, rdlen: usize) -> Option<Vec<u8>> {
    let end = rdata_start + rdlen;
    if rdata_start + 2 > end {
        return None;
    }
    let mut p = skip_name(msg, rdata_start + 2)?;

    while p + 4 <= end {
        let key = u16::from_be_bytes([msg[p], msg[p + 1]]);
        let len = u16::from_be_bytes([msg[p + 2], msg[p + 3]]) as usize;
        p += 4;
        if p + len > end {
            return None;
        }
        if key == SVCPARAM_ECH {
            return Some(msg[p..p + len].to_vec());
        }
        p += len;
    }
    None
}

fn skip_name(buf: &[u8], mut pos: usize) -> Option<usize> {
    loop {
        let len = *buf.get(pos)?;
        if len & 0xc0 == 0xc0 {
            return Some(pos + 2);
        }
        if len == 0 {
            return Some(pos + 1);
        }
        pos += 1 + len as usize;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reply(id: u16, name: &str, qtype: u16, qr: bool, qdcount: u16) -> Vec<u8> {
        let mut msg = Vec::new();
        msg.extend_from_slice(&id.to_be_bytes());
        msg.push(if qr { 0x81 } else { 0x01 });
        msg.push(0x80);
        msg.extend_from_slice(&qdcount.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg.extend_from_slice(&[0, 0, 0, 0]);
        for label in name.split('.') {
            msg.push(label.len() as u8);
            msg.extend_from_slice(label.as_bytes());
        }
        msg.push(0);
        msg.extend_from_slice(&qtype.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg
    }

    /// A reply to `query` that holds one HTTPS record for its name, with `ech` for its
    /// ech parameter.
    fn https_reply(query: &[u8], ech: &[u8]) -> Vec<u8> {
        let mut rdata = vec![0x00, 0x01, 0x00];
        rdata.extend_from_slice(&SVCPARAM_ECH.to_be_bytes());
        rdata.extend_from_slice(&(ech.len() as u16).to_be_bytes());
        rdata.extend_from_slice(ech);

        let mut msg = query.to_vec();
        msg[2] |= 0x80;
        msg[6..8].copy_from_slice(&1u16.to_be_bytes());
        msg.extend_from_slice(&[0xc0, 0x0c]);
        msg.extend_from_slice(&RR_HTTPS.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg.extend_from_slice(&300u32.to_be_bytes());
        msg.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
        msg.extend_from_slice(&rdata);
        msg
    }

    fn sample_ech_list(config_len: usize) -> Vec<u8> {
        let mut list = vec![0u8, 0, 0xfe, 0x0d];
        list.extend_from_slice(&(config_len as u16).to_be_bytes());
        list.extend(std::iter::repeat(0xab).take(config_len));
        let declared = (list.len() - 2) as u16;
        list[..2].copy_from_slice(&declared.to_be_bytes());
        list
    }

    #[test]
    fn the_ech_dns_names_a_resolver_over_udp_or_tcp() {
        let at = |text: &str| text.parse::<SocketAddr>().unwrap();
        assert_eq!(
            EchDns::parse("udp://8.8.8.8"),
            Ok(EchDns::Udp(at("8.8.8.8:53")))
        );
        assert_eq!(
            EchDns::parse(" UDP://8.8.8.8:5353 "),
            Ok(EchDns::Udp(at("8.8.8.8:5353")))
        );
        assert_eq!(
            EchDns::parse("tcp://1.1.1.1"),
            Ok(EchDns::Tcp(at("1.1.1.1:53")))
        );
        assert_eq!(
            EchDns::parse("tcp://[2606:4700:4700::1111]"),
            Ok(EchDns::Tcp(at("[2606:4700:4700::1111]:53")))
        );
        assert_eq!(
            EchDns::parse("udp://[::1]:5353/"),
            Ok(EchDns::Udp(at("[::1]:5353")))
        );
        assert_eq!(
            EchDns::parse(DEFAULT_ECH_DNS),
            Ok(EchDns::Udp(at("1.1.1.1:53")))
        );
        assert_eq!(
            EchDns::parse("tcp://[::1]").unwrap().to_string(),
            "tcp://[::1]:53"
        );
    }

    #[test]
    fn an_ech_dns_this_core_cannot_ask_is_refused() {
        for text in [
            "1.1.1.1",
            "dns.google",
            "tls://1.1.1.1",
            "udp://dns.google",
            "tcp://",
            "udp://1.1.1.1:99999",
            "https://dns.google/dns-query",
            "",
        ] {
            assert!(EchDns::parse(text).is_err(), "{text}");
        }
        assert!(EchDns::parse("https://dns.google/dns-query")
            .unwrap_err()
            .contains("2.3.0"));
    }

    #[test]
    fn the_ech_domain_is_a_dns_name() {
        for name in [
            DEFAULT_ECH_DOMAIN,
            "crypto.cloudflare.com",
            "ip.gs",
            "ip.gs.",
            "_ech.example",
        ] {
            assert!(valid_domain(name), "{name}");
        }
        let long = format!("{}.com", "a".repeat(64));
        for name in [
            "",
            ".",
            "a..b",
            "with space.com",
            "https://ip.gs",
            "ip.gs/",
            long.as_str(),
        ] {
            assert!(!valid_domain(name), "{name}");
        }
    }

    #[test]
    fn with_nothing_configured_the_lookup_is_the_one_older_cores_made() {
        let plan = plan_for(None, None).expect("a plan");
        assert_eq!(plan.len(), ECH_HOSTS.len() * BOOTSTRAP_DNS.len());
        assert_eq!(plan[0].domain, "cloudflare-ech.com");
        assert_eq!(plan[0].dns, EchDns::Udp("1.1.1.1:53".parse().unwrap()));
        assert!(plan
            .iter()
            .all(|lookup| lookup.budget == BOOTSTRAP_LOOKUP_TIMEOUT));
    }

    #[test]
    fn a_configured_lookup_is_the_only_one_made() {
        let plan = plan_for(Some("udp://8.8.8.8"), Some("IP.GS.")).expect("a plan");
        assert_eq!(plan.len(), 1);
        assert_eq!(plan[0].domain, "ip.gs");
        assert_eq!(plan[0].dns, EchDns::Udp("8.8.8.8:53".parse().unwrap()));
        assert_eq!(plan[0].budget, ECH_LOOKUP_TIMEOUT);
        assert_eq!(plan[0].to_string(), "ip.gs via udp://8.8.8.8:53");

        let domain_only = plan_for(None, Some("ip.gs")).expect("a plan");
        assert_eq!(domain_only[0].dns, EchDns::parse(DEFAULT_ECH_DNS).unwrap());
        let dns_only = plan_for(Some("tcp://8.8.8.8"), None).expect("a plan");
        assert_eq!(dns_only[0].domain, DEFAULT_ECH_DOMAIN);
    }

    #[test]
    fn a_configured_lookup_that_cannot_work_is_an_error() {
        assert!(plan_for(Some("8.8.8.8"), Some("ip.gs")).is_err());
        assert!(plan_for(Some("udp://8.8.8.8"), Some("https://ip.gs")).is_err());
    }

    #[test]
    fn the_ech_request_is_read_the_way_the_cli_writes_it() {
        assert_eq!(request_from(None), EchRequest::Off);
        assert_eq!(request_from(Some("auto")), EchRequest::Auto);
        assert_eq!(request_from(Some("AUTO")), EchRequest::Auto);
        assert_eq!(request_from(Some("off")), EchRequest::Off);
        assert_eq!(
            request_from(Some("AEX+")),
            EchRequest::Given("AEX+".to_string())
        );
    }

    #[test]
    fn a_truncated_ech_key_set_is_never_taken_for_a_real_one() {
        assert!(!plausible_ech_list(&[]));
        assert!(!plausible_ech_list(&[0, 3, 0xfe, 0x0d, 0]));
        assert!(!plausible_ech_list(&sample_ech_list(8)));

        let mut wrong_prefix = sample_ech_list(65);
        wrong_prefix[1] ^= 0x01;
        assert!(!plausible_ech_list(&wrong_prefix));
    }

    #[test]
    fn a_cloudflare_sized_ech_key_set_is_accepted() {
        let list = sample_ech_list(65);
        assert_eq!(list.len(), 71);
        assert!(plausible_ech_list(&list));
    }

    #[test]
    fn the_ech_parameter_is_read_from_an_https_record() {
        let (query, _) = build_query("ip.gs", RR_HTTPS);
        let key = sample_ech_list(65);
        let msg = https_reply(&query, &key);
        assert_eq!(answer_ech(&msg, "ip.gs").expect("the key"), key);
        assert!(answer_ech(&query, "ip.gs").is_err());
    }

    #[test]
    fn a_message_over_tcp_goes_behind_its_length() {
        assert_eq!(tcp_message(&[7, 8, 9]), vec![0, 3, 7, 8, 9]);
        assert_eq!(tcp_message(&[0u8; 300])[..2], [1, 44]);
    }

    #[test]
    fn build_query_reports_the_id_it_wrote() {
        let (query, id) = build_query("cloudflare-ech.com", RR_HTTPS);
        assert_eq!(u16::from_be_bytes([query[0], query[1]]), id);
    }

    #[test]
    fn accepts_a_reply_that_matches_the_query() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, true, 1);
        assert!(response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_spoofed_reply_with_the_wrong_transaction_id() {
        let msg = reply(0x9999, "cloudflare-ech.com", RR_HTTPS, true, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_reply_for_a_different_name() {
        let msg = reply(0x1234, "attacker.example", RR_HTTPS, true, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_reply_for_a_different_record_type() {
        let msg = reply(0x1234, "cloudflare-ech.com", 1, true, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_message_that_is_not_a_response() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, false, 1);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_a_reply_with_an_unexpected_question_count() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, true, 2);
        assert!(!response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }

    #[test]
    fn rejects_truncated_input_without_panicking() {
        let msg = reply(0x1234, "cloudflare-ech.com", RR_HTTPS, true, 1);
        for cut in 0..msg.len() {
            assert!(!response_matches(
                &msg[..cut],
                0x1234,
                "cloudflare-ech.com",
                RR_HTTPS
            ));
        }
    }

    #[test]
    fn name_comparison_is_case_insensitive() {
        let msg = reply(0x1234, "CloudFlare-ECH.com", RR_HTTPS, true, 1);
        assert!(response_matches(
            &msg,
            0x1234,
            "cloudflare-ech.com",
            RR_HTTPS
        ));
    }
}
