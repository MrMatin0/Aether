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

extern "C" {
    fn X509_STORE_CTX_get_error(ctx: *const c_void) -> c_int;
    fn X509_verify_cert_error_string(err: c_long) -> *const c_char;
}

const EDGE_PREFIX: [u8; 3] = [141, 101, 113];
const EDGE_SAMPLES: usize = 3;
const RESOLVED_SAMPLES: usize = 2;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
/// Through an upstream proxy (tor, psiphon) the connect also covers building
/// the circuit, which regularly takes longer than a direct tcp connect.
const PROXY_CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 512 * 1024;

/// fix/ech-bootstrap-race: the camouflaged route starts new attempts for this
/// long. It used to walk every fingerprint on every address one after the
/// other, and on a line that lets tcp up but blackholes the ClientHello each
/// of those 25 attempts cost the full handshake timeout: ~200 s for one api
/// call, past the key fetch's 240 s for MASQUE's two and far past a Smart
/// Auto rung.
const ROUTE_BUDGET: Duration = Duration::from_secs(35);
/// Attempts already under way get this much longer to finish their exchange.
const ROUTE_GRACE: Duration = Duration::from_secs(20);
/// How many handshakes run at the same time.
const PARALLEL_ATTEMPTS: usize = 4;
/// The time between two attempts starting.
const ATTEMPT_STAGGER: Duration = Duration::from_millis(300);
/// The ech attempts have the field to themselves this long, so a plaintext
/// ClientHello that happens to come up first does not carry the request
/// while an ech one is a round trip away.
const ECH_HEAD_START: Duration = Duration::from_secs(3);
/// How long the api waits for the core's ECH key lookup (dns.rs) before it
/// starts from [BOOTSTRAP_ECH_CONFIG] instead.
const ECH_KEY_WAIT: Duration = Duration::from_secs(4);
/// How long the system resolver may take over the api name.
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(2);

