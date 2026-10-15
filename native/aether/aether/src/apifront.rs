use std::collections::{HashSet, VecDeque};
use std::ffi::{c_void, CStr};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::raw::{c_char, c_int, c_long};
use std::sync::OnceLock;
use std::time::Duration;

use boring::ssl::{SslConnector, SslContextBuilder, SslMethod, SslVerifyMode, SslVersion};
use boring::x509::{X509NameRef, X509StoreContextRef, X509};
use foreign_types_shared::ForeignTypeRef;
use futures::stream::{FuturesUnordered, StreamExt};
use rand::RngExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::Semaphore;
use tokio::time::Instant;

use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};
use crate::masque_h2::{spoof::SpoofMode, SpoofingStream};

extern "C" {
    fn X509_STORE_CTX_get_error(ctx: *const c_void) -> c_int;
    fn X509_verify_cert_error_string(err: c_long) -> *const c_char;
}

/// Diverse Cloudflare anycast / ingress / DoH prefixes used to reach the WARP
/// registration and key enrollment API without DNS.
///
/// Relying on a single `/24` (`141.101.113.x`) fails when an ISP null-routes or
/// blackholes that prefix. Sampling across multiple distinct Cloudflare AS13335
/// prefixes (API ingress, DoH edge, CDN anycast, WARP ingress) ensures at least
/// one routed path answers on port 443.
pub const EDGE_PREFIXES: &[[u8; 3]] = &[
    [141, 101, 113],
    [162, 159, 36],
    [162, 159, 46],
    [104, 16, 132],
    [172, 64, 155],
    [188, 114, 96],
    [162, 159, 192],
];

const EDGE_SAMPLES: usize = 6;
const RESOLVED_SAMPLES: usize = 2;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
/// Through an upstream proxy (tor, psiphon) the connect also covers building
/// the circuit, which regularly takes longer than a direct tcp connect.
const PROXY_CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 512 * 1024;

/// The camouflaged route starts new attempts for this long.
const ROUTE_BUDGET: Duration = Duration::from_secs(35);
/// Attempts already under way get this much longer to finish their exchange.
const ROUTE_GRACE: Duration = Duration::from_secs(20);
/// How many handshakes run at the same time.
const PARALLEL_ATTEMPTS: usize = 4;
/// The time between two attempts starting.
const ATTEMPT_STAGGER: Duration = Duration::from_millis(300);
/// The ECH attempts have the field to themselves this long, so a plaintext
/// ClientHello that happens to come up first does not carry the request while
/// an ECH one is a round trip away.
const ECH_HEAD_START: Duration = Duration::from_secs(3);
/// How long the API waits for the core's ECH key lookup (`dns.rs`, including
/// camouflaged edge DoH) before starting from decoy/bootstrap ECH configs and
/// harvesting live `retry_configs` directly from the Cloudflare edge.
const ECH_KEY_WAIT: Duration = Duration::from_secs(3);
/// How long the system resolver may take over the api name.
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(2);

/// Deterministic 32-byte X25519 public key whose private key exists nowhere,
/// used to synthesize RFC 9849 decoy/bootstrap `ECHConfigList` payloads.
///
/// When offered to any Cloudflare edge, the edge cannot open the inner
/// ClientHello and rejects ECH per RFC 9849 §6.1.6: it completes or aborts the
/// outer handshake under the cover `public_name` and sends back its live
/// `ECHConfigList` in the `encrypted_client_hello` `retry_configs` extension.
pub const DECOY_ECH_PUBKEY: [u8; 32] = [
    0x7c, 0x75, 0xf0, 0xdd, 0x6b, 0x5e, 0x0b, 0xdf, 0x9e, 0xfa, 0x74, 0x1e, 0x03, 0x39, 0xde,
    0x60, 0x8f, 0x74, 0x1d, 0xa8, 0x18, 0xb0, 0x29, 0x71, 0x41, 0xd5, 0x64, 0xb2, 0xce, 0x6c,
    0x34, 0x39,
];

/// Cover `public_name` domains used in decoy ECH configs when no DNS / DoH
/// answer was available. Rotating outer names avoids a single static outer SNI
/// signature if a censor filters `cloudflare-ech.com`.
pub const DECOY_PUBLIC_NAMES: &[&str] = &[
    "cloudflare-ech.com",
    "crypto.cloudflare.com",
    "one.one.one.one",
];

/// Canonical 71-byte bootstrap `ECHConfigList` (`0xfe0d`, X25519,
/// HKDF-SHA256/AES-128-GCM, `cloudflare-ech.com`).
pub const BOOTSTRAP_ECH_CONFIG: [u8; 71] = [
    0x00, 0x45, 0xfe, 0x0d, 0x00, 0x41, 0x5a, 0x00, 0x20, 0x00, 0x20, 0x7c, 0x75, 0xf0, 0xdd,
    0x6b, 0x5e, 0x0b, 0xdf, 0x9e, 0xfa, 0x74, 0x1e, 0x03, 0x39, 0xde, 0x60, 0x8f, 0x74, 0x1d,
    0xa8, 0x18, 0xb0, 0x29, 0x71, 0x41, 0xd5, 0x64, 0xb2, 0xce, 0x6c, 0x34, 0x39, 0x00, 0x04,
    0x00, 0x01, 0x00, 0x01, 0x00, 0x12, 0x63, 0x6c, 0x6f, 0x75, 0x64, 0x66, 0x6c, 0x61, 0x72,
    0x65, 0x2d, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
];

/// Extra trust anchors: a directory of PEM/DER files, or one PEM bundle.
/// This is also the only way to trust a user installed CA on purpose.
const CA_DIR_ENV: &str = "AETHER_CA_DIR";
const CA_FILE_ENV: &str = "AETHER_CA_FILE";

/// Where the system keeps its root certificates, most authoritative first.
/// BoringSSL's built in default paths (`/etc/ssl/certs`, `/usr/lib/ssl`) do not
/// exist on Android, so without loading these ourselves every certificate the
/// api presents is rejected with "unable to get local issuer certificate".
///
/// Only the first directory that holds any roots is used. On Android 14+
/// `/system/etc/security/cacerts` is still there but frozen at the factory
/// image; merging it with the conscrypt apex would bring back roots an update
/// has since distrusted.
///
/// User installed CAs (`cacerts-added`) are deliberately not trusted, the same
/// default Android applies to apps since API 24: a root pushed onto the device
/// must not be able to read the account token.
const SYSTEM_CA_DIRS: &[&str] = &[
    // Android 14+ ships the roots in the updatable conscrypt module.
    "/apex/com.android.conscrypt/cacerts",
    // Android 13 and older.
    "/system/etc/security/cacerts",
    // Desktop linux, for the cli and tests.
    "/etc/ssl/certs",
];
const SYSTEM_CA_FILES: &[&str] = &[
    "/etc/ssl/certs/ca-certificates.crt",
    "/etc/pki/tls/certs/ca-bundle.crt",
    "/etc/ssl/cert.pem",
];

/// Where Android keeps copies of the system roots a user switched off in
/// settings, per android user. `{user}` is replaced with the current one.
const REMOVED_CA_DIR: &str = "/data/misc/user/{user}/cacerts-removed";

const LEGACY_CIPHERS: &str = "ECDHE-ECDSA-CHACHA20-POLY1305:\
ECDHE-ECDSA-AES128-GCM-SHA256:\
ECDHE-RSA-AES128-GCM-SHA256:\
ECDHE-ECDSA-AES256-SHA:\
ECDHE-RSA-AES128-SHA:\
AES256-SHA";

const LEGACY_GROUPS: &str = "X25519:P-256";
const MODERN_GROUPS: &str = "X25519:P-256:P-384";
const CHROME_GROUPS: &str = "P-256:X25519:P-384";
const ECH_GROUPS: &str = "X25519:P-256:P-384";

