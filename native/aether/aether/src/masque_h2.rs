use std::io;
use std::net::IpAddr;
use std::net::Ipv4Addr;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use boring::pkey::PKey;
use boring::ssl::{SslConnector, SslMethod, SslVersion};
use boring::x509::X509;
use bytes::{BufMut, Bytes, BytesMut};
use http::Method;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot};

use crate::consts;
use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};
use crate::masque::{self, Capsule, CapsuleParser};
use crate::quic::{AssignedAddr, Control, Internals};
use self::spoof::SpoofMode;
use crate::tls;

const H2_ALPN: &[u8] = b"\x02h2";
const CHROME_GROUPS: &str = "P-256:X25519:P-384";

/// The largest DATA frame we let the edge send us. The h2 default is the RFC
/// minimum of 16 KiB, so a fast stream pays four times the frame headers and
/// four times the wakeups it needs to.
const H2_MAX_FRAME_SIZE: u32 = 64 * 1024;

/// How much a single write to the edge may carry. Every capsule sent on its own
/// costs a DATA frame, a TLS record and a TCP segment, which for a 1280-byte
/// packet is mostly overhead, so packets already queued behind one another are
/// gathered up to this much and sent together.
const H2_SEND_BATCH_BYTES: usize = 32 * 1024;

/// How long the tunnel waits, on a clean shutdown, for the send task to put the
/// closing frame on the wire.
const SENDER_CLOSE_GRACE: Duration = Duration::from_millis(250);

struct AbortOnDrop(tokio::task::AbortHandle);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// Everything the send task accepts besides the packets on the outbound queue.
enum SenderMsg {
    /// A capsule that is already framed, used by the data-plane probes.
    Capsule(Bytes),
    /// End the request stream and stop.
    Finish,
}

/// HTTP/2 flow control decides how much data the edge may have in flight toward
/// us before it has to stop and wait for an acknowledgement, which puts a hard
/// ceiling of window / round-trip-time on a download. The h2 crate defaults to
/// the RFC minimum of 64 KiB on both the stream and the connection, and 64 KiB
/// over a 130 ms round trip is about 500 KB/s however fast the line underneath
/// really is. QUIC and WireGuard never run into this because their windows are
/// megabytes wide; this is what puts HTTP/2 on the same footing.
fn h2_builder() -> h2::client::Builder {
    let mut builder = h2::client::Builder::new();
    builder
        .initial_window_size(crate::sysprofile::h2_stream_window_bytes())
        .initial_connection_window_size(crate::sysprofile::h2_connection_window_bytes())
        .max_frame_size(H2_MAX_FRAME_SIZE);
    builder
}

pub struct H2TunnelConfig {
    pub peer: SocketAddr,
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub local_ipv4: Ipv4Addr,
    pub quiet: bool,
    pub pin_endpoint: bool,
    pub expected_pins: Vec<Vec<u8>>,
}

fn log_or_debug(quiet: bool, msg: String) {
    if quiet {
        log::debug!("{msg}");
    } else {
        log::info!("{msg}");
    }
}

fn data_check_enabled() -> bool {
    std::env::var("AETHER_MASQUE_NO_DATA_CHECK").is_err()
}

