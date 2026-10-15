//! Where the core gets its ECH key.
//!
//! Aether Mobile ECH key acquisition engine:
//! One `ECHConfigList` per process, looked up once and shared by everything
//! that offers ECH: the camouflaged route to the WARP API (`apifront.rs`) on
//! every protocol, and the MASQUE tunnel (`lib.rs` `resolve_ech`).
//!
//! Why previous ECH key lookups failed on filtered networks without a VPN:
//!  - Port 53 (UDP and TCP) to `1.1.1.1` and `8.8.8.8` is dropped, poisoned or
//!    answered with private/sinkhole records;
//!  - Unfragmented `reqwest` DoH to `https://1.1.1.1/dns-query` and
//!    `https://8.8.8.8/dns-query` is blocked by IP or TLS-reset on port 443.
//!
//! How this module solves it:
//!  1. Configured lookups (`AETHER_ECH_DNS` / `AETHER_ECH_DOMAIN`) run side by
//!     side with UDP, TCP, standard DoH (`1.1.1.1`, `8.8.8.8`), AND
//!     **camouflaged edge DoH** (`query_edge_doh`): RFC 8484 POST queries over
//!     BoringSSL + `FragmentingStream` (SNI-split ClientHello) to Cloudflare's
//!     dedicated DoH ranges (`162.159.36.1`, `162.159.46.1`, `162.159.36.2`,
//!     `162.159.46.2`) using neutral cover SNIs (`one.one.one.one`,
//!     `cloudflare-dns.com`).
//!  2. Every lookup runs concurrently and the first plausible, offerable
//!     `ECHConfigList` wins immediately.
//!  3. `synthesize_ech_config_list` builds valid RFC 9849 (`0xfe0d`)
//!     `ECHConfigList` wire structures with arbitrary public names and X25519
//!     keys, and `rewrite_public_name` re-tags any real `ECHConfigList` with a
//!     chosen cover `public_name` while preserving its `config_id`, KEM, HPKE
//!     public key, and cipher suites.

use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use boring::ssl::{SslConnector, SslMethod, SslVerifyMode, SslVersion};
use futures::stream::{FuturesUnordered, StreamExt};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::timeout_at;

use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};

pub const BOOTSTRAP_DNS: &[&str] = &["1.1.1.1:53", "1.0.0.1:53", "8.8.8.8:53"];
pub const ECH_HOSTS: &[&str] = &["cloudflare-ech.com", "crypto.cloudflare.com"];

/// DNS-over-HTTPS resolvers asked next to whatever is configured. By IP
/// address on purpose: no name to resolve first and no SNI on the wire; both
/// certificates carry their IP address.
pub const DOH_FALLBACK: &[&str] = &["https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"];

/// Cloudflare's dedicated DoH edge addresses (`162.159.36.0/24` and
/// `162.159.46.0/24`), which answer RFC 8484 `/dns-query` even when `1.1.1.1`
/// and `8.8.8.8` are IP-blocked on filtered networks.
pub const EDGE_DOH_SERVERS: &[(&str, &str)] = &[
    ("162.159.36.1:443", "one.one.one.one"),
    ("162.159.46.1:443", "cloudflare-dns.com"),
    ("162.159.36.2:443", "one.one.one.one"),
    ("162.159.46.2:443", "cloudflare-dns.com"),
];

/// Domains fronted by Cloudflare whose `HTTPS` (type 65) records publish
/// Cloudflare's shared ECH key set.
pub const EDGE_DOH_DOMAINS: &[&str] = &[
    "cloudflare-ech.com",
    "crypto.cloudflare.com",
    "ip.gs",
];

/// --ech: `auto`, or a base64 ECHConfigList.
pub const ECH_VARIABLE: &str = "AETHER_ECH";
/// The resolver(s) the ECHConfigList is asked of (upstream's --ech-dns).
pub const ECH_DNS_VARIABLE: &str = "AETHER_ECH_DNS";
/// The domain(s) whose ECHConfigList is offered (upstream's --ech-domain).
pub const ECH_DOMAIN_VARIABLE: &str = "AETHER_ECH_DOMAIN";

/// The resolver when only the domain is configured.
pub const DEFAULT_ECH_DNS: &str = "udp://1.1.1.1";
/// The domain when only the resolver is configured, and the one always asked
/// for next to a configured domain: it carries Cloudflare's shared key set.
pub const DEFAULT_ECH_DOMAIN: &str = "cloudflare-ech.com";

const RR_HTTPS: u16 = 65;
const SVCPARAM_ECH: u16 = 5;

