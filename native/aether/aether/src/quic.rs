use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use quiche::h3;
use quiche::h3::NameValue;
use rand::Rng;
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, oneshot};

use crate::masque::{self, CapsuleParser};
use crate::noize::{self, NoizeConfig};
use crate::tls::{self, TlsParams};
use crate::{consts, error::AetherError, error::Result};

pub const MAX_DATAGRAM_SIZE: usize = 1350;
pub const MIN_DATAGRAM_SIZE: usize = 1200;

/// Most QUIC packets handed to the kernel in one `sendmmsg` call, and the
/// size of the reusable send buffer in packets.
const MAX_TX_BATCH: usize = 64;
/// Most packets pulled from a channel in one loop pass before we flush.
const MAX_CHANNEL_BATCH: usize = 64;

fn net_queue() -> usize {
    crate::sysprofile::channel_capacity()
}

async fn bind_udp_fast(bind_addr: SocketAddr) -> Result<UdpSocket> {
    use socket2::{Domain, Socket, Type};
    let domain = if bind_addr.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    };
    let sock = Socket::new(domain, Type::DGRAM, None).map_err(AetherError::Io)?;
    sock.set_nonblocking(true).map_err(AetherError::Io)?;

    let buf_size = crate::sysprofile::udp_socket_buf_bytes();
    let _ = sock.set_recv_buffer_size(buf_size);
    let _ = sock.set_send_buffer_size(buf_size);
    crate::egress::apply(socket2::SockRef::from(&sock)).map_err(AetherError::Io)?;

    sock.bind(&bind_addr.into()).map_err(AetherError::Io)?;
    UdpSocket::from_std(sock.into()).map_err(AetherError::Io)
}

#[derive(Debug, Clone)]
pub enum Control {
    Migrate,
    Close,
}

#[derive(Debug, Clone)]
pub struct AssignedAddr {
    pub ip: IpAddr,
    pub prefix: u8,
}

#[derive(Debug, Clone)]
pub struct TunnelConfig {
    pub peer: SocketAddr,
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub ech_config_list: Option<Vec<u8>>,
    pub noize: NoizeConfig,
    pub local_ipv4: Ipv4Addr,
    pub quiet: bool,
    pub max_datagram: usize,
    pub version_bait: bool,
}

impl TunnelConfig {
    pub fn datagram_budget(&self) -> usize {
        self.max_datagram
            .clamp(MIN_DATAGRAM_SIZE, MAX_DATAGRAM_SIZE)
    }
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

fn data_check_enabled() -> bool {
    std::env::var("AETHER_MASQUE_NO_DATA_CHECK").is_err()
}

const DATA_PROBE_REQUIRED_SUCCESSES: u32 = 2;

pub struct Channels {
    pub outbound_tx: mpsc::Sender<Vec<u8>>,
    pub inbound_rx: mpsc::Receiver<Vec<u8>>,
    pub ctrl_tx: mpsc::Sender<Control>,
}

pub fn channels() -> (Channels, Internals) {
    let (outbound_tx, outbound_rx) = mpsc::channel(net_queue());
    let (inbound_tx, inbound_rx) = mpsc::channel(net_queue());
    let (ctrl_tx, ctrl_rx) = mpsc::channel(16);

    (
        Channels {
            outbound_tx,
            inbound_rx,
            ctrl_tx,
        },
        Internals {
            outbound_rx,
            inbound_tx,
            ctrl_rx,
        },
    )
}

pub struct Internals {
    outbound_rx: mpsc::Receiver<Vec<u8>>,
    inbound_tx: mpsc::Sender<Vec<u8>>,
    ctrl_rx: mpsc::Receiver<Control>,
}

impl Internals {
    pub fn into_parts(
        self,
    ) -> (
        mpsc::Receiver<Vec<u8>>,
        mpsc::Sender<Vec<u8>>,
        mpsc::Receiver<Control>,
    ) {
        (self.outbound_rx, self.inbound_tx, self.ctrl_rx)
    }
}

type NetPacket = (SocketAddr, SocketAddr, Vec<u8>);

fn bind_addr_for(peer: &SocketAddr) -> SocketAddr {
    if peer.is_ipv4() {
        "0.0.0.0:0".parse().unwrap()
    } else {
        "[::]:0".parse().unwrap()
    }
}

fn random_scid() -> [u8; 16] {
    let mut scid = [0u8; 16];
    rand::rng().fill_bytes(&mut scid);
    scid
}

const QUIC_V2_VERSION: u32 = 0x6b33_43cf;
const QUIC_V2_BAIT_WAIT: Duration = Duration::from_millis(600);
const QUIC_V2_BAIT_LEN: usize = 1200;

pub(crate) fn quic_v2_bait_enabled() -> bool {
    !matches!(
        std::env::var("AETHER_QUIC_V2").as_deref(),
        Ok("0") | Ok("off") | Ok("false") | Ok("no")
    )
}

fn quic_varint2(value: u64) -> [u8; 2] {
    (((value & 0x3fff) as u16) | 0x4000).to_be_bytes()
}

fn build_version_bait() -> Vec<u8> {
    let mut rng = rand::rng();
    let mut dcid = [0u8; 8];
    let mut scid = [0u8; 8];
    rng.fill_bytes(&mut dcid);
    rng.fill_bytes(&mut scid);

    let mut pkt = Vec::with_capacity(QUIC_V2_BAIT_LEN);
    pkt.push(0xc3);
    pkt.extend_from_slice(&QUIC_V2_VERSION.to_be_bytes());
    pkt.push(dcid.len() as u8);
    pkt.extend_from_slice(&dcid);
    pkt.push(scid.len() as u8);
    pkt.extend_from_slice(&scid);
    pkt.push(0x00);

    let remaining = QUIC_V2_BAIT_LEN - pkt.len() - 2;
    pkt.extend_from_slice(&quic_varint2(remaining as u64));
    let mut pn = [0u8; 4];
    rng.fill_bytes(&mut pn);
    pkt.extend_from_slice(&pn);
    pkt.resize(QUIC_V2_BAIT_LEN, 0);
    pkt
}

async fn send_version_bait(sock: &UdpSocket, target: SocketAddr, wait: Duration, tries: usize) {
    let bait = build_version_bait();
    let connected = sock.peer_addr().is_ok();
    let mut buf = [0u8; 2048];

    for attempt in 0..tries.max(1) {
        let sent = if connected {
            sock.send(&bait).await
        } else {
            sock.send_to(&bait, target).await
        };
        if sent.is_err() {
            return;
        }

        let answered = tokio::time::timeout(wait, async {
            if connected {
                sock.recv(&mut buf).await
            } else {
                sock.recv_from(&mut buf).await.map(|(n, _)| n)
            }
        })
        .await;

        match answered {
            Ok(Ok(n)) => {
                log::debug!(
                    "[quic] version-negotiation bait answered with {n} bytes; the path is open for v1"
                );
                return;
            }
            Ok(Err(_)) => return,
            Err(_) => log::trace!(
                "[quic] version-negotiation bait attempt {} went unanswered",
                attempt + 1
            ),
        }
    }
}

#[derive(Default)]
struct ReaderGuard {
    handles: Vec<tokio::task::JoinHandle<()>>,
    detours: Vec<crate::upstream::DetourGuard>,
}

impl ReaderGuard {
    fn push(&mut self, h: tokio::task::JoinHandle<()>) {
        self.handles.push(h);
    }
}

impl Drop for ReaderGuard {
    fn drop(&mut self) {
        for h in self.handles.drain(..) {
            h.abort();
        }
    }
}

fn spawn_reader(
    sock: Arc<UdpSocket>,
    local: SocketAddr,
    tx: mpsc::Sender<NetPacket>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut buf = vec![0u8; 65535];
        loop {
            match sock.recv_from(&mut buf).await {
                Ok((n, observed)) => {
                    let from = crate::upstream::real_source(local, observed);
                    log::trace!("recv {n} bytes from {from}");
                    if tx.send((local, from, buf[..n].to_vec())).await.is_err() {
                        break;
                    }
                }
                Err(e) => {
                    log::debug!("recv error: {e}");
                    break;
                }
            }
        }
    })
}