/// fix/ech-bootstrap-race: an ECHConfigList no edge can decrypt, to start an
/// ECH handshake when the core has no key (every lookup dropped, poisoned or
/// late, which is the usual case on a filtered line).
///
/// It is shaped exactly like the key set Cloudflare publishes: ECH version
/// 0xfe0d, DHKEM(X25519) with a real X25519 public key, HKDF-SHA256 with
/// AES-128-GCM, no name padding, public name cloudflare-ech.com. Its private
/// key exists nowhere, so the edge cannot open the inner ClientHello. It
/// rejects ECH the way RFC 9849 says it must: it finishes the outer handshake
/// as cloudflare-ech.com (a certificate boring checks against that public
/// name) and hands back the keys it serves right now as retry configs. The
/// retry path below keeps those (dns::remember, so the MASQUE tunnel gets them
/// too) and the second handshake is a real ECH one. The api name is never on
/// the wire in plaintext, and no DNS answer is needed at all.
///
/// It is only ever offered, never kept: [remember_ech] refuses it.
const BOOTSTRAP_ECH_CONFIG: [u8; 71] = [
    0x00, 0x45, 0xfe, 0x0d, 0x00, 0x41, 0x5a, 0x00, 0x20, 0x00, 0x20, 0x7c,
    0x75, 0xf0, 0xdd, 0x6b, 0x5e, 0x0b, 0xdf, 0x9e, 0xfa, 0x74, 0x1e, 0x03,
    0x39, 0xde, 0x60, 0x8f, 0x74, 0x1d, 0xa8, 0x18, 0xb0, 0x29, 0x71, 0x41,
    0xd5, 0x64, 0xb2, 0xce, 0x6c, 0x34, 0x39, 0x00, 0x04, 0x00, 0x01, 0x00,
    0x01, 0x00, 0x12, 0x63, 0x6c, 0x6f, 0x75, 0x64, 0x66, 0x6c, 0x61, 0x72,
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
const ECH_GROUPS: &str = "X25519:P-256";

const ALPN_HTTP1: &[u8] = b"\x08http/1.1";

static TRUST_ROOTS: OnceLock<Vec<X509>> = OnceLock::new();

/// The stream an api exchange runs over.
type ApiStream = tokio_boring::SslStream<FragmentingStream<tokio::net::TcpStream>>;

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
/// A subject of cloudflare-ech.com means the edge rejected ech and answered
/// with the outer name; anything that is not a cloudflare issuer means the
/// connection is being intercepted.
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
    Ech,
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
            Fingerprint::SplitLegacy => "split-tls12",
            Fingerprint::SplitModern => "split-tls13",
            Fingerprint::Modern => "plain-tls13",
            Fingerprint::ChromeLike => "chrome",
        }
    }

    fn all() -> [Fingerprint; 5] {
        [
            Fingerprint::Ech,
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
            _ => FragmentConfig::disabled(),
        }
    }

    fn configure(self) -> Result<boring::ssl::ConnectConfiguration> {
        let mut builder =
            SslConnector::builder(SslMethod::tls()).map_err(|e| AetherError::Tls(e.to_string()))?;

        let tls = |error: boring::error::ErrorStack| AetherError::Tls(error.to_string());

        match self {
            Fingerprint::Ech => {
                builder
                    .set_min_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder
                    .set_max_proto_version(Some(SslVersion::TLS1_3))
                    .map_err(tls)?;
                builder.set_grease_enabled(true);
                builder.set_curves_list(ECH_GROUPS).map_err(tls)?;
                builder.set_alpn_protos(ALPN_HTTP1).map_err(tls)?;
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

        builder.build().configure().map_err(tls)
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

pub fn random_edge_address() -> SocketAddr {
    let host = rand::rng().random_range(1..=254u8);
    let ip = Ipv4Addr::new(EDGE_PREFIX[0], EDGE_PREFIX[1], EDGE_PREFIX[2], host);
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

/// The edge addresses to try. With --ech the api name is not looked up at
/// all: a plaintext DNS question for api.cloudflareclient.com would put on the
/// wire exactly what the ECH handshake hides, and a filtered resolver answers
/// it late or with a block address anyway. Without --ech the system resolver
/// gets [RESOLVE_TIMEOUT].
async fn candidates(host: &str) -> Vec<SocketAddr> {
    let ech = crate::dns::ech_requested();
    let samples = if ech {
        EDGE_SAMPLES + RESOLVED_SAMPLES
    } else {
        EDGE_SAMPLES
    };

    let mut list: Vec<SocketAddr> = Vec::new();
    while list.len() < samples {
        let candidate = random_edge_address();
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

/// The key the core already has for this process (see dns.rs).
fn cached_ech() -> Option<Vec<u8>> {
    crate::dns::cached_key()
}

/// Hands a key an edge sent back to the core, which keeps it for every later
/// ECH handshake of the process, the MASQUE tunnel's included. Returns whether
/// it was kept: only a plausible ECHConfigList is, and never the bootstrap
/// set, which no edge can decrypt.
fn remember_ech(list: Vec<u8>) -> bool {
    if list.as_slice() == BOOTSTRAP_ECH_CONFIG.as_slice() {
        return false;
    }
    crate::dns::remember(list)
}

/// The ECH keys come from the core (fix/ech-from-core): the key the session
/// already has, the base64 list --ech was given, or the lookup
/// AETHER_ECH_DOMAIN / AETHER_ECH_DNS configure. Cloudflare publishes one
/// shared key set, so the keys of any name it fronts with ECH also encrypt a
/// client hello meant for the api.
///
/// fix/ech-bootstrap-race: the lookup gets [ECH_KEY_WAIT], and without an
/// answer by then the route starts from [BOOTSTRAP_ECH_CONFIG] and the edge
/// hands back its live keys. Before, no answer meant no ECH at all, and that
/// is exactly what a filtered line gives.
async fn ech_config_list() -> Option<Vec<u8>> {
    match tokio::time::timeout(ECH_KEY_WAIT, crate::dns::session_key()).await {
        Ok(Some(list)) => return Some(list),
        Ok(None) => log::info!(
            "[apifront] the core has no ECHConfigList for the api; offering the bootstrap key \
             set, the edge hands back its live keys"
        ),
        Err(_) => log::info!(
            "[apifront] no ECHConfigList after {}s; offering the bootstrap key set, the edge \
             hands back its live keys",
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
    let stream = FragmentingStream::new(tcp, fingerprint.fragments());

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
            log::info!("[apifront] ech accepted by {address}; the api name stayed encrypted");
        } else {
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

/// An ECH handshake with `address`, plus a single retry when the edge rejects
/// our keys but hands back the ones it currently serves. With the bootstrap
/// key set that retry is the normal path: the first handshake only fetches
/// the keys.
async fn establish_ech(host: &str, address: SocketAddr, list: Vec<u8>) -> Result<ApiStream> {
    match establish(host, address, Fingerprint::Ech, Some(&list)).await {
        Err(first) => match cached_ech() {
            Some(fresh) if fresh != list => {
                log::info!("[apifront] first ech attempt via {address} failed: {first}");
                log::info!(
                    "[apifront] retrying {address} with the {} byte ech key set it handed back",
                    fresh.len()
                );
                establish(host, address, Fingerprint::Ech, Some(&fresh)).await
            }
            _ => Err(first),
        },
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
    let tls = establish_ech(&request.host, address, list).await?;
    send(request, tls, address, Fingerprint::Ech).await
}

/// One attempt of the camouflaged route: one fingerprint to one address.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Attempt {
    address: SocketAddr,
    fingerprint: Fingerprint,
}

/// Every attempt, in the order they are started: ECH on every address first,
/// then each other fingerprint on every address.
fn attempt_plan(addresses: &[SocketAddr], with_ech: bool) -> VecDeque<Attempt> {
    Fingerprint::all()
        .into_iter()
        .filter(|fingerprint| with_ech || *fingerprint != Fingerprint::Ech)
        .flat_map(|fingerprint| {
            addresses.iter().map(move |&address| Attempt {
                address,
                fingerprint,
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
    let tls = match attempt.fingerprint {
        Fingerprint::Ech => {
            // A key another attempt already got from an edge beats the one
            // this route started with (the bootstrap set, at worst).
            let list = cached_ech()
                .or_else(|| ech.map(<[u8]>::to_vec))
                .ok_or_else(|| AetherError::Ech("no ech config list".into()))?;
            establish_ech(&request.host, attempt.address, list).await?
        }
        other => establish(&request.host, attempt.address, other, None).await?,
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
            if attempt.fingerprint != Fingerprint::Ech && ech_running > 0 && now < ech_alone_until
            {
                wake = wake.min(ech_alone_until);
                break;
            }
            queue.pop_front();
            if attempt.fingerprint == Fingerprint::Ech {
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
                    if attempt.fingerprint == Fingerprint::Ech {
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
    fn every_random_edge_address_stays_inside_the_cloudflare_range() {
        for _ in 0..64 {
            let address = random_edge_address();
            assert_eq!(address.port(), 443);
            match address.ip() {
                IpAddr::V4(v4) => {
                    let octets = v4.octets();
                    assert_eq!([octets[0], octets[1], octets[2]], EDGE_PREFIX);
                    assert!(octets[3] >= 1 && octets[3] <= 254);
                }
                IpAddr::V6(_) => panic!("the edge range is ipv4 only"),
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
        assert_eq!(plan.len(), addresses.len() * Fingerprint::all().len());
        assert!(plan
            .iter()
            .take(addresses.len())
            .all(|attempt| attempt.fingerprint == Fingerprint::Ech));
        assert!(plan
            .iter()
            .skip(addresses.len())
            .all(|attempt| attempt.fingerprint != Fingerprint::Ech));
        for address in &addresses {
            assert!(plan
                .iter()
                .take(addresses.len())
                .any(|attempt| attempt.address == *address));
        }

        let without = attempt_plan(&addresses, false);
        assert_eq!(
            without.len(),
            addresses.len() * (Fingerprint::all().len() - 1)
        );
        assert!(without
            .iter()
            .all(|attempt| attempt.fingerprint != Fingerprint::Ech));
    }

    #[test]
    fn the_route_fits_inside_one_smart_auto_rung() {
        // A Smart Auto rung waits 75 s for the engine; the key fetch gives
        // MASQUE's two api calls 240 s together.
        assert!(ROUTE_BUDGET + ROUTE_GRACE <= Duration::from_secs(60));
        assert!(ECH_KEY_WAIT < ROUTE_BUDGET);
        assert!(ECH_HEAD_START < ROUTE_BUDGET);
        assert!(PARALLEL_ATTEMPTS >= 2);
    }

    #[test]
    fn the_bootstrap_key_set_is_shaped_like_cloudflares() {
        let list = &BOOTSTRAP_ECH_CONFIG;
        assert!(crate::dns::plausible_ech_list(list));
        // ECHConfigList length, then one ECHConfig of version 0xfe0d.
        assert_eq!(u16::from_be_bytes([list[0], list[1]]) as usize, list.len() - 2);
        assert_eq!(&list[2..4], &[0xfe, 0x0d]);
        assert_eq!(u16::from_be_bytes([list[4], list[5]]) as usize, list.len() - 6);
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
    fn boring_takes_the_bootstrap_key_set() {
        let mut config = Fingerprint::Ech.configure().expect("ech configures");
        assert!(crate::tls::set_ech_config_list(&mut config, &BOOTSTRAP_ECH_CONFIG).is_ok());
    }

    #[test]
    fn the_bootstrap_key_set_never_becomes_the_session_key() {
        assert!(!remember_ech(BOOTSTRAP_ECH_CONFIG.to_vec()));
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
    fn the_ech_route_is_tried_before_anything_else() {
        assert_eq!(Fingerprint::all()[0], Fingerprint::Ech);
    }

    #[test]
    fn an_empty_ech_list_is_refused_before_it_reaches_boring() {
        let mut config = Fingerprint::Ech.configure().expect("ech configures");
        assert!(crate::tls::set_ech_config_list(&mut config, &[]).is_err());
    }

    #[test]
    fn the_api_route_keeps_no_ech_key_of_its_own() {
        // fix/ech-from-core: the keys live in dns.rs, so a key set the core
        // would refuse cannot reach the api route through a side door either.
        assert!(!remember_ech(vec![0, 3, 0xfe, 0x0d, 0]));
    }

    #[test]
    fn only_the_split_profiles_chop_the_client_hello() {
        assert!(Fingerprint::Ech.fragments().enabled);
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
            if fingerprint == Fingerprint::Ech {
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
        assert!(cached_ech().is_some(), "the live keys are kept for the tunnel");
        assert!(
            !response.body.trim_start().starts_with('<'),
            "an html page means the edge did not route us to the api"
        );
    }
}