/// ECH version 0xfe0d (RFC 9849).
pub const ECH_VERSION_FE0D: u16 = 0xfe0d;
/// DHKEM(X25519, HKDF-SHA256) KEM ID.
pub const KEM_X25519_HKDF_SHA256: u16 = 0x0020;
/// HKDF-SHA256 KDF ID.
pub const KDF_HKDF_SHA256: u16 = 0x0001;
/// AES-128-GCM AEAD ID.
pub const AEAD_AES_128_GCM: u16 = 0x0001;

/// The path of a DoH resolver given without one.
const DOH_PATH: &str = "/dns-query";
/// RFC 8484's media type, for the question and the answer.
const DOH_MEDIA_TYPE: &str = "application/dns-message";
/// A DoH answer bigger than this is no single HTTPS record.
const MAX_DOH_ANSWER: usize = 64 * 1024;

/// How long a configured lookup may take, over any transport.
const ECH_LOOKUP_TIMEOUT: Duration = Duration::from_secs(8);
/// How long each step of the unconfigured cascade may take.
const BOOTSTRAP_LOOKUP_TIMEOUT: Duration = Duration::from_secs(3);
/// Over UDP the question goes out again after this long without an answer.
const UDP_RESEND_AFTER: Duration = Duration::from_secs(2);
/// A lookup that failed is not repeated for this long: a short cooldown avoids
/// hammering dead resolvers while still allowing recovery on the next attempt.
const FAILURE_HOLD: Duration = Duration::from_secs(15);

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
    /// DNS over HTTPS (RFC 8484, POST), to an IP address.
    Https { address: SocketAddr, path: String },
}