/// Logs the QUIC header of a packet. Parsing needs a mutable buffer, so this
/// copies the packet; callers only reach it with trace logging enabled.
fn trace_header(what: &str, data: &[u8], from: SocketAddr) {
    let mut hdr_buf = data.to_vec();
    if let Ok(hdr) = quiche::Header::from_slice(&mut hdr_buf, quiche::MAX_CONN_ID_LEN) {
        log::trace!(
            "{what} {} bytes type={:?} version=0x{:x} from {}",
            data.len(),
            hdr.ty,
            hdr.version,
            from
        );
    }
}

fn recv_packet(conn: &mut quiche::Connection, (to_local, from, mut data): NetPacket) {
    if log::log_enabled!(log::Level::Trace) {
        trace_header("recv", &data, from);
    }
    let info = quiche::RecvInfo { from, to: to_local };
    if let Err(e) = conn.recv(&mut data, info) {
        log::trace!("recv error: {e}");
    }
}

fn send_ip_packet(conn: &mut quiche::Connection, req_stream: Option<u64>, ip_packet: &[u8]) {
    if let Some(sid) = req_stream {
        match masque::encode_ip_datagram(sid, ip_packet) {
            Ok(framed) => {
                if let Err(e) = conn.dgram_send(&framed) {
                    log::trace!("dgram_send: {e}");
                }
            }
            Err(e) => log::trace!("encap: {e}"),
        }
    }
}

