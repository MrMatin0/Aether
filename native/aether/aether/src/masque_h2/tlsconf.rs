//! The TLS handshake this transport hides behind: a Chrome shaped
//! ClientHello, the account's client certificate, and endpoint pinning.

use boring::pkey::PKey;
use boring::ssl::{SslConnector, SslMethod, SslVersion};
use boring::x509::X509;

use crate::error::{AetherError, Result};

use super::config::H2TunnelConfig;

const H2_ALPN: &[u8] = b"\x02h2";
const CHROME_GROUPS: &str = "P-256:X25519:P-384";

fn tls_err<E: std::fmt::Display>(e: E) -> AetherError {
    AetherError::Tls(e.to_string())
}

pub(super) fn build_tls(cfg: &H2TunnelConfig) -> Result<boring::ssl::ConnectConfiguration> {
    let mut builder = SslConnector::builder(SslMethod::tls()).map_err(tls_err)?;

    builder
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .map_err(tls_err)?;
    builder
        .set_max_proto_version(Some(SslVersion::TLS1_3))
        .map_err(tls_err)?;

    builder.set_grease_enabled(true);

    let groups = std::env::var("AETHER_TLS_GROUPS").ok();
    let groups = groups
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(CHROME_GROUPS);
    builder.set_curves_list(groups).map_err(tls_err)?;

    builder.set_alpn_protos(H2_ALPN).map_err(tls_err)?;

    let cert = X509::from_pem(&cfg.cert_pem).map_err(tls_err)?;
    let key = PKey::private_key_from_pem(&cfg.key_pem).map_err(tls_err)?;
    builder.set_certificate(&cert).map_err(tls_err)?;
    builder.set_private_key(&key).map_err(tls_err)?;

    // Verification:
    //   pin_endpoint + pins -> pin based, because the SNI is deliberately
    //                          spoofable on this path
    //   otherwise           -> NONE, which is what Cloudflare's MASQUE edges
    //                          require
    let pin_refs: Vec<&[u8]> = cfg.expected_pins.iter().map(|p| p.as_slice()).collect();
    crate::tls::install_verification(&mut *builder, cfg.pin_endpoint, &pin_refs)?;

    let connector = builder.build();
    let mut config = connector.configure().map_err(tls_err)?;

    // A pinned certificate is identified by its key, not by a name, so
    // hostname matching does not apply. Standard CA verification needs it.
    let use_pin_verification = cfg.pin_endpoint && !cfg.expected_pins.is_empty();
    config.set_verify_hostname(!use_pin_verification);
    config.set_use_server_name_indication(true);

    Ok(config)
}