const ALPN_HTTP1: &[u8] = b"\x08http/1.1";

static TRUST_ROOTS: OnceLock<Vec<X509>> = OnceLock::new();

/// The stream an api exchange runs over.
type ApiStream =
    tokio_boring::SslStream<SpoofingStream<FragmentingStream<tokio::net::TcpStream>>>;

/// Reads every certificate in a PEM bundle (Android's cacerts files carry a
/// text dump before the PEM block, which the PEM reader skips), falling back to
/// a single DER certificate.
fn parse_certificates(bytes: &[u8]) -> Vec<X509> {
    if let Ok(list) = X509::stack_from_pem(bytes) {
        if !list.is_empty() {
            return list;
        }
    }
    X509::from_der(bytes).map(|cert| vec![cert]).unwrap_or_default()
}

fn collect_roots(dirs: &[String], files: &[String]) -> (Vec<X509>, Vec<String>) {
    let mut roots: Vec<X509> = Vec::new();
    let mut seen: HashSet<Vec<u8>> = HashSet::new();
    let mut sources: Vec<String> = Vec::new();

    let mut keep = |certs: Vec<X509>, roots: &mut Vec<X509>| -> usize {
        let mut added = 0;
        for cert in certs {
            if let Ok(der) = cert.to_der() {
                if seen.insert(der) {
                    roots.push(cert);
                    added += 1;
                }
            }
        }
        added
    };

    for dir in dirs {
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        let mut added = 0;
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            if let Ok(bytes) = std::fs::read(&path) {
                added += keep(parse_certificates(&bytes), &mut roots);
            }
        }
        if added > 0 {
            sources.push(format!("{dir} ({added})"));
        }
    }

    for file in files {
        if let Ok(bytes) = std::fs::read(file) {
            let added = keep(parse_certificates(&bytes), &mut roots);
            if added > 0 {
                sources.push(format!("{file} ({added})"));
            }
        }
    }

    (roots, sources)
}

/// True when the directory holds at least one readable certificate.
fn holds_certificates(dir: &str) -> bool {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return false;
    };
    entries.flatten().any(|entry| {
        let path = entry.path();
        path.is_file()
            && std::fs::read(&path)
                .map(|bytes| !parse_certificates(&bytes).is_empty())
                .unwrap_or(false)
    })
}

/// The one system trust store to use: the first directory with roots in it,
/// else the first bundle file with roots in it.
fn pick_system_store(dirs: &[&str], files: &[&str]) -> (Vec<String>, Vec<String>) {
    for dir in dirs {
        if holds_certificates(dir) {
            return (vec![dir.to_string()], Vec::new());
        }
    }
    for file in files {
        let usable = std::fs::read(file)
            .map(|bytes| !parse_certificates(&bytes).is_empty())
            .unwrap_or(false);
        if usable {
            return (Vec::new(), vec![file.to_string()]);
        }
    }
    (Vec::new(), Vec::new())
}

/// The android user this process runs as (uid / 100000), 0 off android or
/// when it cannot be told.
fn android_user_id() -> u32 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|status| {
            status
                .lines()
                .find_map(|line| line.strip_prefix("Uid:"))
                .and_then(|rest| rest.split_whitespace().next())
                .and_then(|uid| uid.parse::<u32>().ok())
        })
        .map(|uid| uid / 100_000)
        .unwrap_or(0)
}

/// DER of every system root the user switched off in settings.
fn removed_roots() -> HashSet<Vec<u8>> {
    let dir = REMOVED_CA_DIR.replace("{user}", &android_user_id().to_string());
    let (certs, _) = collect_roots(&[dir], &[]);
    certs.iter().filter_map(|cert| cert.to_der().ok()).collect()
}

/// Leaves out every root in `removed`. Returns what is left and how many went.
fn drop_removed(roots: Vec<X509>, removed: &HashSet<Vec<u8>>) -> (Vec<X509>, usize) {
    if removed.is_empty() {
        return (roots, 0);
    }
    let before = roots.len();
    let kept: Vec<X509> = roots
        .into_iter()
        .filter(|cert| {
            cert.to_der()
                .map(|der| !removed.contains(&der))
                .unwrap_or(true)
        })
        .collect();
    let dropped = before - kept.len();
    (kept, dropped)
}

fn env_path(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn trust_roots() -> &'static [X509] {
    TRUST_ROOTS.get_or_init(|| {
        let mut dirs: Vec<String> = env_path(CA_DIR_ENV).into_iter().collect();
        let mut files: Vec<String> = env_path(CA_FILE_ENV).into_iter().collect();
        let (system_dirs, system_files) = pick_system_store(SYSTEM_CA_DIRS, SYSTEM_CA_FILES);
        dirs.extend(system_dirs);
        files.extend(system_files);

        let (roots, sources) = collect_roots(&dirs, &files);
        let (roots, dropped) = drop_removed(roots, &removed_roots());
        if roots.is_empty() {
            log::warn!(
                "[apifront] no root certificates found on this device; api certificates will \
                 fail to verify. Point {CA_DIR_ENV} or {CA_FILE_ENV} at a trust store"
            );
        } else {
            log::info!(
                "[apifront] trust store: {} roots from {}{}",
                roots.len(),
                sources.join(", "),
                if dropped > 0 {
                    format!(", {dropped} left out because the user disabled them")
                } else {
                    String::new()
                }
            );
        }
        roots
    })
}

fn describe_name(name: &X509NameRef) -> String {
    let parts: Vec<String> = name
        .entries()
        .filter_map(|entry| entry.data().as_utf8().ok().map(|text| text.to_string()))
        .collect();
    if parts.is_empty() {
        "?".to_string()
    } else {
        parts.join(", ")
    }
}

/// The verification error BoringSSL recorded on the context, as text. Read
/// straight from the C api: the Rust wrapper's accessor for this has changed
/// name between boring releases.
fn verify_error(ctx: &X509StoreContextRef) -> String {
    let code = unsafe { X509_STORE_CTX_get_error(ctx.as_ptr() as *const c_void) };
    let text = unsafe { X509_verify_cert_error_string(code as c_long) };
    if text.is_null() {
        return format!("verify error {code}");
    }
    let text = unsafe { CStr::from_ptr(text) }.to_string_lossy();
    format!("{text} (code {code})")
}

/// Leaves the verdict to BoringSSL, but says why a certificate was refused.
/// A subject of `cloudflare-ech.com` (or another cover public_name) means the
/// edge rejected ECH and answered with the outer name; anything that is not a
/// Cloudflare issuer means the connection is being intercepted.
fn report_verification(preverify_ok: bool, ctx: &mut X509StoreContextRef) -> bool {
    if !preverify_ok {
        let depth = ctx.error_depth();
        let reason = verify_error(ctx);
        let (subject, issuer) = ctx
            .current_cert()
            .map(|cert| {
                (
                    describe_name(cert.subject_name()),
                    describe_name(cert.issuer_name()),
                )
            })
            .unwrap_or_else(|| ("?".to_string(), "?".to_string()));
        log::info!(
            "[apifront] certificate refused at depth {depth}: {reason} \
             (subject: {subject} | issuer: {issuer} | trust store: {} roots)",
            trust_roots().len()
        );
    }
    preverify_ok
}