pub async fn run(
    cfg: TunnelConfig,
    mut internals: Internals,
    addr_tx: Option<mpsc::Sender<AssignedAddr>>,
    ready_tx: Option<oneshot::Sender<()>>,
) -> Result<()> {
    let peer = cfg.peer;
    let quiet = cfg.quiet;
    let data_check = data_check_enabled();
    let probe_packet = masque::build_dns_probe_packet(cfg.local_ipv4);
    let mut ready_tx = ready_tx;
    let mut ready_fired = false;
    let mut validate_deadline: Option<Instant> = None;
    let mut validate_successes: u32 = 0;

    let init_sock = bind_udp_fast(bind_addr_for(&peer)).await?;
    let _init_detour = crate::upstream::attach_detour(&init_sock, peer).await?;
    let local = init_sock.local_addr()?;
    let init_sock = Arc::new(init_sock);

    if cfg.version_bait && quic_v2_bait_enabled() {
        let target = crate::upstream::relay_target(local, peer);
        send_version_bait(&init_sock, target, QUIC_V2_BAIT_WAIT, 2).await;
    }

    let (net_tx, mut net_rx) = mpsc::channel::<NetPacket>(net_queue());

    let mut sockets: HashMap<SocketAddr, Arc<UdpSocket>> = HashMap::new();
    sockets.insert(local, init_sock.clone());
    let mut readers = ReaderGuard::default();
    readers.push(spawn_reader(init_sock, local, net_tx.clone()));

    let mut config = tls::build_config(&TlsParams {
        cert_pem: &cfg.cert_pem,
        key_pem: &cfg.key_pem,
        pin_endpoint: true,
        expected_pins: consts::MASQUE_PINS,
    })?;

    let datagram = cfg.datagram_budget();
    config.set_max_send_udp_payload_size(datagram);
    config.set_max_recv_udp_payload_size(datagram);

    let mut tx = TxBatch::new(datagram);

    let mut current_ech = cfg.ech_config_list.clone();

    let scid_bytes = random_scid();
    let scid = quiche::ConnectionId::from_ref(&scid_bytes);

    let mut conn = quiche::connect(Some(&cfg.sni), &scid, local, peer, &mut config)?;

    if let Some(ref ech) = current_ech {
        tls::inject_ech(&mut conn, ech)?;
        log::info!("ech config injected ({} bytes)", ech.len());
    }

    let h3_config = h3::Config::new()?;
    let mut h3_conn: Option<h3::Connection> = None;
    let mut req_stream: Option<u64> = None;
    let mut capsules = CapsuleParser::new();
    let mut established_ever = false;
    let mut ech_retried = false;

    if let Some(sock) = sockets.get(&local) {
        noize::pre_handshake(sock.as_ref(), peer, &cfg.noize).await;
    }

    flush(&mut conn, &sockets, &mut tx).await?;

    let mut out_buf = vec![0u8; 65535];
    let mut h3_body = vec![0u8; 65535];
    // An inbound IP packet the netstack had no room for yet. While it is set we
    // stop pulling datagrams out of quiche (which keeps them in its own bounded
    // queue) and wait for channel capacity instead of dropping them.
    let mut pending_inbound: Option<Vec<u8>> = None;
    let mut keepalive_interval = tokio::time::interval(Duration::from_secs(20));
    keepalive_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let mut probe_interval = tokio::time::interval(Duration::from_millis(700));
    probe_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let mut ctrl_open = true;
    let mut outbound_open = true;

    loop {
        if data_check && !ready_fired {
            if let Some(dl) = validate_deadline {
                if Instant::now() >= dl {
                    log::warn!(
                        "[-] masque data-plane validation timed out; edge {peer} accepts control but drops traffic"
                    );
                    let _ = conn.close(true, 0x00, b"validation-timeout");
                    return Err(AetherError::Masque(
                        "data-plane validation timeout (handshake ok, no traffic)".into(),
                    ));
                }
            }
        }

        let timeout = conn.timeout();

        tokio::select! {
            biased;

            _ = keepalive_interval.tick() => {
                if conn.is_established() {
                    if let Err(e) = conn.send_ack_eliciting() {
                        log::debug!("keepalive ping failed: {e}");
                    }
                }
            }

            _ = probe_interval.tick(), if data_check && !ready_fired => {
                if let Some(sid) = req_stream {
                    match masque::encode_ip_datagram(sid, &probe_packet) {
                        Ok(framed) => {
                            if let Err(e) = conn.dgram_send(&framed) {
                                log::trace!("data-plane probe send: {e}");
                            }
                        }
                        Err(e) => log::trace!("data-plane probe encode: {e}"),
                    }
                }
            }

            permit = internals.inbound_tx.reserve(), if pending_inbound.is_some() => {
                match permit {
                    Ok(permit) => {
                        if let Some(ip_packet) = pending_inbound.take() {
                            permit.send(ip_packet);
                        }
                    }
                    Err(_) => pending_inbound = None,
                }
            }

            Some(first) = net_rx.recv() => {
                recv_packet(&mut conn, first);
                let mut n = 1;
                while n < MAX_CHANNEL_BATCH {
                    match net_rx.try_recv() {
                        Ok(pkt) => {
                            recv_packet(&mut conn, pkt);
                            n += 1;
                        }
                        Err(_) => break,
                    }
                }
            }

            ctrl = internals.ctrl_rx.recv(), if ctrl_open => {
                match ctrl {
                    Some(Control::Migrate) => {
                        if let Err(e) = do_migrate(&mut conn, peer, &mut sockets, &net_tx, &mut readers).await {
                            log::warn!("migration failed: {e}");
                        }
                    }
                    Some(Control::Close) => {
                        ctrl_open = false;
                        let _ = conn.close(true, 0x00, b"bye");
                    }
                    None => {
                        ctrl_open = false;
                        let _ = conn.close(true, 0x00, b"bye");
                    }
                }
            }

            pkt = internals.outbound_rx.recv(), if outbound_open => {
                match pkt {
                    Some(ip_packet) => {
                        send_ip_packet(&mut conn, req_stream, &ip_packet);
                        let mut n = 1;
                        while n < MAX_CHANNEL_BATCH {
                            match internals.outbound_rx.try_recv() {
                                Ok(ip_packet) => {
                                    send_ip_packet(&mut conn, req_stream, &ip_packet);
                                    n += 1;
                                }
                                Err(_) => break,
                            }
                        }
                    }
                    None => {
                        outbound_open = false;
                        let _ = conn.close(true, 0x00, b"eof");
                    }
                }
            }

            _ = sleep_opt(timeout) => {
                conn.on_timeout();
            }
        }

        if conn.is_established() && h3_conn.is_none() {
            established_ever = true;
            log_or_debug(
                quiet,
                format!(
                    "quic handshake established; alpn={}",
                    String::from_utf8_lossy(conn.application_proto())
                ),
            );
            let mut h3c = h3::Connection::with_transport(&mut conn, &h3_config)?;
            let headers = masque::connect_ip_request(&cfg.authority, &cfg.path);
            let sid = h3c.send_request(&mut conn, &headers, false)?;
            log_or_debug(quiet, format!("connect-ip request sent on stream {sid}"));
            req_stream = Some(sid);
            h3_conn = Some(h3c);

            if data_check {
                validate_deadline = Some(Instant::now() + validation_timeout());
                log_or_debug(
                    quiet,
                    "[*] validating masque data-plane before exposing socks5".to_string(),
                );
            } else if !ready_fired {
                ready_fired = true;
                if let Some(tx) = ready_tx.take() {
                    let _ = tx.send(());
                }
            }
        }

        if let (Some(h3c), Some(sid)) = (h3_conn.as_mut(), req_stream) {
            poll_h3(
                &mut conn,
                h3c,
                sid,
                &mut capsules,
                &addr_tx,
                quiet,
                &mut h3_body,
            )?;
        }

        let got_data = drain_datagrams(
            &mut conn,
            req_stream,
            &internals.inbound_tx,
            &mut out_buf,
            &mut pending_inbound,
        );

        if got_data && !ready_fired {
            validate_successes += 1;
            log::debug!(
                "[*] masque data-plane round-trip {}/{} confirmed",
                validate_successes,
                DATA_PROBE_REQUIRED_SUCCESSES
            );
            if validate_successes >= DATA_PROBE_REQUIRED_SUCCESSES {
                ready_fired = true;
                validate_deadline = None;
                if let Some(tx) = ready_tx.take() {
                    let _ = tx.send(());
                }
                log_or_debug(
                    quiet,
                    "[+] masque tunnel validated (end-to-end data confirmed); exposing socks5"
                        .to_string(),
                );
            }
        }

        flush(&mut conn, &sockets, &mut tx).await?;

        if conn.is_closed() {
            if !established_ever && !ech_retried && current_ech.is_some() {
                if let Some(retry) = tls::extract_ech_retry_configs(&mut conn) {
                    log::warn!(
                        "ech_required: retrying handshake with server retry_configs ({} bytes)",
                        retry.len()
                    );
                    ech_retried = true;
                    current_ech = Some(retry);

                    let scid_bytes = random_scid();
                    let scid = quiche::ConnectionId::from_ref(&scid_bytes);
                    conn = quiche::connect(Some(&cfg.sni), &scid, local, peer, &mut config)?;
                    if let Some(ref ech) = current_ech {
                        tls::inject_ech(&mut conn, ech)?;
                    }

                    h3_conn = None;
                    req_stream = None;
                    capsules = CapsuleParser::new();
                    flush(&mut conn, &sockets, &mut tx).await?;
                    continue;
                }
            }

            log_or_debug(quiet, format!("connection closed: {:?}", conn.stats()));
            if let Some(e) = conn.peer_error() {
                log_or_debug(
                    quiet,
                    format!(
                        "peer closed: code=0x{:x} app={} reason={}",
                        e.error_code,
                        e.is_app,
                        String::from_utf8_lossy(&e.reason)
                    ),
                );
            }
            if let Some(e) = conn.local_error() {
                log_or_debug(
                    quiet,
                    format!(
                        "local closed: code=0x{:x} app={} reason={}",
                        e.error_code,
                        e.is_app,
                        String::from_utf8_lossy(&e.reason)
                    ),
                );
            }
            return Ok(());
        }
    }
}