fn validation_timeout() -> Duration {
    let secs = std::env::var("AETHER_MASQUE_VALIDATE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(10);
    Duration::from_secs(secs)
}

const DATA_PROBE_REQUIRED_SUCCESSES: u32 = 2;
const DATA_PROBE_RESEND: Duration = Duration::from_millis(700);

fn h2_keepalive_interval() -> Duration {
    let secs = std::env::var("AETHER_MASQUE_H2_KEEPALIVE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(15);
    Duration::from_secs(secs)
}

fn h2_keepalive_timeout() -> Duration {
    let secs = std::env::var("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(20);
    Duration::from_secs(secs)
}

pub fn enabled() -> bool {
    match std::env::var("AETHER_MASQUE_HTTP2") {
        Ok(v) => {
            let v = v.trim().to_lowercase();
            v == "1" || v == "true" || v == "h2" || v == "yes" || v == "on"
        }
        Err(_) => false,
    }
}

pub fn h2_peer(quic_peer: SocketAddr) -> SocketAddr {
    if let Ok(v) = std::env::var("AETHER_MASQUE_H2_PEER") {
        if let Ok(addr) = v.trim().parse::<SocketAddr>() {
            return addr;
        }
    }
    quic_peer
}

/// Wraps the TCP stream with the ClientHello shaping the profile asked for.
///
/// Spoofing runs UNDER fragmentation, so the two compose rather than replace
/// each other: with `sni_split` the first write is cut in the middle of the
/// server name and the fragmenter then chops those pieces further; with
/// `decoy` the prefix record goes out before the fragmenter ever sees the
/// real hello, so the decoy itself is always sent whole. Off means this type
/// is a pass-through, so a profile that never opts in pays nothing.
pub struct SpoofingStream<S> {
    inner: S,
    mode: SpoofMode,
    decoy_pending: Option<std::io::Cursor<Vec<u8>>>,
}

impl<S> SpoofingStream<S> {
    pub fn new(inner: S, mode: SpoofMode) -> Self {
        let decoy_pending =
            (mode == SpoofMode::Decoy).then(|| std::io::Cursor::new(spoof::decoy_record()));
        Self {
            inner,
            mode,
            decoy_pending,
        }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for SpoofingStream<S> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for SpoofingStream<S> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();

        // The decoy record has to be ON THE WIRE before the first byte of the
        // real ClientHello, which is the whole point of the mode. It is small
        // (9 bytes), so it is written whole before `buf` is even looked at.
        if let Some(cursor) = this.decoy_pending.as_mut() {
            let pos = cursor.position() as usize;
            let data = &cursor.get_ref()[pos..];
            if !data.is_empty() {
                match Pin::new(&mut this.inner).poll_write(cx, data) {
                    Poll::Ready(Ok(0)) => {
                        return Poll::Ready(Err(io::Error::new(
                            io::ErrorKind::WriteZero,
                            "decoy record could not be written",
                        )))
                    }
                    Poll::Ready(Ok(n)) => {
                        cursor.set_position((pos + n) as u64);
                        return Poll::Pending;
                    }
                    Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                    Poll::Pending => return Poll::Pending,
                }
            }
            this.decoy_pending = None;
        }

        let cut = match this.mode {
            SpoofMode::SniSplit => spoof::sni_split_point(buf),
            SpoofMode::StreamSplit => spoof::stream_split_points(buf.len()).first().copied(),
            SpoofMode::Off | SpoofMode::Decoy => None,
        };
        match cut {
            Some(at) if at > 0 && at < buf.len() => {
                Pin::new(&mut this.inner).poll_write(cx, &buf[..at])
            }
            _ => Pin::new(&mut this.inner).poll_write(cx, buf),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

fn build_tls(cfg: &H2TunnelConfig) -> Result<boring::ssl::ConnectConfiguration> {
    let mut builder =
        SslConnector::builder(SslMethod::tls()).map_err(|e| AetherError::Tls(e.to_string()))?;

    builder
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_max_proto_version(Some(SslVersion::TLS1_3))
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    builder.set_grease_enabled(true);

    let groups = std::env::var("AETHER_TLS_GROUPS").ok();
    let groups = groups
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(CHROME_GROUPS);
    builder
        .set_curves_list(groups)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    builder
        .set_alpn_protos(H2_ALPN)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let cert = X509::from_pem(&cfg.cert_pem).map_err(|e| AetherError::Tls(e.to_string()))?;
    let key =
        PKey::private_key_from_pem(&cfg.key_pem).map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_certificate(&cert)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_private_key(&key)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    // Install TLS verification:
    // pin_endpoint=true with pins: pin-based verification (SNI can be spoofed)
    // pin_endpoint=false: SslVerifyMode::NONE (default, required for Cloudflare MASQUE edges)
    let pin_refs: Vec<&[u8]> = cfg.expected_pins.iter().map(|p| p.as_slice()).collect();
    tls::install_verification(&mut *builder, cfg.pin_endpoint, &pin_refs)?;

    let connector = builder.build();
    let mut config = connector
        .configure()
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    // When using pin-based verification, SNI may be spoofed for DPI bypass,
    // so hostname verification against the cert's CN/SAN is not applicable.
    // Standard CA verification requires hostname matching.
    let use_pin_verification = cfg.pin_endpoint && !cfg.expected_pins.is_empty();
    config.set_verify_hostname(!use_pin_verification);
    config.set_use_server_name_indication(true);

    Ok(config)
}

fn build_connect_request(cfg: &H2TunnelConfig) -> Result<http::Request<()>> {
    let authority = format!("{}:443", cfg.authority);
    let uri = format!("https://{}", authority);
    http::Request::builder()
        .method(Method::CONNECT)
        .uri(uri)
        .header("cf-connect-proto", consts::CF_CONNECT_PROTOCOL)
        .header("pq-enabled", "false")
        .header("user-agent", "")
        .body(())
        .map_err(|e| AetherError::Masque(format!("build request: {e}")))
}

pub async fn dial(peer: std::net::SocketAddr) -> Result<TcpStream> {
    match crate::upstream::configured() {
        Some(proxy) => proxy.connect(peer).await,
        None => crate::egress::tcp_connect(peer)
            .await
            .map_err(AetherError::Io),
    }
}

pub async fn verify_h2(cfg: &H2TunnelConfig, timeout: Duration) -> Result<Duration> {
    let start = Instant::now();
    let data_check = data_check_enabled();

    let attempt = async {
        let tls_config = build_tls(cfg)?;
        let tcp = dial(cfg.peer).await?;
        let _ = tcp.set_nodelay(true);
        let fragment = FragmentingStream::new(tcp, FragmentConfig::from_env());
        let spoofed = SpoofingStream::new(fragment, SpoofMode::from_env());
        let tls = tokio_boring::connect(tls_config, &cfg.sni, spoofed)
            .await
            .map_err(|e| AetherError::Tls(format!("h2 tls handshake: {e}")))?;
        let (h2, connection) = h2_builder()
            .handshake(tls)
            .await
            .map_err(|e| AetherError::Masque(format!("h2 handshake: {e}")))?;
        let _driver = AbortOnDrop(
            tokio::spawn(async move {
                let _ = connection.await;
            })
            .abort_handle(),
        );
        let mut h2 = h2
            .ready()
            .await
            .map_err(|e| AetherError::Masque(format!("h2 ready: {e}")))?;
        let req = build_connect_request(cfg)?;
        let (resp_fut, mut send_stream) = h2
            .send_request(req, false)
            .map_err(|e| AetherError::Masque(format!("send_request: {e}")))?;
        let response = resp_fut
            .await
            .map_err(|e| AetherError::Masque(format!("await response: {e}")))?;
        let status = response.status();
        if !status.is_success() {
            return Err(AetherError::Masque(format!(
                "h2 connect-ip status {}",
                status.as_u16()
            )));
        }

        if !data_check {
            return Ok(());
        }

        let mut recv_body = response.into_body();
        let mut capsules = CapsuleParser::new();
        let probe = masque::build_dns_probe_packet(cfg.local_ipv4);
        let framed = masque::encode_datagram_capsule(&probe);
        send_capsule(&mut send_stream, Bytes::from(framed)).await?;

        let mut probe_successes: u32 = 0;
        let mut resend = tokio::time::interval(DATA_PROBE_RESEND);
        resend.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        resend.tick().await;

        loop {
            tokio::select! {
                biased;

                data = futures::future::poll_fn(|cx| recv_body.poll_data(cx)) => {
                    match data {
                        Some(Ok(chunk)) => {
                            let _ = recv_body.flow_control().release_capacity(chunk.len());
                            capsules.push(&chunk);
                            loop {
                                match capsules.next() {
                                    Ok(Some(Capsule::Datagram(_))) => {
                                        probe_successes += 1;
                                        if probe_successes >= DATA_PROBE_REQUIRED_SUCCESSES {
                                            return Ok(());
                                        }
                                        let framed = masque::encode_datagram_capsule(&probe);
                                        send_capsule(&mut send_stream, Bytes::from(framed)).await?;
                                        resend.reset();
                                    }
                                    Ok(Some(_)) => continue,
                                    Ok(None) => break,
                                    Err(_) => break,
                                }
                            }
                        }
                        Some(Err(e)) => {
                            return Err(AetherError::Masque(format!("h2 body: {e}")));
                        }
                        None => {
                            return Err(AetherError::Masque("h2 stream closed before data".into()));
                        }
                    }
                }

                _ = resend.tick() => {
                    let framed = masque::encode_datagram_capsule(&probe);
                    send_capsule(&mut send_stream, Bytes::from(framed)).await?;
                }
            }
        }
    };

    match tokio::time::timeout(timeout, attempt).await {
        Ok(Ok(())) => Ok(start.elapsed()),
        Ok(Err(e)) => Err(e),
        Err(_) => Err(AetherError::Other("h2 verify timeout".into())),
    }
}

async fn sleep_until_deadline(deadline: Option<Instant>) {
    match deadline {
        Some(at) => tokio::time::sleep_until(tokio::time::Instant::from_std(at)).await,
        None => std::future::pending().await,
    }
}

pub async fn run(
    cfg: H2TunnelConfig,
    internals: Internals,
    addr_tx: Option<mpsc::Sender<AssignedAddr>>,
    ready_tx: Option<oneshot::Sender<()>>,
) -> Result<()> {
    let (outbound_rx, inbound_tx, mut ctrl_rx) = internals.into_parts();
    let quiet = cfg.quiet;
    let data_check = data_check_enabled();
    let probe_packet = masque::build_dns_probe_packet(cfg.local_ipv4);
    let mut ready_tx = ready_tx;
    let mut ready_fired = false;
    let mut validate_successes: u32 = 0;

    let tls_config = build_tls(&cfg)?;

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

    let spoof_mode = SpoofMode::from_env();
    if spoof_mode != SpoofMode::Off {
        log_or_debug(
            quiet,
            format!("[h2] client hello spoofing: {}", spoof_mode.label()),
        );
    }
    let spoofed = SpoofingStream::new(fragment, spoof_mode);

    let tls = tokio_boring::connect(tls_config, &cfg.sni, spoofed)
        .await
        .map_err(|e| AetherError::Tls(format!("h2 tls handshake: {e}")))?;
    log_or_debug(
        quiet,
        format!(
            "[h2] tls established; alpn={}",
            String::from_utf8_lossy(tls.ssl().selected_alpn_protocol().unwrap_or(b""))
        ),
    );

    let (h2, mut connection) = h2_builder()
        .handshake(tls)
        .await
        .map_err(|e| AetherError::Masque(format!("h2 handshake: {e}")))?;

    log_or_debug(
        quiet,
        format!(
            "[h2] flow control: stream window {}KB, connection window {}KB, max frame {}KB",
            crate::sysprofile::h2_stream_window_bytes() / 1024,
            crate::sysprofile::h2_connection_window_bytes() / 1024,
            H2_MAX_FRAME_SIZE / 1024,
        ),
    );

    let mut ping_pong = connection
        .ping_pong()
        .ok_or_else(|| AetherError::Masque("h2 connection does not support ping".into()))?;

    let driver_handle = tokio::spawn(async move {
        if let Err(e) = connection.await {
            log::debug!("[h2] connection driver ended: {e}");
        }
    });
    let _driver_guard = AbortOnDrop(driver_handle.abort_handle());

    let mut h2 = h2
        .ready()
        .await
        .map_err(|e| AetherError::Masque(format!("h2 ready: {e}")))?;

    let req = build_connect_request(&cfg)?;

    let (resp_fut, send_stream) = h2
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
    log_or_debug(
        quiet,
        format!("[h2] connect-ip status: {}", status.as_u16()),
    );
    if !status.is_success() {
        return Err(AetherError::Masque(format!(
            "h2 connect-ip status {}",
            status.as_u16()
        )));
    }

    let mut recv_body = response.into_body();
    let mut capsules = CapsuleParser::new();

    // Sending gets a task of its own. Kept in the receive loop, a send that has
    // to wait for the edge's window to open would stop poll_data from being
    // polled as well, so a busy upload would stall the download alongside it.
    let (sender_tx, sender_rx) = mpsc::channel::<SenderMsg>(16);
    let (outcome_tx, mut sender_outcome) = oneshot::channel::<Result<()>>();
    let sender_task = tokio::spawn(async move {
        let _ = outcome_tx.send(pump_outbound(send_stream, outbound_rx, sender_rx).await);
    });
    let _sender_guard = AbortOnDrop(sender_task.abort_handle());

    let mut validate_deadline: Option<Instant> = None;
    if data_check {
        let framed = masque::encode_datagram_capsule(&probe_packet);
        if sender_tx
            .send(SenderMsg::Capsule(Bytes::from(framed)))
            .await
            .is_err()
        {
            log::debug!("[h2] initial data-plane probe: the send path is gone");
        }
        validate_deadline = Some(Instant::now() + validation_timeout());
        log_or_debug(
            quiet,
            "[h2] validating data-plane (end-to-end probe) before exposing socks5".to_string(),
        );
    } else if !ready_fired {
        ready_fired = true;
        if let Some(tx) = ready_tx.take() {
            let _ = tx.send(());
        }
    }

    let mut probe_interval = tokio::time::interval(DATA_PROBE_RESEND);
    probe_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let keepalive_period = h2_keepalive_interval();
    let mut keepalive_interval = tokio::time::interval(keepalive_period);
    keepalive_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut awaiting_pong = false;
    let mut pong_deadline: Option<Instant> = None;
    let keepalive_timeout = h2_keepalive_timeout();

    loop {
        if data_check && !ready_fired {
            if let Some(dl) = validate_deadline {
                if Instant::now() >= dl {
                    log::warn!(
                        "[h2] data-plane validation timed out; edge accepts control but drops traffic"
                    );
                    close_sender(&sender_tx, &mut sender_outcome).await;
                    return Err(AetherError::Masque(
                        "h2 data-plane validation timeout (handshake ok, no traffic)".into(),
                    ));
                }
            }
        }

        if let Some(dl) = pong_deadline {
            if Instant::now() >= dl {
                let late = futures::FutureExt::now_or_never(std::future::poll_fn(|cx| {
                    ping_pong.poll_pong(cx)
                }));
                if matches!(late, Some(Ok(_))) {
                    awaiting_pong = false;
                    pong_deadline = None;
                    log::debug!("[h2] keepalive pong received");
                } else {
                    log::warn!(
                        "[h2] no PING response from edge within {:?}; connection is stalled",
                        keepalive_timeout
                    );
                    close_sender(&sender_tx, &mut sender_outcome).await;
                    return Err(AetherError::Masque("h2 keepalive timeout".into()));
                }
            }
        }

        tokio::select! {
            biased;

            _ = keepalive_interval.tick(), if ready_fired && !awaiting_pong => {
                match ping_pong.send_ping(h2::Ping::opaque()) {
                    Ok(()) => {
                        awaiting_pong = true;
                        pong_deadline = Some(Instant::now() + keepalive_timeout);
                        log::debug!("[h2] keepalive ping sent");
                    }
                    Err(e) => log::debug!("[h2] keepalive ping send failed: {e}"),
                }
            }

            pong = std::future::poll_fn(|cx| ping_pong.poll_pong(cx)), if awaiting_pong => {
                match pong {
                    Ok(_) => {
                        awaiting_pong = false;
                        pong_deadline = None;
                        log::debug!("[h2] keepalive pong received");
                    }
                    Err(e) => {
                        log::warn!("[h2] keepalive ping failed: {e}");
                        close_sender(&sender_tx, &mut sender_outcome).await;
                        return Err(AetherError::Masque(format!("h2 keepalive: {e}")));
                    }
                }
            }

            _ = probe_interval.tick(), if data_check && !ready_fired => {
                let framed = masque::encode_datagram_capsule(&probe_packet);
                if sender_tx.try_send(SenderMsg::Capsule(Bytes::from(framed))).is_err() {
                    log::trace!("[h2] data-plane probe resend was dropped");
                }
            }

            _ = sleep_until_deadline(pong_deadline) => {}

            ctrl = ctrl_rx.recv() => {
                match ctrl {
                    Some(Control::Close) | None => {
                        close_sender(&sender_tx, &mut sender_outcome).await;
                        log_or_debug(quiet, "[h2] closing tunnel".to_string());
                        return Ok(());
                    }
                    Some(Control::Migrate) => {}
                }
            }

            outcome = &mut sender_outcome => {
                return match outcome {
                    Ok(Ok(())) => {
                        log_or_debug(quiet, "[h2] send path closed".to_string());
                        Ok(())
                    }
                    Ok(Err(e)) => {
                        log::debug!("[h2] send: {e}");
                        Err(e)
                    }
                    Err(_) => Err(AetherError::Masque("h2 send task stopped".into())),
                };
            }

            data = futures::future::poll_fn(|cx| recv_body.poll_data(cx)) => {
                match data {
                    Some(Ok(chunk)) => {
                        let chunk_len = chunk.len();
                        capsules.push(&chunk);
                        let got_data = drain_capsules(&mut capsules, &inbound_tx, &addr_tx).await;
                        let _ = recv_body.flow_control().release_capacity(chunk_len);
                        if got_data && !ready_fired {
                            validate_successes += 1;
                            log::debug!(
                                "[h2] data-plane round-trip {}/{} confirmed",
                                validate_successes, DATA_PROBE_REQUIRED_SUCCESSES
                            );
                            if validate_successes >= DATA_PROBE_REQUIRED_SUCCESSES {
                                ready_fired = true;
                                validate_deadline = None;
                                if let Some(tx) = ready_tx.take() {
                                    let _ = tx.send(());
                                }
                                log_or_debug(quiet, "[h2] tunnel validated (end-to-end data confirmed); exposing socks5".to_string());
                            } else {
                                let framed = masque::encode_datagram_capsule(&probe_packet);
                                if sender_tx
                                    .try_send(SenderMsg::Capsule(Bytes::from(framed)))
                                    .is_err()
                                {
                                    log::trace!("[h2] follow-up data-plane probe was dropped");
                                }
                            }
                        }
                    }
                    Some(Err(e)) => {
                        log::warn!("[h2] recv body error: {e}");
                        return Err(AetherError::Masque(format!("h2 body: {e}")));
                    }
                    None => {
                        log_or_debug(quiet, "[h2] server closed stream".to_string());
                        return Ok(());
                    }
                }
            }
        }
    }
}

/// Ends the request stream and waits briefly for the send task to get the
/// closing frame out before the connection is torn down.
async fn close_sender(
    sender_tx: &mpsc::Sender<SenderMsg>,
    outcome: &mut oneshot::Receiver<Result<()>>,
) {
    if sender_tx.send(SenderMsg::Finish).await.is_ok() {
        let _ = tokio::time::timeout(SENDER_CLOSE_GRACE, outcome).await;
    }
}

/// Owns the request stream and is the only thing that writes to it, so capsules
/// cannot interleave and a wait for send capacity costs nothing but upload.
async fn pump_outbound(
    mut send: h2::SendStream<Bytes>,
    mut outbound_rx: mpsc::Receiver<Vec<u8>>,
    mut control_rx: mpsc::Receiver<SenderMsg>,
) -> Result<()> {
    let mut batch = BytesMut::with_capacity(H2_SEND_BATCH_BYTES);

    loop {
        tokio::select! {
            biased;

            msg = control_rx.recv() => {
                match msg {
                    Some(SenderMsg::Capsule(framed)) => send_capsule(&mut send, framed).await?,
                    Some(SenderMsg::Finish) | None => {
                        let _ = send.send_data(Bytes::new(), true);
                        return Ok(());
                    }
                }
            }

            packet = outbound_rx.recv() => {
                let Some(packet) = packet else {
                    let _ = send.send_data(Bytes::new(), true);
                    return Ok(());
                };

                batch.reserve(H2_SEND_BATCH_BYTES);
                append_datagram_capsule(&mut batch, &packet);

                while batch.len() < H2_SEND_BATCH_BYTES {
                    match outbound_rx.try_recv() {
                        Ok(next) => append_datagram_capsule(&mut batch, &next),
                        Err(_) => break,
                    }
                }

                let framed = batch.split().freeze();
                send_capsule(&mut send, framed).await?;
            }
        }
    }
}

/// Lays a DATAGRAM capsule onto the end of `out`, byte for byte what
/// `masque::append_datagram_capsule` writes into a `Vec`.
fn append_datagram_capsule(out: &mut BytesMut, packet: &[u8]) {
    put_varint(out, masque::CAPSULE_DATAGRAM);
    put_varint(out, packet.len() as u64);
    out.extend_from_slice(packet);
}

/// QUIC variable-length integer, as capsules use for their type and length.
fn put_varint(out: &mut BytesMut, value: u64) {
    if value < 1 << 6 {
        out.put_u8(value as u8);
    } else if value < 1 << 14 {
        out.put_u16(0x4000 | value as u16);
    } else if value < 1 << 30 {
        out.put_u32(0x8000_0000 | value as u32);
    } else {
        out.put_u64(0xc000_0000_0000_0000 | value);
    }
}

async fn send_capsule(send: &mut h2::SendStream<Bytes>, data: Bytes) -> Result<()> {
    let len = data.len();
    if len == 0 {
        return Ok(());
    }

    send.reserve_capacity(len);
    while send.capacity() < len {
        match futures::future::poll_fn(|cx| send.poll_capacity(cx)).await {
            Some(Ok(_)) => {}
            Some(Err(e)) => return Err(AetherError::Masque(format!("h2 capacity: {e}"))),
            None => return Err(AetherError::Masque("h2 stream closed".into())),
        }
    }

    send.send_data(data, false)
        .map_err(|e| AetherError::Masque(format!("h2 send_data: {e}")))?;
    Ok(())
}

/// Hands every complete capsule to where it belongs. IP packets go to the
/// netstack, and when its queue is full this waits for room rather than
/// dropping the packet.
async fn drain_capsules(
    capsules: &mut CapsuleParser,
    inbound_tx: &mpsc::Sender<Vec<u8>>,
    addr_tx: &Option<mpsc::Sender<AssignedAddr>>,
) -> bool {
    let mut delivered = false;
    loop {
        match capsules.next() {
            Ok(Some(Capsule::Datagram(payload))) => {
                let pkt = match masque::strip_datagram_context(&payload) {
                    Some(inner) => inner,
                    None => {
                        log::trace!("[h2] discarding a datagram that is not an ip packet");
                        continue;
                    }
                };
                delivered = true;
                match inbound_tx.try_send(pkt) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(pkt)) => {
                        log::trace!("[h2] inbound queue full, waiting for the netstack");
                        if inbound_tx.send(pkt).await.is_err() {
                            return delivered;
                        }
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => return delivered,
                }
            }
            Ok(Some(Capsule::AddressAssign(addrs))) => {
                for a in addrs {
                    if let Some(ip) = bytes_to_ip(a.ip_version, &a.address) {
                        log::info!("[h2] edge assigned {}/{}", ip, a.prefix_len);
                        if let Some(tx) = addr_tx {
                            let _ = tx.try_send(AssignedAddr {
                                ip,
                                prefix: a.prefix_len,
                            });
                        }
                    }
                }
            }
            Ok(Some(Capsule::RouteAdvertisement(routes))) => {
                log::info!("[h2] received {} route advertisements", routes.len());
            }
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(e) => {
                log::trace!("[h2] capsule parse: {e}");
                break;
            }
        }
    }
    delivered
}

fn bytes_to_ip(version: u8, bytes: &[u8]) -> Option<IpAddr> {
    match version {
        4 if bytes.len() == 4 => Some(IpAddr::V4([bytes[0], bytes[1], bytes[2], bytes[3]].into())),
        6 if bytes.len() == 16 => {
            let mut b = [0u8; 16];
            b.copy_from_slice(bytes);
            Some(IpAddr::V6(b.into()))
        }
        _ => None,
    }
}

/// MASQUE ClientHello "spoofing" for the HTTP/2 carrier.
///
/// Lives here and not in its own file because the only consumer is this
/// transport: `lib.rs` and every other module stay untouched, which keeps
/// the patch one sync can drop cleanly.
///
/// WHAT THIS IS NOT: the reference repo (MrMatin0/SPOOOOOOOFING) ships options
/// named `wrong_seq` and `custom_decoy` whose code does neither - its
/// "wrong_seq" writes the stream in several pieces (userspace cannot touch a
/// TCP sequence number; the kernel owns those) and its "custom_decoy" sends
/// the real ClientHello immediately (no decoy is ever generated). Its
/// `fake_client_hello` builder emits a record whose declared length does not
/// match its body, which a strict peer would reject on sight.
///
/// So every mode here is named for what the code ACTUALLY does:
///
///  - `sni_split`    - the first write is cut in the middle of the server
///                     name, found by parsing the ClientHello (fragment.rs
///                     already does exactly this when asked).
///  - `stream_split` - the first write is cut into a few small pieces. This
///                    is what the reference's `wrong_seq` really performs.
///  - `decoy`        - a well-formed, deliberately empty TLS prefix record
///                    (content type handshake, a HelloRequest - which a TLS
///                    1.3 server MUST ignore per RFC 8446 §4.1.1) is sent
///                    before the real ClientHello. EXPERIMENTAL: whether a
///                    DPI box keys on the first record while the server
///                    ignores it is a property of the network, not something
///                    a unit test can prove. Off unless the user asks for it.
///
/// SECURITY BOUNDARY: none of this touches verification. The edge certificate
/// is pinned by SPKI hash in tls.rs whether or not a mode is on, so a mode
/// that made the handshake easier to fake could never let a middlebox in -
/// the connection validates end to end or it does not open.
pub mod spoof {
    use std::env;

    use crate::fragment::sni_host_range;

    /// One spoofing strategy. Parsed from `AETHER_MASQUE_H2_SPOOF`.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum SpoofMode {
        /// Nothing extra; the plain handshake.
        Off,
        /// Cut the first write in the middle of the server name.
        SniSplit,
        /// Cut the first write into a few small pieces.
        StreamSplit,
        /// Send a HelloRequest record before the real ClientHello. Experimental.
        Decoy,
    }

    impl SpoofMode {
        /// Parses a mode name. Unknown values are `Off` rather than an error: a
        /// setting that changes the handshake must never be the reason a tunnel
        /// refuses to start, and the env var travels on every launch.
        pub fn parse(raw: &str) -> SpoofMode {
            match raw.trim().to_lowercase().as_str() {
                "sni_split" | "snisplit" => SpoofMode::SniSplit,
                // "wrong_seq" is what the reference called stream splitting.
                "stream_split" | "streamsplit" | "wrong_seq" | "split" => SpoofMode::StreamSplit,
                "decoy" | "fake_client_hello" | "custom_decoy" => SpoofMode::Decoy,
                _ => SpoofMode::Off,
            }
        }

        pub fn from_env() -> SpoofMode {
            match env::var("AETHER_MASQUE_H2_SPOOF") {
                Ok(value) => SpoofMode::parse(&value),
                Err(_) => SpoofMode::Off,
            }
        }

        pub fn label(&self) -> &'static str {
            match self {
                SpoofMode::Off => "off",
                SpoofMode::SniSplit => "sni_split",
                SpoofMode::StreamSplit => "stream_split",
                SpoofMode::Decoy => "decoy",
            }
        }
    }

    /// The SNI the MASQUE transports should present, honouring
    /// `AETHER_MASQUE_SNI`. Falls back to the built-in default when the variable
    /// is unset or holds something that could never be a hostname on the wire.
    pub fn configured_sni() -> String {
        match env::var("AETHER_MASQUE_SNI") {
            Ok(raw) => match sanitize_sni(&raw) {
                Some(host) => host,
                None => {
                    if !raw.trim().is_empty() {
                        log::warn!(
                            "[-] AETHER_MASQUE_SNI {:?} is not a usable hostname; using the default",
                            raw.trim()
                        );
                    }
                    crate::consts::CONNECT_SNI.to_string()
                }
            },
            Err(_) => crate::consts::CONNECT_SNI.to_string(),
        }
    }

    /// Hostname grammar for a name that is about to go into a TLS record:
    /// LDH labels of at most 63 bytes, at least two labels, 253 bytes overall,
    /// one trailing root dot allowed.
    fn sanitize_sni(raw: &str) -> Option<String> {
        let text = raw.trim().trim_end_matches('.').to_lowercase();
        if text.is_empty() || text.len() > 253 || !text.contains('.') {
            return None;
        }
        let valid = text.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
                && !label.starts_with('-')
                && !label.ends_with('-')
        });
        if valid { Some(text) } else { None }
    }

    /// A HelloRequest handshake message in its own TLS record.
    ///
    /// Four zero bytes of handshake body after the 0x01 type byte, wrapped in a
    /// plain TLS record. RFC 8446 §4.1.1: a server that receives a HelloRequest
    /// at any time MUST ignore it, so the real ClientHello right behind it is
    /// processed exactly as if this record were never sent.
    pub fn decoy_record() -> Vec<u8> {
        vec![
            0x16, 0x03, 0x01, // handshake record, TLS 1.0 record version
            0x00, 0x04, // 4 bytes of handshake message
            0x01, 0x00, 0x00, 0x00, // HelloRequest, length 0
        ]
    }

    /// How the first write should be split for [SpoofMode::StreamSplit].
    pub fn stream_split_points(total: usize) -> Vec<usize> {
        const PIECES: [usize; 3] = [64, 32, 24];
        let mut points = Vec::new();
        let mut at = 0usize;
        for piece in PIECES {
            at += piece;
            if at < total {
                points.push(at);
            }
        }
        points
    }

    /// Where [SpoofMode::SniSplit] should cut `buf`, if it holds a ClientHello
    /// with a server name. Delegates to the parser fragmentation already trusts.
    pub fn sni_split_point(buf: &[u8]) -> Option<usize> {
        sni_host_range(buf).map(|(start, end)| start + (end - start) / 2)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn mode_names_parse_and_unknown_values_fall_back_to_off() {
            assert_eq!(SpoofMode::parse("sni_split"), SpoofMode::SniSplit);
            assert_eq!(SpoofMode::parse("stream_split"), SpoofMode::StreamSplit);
            assert_eq!(SpoofMode::parse("decoy"), SpoofMode::Decoy);
            assert_eq!(SpoofMode::parse("wrong_seq"), SpoofMode::StreamSplit);
            assert_eq!(SpoofMode::parse("fake_client_hello"), SpoofMode::Decoy);
            assert_eq!(SpoofMode::parse("custom_decoy"), SpoofMode::Decoy);
            assert_eq!(SpoofMode::parse(""), SpoofMode::Off);
            assert_eq!(SpoofMode::parse("off"), SpoofMode::Off);
            assert_eq!(SpoofMode::parse("nonsense"), SpoofMode::Off);
        }

        #[test]
        fn the_decoy_is_a_well_formed_hello_request_record() {
            let record = decoy_record();
            assert_eq!(record[0], 0x16, "handshake record");
            let declared = u16::from_be_bytes([record[3], record[4]]) as usize;
            assert_eq!(declared, record.len() - 5, "length header matches the body");
            assert_eq!(record[5], 0x01, "HelloRequest handshake type");
            assert_eq!(&record[6..9], &[0x00, 0x00, 0x00], "HelloRequest has no body");
        }

        #[test]
        fn stream_split_points_are_inside_the_buffer_and_increasing() {
            let points = stream_split_points(517);
            assert!(!points.is_empty());
            assert!(points.iter().all(|p| *p < 517));
            assert!(points.windows(2).all(|w| w[0] < w[1]));
            assert!(stream_split_points(10).is_empty());
            assert!(stream_split_points(100).iter().all(|p| *p < 100));
        }

        #[test]
        fn sni_validation_accepts_real_names_and_rejects_garbage() {
            assert_eq!(sanitize_sni(" speed.cloudflare.com "), Some("speed.cloudflare.com".into()));
            assert_eq!(sanitize_sni("Example.COM."), Some("example.com".into()));
            assert_eq!(sanitize_sni("localhost"), None, "one label hides nothing");
            assert_eq!(sanitize_sni("https://example.com"), None);
            assert_eq!(sanitize_sni("exa mple.com"), None);
            assert_eq!(sanitize_sni("-bad.com"), None);
            assert_eq!(sanitize_sni(&"a".repeat(254)), None);
            assert_eq!(sanitize_sni(""), None);
        }

        #[test]
        fn configured_sni_falls_back_to_the_default() {
            env::remove_var("AETHER_MASQUE_SNI");
            assert_eq!(configured_sni(), crate::consts::CONNECT_SNI);
            env::set_var("AETHER_MASQUE_SNI", "speed.cloudflare.com");
            assert_eq!(configured_sni(), "speed.cloudflare.com");
            env::set_var("AETHER_MASQUE_SNI", "not a hostname");
            assert_eq!(configured_sni(), crate::consts::CONNECT_SNI);
            env::remove_var("AETHER_MASQUE_SNI");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    fn ip_packet(marker: u8) -> Vec<u8> {
        let mut pkt = vec![0u8; 24];
        pkt[0] = 0x45;
        pkt[19] = marker;
        pkt
    }

    #[test]
    fn batched_capsules_are_laid_down_exactly_like_the_shared_encoder() {
        for len in [0usize, 20, 63, 64, 1280, 16_383, 16_384, 70_000] {
            let packet = vec![0x45u8; len];
            let mut batch = BytesMut::new();
            append_datagram_capsule(&mut batch, &packet);
            assert_eq!(
                &batch[..],
                masque::encode_datagram_capsule(&packet).as_slice(),
                "a {len}-byte packet must be framed the same way"
            );
        }
    }

    #[test]
    fn a_split_batch_leaves_the_buffer_ready_for_the_next_one() {
        let mut batch = BytesMut::with_capacity(H2_SEND_BATCH_BYTES);
        append_datagram_capsule(&mut batch, &ip_packet(1));
        let first = batch.split().freeze();
        assert!(batch.is_empty());

        append_datagram_capsule(&mut batch, &ip_packet(2));
        let second = batch.split().freeze();
        assert_eq!(&first[..], masque::encode_datagram_capsule(&ip_packet(1)).as_slice());
        assert_eq!(&second[..], masque::encode_datagram_capsule(&ip_packet(2)).as_slice());
    }

    #[tokio::test]
    async fn a_full_inbound_queue_waits_for_the_netstack_instead_of_dropping() {
        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(1);
        let mut parser = CapsuleParser::new();
        for marker in 0..4u8 {
            parser.push(&masque::encode_datagram_capsule(&ip_packet(marker)));
        }

        let reader = tokio::spawn(async move {
            let mut seen = Vec::new();
            for _ in 0..4 {
                tokio::time::sleep(Duration::from_millis(10)).await;
                seen.push(rx.recv().await.expect("a packet")[19]);
            }
            seen
        });

        let delivered = tokio::time::timeout(
            Duration::from_secs(5),
            drain_capsules(&mut parser, &tx, &None),
        )
        .await
        .expect("draining finishes once the reader makes room");
        assert!(delivered);
        assert_eq!(
            reader.await.unwrap(),
            vec![0, 1, 2, 3],
            "every packet arrives, in order, even though the queue only holds one"
        );
    }

    // ---- SpoofingStream ----

    /// Writes whatever it is given into a shared buffer so the test can look
    /// at exactly what went on the wire, and in how many writes.
    #[derive(Clone, Default)]
    struct Sink(std::sync::Arc<std::sync::Mutex<(Vec<u8>, usize)>>);

    impl AsyncWrite for Sink {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            buf: &[u8],
        ) -> Poll<io::Result<usize>> {
            let mut guard = self.0.lock().unwrap();
            guard.0.extend_from_slice(buf);
            guard.1 += 1;
            Poll::Ready(Ok(buf.len()))
        }
        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test]
    async fn decoy_sends_the_prefix_record_before_the_real_bytes() {
        let sink = Sink::default();
        let seen = sink.clone();
        let mut stream = SpoofingStream::new(sink, SpoofMode::Decoy);
        stream.write_all(b"REALHELLO").await.unwrap();
        let (bytes, _) = &*seen.0.lock().unwrap();
        assert_eq!(&bytes[..9], spoof::decoy_record().as_slice());
        assert_eq!(&bytes[9..], b"REALHELLO");
    }

    #[tokio::test]
    async fn off_is_a_plain_passthrough() {
        let sink = Sink::default();
        let seen = sink.clone();
        let mut stream = SpoofingStream::new(sink, SpoofMode::Off);
        stream.write_all(&vec![7u8; 517]).await.unwrap();
        let (bytes, writes) = &*seen.0.lock().unwrap();
        assert_eq!(bytes.len(), 517);
        assert_eq!(*writes, 1, "off changes nothing, not even the write count");
    }

    #[tokio::test]
    async fn stream_split_breaks_a_large_first_write() {
        let sink = Sink::default();
        let seen = sink.clone();
        let mut stream = SpoofingStream::new(sink, SpoofMode::StreamSplit);
        stream.write_all(&vec![3u8; 517]).await.unwrap();
        let (bytes, writes) = &*seen.0.lock().unwrap();
        assert_eq!(bytes.len(), 517, "no byte is added or lost");
        assert!(*writes > 1, "the write was split");
    }
}