fn install_trust(builder: &mut SslContextBuilder) {
    let roots = trust_roots();
    if !roots.is_empty() {
        let store = builder.cert_store_mut();
        for root in roots {
            // A duplicate is not worth failing the handshake over.
            let _ = store.add_cert(root.clone());
        }
    }
    builder.set_verify_callback(SslVerifyMode::PEER, report_verification);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fingerprint {
    /// Encrypted Client Hello with SNI-split fragmentation (hides inner SNI
    /// completely inside HPKE payload and splits outer cover SNI across TCP
    /// segments).
    Ech,
    /// Encrypted Client Hello with multi-record stream splitting (`64/32/24`
    /// byte cuts across the first write), bypassing middleboxes that reassemble
    /// single-cut segments or inspect record boundaries.
    EchStreamSplit,
    SplitLegacy,
    SplitModern,
    Modern,
    ChromeLike,
}

fn split_fragments() -> FragmentConfig {
    FragmentConfig {
        enabled: true,
        size_min: 24,
        size_max: 48,
        delay_min_ms: 2,
        delay_max_ms: 8,
        sni_split: true,
    }
}

impl Fingerprint {
    pub fn label(self) -> &'static str {
        match self {
            Fingerprint::Ech => "ech",
            Fingerprint::EchStreamSplit => "ech-stream-split",
            Fingerprint::SplitLegacy => "split-tls12",
            Fingerprint::SplitModern => "split-tls13",
            Fingerprint::Modern => "plain-tls13",
            Fingerprint::ChromeLike => "chrome",
        }
    }

    pub fn is_ech(self) -> bool {
        matches!(self, Fingerprint::Ech | Fingerprint::EchStreamSplit)
    }

    fn all() -> [Fingerprint; 6] {
        [
            Fingerprint::Ech,
            Fingerprint::EchStreamSplit,
            Fingerprint::SplitLegacy,
            Fingerprint::SplitModern,
            Fingerprint::Modern,
            Fingerprint::ChromeLike,
        ]
    }

    fn fragments(self) -> FragmentConfig {
        match self {
            Fingerprint::Ech | Fingerprint::SplitLegacy | Fingerprint::SplitModern => {
                split_fragments()
            }
            Fingerprint::EchStreamSplit => FragmentConfig {
                enabled: true,
                size_min: 16,
                size_max: 36,
                delay_min_ms: 1,
                delay_max_ms: 5,
                sni_split: true,
            },
            _ => FragmentConfig::disabled(),
        }
    }

    fn spoof_mode(self) -> SpoofMode {
        match self {
            Fingerprint::EchStreamSplit => SpoofMode::StreamSplit,
            _ => SpoofMode::Off,
        }
    }

    fn configure(self) -> Result<boring::ssl::ConnectConfiguration> {
        let mut builder =
            SslConnector::builder(SslMethod::tls()).map_err(|e| AetherError::Tls(e.to_string()))?;

        let tls = |error: boring::error::ErrorStack| AetherError::Tls(error.to_string());

        match self {
            Fingerprint::Ech | Fingerprint::EchStreamSplit => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(true);
                builder.set_permute_extensions(true);
                builder.set_curves_list(ECH_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
                builder.enable_signed_cert_timestamps();
                builder.enable_ocsp_stapling();
            }
            Fingerprint::SplitLegacy => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder.set_grease_enabled(false);
                builder.set_cipher_list(LEGACY_CIPHERS).map_err(tls)?;
                builder.set_curves_list(LEGACY_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
            }
            Fingerprint::SplitModern => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(false);
                builder.set_curves_list(MODERN_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
            }
            Fingerprint::Modern => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(false);
                builder.set_curves_list(MODERN_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
            }
            Fingerprint::ChromeLike => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_2))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(true);
                builder.set_permute_extensions(true);
                builder.set_curves_list(CHROME_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
                builder.enable_signed_cert_timestamps();
                builder.enable_ocsp_stapling();
            }
        }

        install_trust(&mut builder);

        let mut config = builder.build().configure().map_err(tls)?;
        if !self.is_ech() {
            // Emit synthetic ECH GREASE on plaintext fallback fingerprints so
            // extension-inspecting DPI boxes still see an ECH extension.
            crate::tls::set_enable_ech_grease(&mut config, true);
        }
        Ok(config)
    }
}

#[derive(Debug, Clone)]
pub struct ApiRequest {
    pub method: String,
    pub host: String,
    pub path: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
}

#[derive(Debug, Clone)]
pub struct ApiResponse {
    pub status: u16,
    pub body: String,
    pub route: String,
}

/// Picks a random Cloudflare edge address from the first prefix (`141.101.113.0/24`)
/// or from a specific prefix slot.
pub fn random_edge_address() -> SocketAddr {
    random_edge_in_prefix(&EDGE_PREFIXES[0])
}

pub fn random_edge_in_prefix(prefix: &[u8; 3]) -> SocketAddr {
    let host = rand::rng().random_range(1..=254u8);
    let ip = Ipv4Addr::new(prefix[0], prefix[1], prefix[2], host);
    SocketAddr::new(IpAddr::V4(ip), 443)
}

/// Whether an address the system resolver gave for the api can be a
/// Cloudflare edge: public IPv4 only. A filtered resolver answers a blocked
/// name with a private address (10.10.34.x in Iran), which leads to a block
/// page or nowhere and only costs a connect timeout per fingerprint.
fn usable_edge(address: &SocketAddr) -> bool {
    match address.ip() {
        IpAddr::V4(v4) => {
            let [first, second, ..] = v4.octets();
            let shared = first == 100 && (second & 0xc0) == 64;
            !(v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_unspecified()
                || v4.is_broadcast()
                || v4.is_documentation()
                || v4.is_multicast()
                || first == 0
                || shared)
        }
        IpAddr::V6(_) => false,
    }
}

/// Builds a diverse list of Cloudflare edge addresses across multiple `/24`
/// prefixes (`141.101.113.x`, `162.159.36.x`, `162.159.46.x`, `104.16.132.x`,
/// `172.64.155.x`, `188.114.96.x`, `162.159.192.x`).
///
/// With ECH requested (`--ech`), the API hostname (`api.cloudflareclient.com`)
/// is NEVER looked up over plaintext system DNS so the name stays off the wire.
async fn candidates(host: &str) -> Vec<SocketAddr> {
    let ech = crate::dns::ech_requested();
    let samples = if ech {
        EDGE_SAMPLES + RESOLVED_SAMPLES
    } else {
        EDGE_SAMPLES
    };

    let mut list: Vec<SocketAddr> = Vec::new();
    let mut prefix_idx = 0usize;
    while list.len() < samples {
        let prefix = &EDGE_PREFIXES[prefix_idx % EDGE_PREFIXES.len()];
        prefix_idx += 1;
        let candidate = random_edge_in_prefix(prefix);
        if !list.contains(&candidate) {
            list.push(candidate);
        }
    }

    if ech {
        return list;
    }

    match tokio::time::timeout(RESOLVE_TIMEOUT, tokio::net::lookup_host((host, 443))).await {
        Ok(Ok(resolved)) => {
            for address in resolved.filter(usable_edge).take(RESOLVED_SAMPLES) {
                if !list.contains(&address) {
                    list.push(address);
                }
            }
        }
        Ok(Err(error)) => log::debug!("[apifront] {host} did not resolve: {error}"),
        Err(_) => log::info!(
            "[apifront] the system resolver took over {}s for {host}; using edge addresses only",
            RESOLVE_TIMEOUT.as_secs()
        ),
    }

    list
}

/// True when `list` is one of our synthetic decoy `ECHConfigList` payloads
/// whose private key does not exist (and therefore must never be cached as the
/// session key).
pub fn is_decoy_ech_config(list: &[u8]) -> bool {
    if list == BOOTSTRAP_ECH_CONFIG.as_slice() {
        return true;
    }
    // Check if the 32-byte X25519 public key inside the first config matches
    // DECOY_ECH_PUBKEY.
    list.windows(DECOY_ECH_PUBKEY.len())
        .any(|w| w == DECOY_ECH_PUBKEY)
}