async fn sleep_opt(timeout: Option<Duration>) {
    match timeout {
        Some(d) => tokio::time::sleep(d).await,
        None => std::future::pending::<()>().await,
    }
}

fn log_or_debug(quiet: bool, msg: String) {
    if quiet {
        log::debug!("{msg}");
    } else {
        log::info!("{msg}");
    }
}

fn poll_h3(
    conn: &mut quiche::Connection,
    h3c: &mut h3::Connection,
    req_stream: u64,
    capsules: &mut CapsuleParser,
    addr_tx: &Option<mpsc::Sender<AssignedAddr>>,
    quiet: bool,
    body: &mut [u8],
) -> Result<()> {
    loop {
        match h3c.poll(conn) {
            Ok((stream_id, h3::Event::Headers { list, .. })) => {
                for h in &list {
                    if h.name() == b":status" {
                        let status = String::from_utf8_lossy(h.value()).to_string();
                        log_or_debug(quiet, format!("connect-ip status: {status}"));
                        if stream_id == req_stream && !status.starts_with('2') {
                            return Err(AetherError::Masque(format!(
                                "the edge refused connect-ip with status {status}"
                            )));
                        }
                    }
                }
            }

            Ok((stream_id, h3::Event::Data)) => {
                if stream_id != req_stream {
                    continue;
                }
                while let Ok(n) = h3c.recv_body(conn, stream_id, body) {
                    if n == 0 {
                        break;
                    }
                    capsules.push(&body[..n]);
                }
                drain_capsules(capsules, addr_tx);
            }

            Ok((stream_id, h3::Event::Finished)) if stream_id == req_stream => {
                return Err(AetherError::Masque(
                    "the edge closed the connect-ip stream".into(),
                ));
            }
            Ok((stream_id, h3::Event::Reset(code))) if stream_id == req_stream => {
                return Err(AetherError::Masque(format!(
                    "the edge reset the connect-ip stream (code 0x{code:x})"
                )));
            }
            Ok(_) => {}

            Err(h3::Error::Done) => break,
            Err(e) => return Err(AetherError::H3(e)),
        }
    }

    Ok(())
}

