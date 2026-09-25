use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Mutex;
use std::time::Duration;

use boring::ssl::{SslConnector, SslMethod, SslVersion};
use rand::RngExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};

const EDGE_PREFIX: [u8; 3] = [141, 101, 113];
const EDGE_SAMPLES: usize = 3;
const RESOLVED_SAMPLES: usize = 2;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 512 * 1024;

/// Base64 ECHConfigList override, for networks where the dns lookup is tampered with.
const ECH_ENV: &str = "AETHER_API_ECH";

/// The smallest ECHConfig contents worth considering: config id, kem, an x25519
/// public key, one cipher suite, max name length, a public name and extensions
/// come to well over this.
const MIN_ECH_CONFIG: usize = 32;

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

static ECH_CACHE: Mutex<Option<Vec<u8>>> = Mutex::new(None);

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

async fn candidates(host: &str) -> Vec<SocketAddr> {
    let mut list: Vec<SocketAddr> = Vec::new();

    while list.len() < EDGE_SAMPLES {
        let candidate = random_edge_address();
        if !list.contains(&candidate) {
            list.push(candidate);
        }
    }

    if let Ok(resolved) = tokio::net::lookup_host((host, 443)).await {
        for address in resolved
            .filter(|entry| entry.is_ipv4())
            .take(RESOLVED_SAMPLES)
        {
            if !list.contains(&address) {
                list.push(address);
            }
        }
    }

    list
}

/// An ECHConfigList is a 2 byte length followed by at least one ECHConfig
/// (2 byte version, 2 byte length, then the contents). A list whose prefix does
/// not match its size, or that is too short to hold a real config, is refused
/// by boring anyway; caching it would poison every later attempt.
fn plausible_ech_list(list: &[u8]) -> bool {
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

fn cached_ech() -> Option<Vec<u8>> {
    ECH_CACHE.lock().ok().and_then(|guard| guard.clone())
}

/// Caches a key set, but only one that looks like a real ECHConfigList.
/// Returns whether it was kept.
fn remember_ech(list: Vec<u8>) -> bool {
    if !plausible_ech_list(&list) {
        return false;
    }
    if let Ok(mut guard) = ECH_CACHE.lock() {
        *guard = Some(list);
    }
    true
}

/// Where the ECH keys come from, in order: what an edge last handed back,
/// an operator supplied override, then the same dns lookup the masque path uses.
/// Cloudflare publishes one shared key set, so the keys for cloudflare-ech.com
/// also encrypt a client hello meant for the api.
async fn ech_config_list() -> Option<Vec<u8>> {
    if let Some(list) = cached_ech() {
        return Some(list);
    }

    if let Ok(raw) = std::env::var(ECH_ENV) {
        let raw = raw.trim();
        if !raw.is_empty() {
            match crate::tls::decode_ech_config_list(raw) {
                Ok(list) if remember_ech(list.clone()) => {
                    log::info!("[apifront] using the ECHConfigList from {ECH_ENV}");
                    return Some(list);
                }
                Ok(list) => log::warn!(
                    "[apifront] {ECH_ENV} holds {} bytes that are not an ECHConfigList; ignoring it",
                    list.len()
                ),
                Err(e) => log::warn!("[apifront] {ECH_ENV} is not valid base64 ({e}); ignoring it"),
            }
        }
    }

    match crate::dns::fetch_ech_config().await {
        Ok(list) if remember_ech(list.clone()) => Some(list),
        Ok(list) => {
            log::info!(
                "[apifront] the dns answer held {} bytes that are not an ECHConfigList; skipping the ech route",
                list.len()
            );
            None
        }
        Err(e) => {
            log::info!("[apifront] no ECHConfigList for the api ({e}); skipping the ech route");
            None
        }
    }
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

async fn exchange(
    request: &ApiRequest,
    address: SocketAddr,
    fingerprint: Fingerprint,
    ech: Option<&[u8]>,
) -> Result<ApiResponse> {
    let tcp = match crate::upstream::configured() {
        Some(proxy) => tokio::time::timeout(CONNECT_TIMEOUT, proxy.connect(address))
            .await
            .map_err(|_| AetherError::Api(format!("connect to {address} timed out")))?
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
        tokio_boring::connect(config, &request.host, stream),
    )
    .await
    .map_err(|_| AetherError::Api(format!("tls handshake with {address} timed out")))?;

    let mut tls = match handshake {
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

/// One ech attempt, plus a single retry when the edge rejects our keys but
/// hands back the ones it currently serves.
async fn attempt_ech(
    request: &ApiRequest,
    address: SocketAddr,
    ech: &mut Option<Vec<u8>>,
) -> Result<ApiResponse> {
    let list = match ech.clone() {
        Some(list) => list,
        None => return Err(AetherError::Ech("no ech config list".into())),
    };

    match exchange(request, address, Fingerprint::Ech, Some(&list)).await {
        Err(first) => match cached_ech() {
            Some(fresh) if fresh != list => {
                log::info!("[apifront] first ech attempt via {address} failed: {first}");
                log::info!(
                    "[apifront] retrying {address} with the {} byte ech key set it handed back",
                    fresh.len()
                );
                *ech = Some(fresh.clone());
                exchange(request, address, Fingerprint::Ech, Some(&fresh)).await
            }
            _ => Err(first),
        },
        outcome => outcome,
    }
}

pub async fn fetch(request: &ApiRequest) -> Result<ApiResponse> {
    let addresses = candidates(&request.host).await;
    if addresses.is_empty() {
        return Err(AetherError::Api(
            "no camouflaged route to the api was available".into(),
        ));
    }

    let mut ech = ech_config_list().await;

    let mut rejection: Option<ApiResponse> = None;
    let mut failure: Option<AetherError> = None;

    for fingerprint in Fingerprint::all() {
        if fingerprint == Fingerprint::Ech && ech.is_none() {
            continue;
        }

        for address in &addresses {
            let outcome = match fingerprint {
                Fingerprint::Ech => attempt_ech(request, *address, &mut ech).await,
                other => exchange(request, *address, other, None).await,
            };

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
                    if rejection.is_none() || response.status != 403 {
                        rejection = Some(response);
                    }
                }
                Err(error) => {
                    log::info!(
                        "[apifront] {} attempt via {address} failed: {error}",
                        fingerprint.label()
                    );
                    failure = Some(error);
                }
            }
        }
    }

    if let Some(response) = rejection {
        return Ok(response);
    }

    Err(failure.unwrap_or_else(|| AetherError::Api("every camouflaged route failed".into())))
}

#[cfg(test)]
mod tests {
    use super::*;

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

    fn sample_ech_list(config_len: usize) -> Vec<u8> {
        let mut list = vec![0u8, 0, 0xfe, 0x0d];
        list.extend_from_slice(&(config_len as u16).to_be_bytes());
        list.extend(std::iter::repeat(0xab).take(config_len));
        let declared = (list.len() - 2) as u16;
        list[..2].copy_from_slice(&declared.to_be_bytes());
        list
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

        let mut ech = ech_config_list().await;
        assert!(ech.is_some(), "no ECHConfigList; set {ECH_ENV} to a base64 list");

        let address = random_edge_address();
        let response = attempt_ech(&request, address, &mut ech)
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
}