/// Synthesizes a decoy `ECHConfigList` for a given attempt slot, rotating
/// through `DECOY_PUBLIC_NAMES` (`cloudflare-ech.com`, `crypto.cloudflare.com`,
/// `one.one.one.one`) so outer ClientHellos do not all share one fixed SNI.
pub fn decoy_ech_config_for_slot(slot: usize) -> Vec<u8> {
    let name = DECOY_PUBLIC_NAMES[slot % DECOY_PUBLIC_NAMES.len()];
    crate::dns::synthesize_ech_config_list(0x5a, &DECOY_ECH_PUBKEY, name)
        .unwrap_or_else(|| BOOTSTRAP_ECH_CONFIG.to_vec())
}

/// The key the core already has for this process (see `dns.rs`).
fn cached_ech() -> Option<Vec<u8>> {
    crate::dns::cached_key()
}

/// Hands a key an edge sent back to the core, which keeps it for every later
/// ECH handshake of the process, the MASQUE tunnel's included. Returns whether
/// it was kept: only a plausible `ECHConfigList` is, and never a decoy/bootstrap
/// set, which no edge can decrypt.
fn remember_ech(list: Vec<u8>) -> bool {
    if is_decoy_ech_config(&list) {
        return false;
    }
    crate::dns::remember(list)
}

/// Resolves the initial `ECHConfigList` for the camouflaged route:
///  1. Waits up to `ECH_KEY_WAIT` (3 s) for `dns::session_key()` (which races
///     camouflaged edge DoH to `162.159.36.1`/`162.159.46.1`, standard DoH, and
///     UDP/TCP DNS).
///  2. If all DNS/DoH paths are blocked or time out, returns
///     `BOOTSTRAP_ECH_CONFIG` so the first handshake triggers RFC 9849
///     `retry_configs` recovery directly from the Cloudflare TLS edge with zero
///     DNS dependency.
async fn ech_config_list() -> Option<Vec<u8>> {
    match tokio::time::timeout(ECH_KEY_WAIT, crate::dns::session_key()).await {
        Ok(Some(list)) => return Some(list),
        Ok(None) => log::info!(
            "[apifront] the core has no ECHConfigList for the api; offering decoy ECH config \
             set so the edge hands back its live retry_configs"
        ),
        Err(_) => log::info!(
            "[apifront] no ECHConfigList after {}s; offering decoy ECH config set so the edge \
             hands back its live retry_configs",
            ECH_KEY_WAIT.as_secs()
        ),
    }
    Some(BOOTSTRAP_ECH_CONFIG.to_vec())
}

fn render_request(request: &ApiRequest) -> Vec<u8> {
    let mut head = String::new();
    head.push_str(&format!("{} {} HTTP/1.1\r\n", request.method, request.path));
    head.push_str(&format!("Host: {}\r\n", request.host));

    for (name, value) in &request.headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }

    head.push_str("Accept-Encoding: identity\r\n");
    head.push_str(&format!(
        "Content-Length: {}\r\n",
        request.body.as_ref().map(Vec::len).unwrap_or(0)
    ));
    head.push_str("Connection: close\r\n\r\n");

    let mut wire = head.into_bytes();
    if let Some(body) = &request.body {
        wire.extend_from_slice(body);
    }
    wire
}

fn parse_response(raw: &[u8]) -> Result<(u16, String)> {
    let split = raw
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .ok_or_else(|| AetherError::Api("truncated response head".into()))?;

    let head = String::from_utf8_lossy(&raw[..split]);
    let mut body = raw[split + 4..].to_vec();

    let mut lines = head.split("\r\n");
    let status_line = lines
        .next()
        .ok_or_else(|| AetherError::Api("empty response".into()))?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|token| token.parse::<u16>().ok())
        .ok_or_else(|| AetherError::Api(format!("bad status line: {status_line}")))?;

    let chunked = lines.any(|line| {
        let lowered = line.to_lowercase();
        lowered.starts_with("transfer-encoding:") && lowered.contains("chunked")
    });

    if chunked {
        body = dechunk(&body);
    }

    Ok((status, String::from_utf8_lossy(&body).into_owned()))
}

fn dechunk(body: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut cursor = 0usize;

    while cursor < body.len() {
        let line_end = match body[cursor..]
            .windows(2)
            .position(|window| window == b"\r\n")
        {
            Some(offset) => cursor + offset,
            None => break,
        };
        let line = String::from_utf8_lossy(&body[cursor..line_end]);
        let token = line.split(';').next().unwrap_or("").trim();
        let size = match usize::from_str_radix(token, 16) {
            Ok(0) | Err(_) => break,
            Ok(value) => value,
        };
        let start = line_end + 2;
        let end = match start.checked_add(size) {
            Some(end) if end <= body.len() => end,
            _ => break,
        };
        out.extend_from_slice(&body[start..end]);
        cursor = end + 2;
    }

    out
}

/// True when the tcp connection itself never came up. Such an address is dead
/// (or null routed) for every fingerprint, so there is no point spending
/// another connect timeout on it per profile.
fn never_connected(error: &AetherError) -> bool {
    matches!(error, AetherError::Api(message) if message.starts_with("connect to "))
}

/// True when the account api itself answered and refused: a json body with a
/// 4xx. Another address or fingerprint reaches the same api and gets the same
/// answer, and on a 429 every extra attempt only digs the rate limit deeper.
/// A 403 is left out (that is usually the edge refusing this network, which
/// another route can get past), and so is a 408.
fn final_api_answer(response: &ApiResponse) -> bool {
    (400..500).contains(&response.status)
        && response.status != 403
        && response.status != 408
        && response.body.trim_start().starts_with('{')
}

/// A tcp connection to `address` and a tls handshake for `host` over it, with
/// `fingerprint`'s ClientHello and, when given, ECH with `ech`. The request is
/// not sent yet (see [send]).
async fn establish(
    host: &str,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
) -> Result<ApiStream> {
    let tcp = match crate::upstream::configured() {
        Some(proxy) => tokio::time::timeout(PROXY_CONNECT_TIMEOUT, proxy.connect(address))
            .await
            .map_err(|_| {
                AetherError::Api(format!("connect to {address} through the proxy timed out"))
            })?
            .map_err(|e| {
                AetherError::Api(format!("connect to {address} through the proxy: {e}"))
            })?,
        None => tokio::time::timeout(CONNECT_TIMEOUT, crate::egress::tcp_connect(address))
            .await
            .map_err(|_| AetherError::Api(format!("connect to {address} timed out")))?
            .map_err(|e| AetherError::Api(format!("connect to {address}: {e}")))?,
    };
    tcp.set_nodelay(true).ok();

    let mut config = fingerprint.configure()?;
    if let Some(list) = ech {
        crate::tls::set_ech_config_list(&mut config, list)?;
    }
    let frag_stream = FragmentingStream::new(tcp, fingerprint.fragments());
    let stream = SpoofingStream::new(frag_stream, fingerprint.spoof_mode());

    let handshake = tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        tokio_boring::connect(config, host, stream),
    )
    .await
    .map_err(|_| AetherError::Api(format!("tls handshake with {address} timed out")))?;

    let tls = match handshake {
        Ok(tls) => tls,
        Err(error) => {
            if ech.is_some() {
                if let Some(retry) = error.ssl().and_then(crate::tls::ech_retry_configs) {
                    let size = retry.len();
                    if remember_ech(retry) {
                        return Err(AetherError::Ech(format!(
                            "{address} rejected our ech keys and handed back fresh ones \
                             ({size} bytes): {error}"
                        )));
                    }
                    log::info!(
                        "[apifront] {address} handed back a {size} byte ech key set that is not \
                         a usable ECHConfigList; keeping ours"
                    );
                }
            }
            return Err(AetherError::Api(format!(
                "tls handshake with {address}: {error}"
            )));
        }
    };

    if ech.is_some() {
        if crate::tls::ech_accepted(tls.ssl()) {
            log::info!(
                "[apifront] ech ({}) accepted by {address}; the api name stayed encrypted",
                fingerprint.label()
            );
        } else {
            if let Some(retry) = crate::tls::ech_retry_configs(tls.ssl()) {
                let size = retry.len();
                if remember_ech(retry) {
                    return Err(AetherError::Ech(format!(
                        "{address} completed outer handshake without ECH and handed back \
                         {size} bytes of retry_configs"
                    )));
                }
            }
            return Err(AetherError::Ech(format!(
                "{address} completed the handshake without accepting ech"
            )));
        }
    }

    if tls.ssl().selected_alpn_protocol() == Some(b"h2") {
        return Err(AetherError::Api(format!(
            "{address} negotiated http/2 which this path does not speak"
        )));
    }

    Ok(tls)
}