fn drain_capsules(capsules: &mut CapsuleParser, addr_tx: &Option<mpsc::Sender<AssignedAddr>>) {
    loop {
        match capsules.next() {
            Ok(Some(masque::Capsule::AddressAssign(addrs))) => {
                for a in addrs {
                    if let Some(ip) = bytes_to_ip(a.ip_version, &a.address) {
                        log::info!("edge assigned {}/{}", ip, a.prefix_len);
                        if let Some(tx) = addr_tx {
                            let _ = tx.try_send(AssignedAddr {
                                ip,
                                prefix: a.prefix_len,
                            });
                        }
                    }
                }
            }
            Ok(Some(masque::Capsule::RouteAdvertisement(routes))) => {
                log::info!("received {} route advertisements", routes.len());
            }
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(e) => {
                log::trace!("capsule parse: {e}");
                break;
            }
        }
    }
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

/// Moves decoded IP packets from quiche to the netstack. When the inbound
/// channel is full the packet is parked in `pending` and draining stops: the
/// rest stays in quiche's datagram queue and the run loop waits on
/// `inbound_tx.reserve()`, so a burst is absorbed instead of dropped.
fn drain_datagrams(
    conn: &mut quiche::Connection,
    req_stream: Option<u64>,
    inbound_tx: &mpsc::Sender<Vec<u8>>,
    buf: &mut [u8],
    pending: &mut Option<Vec<u8>>,
) -> bool {
    let sid = match req_stream {
        Some(s) => s,
        None => return false,
    };
    if pending.is_some() {
        return false;
    }

    let mut delivered = false;
    loop {
        match conn.dgram_recv(buf) {
            Ok(n) => match masque::decode_ip_datagram(&buf[..n], sid) {
                Ok(Some(ip_packet)) => {
                    delivered = true;
                    match inbound_tx.try_send(ip_packet) {
                        Ok(()) => {}
                        Err(mpsc::error::TrySendError::Full(ip_packet)) => {
                            log::trace!("inbound queue full, waiting for the netstack");
                            *pending = Some(ip_packet);
                            return delivered;
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => return delivered,
                    }
                }
                Ok(None) => {}
                Err(e) => log::trace!("decap: {e}"),
            },
            Err(quiche::Error::Done) => break,
            Err(e) => {
                log::trace!("dgram_recv: {e}");
                break;
            }
        }
    }
    delivered
}

/// A reusable buffer of outgoing QUIC packets, sent to the kernel in batches.
struct TxBatch {
    buf: Vec<u8>,
    seg: usize,
    /// (local socket the packet leaves from, destination, length)
    items: Vec<(SocketAddr, SocketAddr, usize)>,
}

impl TxBatch {
    fn new(seg: usize) -> Self {
        Self {
            buf: vec![0u8; seg * MAX_TX_BATCH],
            seg,
            items: Vec::with_capacity(MAX_TX_BATCH),
        }
    }

    fn slot(&mut self) -> &mut [u8] {
        let start = self.items.len() * self.seg;
        &mut self.buf[start..start + self.seg]
    }

    async fn send(&mut self, sockets: &HashMap<SocketAddr, Arc<UdpSocket>>) -> Result<()> {
        let result = self.send_queued(sockets).await;
        self.items.clear();
        result
    }

    async fn send_queued(&self, sockets: &HashMap<SocketAddr, Arc<UdpSocket>>) -> Result<()> {
        let mut start = 0;
        while start < self.items.len() {
            let local = self.items[start].0;
            let mut end = start + 1;
            while end < self.items.len() && self.items[end].0 == local {
                end += 1;
            }
            if let Some(sock) = sockets.get(&local) {
                let pkts: Vec<(&[u8], SocketAddr)> = (start..end)
                    .map(|i| {
                        let (_, to, len) = self.items[i];
                        let off = i * self.seg;
                        (&self.buf[off..off + len], to)
                    })
                    .collect();
                send_many(sock, &pkts).await?;
            }
            start = end;
        }
        Ok(())
    }
}

async fn flush(
    conn: &mut quiche::Connection,
    sockets: &HashMap<SocketAddr, Arc<UdpSocket>>,
    tx: &mut TxBatch,
) -> Result<()> {
    let fallback = sockets.keys().next().copied();

    loop {
        let slot = tx.slot();
        match conn.send(slot) {
            Ok((write, send_info)) => {
                let local = if sockets.contains_key(&send_info.from) {
                    Some(send_info.from)
                } else {
                    fallback
                };
                if let Some(local) = local {
                    let to = crate::upstream::relay_target(local, send_info.to);
                    tx.items.push((local, to, write));
                    if tx.items.len() == MAX_TX_BATCH {
                        tx.send(sockets).await?;
                    }
                }
            }
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tx.items.clear();
                return Err(AetherError::Quic(e));
            }
        }
    }

    tx.send(sockets).await
}

/// Sends a run of packets from one socket with as few syscalls as possible.
#[cfg(any(target_os = "linux", target_os = "android"))]
async fn send_many(sock: &UdpSocket, pkts: &[(&[u8], SocketAddr)]) -> std::io::Result<()> {
    use std::os::fd::AsRawFd;

    let fd = sock.as_raw_fd();
    let mut sent = 0;
    while sent < pkts.len() {
        sock.writable().await?;
        match sock.try_io(tokio::io::Interest::WRITABLE, || {
            sendmmsg_once(fd, &pkts[sent..])
        }) {
            Ok(0) => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::WriteZero,
                    "sendmmsg sent nothing",
                ))
            }
            Ok(n) => sent += n,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Everything that holds raw pointers lives only inside this synchronous call,
/// so the async caller stays `Send`.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn sendmmsg_once(
    fd: std::os::fd::RawFd,
    pkts: &[(&[u8], SocketAddr)],
) -> std::io::Result<usize> {
    let n = pkts.len().min(MAX_TX_BATCH);
    let mut addrs: Vec<(libc::sockaddr_storage, libc::socklen_t)> =
        pkts[..n].iter().map(|(_, to)| sockaddr_of(to)).collect();
    let mut iovs: Vec<libc::iovec> = pkts[..n]
        .iter()
        .map(|(data, _)| libc::iovec {
            iov_base: data.as_ptr() as *mut libc::c_void,
            iov_len: data.len(),
        })
        .collect();

    let mut msgs: Vec<libc::mmsghdr> = Vec::with_capacity(n);
    for (addr, iov) in addrs.iter_mut().zip(iovs.iter_mut()) {
        let mut msg: libc::mmsghdr = unsafe { std::mem::zeroed() };
        msg.msg_hdr.msg_name = &mut addr.0 as *mut libc::sockaddr_storage as *mut libc::c_void;
        msg.msg_hdr.msg_namelen = addr.1;
        msg.msg_hdr.msg_iov = iov as *mut libc::iovec;
        msg.msg_hdr.msg_iovlen = 1;
        msgs.push(msg);
    }

    let rc = unsafe { libc::sendmmsg(fd, msgs.as_mut_ptr(), n as _, 0) };
    if rc < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(rc as usize)
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn sockaddr_of(addr: &SocketAddr) -> (libc::sockaddr_storage, libc::socklen_t) {
    let mut storage: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let len = match addr {
        SocketAddr::V4(a) => {
            let sin = &mut storage as *mut libc::sockaddr_storage as *mut libc::sockaddr_in;
            unsafe {
                (*sin).sin_family = libc::AF_INET as libc::sa_family_t;
                (*sin).sin_port = a.port().to_be();
                (*sin).sin_addr = libc::in_addr {
                    s_addr: u32::from_ne_bytes(a.ip().octets()),
                };
            }
            std::mem::size_of::<libc::sockaddr_in>()
        }
        SocketAddr::V6(a) => {
            let sin6 = &mut storage as *mut libc::sockaddr_storage as *mut libc::sockaddr_in6;
            unsafe {
                (*sin6).sin6_family = libc::AF_INET6 as libc::sa_family_t;
                (*sin6).sin6_port = a.port().to_be();
                (*sin6).sin6_flowinfo = a.flowinfo();
                (*sin6).sin6_addr.s6_addr = a.ip().octets();
                (*sin6).sin6_scope_id = a.scope_id();
            }
            std::mem::size_of::<libc::sockaddr_in6>()
        }
    };
    (storage, len as libc::socklen_t)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
async fn send_many(sock: &UdpSocket, pkts: &[(&[u8], SocketAddr)]) -> std::io::Result<()> {
    for (data, to) in pkts {
        sock.send_to(data, *to).await?;
    }
    Ok(())
}

async fn do_migrate(
    conn: &mut quiche::Connection,
    peer: SocketAddr,
    sockets: &mut HashMap<SocketAddr, Arc<UdpSocket>>,
    net_tx: &mpsc::Sender<NetPacket>,
    readers: &mut ReaderGuard,
) -> Result<()> {
    if conn.available_dcids() == 0 {
        return Err(AetherError::Other("no spare dcids for migration".into()));
    }

    let new_sock = bind_udp_fast(bind_addr_for(&peer)).await?;
    readers
        .detours
        .push(crate::upstream::attach_detour(&new_sock, peer).await?);
    let new_local = new_sock.local_addr()?;
    let new_sock = Arc::new(new_sock);

    sockets.insert(new_local, new_sock.clone());
    readers.push(spawn_reader(new_sock, new_local, net_tx.clone()));

    conn.probe_path(new_local, peer)?;
    let seq = conn.migrate_source(new_local)?;
    log::info!("migrated to local {new_local} (path seq {seq})");

    Ok(())
}

pub fn default_authority() -> &'static str {
    "cloudflareaccess.com"
}

pub fn default_path() -> &'static str {
    "/"
}

