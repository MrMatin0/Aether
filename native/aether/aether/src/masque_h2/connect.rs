//! Opening a CONNECT-IP stream.
//!
//! Verification and the live tunnel need byte-for-byte the same sequence -
//! tcp, tls, h2 handshake, CONNECT request, status check - and used to carry a
//! copy each, which is how they drifted.

use bytes::Bytes;

use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};

use super::config::{log_or_debug, H2TunnelConfig};
use super::request::{build_connect_request, dial};
use super::stream::AbortOnDrop;
use super::tlsconf::build_tls;

/// An accepted CONNECT-IP stream, with the connection driver bound to its
/// lifetime.
pub(super) struct ConnectIpStream {
    pub(super) send: h2::SendStream<Bytes>,
    pub(super) recv: h2::RecvStream,
    /// `None` when the peer does not support PING. Only one can ever be taken
    /// from a connection, so it is taken here or not at all.
    pub(super) ping_pong: Option<h2::PingPong>,
    /// Aborts the connection driver when this stream is dropped.
    pub(super) _driver: AbortOnDrop,
}

pub(super) async fn open_connect_ip(cfg: &H2TunnelConfig) -> Result<ConnectIpStream> {
    let quiet = cfg.quiet;

    let tls_config = build_tls(cfg)?;

    log_or_debug(quiet, format!("[h2] connecting tcp to {}", cfg.peer));
    let tcp = dial(cfg.peer).await?;
    let _ = tcp.set_nodelay(true);

    let frag_cfg = FragmentConfig::from_env();
    if frag_cfg.enabled {
        log_or_debug(
            quiet,
            format!(
                "[h2] fragmenting client hello: size={}..{} delay={}..{}ms",
                frag_cfg.size_min, frag_cfg.size_max, frag_cfg.delay_min_ms, frag_cfg.delay_max_ms
            ),
        );
    }
    let fragment = FragmentingStream::new(tcp, frag_cfg);

    let tls = tokio_boring::connect(tls_config, &cfg.sni, fragment)
        .await
        .map_err(|e| AetherError::Tls(format!("h2 tls handshake: {e}")))?;
    log_or_debug(
        quiet,
        format!(
            "[h2] tls established; alpn={}",
            String::from_utf8_lossy(tls.ssl().selected_alpn_protocol().unwrap_or(b""))
        ),
    );

    let (h2, mut connection) = h2::client::handshake(tls)
        .await
        .map_err(|e| AetherError::Masque(format!("h2 handshake: {e}")))?;

    // Taken before the driver takes ownership of the connection.
    let ping_pong = connection.ping_pong();

    // The guard is created immediately, so every `?` below takes the driver
    // task with it. Aborting by hand is what used to leak one driver per
    // failed verification.
    let driver = AbortOnDrop(tokio::spawn(async move {
        if let Err(e) = connection.await {
            log::debug!("[h2] connection driver ended: {e}");
        }
    }));

    let mut h2 = h2
        .ready()
        .await
        .map_err(|e| AetherError::Masque(format!("h2 ready: {e}")))?;

    let req = build_connect_request(cfg)?;
    let (resp_fut, send) = h2
        .send_request(req, false)
        .map_err(|e| AetherError::Masque(format!("send_request: {e}")))?;
    log_or_debug(
        quiet,
        format!("[h2] connect-ip request sent to {}", cfg.authority),
    );

    let response = resp_fut
        .await
        .map_err(|e| AetherError::Masque(format!("await response: {e}")))?;
    let status = response.status();
    log_or_debug(quiet, format!("[h2] connect-ip status: {}", status.as_u16()));
    if !status.is_success() {
        return Err(AetherError::Masque(format!(
            "h2 connect-ip status {}",
            status.as_u16()
        )));
    }

    Ok(ConnectIpStream {
        send,
        recv: response.into_body(),
        ping_pong,
        _driver: driver,
    })
}