impl EchDns {
    /// `udp://ip[:port]` or `tcp://ip[:port]`, on port 53 unless one is given;
    /// `https://ip[:port][/path]`, on port 443 and `/dns-query` unless given.
    /// An IPv6 address goes in brackets.
    pub fn parse(value: &str) -> std::result::Result<Self, String> {
        let value = value.trim();
        if let Some(rest) = strip_scheme(value, "https://") {
            let (authority, path) = match rest.find('/') {
                Some(at) => rest.split_at(at),
                None => (rest, ""),
            };
            let address = socket_address(authority, 443).ok_or_else(|| {
                format!(
                    "{value}: DNS-over-HTTPS for the ECH key needs the resolver's IP address \
                     (https://1.1.1.1/dns-query); a name would need a lookup of its own"
                )
            })?;
            let path = match path {
                "" | "/" => DOH_PATH.to_string(),
                other => other.to_string(),
            };
            if !path.bytes().all(|b| b.is_ascii_graphic()) {
                return Err(format!("{value}: {path} is no URL path"));
            }
            return Ok(EchDns::Https { address, path });
        }
        let (rest, tcp) = if let Some(rest) = strip_scheme(value, "udp://") {
            (rest, false)
        } else if let Some(rest) = strip_scheme(value, "tcp://") {
            (rest, true)
        } else {
            return Err(format!("{value} is no udp://, tcp:// or https:// address"));
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
            EchDns::Https { address, path } => write!(f, "https://{address}{path}"),
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

/// The entries of a comma (or space) separated setting.
fn entries(value: &str) -> Vec<&str> {
    value
        .split(|c: char| c == ',' || c.is_whitespace())
        .map(str::trim)
        .filter(|entry| !entry.is_empty())
        .collect()
}

/// The DoH resolvers every plan races next to the rest.
fn doh_fallback() -> Vec<EchDns> {
    DOH_FALLBACK
        .iter()
        .filter_map(|resolver| EchDns::parse(resolver).ok())
        .collect()
}

/// The lookups to make, as the environment configures them. They all run at
/// once (see [fetch_ech_config]).
pub fn lookup_plan() -> Result<Vec<EchLookup>> {
    plan_for(
        configured(ECH_DNS_VARIABLE).as_deref(),
        configured(ECH_DOMAIN_VARIABLE).as_deref(),
    )
}

/// Configured resolvers and domains come first, every resolver asked about
/// every domain, the other half taking its default; then the DoH fallback and
/// cloudflare-ech.com. Neither configured means the cascade older cores made,
/// plus the DoH fallback. A configured value with no usable entry at all is an
/// error; a single bad entry in a list is left out with a warning.
fn plan_for(dns: Option<&str>, domain: Option<&str>) -> Result<Vec<EchLookup>> {
    let mut plan = Vec::new();

    if dns.is_none() && domain.is_none() {
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
        for resolver in doh_fallback() {
            for host in ECH_HOSTS {
                plan.push(EchLookup {
                    dns: resolver.clone(),
                    domain: (*host).to_string(),
                    budget: ECH_LOOKUP_TIMEOUT,
                });
            }
        }
        return Ok(plan);
    }

    let mut resolvers: Vec<EchDns> = Vec::new();
    let mut refused: Vec<String> = Vec::new();
    for entry in entries(dns.unwrap_or(DEFAULT_ECH_DNS)) {
        match EchDns::parse(entry) {
            Ok(resolver) => {
                if !resolvers.contains(&resolver) {
                    resolvers.push(resolver);
                }
            }
            Err(error) => refused.push(error),
        }
    }
    if resolvers.is_empty() {
        let why = if refused.is_empty() {
            "names no resolver".to_string()
        } else {
            refused.join("; ")
        };
        return Err(AetherError::Ech(format!("{ECH_DNS_VARIABLE}: {why}")));
    }
    for error in &refused {
        log::warn!("[-] {ECH_DNS_VARIABLE}: {error}; left out");
    }

    let mut domains: Vec<String> = Vec::new();
    let mut invalid: Vec<String> = Vec::new();
    for name in entries(domain.unwrap_or(DEFAULT_ECH_DOMAIN)) {
        if valid_domain(name) {
            let name = name.trim_end_matches('.').to_ascii_lowercase();
            if !domains.contains(&name) {
                domains.push(name);
            }
        } else {
            invalid.push(name.to_string());
        }
    }
    if domains.is_empty() {
        let names = if invalid.is_empty() {
            "nothing".to_string()
        } else {
            invalid.join(", ")
        };
        return Err(AetherError::Ech(format!(
            "{ECH_DOMAIN_VARIABLE}: {names} is no domain name"
        )));
    }
    for name in &invalid {
        log::warn!("[-] {ECH_DOMAIN_VARIABLE}: {name} is no domain name; left out");
    }

    for resolver in doh_fallback() {
        if !resolvers.contains(&resolver) {
            resolvers.push(resolver);
        }
    }
    let fallback = DEFAULT_ECH_DOMAIN.to_string();
    if !domains.contains(&fallback) {
        domains.push(fallback);
    }

    for resolver in &resolvers {
        for name in &domains {
            plan.push(EchLookup {
                dns: resolver.clone(),
                domain: name.clone(),
                budget: ECH_LOOKUP_TIMEOUT,
            });
        }
    }
    Ok(plan)
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

/// Synthesize a valid RFC 9849 (`0xfe0d`) `ECHConfigList` with the given
/// `config_id`, 32-byte X25519 `public_key`, and outer `public_name`.
///
/// Used both to construct decoy/bootstrap configs with diverse outer names
/// (e.g., `cloudflare-ech.com`, `crypto.cloudflare.com`, `one.one.one.one`)
/// when DNS is completely cut, and in tests.
pub fn synthesize_ech_config_list(
    config_id: u8,
    public_key: &[u8; 32],
    public_name: &str,
) -> Option<Vec<u8>> {
    let name = public_name.trim().trim_end_matches('.');
    if name.is_empty() || name.len() > 253 || !valid_domain(name) {
        return None;
    }
    let name_bytes = name.as_bytes();
    if name_bytes.len() > 255 {
        return None;
    }

    let mut contents = Vec::with_capacity(64 + name_bytes.len());
    // config_id (1 byte)
    contents.push(config_id);
    // kem_id: DHKEM(X25519, HKDF-SHA256) = 0x0020
    contents.extend_from_slice(&KEM_X25519_HKDF_SHA256.to_be_bytes());
    // public_key length (2 bytes) + 32-byte X25519 key
    contents.extend_from_slice(&(public_key.len() as u16).to_be_bytes());
    contents.extend_from_slice(public_key);
    // cipher_suites: 4 bytes total -> 1 suite (HKDF-SHA256 = 0x0001, AES-128-GCM = 0x0001)
    contents.extend_from_slice(&4u16.to_be_bytes());
    contents.extend_from_slice(&KDF_HKDF_SHA256.to_be_bytes());
    contents.extend_from_slice(&AEAD_AES_128_GCM.to_be_bytes());
    // maximum_name_length (0 = no extra name padding)
    contents.push(0);
    // public_name length (1 byte) + public_name
    contents.push(name_bytes.len() as u8);
    contents.extend_from_slice(name_bytes);
    // extensions length (0)
    contents.extend_from_slice(&0u16.to_be_bytes());

    let mut config = Vec::with_capacity(4 + contents.len());
    config.extend_from_slice(&ECH_VERSION_FE0D.to_be_bytes());
    config.extend_from_slice(&(contents.len() as u16).to_be_bytes());
    config.extend_from_slice(&contents);

    let mut list = Vec::with_capacity(2 + config.len());
    list.extend_from_slice(&(config.len() as u16).to_be_bytes());
    list.extend_from_slice(&config);
    Some(list)
}

/// Rewrites the `public_name` inside the first `0xfe0d` `ECHConfig` of `list`
/// while preserving its `config_id`, KEM, HPKE public key, cipher suites, and
/// extensions.
///
/// NOTE: RFC 9849 binds the full `ECHConfig` bytes into the HPKE `info` context
/// (`"tls ech\0" || ECHConfig`), so changing `public_name` on a real config is
/// only used for decoy/bootstrap probes where we want the outer SNI to carry a
/// specific cover domain while triggering `retry_configs` recovery.
pub fn rewrite_public_name(list: &[u8], new_public_name: &str) -> Option<Vec<u8>> {
    let name = new_public_name.trim().trim_end_matches('.');
    if name.is_empty() || name.len() > 253 || !valid_domain(name) {
        return None;
    }
    if !plausible_ech_list(list) {
        return None;
    }
    let version = u16::from_be_bytes([list[2], list[3]]);
    if version != ECH_VERSION_FE0D {
        return None;
    }
    let config_len = u16::from_be_bytes([list[4], list[5]]) as usize;
    let body = list.get(6..6 + config_len)?;
    if body.len() < 4 {
        return None;
    }
    let config_id = body[0];
    let kem_id = u16::from_be_bytes([body[1], body[2]]);
    let pk_len = u16::from_be_bytes([body[3], body[4]]) as usize;
    let mut at = 5usize.checked_add(pk_len)?;
    let pk = body.get(5..at)?;

    let suites_len = u16::from_be_bytes([*body.get(at)?, *body.get(at + 1)?]) as usize;
    at += 2;
    let suites = body.get(at..at + suites_len)?;
    at += suites_len;

    let max_name_len = *body.get(at)?;
    at += 1;
    let old_name_len = *body.get(at)? as usize;
    at += 1;
    let after_name = at.checked_add(old_name_len)?;
    let extensions = body.get(after_name..)?;

    let name_bytes = name.as_bytes();
    let mut new_body = Vec::with_capacity(body.len() + name_bytes.len());
    new_body.push(config_id);
    new_body.extend_from_slice(&kem_id.to_be_bytes());
    new_body.extend_from_slice(&(pk.len() as u16).to_be_bytes());
    new_body.extend_from_slice(pk);
    new_body.extend_from_slice(&(suites.len() as u16).to_be_bytes());
    new_body.extend_from_slice(suites);
    new_body.push(max_name_len);
    new_body.push(name_bytes.len() as u8);
    new_body.extend_from_slice(name_bytes);
    new_body.extend_from_slice(extensions);

    let mut out = Vec::with_capacity(6 + new_body.len());
    let total_len = (4 + new_body.len()) as u16;
    out.extend_from_slice(&total_len.to_be_bytes());
    out.extend_from_slice(&ECH_VERSION_FE0D.to_be_bytes());
    out.extend_from_slice(&(new_body.len() as u16).to_be_bytes());
    out.extend_from_slice(&new_body);
    Some(out)
}

/// Extract the outer `public_name` from the first `0xfe0d` `ECHConfig` in
/// `list`, if present.
pub fn extract_public_name(list: &[u8]) -> Option<String> {
    if !plausible_ech_list(list) {
        return None;
    }
    let version = u16::from_be_bytes([list[2], list[3]]);
    if version != ECH_VERSION_FE0D {
        return None;
    }
    let config_len = u16::from_be_bytes([list[4], list[5]]) as usize;
    let body = list.get(6..6 + config_len)?;
    if body.len() < 5 {
        return None;
    }
    let pk_len = u16::from_be_bytes([body[3], body[4]]) as usize;
    let mut at = 5usize.checked_add(pk_len)?;
    let suites_len = u16::from_be_bytes([*body.get(at)?, *body.get(at + 1)?]) as usize;
    at = at.checked_add(2)?.checked_add(suites_len)?.checked_add(1)?;
    let name_len = *body.get(at)? as usize;
    at += 1;
    let name_bytes = body.get(at..at + name_len)?;
    std::str::from_utf8(name_bytes).ok().map(str::to_string)
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

/// Clears the cached ECH key and failure state (used when a previously cached
/// key was rotated out or in tests).
pub fn clear_cached_key() {
    if let Ok(mut guard) = SESSION_KEY.lock() {
        *guard = None;
    }
    if let Ok(mut guard) = LAST_FAILURE.lock() {
        *guard = None;
    }
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

/// The ECHConfigList of this process: the one it already has, else the first
/// plausible one any lookup of the plan or the camouflaged edge DoH race finds.
/// All lookups run at once; the rest are dropped as soon as one answers.
pub async fn fetch_ech_config() -> Result<Vec<u8>> {
    if let Some(list) = cached_key() {
        return Ok(list);
    }
    if let Some(reason) = recent_failure() {
        return Err(AetherError::Ech(format!(
            "{reason} (not asked again for a moment)"
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

    let mut pending: FuturesUnordered<
        std::pin::Pin<Box<dyn std::future::Future<Output = (String, Result<Vec<u8>>)> + Send>>,
    > = FuturesUnordered::new();

    for lookup in &plan {
        let lookup = lookup.clone();
        pending.push(Box::pin(async move {
            let label = lookup.to_string();
            let outcome = run_lookup(&lookup).await;
            (label, outcome)
        }));
    }

    // Race camouflaged edge DoH (BoringSSL + SNI-split FragmentingStream to
    // 162.159.36.x / 162.159.46.x) alongside standard lookups so filtered
    // networks where port 53 and 1.1.1.1:443 are blocked still get the live
    // ECHConfigList in ~1 RTT.
    for &(edge_str, sni) in EDGE_DOH_SERVERS {
        if let Ok(address) = edge_str.parse::<SocketAddr>() {
            for &domain in EDGE_DOH_DOMAINS {
                let label = format!("{domain} via edge-doh://{address} (sni={sni})");
                pending.push(Box::pin(async move {
                    let outcome =
                        query_edge_doh(address, sni, DOH_PATH, domain, ECH_LOOKUP_TIMEOUT).await;
                    (label, outcome)
                }));
            }
        }
    }

    let mut reasons: Vec<String> = Vec::new();
    while let Some((label, outcome)) = pending.next().await {
        match outcome {
            Ok(list) if remember(list.clone()) => {
                log::info!("fetched ECHConfigList ({} bytes) for {label}", list.len());
                return Ok(list);
            }
            Ok(list) => {
                let reason = format!(
                    "{label} answered {} bytes that are no ECHConfigList",
                    list.len()
                );
                log::debug!("ech lookup: {reason}");
                reasons.push(reason);
            }
            Err(error) => {
                let reason = reason_of(error);
                log::debug!("ech lookup: {reason}");
                reasons.push(reason);
            }
        }
    }

    let reason = match reasons.len() {
        0 => "no lookup to make".to_string(),
        1 => reasons.remove(0),
        n => format!(
            "no ECHConfigList resolved in {n} lookups: {}",
            reasons.join("; ")
        ),
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
        match &lookup.dns {
            EchDns::Udp(server) => query_udp(*server, &lookup.domain).await,
            EchDns::Tcp(server) => query_tcp(*server, &lookup.domain).await,
            EchDns::Https { address, path } => {
                query_https(*address, path, &lookup.domain, lookup.budget).await
            }
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

/// The URL of a DoH resolver at `address`.
fn doh_url(address: SocketAddr, path: &str) -> String {
    format!("https://{address}{path}")
}

/// The HTTPS record of `domain`, asked of the DoH resolver at `address` (RFC
/// 8484, POST). First attempts camouflaged BoringSSL + SNI/stream-split
/// fragmentation so DPI cannot reset the handshake, falling back to `reqwest`
/// when needed.
async fn query_https(
    address: SocketAddr,
    path: &str,
    domain: &str,
    budget: Duration,
) -> Result<Vec<u8>> {
    if let Ok(list) = query_edge_doh(address, "one.one.one.one", path, domain, budget).await {
        return Ok(list);
    }

    let url = doh_url(address, path);
    let builder = reqwest::Client::builder()
        .connect_timeout(budget)
        .timeout(budget);
    let builder = match crate::upstream::configured() {
        Some(proxy) => builder.proxy(proxy.as_reqwest_proxy()?),
        None => builder.no_proxy(),
    };
    let client = builder
        .build()
        .map_err(|e| AetherError::Ech(format!("{url}: no DoH client: {e}")))?;

    let (query, id) = build_query(domain, RR_HTTPS);
    let response = client
        .post(url.as_str())
        .header(reqwest::header::CONTENT_TYPE, DOH_MEDIA_TYPE)
        .header(reqwest::header::ACCEPT, DOH_MEDIA_TYPE)
        .body(query)
        .send()
        .await
        .map_err(|e| AetherError::Ech(format!("{url}: {e}")))?;

    let status = response.status();
    if !status.is_success() {
        return Err(AetherError::Ech(format!(
            "{url} answered HTTP {}",
            status.as_u16()
        )));
    }
    let body = response
        .bytes()
        .await
        .map_err(|e| AetherError::Ech(format!("{url}: {e}")))?;
    if body.len() > MAX_DOH_ANSWER {
        return Err(AetherError::Ech(format!(
            "{url} answered {} bytes, too many for one HTTPS record",
            body.len()
        )));
    }

    if !response_matches(&body, id, domain, RR_HTTPS) {
        return Err(AetherError::Ech(format!(
            "{url}: the reply does not match the query"
        )));
    }
    answer_ech(&body, domain)
}

/// Camouflaged DNS-over-HTTPS (RFC 8484 POST) over BoringSSL with Chrome TLS
/// extensions, ECH GREASE, and `FragmentingStream` (SNI-split ClientHello).
///
/// Connects directly to `address` (such as `162.159.36.1:443` or
/// `162.159.46.1:443`) while presenting `sni` (`one.one.one.one` or
/// `cloudflare-dns.com`) cut in the middle of the hostname so stateless DPI
/// keyword matchers never see the SNI whole.
pub async fn query_edge_doh(
    address: SocketAddr,
    sni: &str,
    path: &str,
    domain: &str,
    budget: Duration,
) -> Result<Vec<u8>> {
    let work = async {
        let tcp = match crate::upstream::configured() {
            Some(proxy) => proxy.connect(address).await.map_err(|e| {
                AetherError::Ech(format!("edge DoH connect to {address} via proxy: {e}"))
            })?,
            None => crate::egress::tcp_connect(address)
                .await
                .map_err(|e| AetherError::Ech(format!("edge DoH connect to {address}: {e}")))?,
        };
        let _ = tcp.set_nodelay(true);

        let mut builder = SslConnector::builder(SslMethod::tls())
            .map_err(|e| AetherError::Ech(format!("edge DoH ssl builder: {e}")))?;
        builder
            .set_min_proto_version(Some(SslVersion::TLS1_2))
            .map_err(|e| AetherError::Ech(e.to_string()))?;
        builder
            .set_max_proto_version(Some(SslVersion::TLS1_3))
            .map_err(|e| AetherError::Ech(e.to_string()))?;
        builder.set_grease_enabled(true);
        builder.set_permute_extensions(true);
        builder
            .set_curves_list("X25519:P-256:P-384")
            .map_err(|e| AetherError::Ech(e.to_string()))?;
        builder
            .set_alpn_protos(b"\x08http/1.1")
            .map_err(|e| AetherError::Ech(e.to_string()))?;
        // DoH answers are validated for DNS transaction ID, question section,
        // and plausible 0xfe0d ECHConfigList structure; we also only use the
        // returned public key to encrypt the inner ClientHello (whose server
        // certificate is verified by the Android system trust store).
        builder.set_verify(SslVerifyMode::NONE);

        let mut config = builder
            .build()
            .configure()
            .map_err(|e| AetherError::Ech(e.to_string()))?;
        config.set_verify_hostname(false);
        crate::tls::set_enable_ech_grease(&mut config, true);

        let frag = FragmentConfig {
            enabled: true,
            size_min: 18,
            size_max: 40,
            delay_min_ms: 1,
            delay_max_ms: 5,
            sni_split: true,
        };
        let stream = FragmentingStream::new(tcp, frag);
        let mut tls = tokio_boring::connect(config, sni, stream)
            .await
            .map_err(|e| AetherError::Ech(format!("edge DoH tls with {address}: {e}")))?;

        let (query, id) = build_query(domain, RR_HTTPS);
        let req = format!(
            "POST {path} HTTP/1.1\r\n\
             Host: {sni}\r\n\
             User-Agent: Mozilla/5.0\r\n\
             Content-Type: {DOH_MEDIA_TYPE}\r\n\
             Accept: {DOH_MEDIA_TYPE}\r\n\
             Content-Length: {}\r\n\
             Connection: close\r\n\r\n",
            query.len()
        );

        tls.write_all(req.as_bytes())
            .await
            .map_err(|e| AetherError::Ech(format!("edge DoH write to {address}: {e}")))?;
        tls.write_all(&query)
            .await
            .map_err(|e| AetherError::Ech(format!("edge DoH write body to {address}: {e}")))?;
        tls.flush()
            .await
            .map_err(|e| AetherError::Ech(format!("edge DoH flush to {address}: {e}")))?;

        let mut raw = Vec::with_capacity(1024);
        let mut buf = [0u8; 4096];
        loop {
            let n = tls
                .read(&mut buf)
                .await
                .map_err(|e| AetherError::Ech(format!("edge DoH read from {address}: {e}")))?;
            if n == 0 {
                break;
            }
            raw.extend_from_slice(&buf[..n]);
            if raw.len() > MAX_DOH_ANSWER {
                return Err(AetherError::Ech(format!(
                    "edge DoH answer from {address} exceeded {MAX_DOH_ANSWER} bytes"
                )));
            }
        }

        let body = extract_http_body(&raw)
            .ok_or_else(|| AetherError::Ech(format!("edge DoH {address}: invalid HTTP response")))?;
        if !response_matches(&body, id, domain, RR_HTTPS) {
            return Err(AetherError::Ech(format!(
                "edge DoH {address}: reply did not match DNS query"
            )));
        }
        answer_ech(&body, domain)
    };

    match tokio::time::timeout(budget, work).await {
        Ok(result) => result,
        Err(_) => Err(AetherError::Ech(format!(
            "edge DoH {address} did not answer for {domain} within {budget:?}"
        ))),
    }
}

fn extract_http_body(raw: &[u8]) -> Option<Vec<u8>> {
    let split = raw.windows(4).position(|w| w == b"\r\n\r\n")?;
    let head = String::from_utf8_lossy(&raw[..split]);
    let mut lines = head.split("\r\n");
    let status_line = lines.next()?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())?;
    if !(200..300).contains(&status) {
        return None;
    }
    let body = &raw[split + 4..];
    let chunked = lines.any(|line| {
        let lower = line.to_ascii_lowercase();
        lower.starts_with("transfer-encoding:") && lower.contains("chunked")
    });
    if chunked {
        Some(dechunk_http_body(body))
    } else {
        Some(body.to_vec())
    }
}

fn dechunk_http_body(body: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut cursor = 0usize;
    while cursor < body.len() {
        let Some(offset) = body[cursor..].windows(2).position(|w| w == b"\r\n") else {
            break;
        };
        let line_end = cursor + offset;
        let line = String::from_utf8_lossy(&body[cursor..line_end]);
        let token = line.split(';').next().unwrap_or("").trim();
        let Ok(size) = usize::from_str_radix(token, 16) else {
            break;
        };
        if size == 0 {
            break;
        }
        let start = line_end + 2;
        let Some(end) = start.checked_add(size) else {
            break;
        };
        if end > body.len() {
            break;
        }
        out.extend_from_slice(&body[start..end]);
        cursor = end + 2;
    }
    out
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

    fn doh(address: &str, path: &str) -> EchDns {
        EchDns::Https {
            address: address.parse().unwrap(),
            path: path.to_string(),
        }
    }

    #[test]
    fn synthesize_and_rewrite_ech_config_list_preserve_rfc9849_layout() {
        let pubkey = [
            0x7c, 0x75, 0xf0, 0xdd, 0x6b, 0x5e, 0x0b, 0xdf, 0x9e, 0xfa, 0x74, 0x1e, 0x03, 0x39,
            0xde, 0x60, 0x8f, 0x74, 0x1d, 0xa8, 0x18, 0xb0, 0x29, 0x71, 0x41, 0xd5, 0x64, 0xb2,
            0xce, 0x6c, 0x34, 0x39,
        ];
        let list = synthesize_ech_config_list(0x5a, &pubkey, "cloudflare-ech.com")
            .expect("synthesizes");
        assert_eq!(list.len(), 71);
        assert!(plausible_ech_list(&list));
        assert_eq!(
            extract_public_name(&list).as_deref(),
            Some("cloudflare-ech.com")
        );

        let rewritten =
            rewrite_public_name(&list, "crypto.cloudflare.com").expect("rewrites public_name");
        assert!(plausible_ech_list(&rewritten));
        assert_eq!(
            extract_public_name(&rewritten).as_deref(),
            Some("crypto.cloudflare.com")
        );

        let builder = SslConnector::builder(SslMethod::tls()).expect("builder");
        let mut cfg = builder.build().configure().expect("configure");
        assert!(crate::tls::set_ech_config_list(&mut cfg, &list).is_ok());
        assert!(crate::tls::set_ech_config_list(&mut cfg, &rewritten).is_ok());
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
    fn the_ech_dns_names_a_doh_resolver_by_its_address() {
        assert_eq!(
            EchDns::parse("https://1.1.1.1/dns-query"),
            Ok(doh("1.1.1.1:443", "/dns-query"))
        );
        assert_eq!(
            EchDns::parse("HTTPS://8.8.8.8"),
            Ok(doh("8.8.8.8:443", "/dns-query"))
        );
        assert_eq!(
            EchDns::parse("https://8.8.4.4/"),
            Ok(doh("8.8.4.4:443", "/dns-query"))
        );
        assert_eq!(
            EchDns::parse("https://1.0.0.1:8443/resolve"),
            Ok(doh("1.0.0.1:8443", "/resolve"))
        );
        assert_eq!(
            EchDns::parse("https://[2606:4700:4700::1111]/dns-query"),
            Ok(doh("[2606:4700:4700::1111]:443", "/dns-query"))
        );
        assert_eq!(
            EchDns::parse("https://1.1.1.1/dns-query")
                .unwrap()
                .to_string(),
            "https://1.1.1.1:443/dns-query"
        );
        for fallback in DOH_FALLBACK {
            assert!(EchDns::parse(fallback).is_ok(), "{fallback}");
        }
    }

    #[test]
    fn the_doh_url_brackets_an_ipv6_resolver() {
        assert_eq!(
            doh_url("1.1.1.1:443".parse().unwrap(), "/dns-query"),
            "https://1.1.1.1:443/dns-query"
        );
        assert_eq!(
            doh_url("[2606:4700:4700::1111]:443".parse().unwrap(), "/dns-query"),
            "https://[2606:4700:4700::1111]:443/dns-query"
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
            "https://",
            "https://1.1.1.1/dns query",
            "",
        ] {
            assert!(EchDns::parse(text).is_err(), "{text}");
        }
        assert!(EchDns::parse("https://dns.google/dns-query")
            .unwrap_err()
            .contains("IP address"));
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
    fn with_nothing_configured_the_old_cascade_races_the_doh_fallback() {
        let plan = plan_for(None, None).expect("a plan");
        let udp = ECH_HOSTS.len() * BOOTSTRAP_DNS.len();
        assert_eq!(plan.len(), udp + ECH_HOSTS.len() * DOH_FALLBACK.len());
        assert_eq!(plan[0].domain, "cloudflare-ech.com");
        assert_eq!(plan[0].dns, EchDns::Udp("1.1.1.1:53".parse().unwrap()));
        assert!(plan[..udp]
            .iter()
            .all(|lookup| lookup.budget == BOOTSTRAP_LOOKUP_TIMEOUT));
        assert!(plan[udp..]
            .iter()
            .all(|lookup| matches!(lookup.dns, EchDns::Https { .. })));
    }

    #[test]
    fn a_configured_lookup_comes_first_and_doh_is_asked_alongside() {
        let plan = plan_for(Some("udp://8.8.8.8"), Some("IP.GS.")).expect("a plan");
        assert_eq!(plan[0].domain, "ip.gs");
        assert_eq!(plan[0].dns, EchDns::Udp("8.8.8.8:53".parse().unwrap()));
        assert_eq!(plan[0].budget, ECH_LOOKUP_TIMEOUT);
        assert_eq!(plan[0].to_string(), "ip.gs via udp://8.8.8.8:53");

        // One configured resolver and the two DoH ones, each asked about the
        // configured domain and cloudflare-ech.com.
        assert_eq!(plan.len(), (1 + DOH_FALLBACK.len()) * 2);
        for resolver in DOH_FALLBACK {
            let resolver = EchDns::parse(resolver).unwrap();
            for name in ["ip.gs", DEFAULT_ECH_DOMAIN] {
                assert!(
                    plan.iter().any(|l| l.dns == resolver && l.domain == name),
                    "{name} via {resolver}"
                );
            }
        }
        assert!(plan.iter().all(|l| l.budget == ECH_LOOKUP_TIMEOUT));

        let domain_only = plan_for(None, Some("ip.gs")).expect("a plan");
        assert_eq!(domain_only[0].dns, EchDns::parse(DEFAULT_ECH_DNS).unwrap());
        let dns_only = plan_for(Some("tcp://8.8.8.8"), None).expect("a plan");
        assert_eq!(dns_only[0].domain, DEFAULT_ECH_DOMAIN);
        assert_eq!(dns_only.len(), 1 + DOH_FALLBACK.len());
    }

    #[test]
    fn a_configured_list_is_asked_in_full_and_a_bad_entry_left_out() {
        let plan = plan_for(
            Some("https://1.1.1.1/dns-query, udp://8.8.8.8 bogus https://1.1.1.1"),
            Some("ip.gs,cloudflare-ech.com, bad/domain"),
        )
        .expect("a plan");
        // https://1.1.1.1 twice is one resolver; bogus and bad/domain are left out.
        assert_eq!(plan.len(), 3 * 2);
        assert_eq!(plan[0].dns, doh("1.1.1.1:443", "/dns-query"));
        assert_eq!(plan[0].domain, "ip.gs");
        assert!(plan
            .iter()
            .any(|l| l.dns == EchDns::Udp("8.8.8.8:53".parse().unwrap())));
    }

    #[test]
    fn a_configured_lookup_that_cannot_work_is_an_error() {
        assert!(plan_for(Some("8.8.8.8"), Some("ip.gs")).is_err());
        assert!(plan_for(Some("udp://8.8.8.8"), Some("https://ip.gs")).is_err());
        assert!(plan_for(Some(" , "), Some("ip.gs")).is_err());
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

    #[tokio::test]
    #[ignore = "needs live network access to a DoH resolver"]
    async fn the_ech_key_comes_back_over_doh() {
        for resolver in DOH_FALLBACK {
            let Ok(EchDns::Https { address, path }) = EchDns::parse(resolver) else {
                panic!("{resolver} parses");
            };
            let list = query_https(address, &path, DEFAULT_ECH_DOMAIN, ECH_LOOKUP_TIMEOUT)
                .await
                .unwrap_or_else(|e| panic!("{resolver}: {e}"));
            assert!(plausible_ech_list(&list), "{resolver}: {} bytes", list.len());
        }
    }
}