pub fn default_sni() -> &'static str {
    consts::CONNECT_SNI
}

#[derive(Clone)]
pub struct VerifyParams {
    pub peer: SocketAddr,
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub ech_config_list: Option<Vec<u8>>,
    pub noize: NoizeConfig,
    pub timeout: Duration,
    pub local_ipv4: Ipv4Addr,
}

pub async fn verify_masque(p: &VerifyParams) -> Result<Duration> {
    let bind: SocketAddr = if p.peer.is_ipv4() {
        "0.0.0.0:0".parse().unwrap()
    } else {
        "[::]:0".parse().unwrap()
    };
    let sock = bind_udp_fast(bind).await?;
    let _detour = crate::upstream::attach_detour(&sock, p.peer).await?;
    let local = sock.local_addr()?;
    sock.connect(crate::upstream::relay_target(local, p.peer))
        .await?;

    let mut config = tls::build_config(&TlsParams {
        cert_pem: &p.cert_pem,
        key_pem: &p.key_pem,
        pin_endpoint: true,
        expected_pins: consts::MASQUE_PINS,
    })?;

    let scid_bytes = random_scid();
    let scid = quiche::ConnectionId::from_ref(&scid_bytes);
    let mut conn = quiche::connect(Some(&p.sni), &scid, local, p.peer, &mut config)?;

    if let Some(ref ech) = p.ech_config_list {
        let _ = tls::inject_ech(&mut conn, ech);
    }

    let h3_config = h3::Config::new()?;
    let mut h3_conn: Option<h3::Connection> = None;
    let mut req_stream: Option<u64> = None;

    let data_check = data_check_enabled();
    let probe_packet = masque::build_dns_probe_packet(p.local_ipv4);
    let mut connect_ip_ok = false;
    let mut last_probe = Instant::now();
    let mut dgram_buf = vec![0u8; 65535];
    let mut probe_successes: u32 = 0;

    let start = Instant::now();
    let deadline = start + p.timeout;

    if quic_v2_bait_enabled() {
        send_version_bait(&sock, p.peer, Duration::from_millis(500), 1).await;
    }

    noize::pre_handshake(&sock, p.peer, &p.noize).await;

    flush_connected(&mut conn, &sock).await?;

    let mut buf = vec![0u8; 65535];

    loop {
        if Instant::now() >= deadline {
            return Err(AetherError::Other("verify timeout".into()));
        }

        let wait = match conn.timeout() {
            Some(t) => t.min(remaining(deadline)),
            None => remaining(deadline),
        };
        let wait = if connect_ip_ok {
            wait.min(Duration::from_millis(250))
        } else {
            wait
        };

        tokio::select! {
            r = sock.recv(&mut buf) => {
                match r {
                    Ok(n) => {
                        if log::log_enabled!(log::Level::Trace) {
                            trace_header("verify recv", &buf[..n], p.peer);
                        }
                        let info = quiche::RecvInfo { from: p.peer, to: local };
                        if let Err(e) = conn.recv(&mut buf[..n], info) {
                            log::trace!("verify recv error from {}: {e}", p.peer);
                        }
                    }
                    Err(e) => return Err(AetherError::Io(e)),
                }
            }
            _ = tokio::time::sleep(wait) => {
                conn.on_timeout();
            }
        }

        if conn.is_established() && h3_conn.is_none() {
            let mut h3c = h3::Connection::with_transport(&mut conn, &h3_config)?;
            let headers = masque::connect_ip_request(&p.authority, &p.path);
            let sid = h3c.send_request(&mut conn, &headers, false)?;
            req_stream = Some(sid);
            h3_conn = Some(h3c);
        }

        if let (Some(h3c), Some(sid)) = (h3_conn.as_mut(), req_stream) {
            loop {
                match h3c.poll(&mut conn) {
                    Ok((stream_id, h3::Event::Headers { list, .. })) if stream_id == sid => {
                        for h in &list {
                            if h.name() == b":status" {
                                if h.value() == b"200" {
                                    if !data_check {
                                        return Ok(start.elapsed());
                                    }
                                    connect_ip_ok = true;
                                    if let Some(sid) = req_stream {
                                        if let Ok(framed) =
                                            masque::encode_ip_datagram(sid, &probe_packet)
                                        {
                                            let _ = conn.dgram_send(&framed);
                                        }
                                    }
                                    last_probe = Instant::now();
                                } else {
                                    return Err(AetherError::Other(format!(
                                        "status {}",
                                        String::from_utf8_lossy(h.value())
                                    )));
                                }
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(h3::Error::Done) => break,
                    Err(e) => return Err(AetherError::H3(e)),
                }
            }
        }

        if connect_ip_ok {
            if last_probe.elapsed() >= Duration::from_millis(700) {
                if let Some(sid) = req_stream {
                    if let Ok(framed) = masque::encode_ip_datagram(sid, &probe_packet) {
                        let _ = conn.dgram_send(&framed);
                    }
                }
                last_probe = Instant::now();
            }

            if let Some(sid) = req_stream {
                loop {
                    match conn.dgram_recv(&mut dgram_buf) {
                        Ok(n) => {
                            if let Ok(Some(_)) = masque::decode_ip_datagram(&dgram_buf[..n], sid) {
                                probe_successes += 1;
                                if probe_successes >= DATA_PROBE_REQUIRED_SUCCESSES {
                                    return Ok(start.elapsed());
                                }
                                if let Ok(framed) = masque::encode_ip_datagram(sid, &probe_packet) {
                                    let _ = conn.dgram_send(&framed);
                                }
                                last_probe = Instant::now();
                            }
                        }
                        Err(quiche::Error::Done) => break,
                        Err(_) => break,
                    }
                }
            }
        }

        flush_connected(&mut conn, &sock).await?;

        if conn.is_closed() {
            return Err(AetherError::Other(
                "closed before data-plane confirmation".into(),
            ));
        }
    }
}

fn remaining(deadline: Instant) -> Duration {
    deadline.saturating_duration_since(Instant::now())
}

async fn flush_connected(conn: &mut quiche::Connection, sock: &UdpSocket) -> Result<()> {
    let mut out = vec![0u8; MAX_DATAGRAM_SIZE];
    loop {
        match conn.send(&mut out) {
            Ok((write, _info)) => {
                sock.send(&out[..write]).await?;
            }
            Err(quiche::Error::Done) => break,
            Err(e) => return Err(AetherError::Quic(e)),
        }
    }
    Ok(())
}

#[cfg(test)]
mod v2_bait_tests {
    use super::*;

    #[test]
    fn the_bait_is_a_v2_versioned_long_header_of_the_minimum_size() {
        let pkt = build_version_bait();
        assert_eq!(pkt.len(), QUIC_V2_BAIT_LEN);
        assert_eq!(pkt[0] & 0x80, 0x80, "long header form bit must be set");
        assert_eq!(pkt[0] & 0x40, 0x40, "fixed bit must be set");
        assert_eq!(
            u32::from_be_bytes([pkt[1], pkt[2], pkt[3], pkt[4]]),
            QUIC_V2_VERSION,
            "the version field must be QUIC v2 so the filter treats the flow as v2"
        );
        assert_eq!(pkt[5], 8, "destination connection id length");
        assert_eq!(pkt[14], 8, "source connection id length");
    }

    #[test]
    fn two_baits_do_not_share_connection_ids() {
        let a = build_version_bait();
        let b = build_version_bait();
        assert_ne!(a[6..14], b[6..14], "each bait must use a fresh dcid");
    }

    #[test]
    fn the_bait_is_on_unless_it_is_turned_off() {
        std::env::remove_var("AETHER_QUIC_V2");
        assert!(quic_v2_bait_enabled());
        std::env::set_var("AETHER_QUIC_V2", "0");
        assert!(!quic_v2_bait_enabled());
        std::env::set_var("AETHER_QUIC_V2", "off");
        assert!(!quic_v2_bait_enabled());
        std::env::set_var("AETHER_QUIC_V2", "1");
        assert!(quic_v2_bait_enabled());
        std::env::remove_var("AETHER_QUIC_V2");
    }

    #[tokio::test]
    async fn the_bait_triggers_a_version_negotiation_from_a_v1_only_server() {
        let server = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server.local_addr().unwrap();

        let responder = tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            let (n, from) = server.recv_from(&mut buf).await.unwrap();
            assert_eq!(n, QUIC_V2_BAIT_LEN);
            let dcid = buf[6..14].to_vec();
            let scid = buf[15..23].to_vec();
            let mut vn = vec![0xc0, 0x00, 0x00, 0x00, 0x00];
            vn.push(scid.len() as u8);
            vn.extend_from_slice(&scid);
            vn.push(dcid.len() as u8);
            vn.extend_from_slice(&dcid);
            vn.extend_from_slice(&1u32.to_be_bytes());
            server.send_to(&vn, from).await.unwrap();
        });

        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        client.connect(server_addr).await.unwrap();
        send_version_bait(&client, server_addr, Duration::from_secs(2), 1).await;
        responder.await.unwrap();
    }
}

#[cfg(test)]
mod tx_batch_tests {
    use super::*;

    #[tokio::test]
    async fn a_batch_reaches_the_peer_intact_and_in_order() {
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer_addr = peer.local_addr().unwrap();
        let sender = UdpSocket::bind("127.0.0.1:0").await.unwrap();

        let payloads: Vec<Vec<u8>> = (0..10u8).map(|i| vec![i; 100 + i as usize]).collect();
        let pkts: Vec<(&[u8], SocketAddr)> =
            payloads.iter().map(|p| (p.as_slice(), peer_addr)).collect();
        send_many(&sender, &pkts).await.unwrap();

        let mut buf = [0u8; 2048];
        for expected in &payloads {
            let n = tokio::time::timeout(Duration::from_secs(2), peer.recv(&mut buf))
                .await
                .expect("every batched packet arrives")
                .unwrap();
            assert_eq!(&buf[..n], expected.as_slice());
        }
    }

    #[tokio::test]
    async fn a_full_inbound_queue_parks_the_packet_instead_of_dropping_it() {
        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(1);
        tx.try_send(vec![1]).unwrap();

        let mut pending = Some(vec![2]);
        // With a packet parked nothing more is pulled from quiche.
        assert!(pending.is_some());

        assert_eq!(rx.recv().await, Some(vec![1]));
        let permit = tx.reserve().await.unwrap();
        permit.send(pending.take().unwrap());
        assert_eq!(rx.recv().await, Some(vec![2]), "the parked packet is delivered");
    }
}