/// An ECH handshake with `address`, plus an immediate retry when the edge
/// rejects our keys (or decoy config) and hands back the live `retry_configs`
/// it currently serves.
///
/// If the edge's `ECH_REJECTED` handshake fails before `ech_retry_configs` can
/// be extracted (for instance if a middlebox interferes with the outer alert or
/// cert), falls back to a quick camouflaged edge DoH query directly against
/// `address` to pull the `HTTPS` record before retrying.
async fn establish_ech(
    host: &str,
    address: SocketAddr,
    fingerprint: Fingerprint,
    list: Vec<u8>,
) -> Result<ApiStream> {
    match establish(host, address, fingerprint, Some(&list)).await {
        Err(first) => {
            if let Some(fresh) = cached_ech().filter(|fresh| *fresh != list) {
                log::info!("[apifront] first ech attempt via {address} failed: {first}");
                log::info!(
                    "[apifront] retrying {address} ({}) with the {} byte ech key set it handed back",
                    fingerprint.label(),
                    fresh.len()
                );
                return establish(host, address, fingerprint, Some(&fresh)).await;
            }

            // If we offered a decoy config and did not get retry_configs from
            // the TLS alert, probe `/dns-query` on the same edge over a
            // fragmented TLS session to harvest the ECHConfigList.
            if is_decoy_ech_config(&list) {
                if let Ok(doh_list) = crate::dns::query_edge_doh(
                    address,
                    "one.one.one.one",
                    "/dns-query",
                    crate::dns::DEFAULT_ECH_DOMAIN,
                    Duration::from_secs(4),
                )
                .await
                {
                    if remember_ech(doh_list.clone()) {
                        log::info!(
                            "[apifront] recovered {} byte ECHConfigList via edge DoH on {address}; retrying ECH handshake",
                            doh_list.len()
                        );
                        return establish(host, address, fingerprint, Some(&doh_list)).await;
                    }
                }
            }

            Err(first)
        }
        established => established,
    }
}

/// The request, over a stream [establish] brought up, and the answer.
async fn send(
    request: &ApiRequest,
    mut tls: ApiStream,
    address: SocketAddr,
    fingerprint: Fingerprint,
) -> Result<ApiResponse> {
    let wire = render_request(request);

    let collected = tokio::time::timeout(EXCHANGE_TIMEOUT, async {
        tls.write_all(&wire).await?;
        tls.flush().await?;

        let mut buffer = Vec::new();
        let mut chunk = [0u8; 8192];
        loop {
            let read = tls.read(&mut chunk).await?;
            if read == 0 {
                break;
            }
            buffer.extend_from_slice(&chunk[..read]);
            if buffer.len() > MAX_BODY {
                break;
            }
        }
        Ok::<Vec<u8>, std::io::Error>(buffer)
    })
    .await
    .map_err(|_| AetherError::Api(format!("exchange with {address} timed out")))?
    .map_err(|e| AetherError::Api(format!("exchange with {address}: {e}")))?;

    let (status, body) = parse_response(&collected)?;

    Ok(ApiResponse {
        status,
        body,
        route: format!("{address} / {}", fingerprint.label()),
    })
}

#[cfg(test)]
async fn exchange(
    request: &ApiRequest,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
) -> Result<ApiResponse> {
    let tls = establish(&request.host, address, fingerprint, ech).await?;
    send(request, tls, address, fingerprint).await
}

#[cfg(test)]
async fn attempt_ech(
    request: &ApiRequest,
    address: SocketAddr,
    list: Vec<u8>,
) -> Result<ApiResponse> {
    let tls = establish_ech(&request.host, address, Fingerprint::Ech, list).await?;
    send(request, tls, address, Fingerprint::Ech).await
}

/// One attempt of the camouflaged route: one fingerprint to one address.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Attempt {
    address: SocketAddr,
    fingerprint: Fingerprint,
    slot: usize,
}

/// Every attempt, in the order they are started: ECH fingerprints (`Ech` and
/// `EchStreamSplit`) on every address first, then each plaintext fallback
/// fingerprint on every address.
fn attempt_plan(addresses: &[SocketAddr], with_ech: bool) -> VecDeque<Attempt> {
    Fingerprint::all()
        .into_iter()
        .filter(|fingerprint| with_ech || !fingerprint.is_ech())
        .flat_map(|fingerprint| {
            addresses
                .iter()
                .enumerate()
                .map(move |(slot, &address)| Attempt {
                    address,
                    fingerprint,
                    slot,
                })
        })
        .collect()
}

/// One attempt, start to answer. The handshakes of many attempts race, but
/// only one request is on the wire at a time (`turn`): the first attempt
/// whose handshake is done sends it, and the next one only if that fails. A
/// registration sent twice would make two devices.
async fn run_attempt(
    request: &ApiRequest,
    attempt: Attempt,
    ech: Option<&[u8]>,
    turn: &Semaphore,
) -> Result<ApiResponse> {
    let tls = if attempt.fingerprint.is_ech() {
        // A live key another attempt already got from an edge or DoH beats the
        // one this route started with; if we only have a decoy config, rotate
        // its outer public_name across attempt slots.
        let list = cached_ech()
            .or_else(|| {
                ech.map(|raw| {
                    if is_decoy_ech_config(raw) {
                        decoy_ech_config_for_slot(attempt.slot)
                    } else {
                        raw.to_vec()
                    }
                })
            })
            .ok_or_else(|| AetherError::Ech("no ech config list".into()))?;
        establish_ech(&request.host, attempt.address, attempt.fingerprint, list).await?
    } else {
        establish(&request.host, attempt.address, attempt.fingerprint, None).await?
    };

    let _turn = turn
        .acquire()
        .await
        .map_err(|_| AetherError::Api("the camouflaged route was closed".into()))?;
    send(request, tls, attempt.address, attempt.fingerprint).await
}

pub async fn fetch(request: &ApiRequest) -> Result<ApiResponse> {
    // Load (and log) the trust store up front rather than inside the first handshake.
    let _ = trust_roots();

    // The edge addresses and the ECH key are worked out side by side.
    let (addresses, ech) = tokio::join!(candidates(&request.host), ech_config_list());
    if addresses.is_empty() {
        return Err(AetherError::Api(
            "no camouflaged route to the api was available".into(),
        ));
    }

    // Through an upstream proxy a failed connect says the circuit was slow or
    // down, not that the edge address is dead, so addresses are never written
    // off in that case.
    let proxied = crate::upstream::configured().is_some();

    let ech_key = ech.as_deref();
    let turn = Semaphore::new(1);
    let turn = &turn;

    let mut queue = attempt_plan(&addresses, ech_key.is_some());
    log::info!(
        "[apifront] racing {} attempts over {} edge addresses{}, {} at a time",
        queue.len(),
        addresses.len(),
        if ech_key.is_some() { ", ech first" } else { "" },
        PARALLEL_ATTEMPTS
    );

    let started = Instant::now();
    let stop_starting = started + ROUTE_BUDGET;
    let give_up = stop_starting + ROUTE_GRACE;
    let ech_alone_until = started + ECH_HEAD_START;
    let mut next_start = started;
    let mut ech_running = 0usize;
    let mut running = FuturesUnordered::new();

    let mut rejection: Option<ApiResponse> = None;
    let mut failure: Option<AetherError> = None;
    let mut unreachable: Vec<SocketAddr> = Vec::new();

    loop {
        let now = Instant::now();
        if now >= give_up {
            log::info!(
                "[apifront] the camouflaged route used its {}s; giving up",
                (ROUTE_BUDGET + ROUTE_GRACE).as_secs()
            );
            break;
        }
        if now >= stop_starting && !queue.is_empty() {
            log::info!(
                "[apifront] {}s on the camouflaged route; {} attempts left unstarted",
                ROUTE_BUDGET.as_secs(),
                queue.len()
            );
            queue.clear();
        }

        let mut wake = give_up;
        while let Some(attempt) = queue.front().copied() {
            if unreachable.contains(&attempt.address) {
                queue.pop_front();
                continue;
            }
            if running.len() >= PARALLEL_ATTEMPTS {
                break;
            }
            if now < next_start {
                wake = wake.min(next_start);
                break;
            }
            if !attempt.fingerprint.is_ech() && ech_running > 0 && now < ech_alone_until {
                wake = wake.min(ech_alone_until);
                break;
            }
            queue.pop_front();
            if attempt.fingerprint.is_ech() {
                ech_running += 1;
            }
            running.push(async move {
                let outcome = run_attempt(request, attempt, ech_key, turn).await;
                (attempt, outcome)
            });
            next_start = now + ATTEMPT_STAGGER;
        }

        if running.is_empty() && queue.is_empty() {
            break;
        }

        tokio::select! {
            finished = running.next(), if !running.is_empty() => {
                if let Some((attempt, outcome)) = finished {
                    if attempt.fingerprint.is_ech() {
                        ech_running = ech_running.saturating_sub(1);
                    }
                    let address = attempt.address;
                    match outcome {
                        Ok(response) if (200..300).contains(&response.status) => {
                            return Ok(response);
                        }
                        Ok(response) => {
                            log::info!(
                                "[apifront] {} answered {} via {}: {}",
                                request.host,
                                response.status,
                                response.route,
                                response.body.chars().take(160).collect::<String>()
                            );
                            if final_api_answer(&response) {
                                log::info!(
                                    "[apifront] the api itself refused the request; another \
                                     route would get the same answer, so stopping here"
                                );
                                return Ok(response);
                            }
                            if rejection.is_none() || response.status != 403 {
                                rejection = Some(response);
                            }
                        }
                        Err(error) => {
                            log::info!(
                                "[apifront] {} attempt via {address} failed: {error}",
                                attempt.fingerprint.label()
                            );
                            if !proxied
                                && never_connected(&error)
                                && !unreachable.contains(&address)
                            {
                                log::info!(
                                    "[apifront] {address} never accepted a tcp connection; \
                                     leaving it out of the remaining attempts"
                                );
                                unreachable.push(address);
                                if unreachable.len() == addresses.len() {
                                    log::info!(
                                        "[apifront] no edge address accepted a tcp \
                                         connection; giving up early"
                                    );
                                }
                            }
                            failure = Some(error);
                        }
                    }
                }
            }
            _ = tokio::time::sleep_until(wake) => {}
        }
    }

    if let Some(response) = rejection {
        return Ok(response);
    }

    Err(failure.unwrap_or_else(|| {
        AetherError::Api(format!(
            "no camouflaged route answered within {}s",
            (ROUTE_BUDGET + ROUTE_GRACE).as_secs()
        ))
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn self_signed(common_name: &str) -> X509 {
        use boring::asn1::Asn1Time;
        use boring::ec::{EcGroup, EcKey};
        use boring::hash::MessageDigest;
        use boring::nid::Nid;
        use boring::pkey::PKey;
        use boring::x509::{X509Builder, X509NameBuilder};

        let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1).expect("group");
        let key = PKey::from_ec_key(EcKey::generate(&group).expect("ec key")).expect("pkey");

        let mut name = X509NameBuilder::new().expect("name builder");
        name.append_entry_by_nid(Nid::COMMONNAME, common_name)
            .expect("common name");
        let name = name.build();

        let mut builder = X509Builder::new().expect("x509 builder");
        builder.set_version(2).expect("version");
        builder.set_subject_name(&name).expect("subject");
        builder.set_issuer_name(&name).expect("issuer");
        builder.set_pubkey(&key).expect("pubkey");
        builder
            .set_not_before(&Asn1Time::days_from_now(0).expect("now"))
            .expect("not before");
        builder
            .set_not_after(&Asn1Time::days_from_now(1).expect("tomorrow"))
            .expect("not after");
        builder
            .sign(&key, MessageDigest::sha256())
            .expect("sign");
        builder.build()
    }

    fn answer(status: u16, body: &str) -> ApiResponse {
        ApiResponse {
            status,
            body: body.to_string(),
            route: "test".to_string(),
        }
    }

    fn edge(host: u8) -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(141, 101, 113, host)), 443)
    }

    #[test]
    fn the_verify_error_text_comes_from_boring() {
        let text = unsafe { CStr::from_ptr(X509_verify_cert_error_string(20)) }.to_string_lossy();
        assert!(
            text.contains("local issuer"),
            "code 20 should read as unable to get local issuer certificate, got {text}"
        );
    }

    #[test]
    fn an_android_style_cacerts_file_is_read() {
        let cert = self_signed("aether test root");
        let mut file = b"Certificate:\n    Data:\n        Version: 3 (0x2)\n        (text dump)\n".to_vec();
        file.extend_from_slice(&cert.to_pem().expect("pem"));
        file.extend_from_slice(b"SHA1 Fingerprint=00:11:22\n");

        let parsed = parse_certificates(&file);
        assert_eq!(parsed.len(), 1);
        assert_eq!(
            parsed[0].to_der().expect("der"),
            cert.to_der().expect("der")
        );
    }

    #[test]
    fn a_der_certificate_and_garbage_are_told_apart() {
        let cert = self_signed("aether der root");
        assert_eq!(parse_certificates(&cert.to_der().expect("der")).len(), 1);
        assert!(parse_certificates(b"not a certificate").is_empty());
    }

    #[test]
    fn roots_are_collected_from_a_directory_without_duplicates() {
        let dir = std::env::temp_dir().join(format!("aether-cacerts-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let first = self_signed("aether root one");
        let second = self_signed("aether root two");
        std::fs::write(dir.join("a.0"), first.to_pem().expect("pem")).expect("write");
        std::fs::write(dir.join("b.0"), second.to_pem().expect("pem")).expect("write");
        std::fs::write(dir.join("a.1"), first.to_pem().expect("pem")).expect("write");

        let dirs = vec![dir.to_string_lossy().into_owned()];
        let (roots, sources) = collect_roots(&dirs, &[]);
        std::fs::remove_dir_all(&dir).ok();

        assert_eq!(roots.len(), 2);
        assert_eq!(sources.len(), 1);
    }

    #[test]
    fn only_the_first_system_store_with_roots_is_used() {
        let base = std::env::temp_dir().join(format!("aether-store-pick-{}", std::process::id()));
        let empty = base.join("empty");
        let first = base.join("first");
        let second = base.join("second");
        for dir in [&empty, &first, &second] {
            std::fs::create_dir_all(dir).expect("temp dir");
        }
        std::fs::write(empty.join("junk"), b"not a certificate").expect("write");
        std::fs::write(
            first.join("a.0"),
            self_signed("aether first store").to_pem().expect("pem"),
        )
        .expect("write");
        std::fs::write(
            second.join("b.0"),
            self_signed("aether stale store").to_pem().expect("pem"),
        )
        .expect("write");

        let missing = base.join("missing").to_string_lossy().into_owned();
        let empty = empty.to_string_lossy().into_owned();
        let first = first.to_string_lossy().into_owned();
        let second = second.to_string_lossy().into_owned();
        let candidates = [
            missing.as_str(),
            empty.as_str(),
            first.as_str(),
            second.as_str(),
        ];
        let (dirs, files) = pick_system_store(&candidates, &[]);
        std::fs::remove_dir_all(&base).ok();

        assert_eq!(dirs, vec![first]);
        assert!(files.is_empty());
    }

    #[test]
    fn user_installed_cas_are_not_a_default_trust_source() {
        for dir in SYSTEM_CA_DIRS {
            assert!(
                !dir.contains("cacerts-added"),
                "{dir} would let a user installed root read the account token"
            );
        }
    }

    #[test]
    fn a_root_the_user_disabled_is_left_out() {
        let kept = self_signed("aether kept root");
        let disabled = self_signed("aether disabled root");
        let removed: HashSet<Vec<u8>> = [disabled.to_der().expect("der")].into_iter().collect();

        let (roots, dropped) = drop_removed(vec![kept.clone(), disabled], &removed);
        assert_eq!(dropped, 1);
        assert_eq!(roots.len(), 1);
        assert_eq!(roots[0].to_der().expect("der"), kept.to_der().expect("der"));

        let (roots, dropped) = drop_removed(vec![kept], &HashSet::new());
        assert_eq!((roots.len(), dropped), (1, 0));
    }

    #[test]
    fn only_a_failed_tcp_connect_marks_an_address_dead() {
        assert!(never_connected(&AetherError::Api(
            "connect to 141.101.113.234:443 timed out".into()
        )));
        assert!(!never_connected(&AetherError::Api(
            "tls handshake with 141.101.113.131:443 timed out".into()
        )));
        assert!(!never_connected(&AetherError::Ech("connect to x".into())));
    }

    #[test]
    fn a_json_refusal_from_the_api_ends_the_search() {
        assert!(final_api_answer(&answer(
            429,
            "{\"success\":false,\"errors\":[{\"code\":1015}]}"
        )));
        assert!(final_api_answer(&answer(400, "  {\"success\":false}")));
        assert!(final_api_answer(&answer(401, "{\"errors\":[]}")));
    }

    #[test]
    fn an_edge_refusal_keeps_the_search_going() {
        assert!(!final_api_answer(&answer(403, "{\"errors\":[]}")));
        assert!(!final_api_answer(&answer(408, "{}")));
        assert!(!final_api_answer(&answer(400, "<html>bad request</html>")));
        assert!(!final_api_answer(&answer(502, "{}")));
    }

    #[test]
    fn every_random_edge_address_stays_inside_the_cloudflare_ranges() {
        for prefix in EDGE_PREFIXES {
            for _ in 0..16 {
                let address = random_edge_in_prefix(prefix);
                assert_eq!(address.port(), 443);
                match address.ip() {
                    IpAddr::V4(v4) => {
                        let octets = v4.octets();
                        assert_eq!([octets[0], octets[1], octets[2]], *prefix);
                        assert!(octets[3] >= 1 && octets[3] <= 254);
                    }
                    IpAddr::V6(_) => panic!("the edge range is ipv4 only"),
                }
            }
        }
    }

    #[test]
    fn a_poisoned_dns_answer_is_never_taken_for_an_edge() {
        for text in [
            "10.10.34.34:443",
            "10.10.34.36:443",
            "127.0.0.1:443",
            "0.0.0.0:443",
            "0.1.2.3:443",
            "192.168.1.1:443",
            "172.16.5.4:443",
            "169.254.1.1:443",
            "100.64.0.1:443",
            "224.0.0.1:443",
            "255.255.255.255:443",
            "[2606:4700::6810:1]:443",
        ] {
            let address: SocketAddr = text.parse().unwrap();
            assert!(!usable_edge(&address), "{text}");
        }
        for text in ["104.16.18.94:443", "162.159.192.1:443", "141.101.113.7:443"] {
            let address: SocketAddr = text.parse().unwrap();
            assert!(usable_edge(&address), "{text}");
        }
    }

    #[test]
    fn every_ech_attempt_is_queued_before_any_other_fingerprint() {
        let addresses: Vec<SocketAddr> = (1..=4).map(edge).collect();
        let plan = attempt_plan(&addresses, true);
        let ech_count = addresses.len() * 2; // Ech + EchStreamSplit
        assert_eq!(plan.len(), addresses.len() * Fingerprint::all().len());
        assert!(plan
            .iter()
            .take(ech_count)
            .all(|attempt| attempt.fingerprint.is_ech()));
        assert!(plan
            .iter()
            .skip(ech_count)
            .all(|attempt| !attempt.fingerprint.is_ech()));
        for address in &addresses {
            assert!(plan
                .iter()
                .take(addresses.len())
                .any(|attempt| attempt.address == *address));
        }

        let without = attempt_plan(&addresses, false);
        assert_eq!(
            without.len(),
            addresses.len() * (Fingerprint::all().len() - 2)
        );
        assert!(without
            .iter()
            .all(|attempt| !attempt.fingerprint.is_ech()));
    }

    #[test]
    fn the_route_fits_inside_one_smart_auto_rung() {
        // A Smart Auto rung waits 75 s for the engine; the key fetch gives
        // MASQUE's two api calls 240 s together.
        assert!(ROUTE_BUDGET + ROUTE_GRACE <= Duration::from_secs(60));
        assert!(ECH_KEY_WAIT < ROUTE_BUDGET);
        assert!(ECH_HEAD_START < ROUTE_BUDGET);
        assert!(RESOLVE_TIMEOUT < ROUTE_BUDGET);
        assert!(PARALLEL_ATTEMPTS >= 2);
    }

    #[test]
    fn the_bootstrap_key_set_is_shaped_like_cloudflares() {
        let list = &BOOTSTRAP_ECH_CONFIG;
        assert!(crate::dns::plausible_ech_list(list));
        // ECHConfigList length, then one ECHConfig of version 0xfe0d.
        assert_eq!(
            u16::from_be_bytes([list[0], list[1]]) as usize,
            list.len() - 2
        );
        assert_eq!(&list[2..4], &[0xfe, 0x0d]);
        assert_eq!(
            u16::from_be_bytes([list[4], list[5]]) as usize,
            list.len() - 6
        );
        // DHKEM(X25519, HKDF-SHA256) with a 32 byte public key.
        assert_eq!(&list[7..9], &[0x00, 0x20]);
        assert_eq!(&list[9..11], &[0x00, 0x20]);
        // One suite: HKDF-SHA256, AES-128-GCM. No name padding.
        assert_eq!(&list[43..49], &[0x00, 0x04, 0x00, 0x01, 0x00, 0x01]);
        assert_eq!(list[49], 0);
        // The public name the edge answers a rejection as, and no extensions.
        let name_len = list[50] as usize;
        assert_eq!(&list[51..51 + name_len], b"cloudflare-ech.com");
        assert_eq!(&list[51 + name_len..], &[0, 0]);
    }

    #[test]
    fn decoy_ech_configs_rotate_public_names_and_never_become_session_keys() {
        for slot in 0..DECOY_PUBLIC_NAMES.len() {
            let decoy = decoy_ech_config_for_slot(slot);
            assert!(crate::dns::plausible_ech_list(&decoy));
            assert_eq!(
                crate::dns::extract_public_name(&decoy).as_deref(),
                Some(DECOY_PUBLIC_NAMES[slot])
            );
            assert!(is_decoy_ech_config(&decoy));
            assert!(!remember_ech(decoy));
        }
    }

    #[test]
    fn boring_takes_the_bootstrap_key_set() {
        let mut config = Fingerprint::Ech.configure().expect("ech configures");
        assert!(crate::tls::set_ech_config_list(&mut config, &BOOTSTRAP_ECH_CONFIG).is_ok());
    }

    #[test]
    fn the_bootstrap_key_set_never_becomes_the_session_key() {
        assert!(!remember_ech(BOOTSTRAP_ECH_CONFIG.to_vec()));
    }

    #[test]
    fn the_ech_route_is_tried_before_anything_else() {
        assert_eq!(Fingerprint::all()[0], Fingerprint::Ech);
        assert_eq!(Fingerprint::all()[1], Fingerprint::EchStreamSplit);
    }

    #[test]
    fn an_empty_ech_list_is_refused_before_it_reaches_boring() {
        let mut config = Fingerprint::Ech.configure().expect("ech configures");
        assert!(crate::tls::set_ech_config_list(&mut config, &[]).is_err());
    }

    #[test]
    fn the_api_route_keeps_no_ech_key_of_its_own() {
        assert!(!remember_ech(vec![0, 3, 0xfe, 0x0d, 0]));
    }

    #[test]
    fn the_request_carries_the_host_header_and_a_length() {
        let request = ApiRequest {
            method: "POST".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg".to_string(),
            headers: vec![("Content-Type".to_string(), "application/json".to_string())],
            body: Some(b"{\"a\":1}".to_vec()),
        };

        let wire = String::from_utf8(render_request(&request)).expect("utf8");
        assert!(wire.starts_with("POST /v0a4471/reg HTTP/1.1\r\n"));
        assert!(wire.contains("Host: api.cloudflareclient.com\r\n"));
        assert!(wire.contains("Content-Type: application/json\r\n"));
        assert!(wire.contains("Content-Length: 7\r\n"));
        assert!(wire.ends_with("\r\n\r\n{\"a\":1}"));
    }

    #[test]
    fn a_body_less_request_still_declares_a_zero_length() {
        let request = ApiRequest {
            method: "GET".to_string(),
            host: "example.invalid".to_string(),
            path: "/".to_string(),
            headers: Vec::new(),
            body: None,
        };
        let wire = String::from_utf8(render_request(&request)).expect("utf8");
        assert!(wire.contains("Content-Length: 0\r\n"));
    }

    #[test]
    fn a_plain_response_is_parsed() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":\"x\"}";
        let (status, body) = parse_response(raw).expect("parsed");
        assert_eq!(status, 200);
        assert_eq!(body, "{\"id\":\"x\"}");
    }

    #[test]
    fn a_chunked_response_is_reassembled() {
        let raw = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n4\r\n:1}\n\r\n0\r\n\r\n";
        let (status, body) = parse_response(raw).expect("parsed");
        assert_eq!(status, 200);
        assert_eq!(body, "{\"a\":1}\n");
    }

    #[test]
    fn a_rejection_status_is_reported_rather_than_hidden() {
        let raw = b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 30\r\n\r\nslow down";
        let (status, body) = parse_response(raw).expect("parsed");
        assert_eq!(status, 429);
        assert_eq!(body, "slow down");
    }

    #[test]
    fn a_headless_response_is_an_error() {
        assert!(parse_response(b"garbage").is_err());
    }

    #[test]
    fn a_chunked_body_is_joined_on_bytes_without_panicking() {
        let text = "ééé".as_bytes();
        let mut raw = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".to_vec();
        raw.extend_from_slice(format!("{:x}\r\n", 3).as_bytes());
        raw.extend_from_slice(&text[..3]);
        raw.extend_from_slice(format!("\r\n{:x}\r\n", text.len() - 3).as_bytes());
        raw.extend_from_slice(&text[3..]);
        raw.extend_from_slice(b"\r\n0\r\n\r\n");
        let (status, body) = parse_response(&raw).expect("parsed");
        assert_eq!(status, 200);
        assert_eq!(body, "ééé");

        let raw =
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffffffffffffffff\r\nabc\r\n";
        let (_, body) = parse_response(raw).expect("parsed");
        assert!(body.is_empty());
    }

    #[test]
    fn each_fingerprint_builds_a_usable_configuration() {
        for fingerprint in Fingerprint::all() {
            assert!(
                fingerprint.configure().is_ok(),
                "{} should configure",
                fingerprint.label()
            );
        }
    }

    #[test]
    fn only_the_split_profiles_chop_the_client_hello() {
        assert!(Fingerprint::Ech.fragments().enabled);
        assert!(Fingerprint::EchStreamSplit.fragments().enabled);
        assert!(Fingerprint::SplitLegacy.fragments().enabled);
        assert!(Fingerprint::SplitModern.fragments().enabled);
        assert!(!Fingerprint::Modern.fragments().enabled);
        assert!(!Fingerprint::ChromeLike.fragments().enabled);
    }

    #[tokio::test]
    #[ignore = "needs live network access to the cloudflare edge"]
    async fn every_fingerprint_reaches_the_live_edge() {
        let request = ApiRequest {
            method: "GET".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg/nonexistent".to_string(),
            headers: vec![("User-Agent".to_string(), "WARP for Android".to_string())],
            body: None,
        };

        let mut reached = 0;
        for fingerprint in Fingerprint::all() {
            if fingerprint.is_ech() {
                continue;
            }
            let address = random_edge_address();
            match exchange(&request, address, fingerprint, None).await {
                Ok(response) => {
                    reached += 1;
                    println!(
                        "{} -> {} via {}",
                        fingerprint.label(),
                        response.status,
                        response.route
                    );
                }
                Err(error) => println!("{} failed: {error}", fingerprint.label()),
            }
        }

        assert!(reached > 0, "no fingerprint reached the edge");
    }

    #[tokio::test]
    #[ignore = "needs live network access to the cloudflare edge"]
    async fn the_ech_route_reaches_the_warp_api() {
        let request = ApiRequest {
            method: "GET".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg/nonexistent".to_string(),
            headers: vec![("User-Agent".to_string(), "WARP for Android".to_string())],
            body: None,
        };

        let list = ech_config_list()
            .await
            .expect("always a key set, the bootstrap one at worst");

        let address = random_edge_address();
        let response = attempt_ech(&request, address, list)
            .await
            .expect("the ech route should reach the api");
        println!(
            "ech -> {} via {}: {}",
            response.status, response.route, response.body
        );
        assert!(
            !response.body.trim_start().starts_with('<'),
            "an html page means the edge did not route us to the api"
        );
    }

    #[tokio::test]
    #[ignore = "needs live network access to the cloudflare edge"]
    async fn the_bootstrap_key_set_brings_back_the_live_keys() {
        // No DNS at all: the edge rejects the bootstrap set, hands back its
        // live keys, and the retry is a real ECH handshake.
        let request = ApiRequest {
            method: "GET".to_string(),
            host: "api.cloudflareclient.com".to_string(),
            path: "/v0a4471/reg/nonexistent".to_string(),
            headers: vec![("User-Agent".to_string(), "WARP for Android".to_string())],
            body: None,
        };

        let address = random_edge_address();
        let response = attempt_ech(&request, address, BOOTSTRAP_ECH_CONFIG.to_vec())
            .await
            .expect("the edge should hand back its keys and accept them");
        assert!(
            cached_ech().is_some(),
            "the live keys are kept for the tunnel"
        );
        assert!(
            !response.body.trim_start().starts_with('<'),
            "an html page means the edge did not route us to the api"
        );
    }
}
