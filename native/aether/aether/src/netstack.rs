use std::cell::RefCell;
use std::collections::HashMap;
use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Checksum, Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::{tcp, udp};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpEndpoint, Ipv4Address, Ipv6Address};
use tokio::sync::{mpsc, oneshot};

use crate::error::{AetherError, Result};

fn tcp_rx_buf() -> usize {
    crate::sysprofile::netstack_tcp_rx_buf_bytes()
}

fn tcp_tx_buf() -> usize {
    crate::sysprofile::netstack_tcp_tx_buf_bytes()
}

fn udp_buf() -> usize {
    crate::sysprofile::netstack_udp_buf_bytes()
}

fn udp_meta() -> usize {
    match crate::sysprofile::tuning().tier {
        crate::sysprofile::Tier::Low => 32,
        crate::sysprofile::Tier::Medium => 64,
        crate::sysprofile::Tier::High => 128,
    }
}

fn app_queue() -> usize {
    crate::sysprofile::channel_capacity()
}

const MAX_INGEST_PER_TICK: usize = 512;
const MAX_RECV_CHUNKS: usize = 128;
/// Upper bound for a single TCP chunk handed to the app.
const MAX_RECV_CHUNK_BYTES: usize = 64 * 1024;
/// Once this many packets are waiting for the outbound channel the device stops
/// handing out transmit tokens, so smoltcp holds data in its socket buffers
/// instead of the stack dropping packets it already produced.
const TX_BACKLOG: usize = 256;
/// Safety net for packets generated on the receive path (RST / ICMP replies),
/// which cannot be refused. Only hit if the outbound side is stuck for good.
const TX_HARD_LIMIT: usize = 4096;
/// Recycled packet buffers kept around to avoid one allocation per tx packet.
const POOL_MAX_BUFS: usize = 128;
const POOL_MIN_BUF_BYTES: usize = 1280;
const POOL_MAX_BUF_BYTES: usize = 4096;
const PENDING_SHRINK_ABOVE: usize = 256 * 1024;
const DROP_REPORT_STEP: usize = 512;
const MAX_IDLE_TICK: std::time::Duration = std::time::Duration::from_millis(250);

const ORPHAN_LINGER: std::time::Duration = std::time::Duration::from_secs(10);

fn max_tcp_pending() -> usize {
    tcp_rx_buf().saturating_mul(2).max(64 * 1024)
}

pub(crate) fn tcp_keepalive() -> std::time::Duration {
    let secs = std::env::var("AETHER_TCP_KEEPALIVE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(60);
    std::time::Duration::from_secs(secs)
}

fn tcp_connect_timeout() -> std::time::Duration {
    let secs = std::env::var("AETHER_TCP_CONNECT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(30);
    std::time::Duration::from_secs(secs)
}

/// Congestion controller for netstack TCP sockets. Cubic by default;
/// `AETHER_TCP_CC=reno` or `AETHER_TCP_CC=none` override it.
fn tcp_congestion_control() -> tcp::CongestionControl {
    let value = std::env::var("AETHER_TCP_CC")
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    match value.as_str() {
        "reno" => tcp::CongestionControl::Reno,
        "none" | "off" => tcp::CongestionControl::None,
        _ => tcp::CongestionControl::Cubic,
    }
}

fn smol_duration(duration: std::time::Duration) -> smoltcp::time::Duration {
    smoltcp::time::Duration::from_millis(duration.as_millis().min(u64::MAX as u128) as u64)
}

#[derive(Debug, Clone, Copy)]
struct TcpLimits {
    connect: std::time::Duration,
    keepalive: std::time::Duration,
    orphan_linger: std::time::Duration,
    congestion: tcp::CongestionControl,
}

impl TcpLimits {
    fn from_env() -> Self {
        Self {
            connect: tcp_connect_timeout(),
            keepalive: tcp_keepalive(),
            orphan_linger: ORPHAN_LINGER,
            congestion: tcp_congestion_control(),
        }
    }
}

type OpenTcpResp = oneshot::Sender<std::result::Result<TcpConn, String>>;
type OpenUdpResp = oneshot::Sender<std::result::Result<UdpConn, String>>;

type BufPool = RefCell<Vec<Vec<u8>>>;

fn recycle(pool: &BufPool, buf: Vec<u8>) {
    let cap = buf.capacity();
    if !(POOL_MIN_BUF_BYTES..=POOL_MAX_BUF_BYTES).contains(&cap) {
        return;
    }
    let mut pool = pool.borrow_mut();
    if pool.len() < POOL_MAX_BUFS {
        pool.push(buf);
    }
}

pub struct StackDevice {
    rx: VecDeque<Vec<u8>>,
    tx: VecDeque<Vec<u8>>,
    pool: BufPool,
    mtu: usize,
    tx_overflow: usize,
}

impl StackDevice {
    fn new(mtu: usize) -> Self {
        Self {
            rx: VecDeque::new(),
            tx: VecDeque::new(),
            pool: RefCell::new(Vec::new()),
            mtu,
            tx_overflow: 0,
        }
    }

    fn tx_backlogged(&self) -> bool {
        self.tx.len() >= TX_BACKLOG
    }

    fn recycle(&self, buf: Vec<u8>) {
        recycle(&self.pool, buf);
    }
}

pub struct StackRxToken<'a> {
    buf: Vec<u8>,
    pool: &'a BufPool,
}

pub struct StackTxToken<'a> {
    queue: &'a mut VecDeque<Vec<u8>>,
    pool: &'a BufPool,
    overflow: &'a mut usize,
}

impl RxToken for StackRxToken<'_> {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        let StackRxToken { buf, pool } = self;
        let r = f(&buf);
        // The inbound buffer is MTU sized: reuse it for the next tx packet.
        recycle(pool, buf);
        r
    }
}

impl TxToken for StackTxToken<'_> {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = self.pool.borrow_mut().pop().unwrap_or_default();
        buf.clear();
        buf.resize(len, 0);
        let r = f(&mut buf);
        if self.queue.len() < TX_HARD_LIMIT {
            self.queue.push_back(buf);
        } else {
            *self.overflow += 1;
            recycle(self.pool, buf);
        }
        r
    }
}

impl Device for StackDevice {
    type RxToken<'a>
        = StackRxToken<'a>
    where
        Self: 'a;
    type TxToken<'a>
        = StackTxToken<'a>
    where
        Self: 'a;

    fn receive(&mut self, _t: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let buf = self.rx.pop_front()?;
        Some((
            StackRxToken {
                buf,
                pool: &self.pool,
            },
            StackTxToken {
                queue: &mut self.tx,
                pool: &self.pool,
                overflow: &mut self.tx_overflow,
            },
        ))
    }

    fn transmit(&mut self, _t: Instant) -> Option<Self::TxToken<'_>> {
        // Backpressure instead of loss: while the outbound side is behind,
        // smoltcp keeps the data in its socket buffers and retries later.
        if self.tx_backlogged() {
            return None;
        }
        Some(StackTxToken {
            queue: &mut self.tx,
            pool: &self.pool,
            overflow: &mut self.tx_overflow,
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.mtu;
        caps.checksum.ipv4 = Checksum::Tx;
        caps.checksum.tcp = Checksum::Tx;
        caps.checksum.udp = Checksum::Tx;
        caps
    }
}

pub enum Cmd {
    OpenTcp {
        dst: SocketAddr,
        resp: OpenTcpResp,
    },
    OpenUdp {
        resp: OpenUdpResp,
    },
    SetAddrs {
        v4: Option<(Ipv4Addr, u8)>,
        v6: Option<(Ipv6Addr, u8)>,
    },
}

pub enum DataIn {
    Tcp(usize, Vec<u8>),
    TcpClose(usize),
    Udp(usize, SocketAddr, Vec<u8>),
    UdpClose(usize),
}

/// Sent back to the stack task when an app channel that was full has room again.
#[derive(Debug, Clone, Copy)]
enum Wake {
    Tcp(usize),
    Udp(usize),
}

pub struct TcpConn {
    pub id: usize,
    pub from_stack: mpsc::Receiver<Vec<u8>>,
    data_in: mpsc::Sender<DataIn>,
    split: bool,
}

impl TcpConn {
    pub async fn send(&self, data: Vec<u8>) -> Result<()> {
        self.data_in
            .send(DataIn::Tcp(self.id, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::TcpClose(self.id)).await;
    }

    pub fn into_split(mut self) -> (TcpSender, mpsc::Receiver<Vec<u8>>) {
        self.split = true;
        (
            TcpSender {
                id: self.id,
                data_in: self.data_in.clone(),
            },
            std::mem::replace(&mut self.from_stack, {
                let (_tx, rx) = mpsc::channel(1);
                rx
            }),
        )
    }
}

impl Drop for TcpConn {
    fn drop(&mut self) {
        if !self.split {
            let _ = self.data_in.try_send(DataIn::TcpClose(self.id));
        }
    }
}

pub struct TcpSender {
    id: usize,
    data_in: mpsc::Sender<DataIn>,
}

impl TcpSender {
    pub async fn send(&self, data: Vec<u8>) -> Result<()> {
        self.data_in
            .send(DataIn::Tcp(self.id, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::TcpClose(self.id)).await;
    }
}

impl Drop for TcpSender {
    fn drop(&mut self) {
        let _ = self.data_in.try_send(DataIn::TcpClose(self.id));
    }
}

pub struct UdpConn {
    pub id: usize,
    pub from_stack: mpsc::Receiver<(SocketAddr, Vec<u8>)>,
    data_in: mpsc::Sender<DataIn>,
    split: bool,
}

impl UdpConn {
    pub async fn send_to(&self, dst: SocketAddr, data: Vec<u8>) -> Result<()> {
        self.data_in
            .send(DataIn::Udp(self.id, dst, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::UdpClose(self.id)).await;
    }

    pub fn into_split(mut self) -> (UdpSender, mpsc::Receiver<(SocketAddr, Vec<u8>)>) {
        self.split = true;
        (
            UdpSender {
                id: self.id,
                data_in: self.data_in.clone(),
            },
            std::mem::replace(&mut self.from_stack, {
                let (_tx, rx) = mpsc::channel(1);
                rx
            }),
        )
    }
}

impl Drop for UdpConn {
    fn drop(&mut self) {
        if !self.split {
            let _ = self.data_in.try_send(DataIn::UdpClose(self.id));
        }
    }
}

pub struct UdpSender {
    id: usize,
    data_in: mpsc::Sender<DataIn>,
}

impl UdpSender {
    pub async fn send_to(&self, dst: SocketAddr, data: Vec<u8>) -> Result<()> {
        self.data_in
            .send(DataIn::Udp(self.id, dst, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::UdpClose(self.id)).await;
    }
}

impl Drop for UdpSender {
    fn drop(&mut self) {
        let _ = self.data_in.try_send(DataIn::UdpClose(self.id));
    }
}

#[derive(Clone)]
pub struct StackHandle {
    cmd_tx: mpsc::Sender<Cmd>,
}

impl StackHandle {
    pub async fn open_tcp(&self, dst: SocketAddr) -> Result<TcpConn> {
        let (resp_tx, resp_rx) = oneshot::channel();
        self.cmd_tx
            .send(Cmd::OpenTcp { dst, resp: resp_tx })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))?;
        resp_rx
            .await
            .map_err(|_| AetherError::Other("netstack dropped".into()))?
            .map_err(AetherError::Other)
    }

    pub async fn open_udp(&self) -> Result<UdpConn> {
        let (resp_tx, resp_rx) = oneshot::channel();
        self.cmd_tx
            .send(Cmd::OpenUdp { resp: resp_tx })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))?;
        resp_rx
            .await
            .map_err(|_| AetherError::Other("netstack dropped".into()))?
            .map_err(AetherError::Other)
    }

    pub async fn set_addrs(
        &self,
        v4: Option<(Ipv4Addr, u8)>,
        v6: Option<(Ipv6Addr, u8)>,
    ) -> Result<()> {
        self.cmd_tx
            .send(Cmd::SetAddrs { v4, v6 })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }
}

struct TcpState {
    handle: SocketHandle,
    to_app: mpsc::Sender<Vec<u8>>,
    from_stack_rx: Option<mpsc::Receiver<Vec<u8>>>,
    connect_resp: Option<OpenTcpResp>,
    connect_deadline: std::time::Instant,
    pending: VecDeque<u8>,
    established: bool,
    half_closed: bool,
    orphaned_at: Option<std::time::Instant>,
    aborted: bool,
    /// A waiter task is parked on `to_app` capacity.
    app_wait: bool,
}

struct UdpState {
    handle: SocketHandle,
    to_app: mpsc::Sender<(SocketAddr, Vec<u8>)>,
    app_wait: bool,
}

pub struct NetStack {
    iface: Interface,
    device: StackDevice,
    sockets: SocketSet<'static>,
    tcp_conns: HashMap<usize, TcpState>,
    udp_conns: HashMap<usize, UdpState>,
    next_id: usize,
    next_port: u16,
    data_in_tx: mpsc::Sender<DataIn>,
    wake_tx: mpsc::UnboundedSender<Wake>,
    tcp_limits: TcpLimits,
}

impl NetStack {
    fn new(
        iface: Interface,
        device: StackDevice,
        data_in_tx: mpsc::Sender<DataIn>,
        tcp_limits: TcpLimits,
    ) -> (Self, mpsc::UnboundedReceiver<Wake>) {
        let (wake_tx, wake_rx) = mpsc::unbounded_channel();
        let stack = Self {
            iface,
            device,
            sockets: SocketSet::new(Vec::new()),
            tcp_conns: HashMap::new(),
            udp_conns: HashMap::new(),
            next_id: 1,
            next_port: 49152,
            data_in_tx,
            wake_tx,
            tcp_limits,
        };
        (stack, wake_rx)
    }
}

fn strip_cidr(s: &str) -> &str {
    match s.split_once('/') {
        Some((ip, _)) => ip,
        None => s,
    }
}

fn to_ip_address(ip: IpAddr) -> IpAddress {
    match ip {
        IpAddr::V4(v4) => IpAddress::Ipv4(Ipv4Address::from(v4)),
        IpAddr::V6(v6) => IpAddress::Ipv6(Ipv6Address::from(v6)),
    }
}

fn to_ip_endpoint(addr: SocketAddr) -> IpEndpoint {
    IpEndpoint::new(to_ip_address(addr.ip()), addr.port())
}

fn cidr_prefix(s: &str) -> Option<u8> {
    s.split_once('/').and_then(|(_, p)| p.parse().ok())
}

fn parse_v4(s: &str) -> Result<Option<(Ipv4Addr, u8)>> {
    if s.is_empty() {
        return Ok(None);
    }
    let ip: Ipv4Addr = strip_cidr(s)
        .parse()
        .map_err(|_| AetherError::Other(format!("bad ipv4 {s}")))?;
    Ok(Some((ip, cidr_prefix(s).unwrap_or(32))))
}

fn parse_v6(s: &str) -> Result<Option<(Ipv6Addr, u8)>> {
    if s.is_empty() {
        return Ok(None);
    }
    let ip: Ipv6Addr = strip_cidr(s)
        .parse()
        .map_err(|_| AetherError::Other(format!("bad ipv6 {s}")))?;
    Ok(Some((ip, cidr_prefix(s).unwrap_or(128))))
}

fn routable_prefix_v4(p: u8) -> u8 {
    if p >= 31 {
        24
    } else {
        p
    }
}

fn routable_prefix_v6(p: u8) -> u8 {
    if p >= 127 {
        64
    } else {
        p
    }
}

fn apply_addrs(iface: &mut Interface, v4: Option<(Ipv4Addr, u8)>, v6: Option<(Ipv6Addr, u8)>) {
    iface.update_ip_addrs(|addrs| {
        addrs.clear();
        if let Some((ip, p)) = v4 {
            let _ = addrs.push(IpCidr::new(
                IpAddress::Ipv4(Ipv4Address::from(ip)),
                routable_prefix_v4(p),
            ));
        }
        if let Some((ip, p)) = v6 {
            let _ = addrs.push(IpCidr::new(
                IpAddress::Ipv6(Ipv6Address::from(ip)),
                routable_prefix_v6(p),
            ));
        }
    });

    if let Some((ip, _)) = v4 {
        let o = ip.octets();
        let host = if o[3] == 1 { 2 } else { 1 };
        let gw = Ipv4Address::new(o[0], o[1], o[2], host);
        let _ = iface.routes_mut().add_default_ipv4_route(gw);
    }
    if let Some((ip, _)) = v6 {
        let mut o = ip.octets();
        o[15] = if o[15] == 1 { 2 } else { 1 };
        let _ = iface
            .routes_mut()
            .add_default_ipv6_route(Ipv6Address::from(o));
    }
}

type AddrPair = (Option<(Ipv4Addr, u8)>, Option<(Ipv6Addr, u8)>);

fn current_addrs(iface: &Interface) -> AddrPair {
    let mut v4 = None;
    let mut v6 = None;
    for cidr in iface.ip_addrs() {
        match cidr {
            IpCidr::Ipv4(c) => v4 = Some((c.address(), c.prefix_len())),
            IpCidr::Ipv6(c) => v6 = Some((c.address(), c.prefix_len())),
        }
    }
    (v4, v6)
}

fn endpoint_to_socketaddr(ep: IpEndpoint) -> SocketAddr {
    let ip = match ep.addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(v4.into()),
        IpAddress::Ipv6(v6) => IpAddr::V6(v6.into()),
    };
    SocketAddr::new(ip, ep.port)
}

pub fn spawn(
    ipv4: &str,
    ipv6: &str,
    mtu: usize,
    inbound_rx: mpsc::Receiver<Vec<u8>>,
    outbound_tx: mpsc::Sender<Vec<u8>>,
) -> Result<StackHandle> {
    spawn_with_limits(
        ipv4,
        ipv6,
        mtu,
        inbound_rx,
        outbound_tx,
        TcpLimits::from_env(),
    )
}

fn spawn_with_limits(
    ipv4: &str,
    ipv6: &str,
    mtu: usize,
    inbound_rx: mpsc::Receiver<Vec<u8>>,
    outbound_tx: mpsc::Sender<Vec<u8>>,
    tcp_limits: TcpLimits,
) -> Result<StackHandle> {
    let mut device = StackDevice::new(mtu);

    let config = Config::new(HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());

    let v4 = parse_v4(ipv4)?;
    let v6 = parse_v6(ipv6)?;
    apply_addrs(&mut iface, v4, v6);

    let (cmd_tx, cmd_rx) = mpsc::channel(256);
    let (data_in_tx, data_in_rx) = mpsc::channel(app_queue());

    let (stack, wake_rx) = NetStack::new(iface, device, data_in_tx, tcp_limits);

    tokio::spawn(run(
        stack,
        cmd_rx,
        data_in_rx,
        inbound_rx,
        wake_rx,
        outbound_tx,
    ));

    Ok(StackHandle { cmd_tx })
}

fn alloc_port(p: &mut u16) -> u16 {
    let port = *p;
    *p = if port >= 65000 { 49152 } else { port + 1 };
    port
}

async fn run(
    mut s: NetStack,
    mut cmd_rx: mpsc::Receiver<Cmd>,
    mut data_in_rx: mpsc::Receiver<DataIn>,
    mut inbound_rx: mpsc::Receiver<Vec<u8>>,
    mut wake_rx: mpsc::UnboundedReceiver<Wake>,
    outbound_tx: mpsc::Sender<Vec<u8>>,
) -> Result<()> {
    let mut deferred: VecDeque<DataIn> = VecDeque::new();
    let mut next_overflow_report: usize = DROP_REPORT_STEP;

    loop {
        let now = Instant::now();
        let poll_outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            s.iface.poll(now, &mut s.device, &mut s.sockets);
        }));
        if poll_outcome.is_err() {
            s.device.rx.clear();
            s.device.tx.clear();
        }
        let tcp_more = service_tcp(&mut s);
        let udp_more = service_udp(&mut s);
        flush_tx(&mut s, &outbound_tx);

        if s.device.tx_overflow >= next_overflow_report {
            next_overflow_report = s.device.tx_overflow + DROP_REPORT_STEP;
            log::debug!(
                "[netstack] outbound stalled, dropped {} reply packets past the hard limit",
                s.device.tx_overflow
            );
        }

        while let Some(d) = deferred.pop_front() {
            if let Some(back) = try_handle_data(&mut s, d) {
                deferred.push_front(back);
                break;
            }
        }

        // A connection hit its per-tick budget: yield and come straight back.
        let run_again = tcp_more || udp_more;

        let delay = if s.device.tx_backlogged() {
            // Egress is parked until the outbound channel drains; the `reserve`
            // branch below wakes us. This is only a timer fallback.
            Some(MAX_IDLE_TICK)
        } else {
            let polled = s
                .iface
                .poll_delay(Instant::now(), &s.sockets)
                .map(|d| std::time::Duration::from_micros(d.total_micros()));

            if s.tcp_conns.is_empty() && s.udp_conns.is_empty() && deferred.is_empty() {
                polled
            } else {
                Some(polled.map_or(MAX_IDLE_TICK, |d| d.min(MAX_IDLE_TICK)))
            }
        };

        let tx_waiting = !s.device.tx.is_empty() && !outbound_tx.is_closed();

        tokio::select! {
            biased;

            maybe = inbound_rx.recv() => {
                match maybe {
                    Some(pkt) => {
                        s.device.rx.push_back(pkt);
                        let mut n = 0;
                        while n < MAX_INGEST_PER_TICK {
                            match inbound_rx.try_recv() {
                                Ok(p) => { s.device.rx.push_back(p); n += 1; }
                                Err(_) => break,
                            }
                        }
                    }
                    None => return Ok(()),
                }
            }

            permit = outbound_tx.reserve(), if tx_waiting => {
                if let Ok(permit) = permit {
                    if let Some(pkt) = s.device.tx.pop_front() {
                        permit.send(pkt);
                    }
                    flush_tx(&mut s, &outbound_tx);
                }
            }

            maybe = cmd_rx.recv() => {
                match maybe {
                    Some(cmd) => handle_cmd(&mut s, cmd),
                    None => return Ok(()),
                }
            }

            maybe = wake_rx.recv() => {
                if let Some(w) = maybe {
                    clear_app_wait(&mut s, w);
                    while let Ok(w) = wake_rx.try_recv() {
                        clear_app_wait(&mut s, w);
                    }
                }
            }

            maybe = data_in_rx.recv(), if deferred.is_empty() => {
                if let Some(d) = maybe {
                    if let Some(back) = try_handle_data(&mut s, d) {
                        deferred.push_back(back);
                    } else {
                        while deferred.is_empty() {
                            match data_in_rx.try_recv() {
                                Ok(d2) => {
                                    if let Some(back) = try_handle_data(&mut s, d2) {
                                        deferred.push_back(back);
                                    }
                                }
                                Err(_) => break,
                            }
                        }
                    }
                }
            }

            _ = tokio::task::yield_now(), if run_again => {}

            _ = sleep_opt(delay), if !run_again => {}
        }
    }
}

async fn sleep_opt(delay: Option<std::time::Duration>) {
    match delay {
        Some(d) => tokio::time::sleep(d).await,
        None => std::future::pending::<()>().await,
    }
}

fn clear_app_wait(s: &mut NetStack, wake: Wake) {
    match wake {
        Wake::Tcp(id) => {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                st.app_wait = false;
            }
        }
        Wake::Udp(id) => {
            if let Some(st) = s.udp_conns.get_mut(&id) {
                st.app_wait = false;
            }
        }
    }
}

/// Replaces the old fixed 2ms retry: parks a tiny task on the app channel and
/// wakes the stack exactly when the app has consumed something (or went away).
/// The permit is released immediately; only the stack sends on this channel,
/// so the freed slot is still there when the stack comes back to it.
fn spawn_app_waiter<T: Send + 'static>(
    to_app: &mpsc::Sender<T>,
    wake_tx: &mpsc::UnboundedSender<Wake>,
    wake: Wake,
) {
    let to_app = to_app.clone();
    let wake_tx = wake_tx.clone();
    tokio::spawn(async move {
        drop(to_app.reserve_owned().await);
        let _ = wake_tx.send(wake);
    });
}

fn new_tcp_socket(limits: &TcpLimits) -> tcp::Socket<'static> {
    let rx_buf = tcp::SocketBuffer::new(vec![0u8; tcp_rx_buf()]);
    let tx_buf = tcp::SocketBuffer::new(vec![0u8; tcp_tx_buf()]);
    let mut socket = tcp::Socket::new(rx_buf, tx_buf);
    socket.set_nagle_enabled(false);
    socket.set_congestion_control(limits.congestion);
    socket.set_keep_alive(Some(smol_duration(limits.keepalive)));
    socket.set_timeout(Some(smol_duration(limits.keepalive.saturating_mul(3))));
    socket
}

fn handle_cmd(s: &mut NetStack, cmd: Cmd) {
    match cmd {
        Cmd::OpenTcp { dst, resp } => {
            let limits = s.tcp_limits;
            let mut socket = new_tcp_socket(&limits);

            let local_port = alloc_port(&mut s.next_port);
            let remote = to_ip_endpoint(dst);

            if let Err(e) = socket.connect(s.iface.context(), remote, local_port) {
                let _ = resp.send(Err(format!("connect: {e:?}")));
                return;
            }

            let handle = s.sockets.add(socket);
            let id = s.next_id;
            s.next_id += 1;

            let (to_app_tx, to_app_rx) = mpsc::channel(app_queue());

            s.tcp_conns.insert(
                id,
                TcpState {
                    handle,
                    to_app: to_app_tx,
                    from_stack_rx: Some(to_app_rx),
                    connect_resp: Some(resp),
                    connect_deadline: std::time::Instant::now() + limits.connect,
                    pending: VecDeque::new(),
                    established: false,
                    half_closed: false,
                    orphaned_at: None,
                    aborted: false,
                    app_wait: false,
                },
            );
        }
        Cmd::OpenUdp { resp } => {
            let rx_meta = vec![udp::PacketMetadata::EMPTY; udp_meta()];
            let tx_meta = vec![udp::PacketMetadata::EMPTY; udp_meta()];
            let rx_buf = udp::PacketBuffer::new(rx_meta, vec![0u8; udp_buf()]);
            let tx_buf = udp::PacketBuffer::new(tx_meta, vec![0u8; udp_buf()]);
            let mut socket = udp::Socket::new(rx_buf, tx_buf);

            let local_port = alloc_port(&mut s.next_port);
            if let Err(e) = socket.bind(local_port) {
                let _ = resp.send(Err(format!("bind: {e:?}")));
                return;
            }

            let handle = s.sockets.add(socket);
            let id = s.next_id;
            s.next_id += 1;

            let (to_app_tx, to_app_rx) = mpsc::channel(app_queue());
            s.udp_conns.insert(
                id,
                UdpState {
                    handle,
                    to_app: to_app_tx,
                    app_wait: false,
                },
            );

            let conn = UdpConn {
                id,
                from_stack: to_app_rx,
                data_in: s.data_in_tx.clone(),
                split: false,
            };
            let _ = resp.send(Ok(conn));
        }
        Cmd::SetAddrs { v4, v6 } => {
            let (current_v4, current_v6) = current_addrs(&s.iface);
            apply_addrs(&mut s.iface, v4.or(current_v4), v6.or(current_v6));
            log::info!("netstack addresses synchronized from edge capsule");
        }
    }
}

/// Returns `Some(d)` when the datagram must be deferred (TCP pending full).
fn try_handle_data(s: &mut NetStack, d: DataIn) -> Option<DataIn> {
    match d {
        DataIn::Tcp(id, mut data) => {
            let Some(st) = s.tcp_conns.get_mut(&id) else {
                s.device.recycle(data);
                return None;
            };

            // Fast path: nothing queued ahead of this write and the socket has
            // room, so copy straight into smoltcp instead of through `pending`.
            if st.established && st.pending.is_empty() {
                let socket = s.sockets.get_mut::<tcp::Socket>(st.handle);
                if socket.can_send() {
                    let sent = socket.send_slice(&data).unwrap_or(0);
                    if sent == data.len() {
                        s.device.recycle(data);
                        return None;
                    }
                    data.drain(..sent);
                }
            }

            let space = max_tcp_pending().saturating_sub(st.pending.len());
            if space == 0 {
                return Some(DataIn::Tcp(id, data));
            }
            if data.len() <= space {
                st.pending.extend(&data[..]);
                s.device.recycle(data);
                None
            } else {
                st.pending.extend(&data[..space]);
                // Keep the remainder in the same allocation.
                data.drain(..space);
                Some(DataIn::Tcp(id, data))
            }
        }
        DataIn::TcpClose(id) => {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                st.half_closed = true;
            }
            None
        }
        DataIn::Udp(id, dst, data) => {
            if let Some(st) = s.udp_conns.get(&id) {
                let sock = s.sockets.get_mut::<udp::Socket>(st.handle);
                let _ = sock.send_slice(&data, to_ip_endpoint(dst));
            }
            s.device.recycle(data);
            None
        }
        DataIn::UdpClose(id) => {
            if let Some(st) = s.udp_conns.remove(&id) {
                s.sockets.remove(st.handle);
            }
            None
        }
    }
}

fn send_pending(socket: &mut tcp::Socket<'_>, pending: &mut VecDeque<u8>) {
    let (head, tail) = pending.as_slices();
    let mut sent = socket.send_slice(head).unwrap_or(0);
    if sent == head.len() && !tail.is_empty() {
        sent += socket.send_slice(tail).unwrap_or(0);
    }
    if sent > 0 {
        pending.drain(..sent);
    }
}

fn shrink_pending(pending: &mut VecDeque<u8>) {
    if pending.capacity() > PENDING_SHRINK_ABOVE && pending.len() * 4 < pending.capacity() {
        pending.shrink_to(pending.len().saturating_mul(2).max(64 * 1024));
    }
}

/// Services every TCP connection once. Returns `true` when some connection hit
/// its per-tick budget and the loop should run again right away.
fn service_tcp(s: &mut NetStack) -> bool {
    let NetStack {
        sockets,
        tcp_conns,
        data_in_tx,
        wake_tx,
        tcp_limits,
        ..
    } = s;
    let now = std::time::Instant::now();
    let orphan_linger = tcp_limits.orphan_linger;
    let mut more = false;

    tcp_conns.retain(|&id, st| {
        let handle = st.handle;

        if st.aborted {
            sockets.remove(handle);
            return false;
        }

        if !st.established {
            let state = sockets.get::<tcp::Socket>(handle).state();
            if matches!(state, tcp::State::Established | tcp::State::CloseWait) {
                st.established = true;
                if let (Some(resp), Some(rx)) = (st.connect_resp.take(), st.from_stack_rx.take()) {
                    let conn = TcpConn {
                        id,
                        from_stack: rx,
                        data_in: data_in_tx.clone(),
                        split: false,
                    };
                    let _ = resp.send(Ok(conn));
                }
            } else if matches!(state, tcp::State::Closed | tcp::State::TimeWait) {
                if let Some(resp) = st.connect_resp.take() {
                    let _ = resp.send(Err("connection refused".into()));
                }
                sockets.remove(handle);
                return false;
            } else {
                let abandoned = st.connect_resp.as_ref().is_none_or(|resp| resp.is_closed());
                if abandoned || now >= st.connect_deadline {
                    if let Some(resp) = st.connect_resp.take() {
                        let _ = resp.send(Err("connection timed out".into()));
                    }
                    sockets.remove(handle);
                    return false;
                }
                return true;
            }
        }

        let socket = sockets.get_mut::<tcp::Socket>(handle);

        if !st.pending.is_empty() && socket.can_send() {
            send_pending(socket, &mut st.pending);
            shrink_pending(&mut st.pending);
        }

        if st.half_closed && st.pending.is_empty() {
            socket.close();
        }

        let mut app_gone = st.to_app.is_closed();
        let mut delivered = 0;
        while !app_gone && socket.can_recv() {
            if delivered >= MAX_RECV_CHUNKS {
                more = true;
                break;
            }
            match st.to_app.try_reserve() {
                Ok(permit) => {
                    // One allocation per drained chunk (handles ring wrap-around).
                    let want = socket.recv_queue().min(MAX_RECV_CHUNK_BYTES);
                    let mut chunk = vec![0u8; want];
                    match socket.recv_slice(&mut chunk) {
                        Ok(n) if n > 0 => {
                            chunk.truncate(n);
                            permit.send(chunk);
                            delivered += 1;
                        }
                        _ => break,
                    }
                }
                Err(mpsc::error::TrySendError::Full(())) => {
                    if !st.app_wait {
                        st.app_wait = true;
                        spawn_app_waiter(&st.to_app, wake_tx, Wake::Tcp(id));
                    }
                    break;
                }
                Err(mpsc::error::TrySendError::Closed(())) => app_gone = true,
            }
        }

        if app_gone {
            let orphaned_at = *st.orphaned_at.get_or_insert(now);
            if socket.can_recv() || now.duration_since(orphaned_at) >= orphan_linger {
                if socket.state() != tcp::State::Closed {
                    socket.abort();
                }
                // Removed on the next pass, after the RST has been flushed.
                st.aborted = true;
                return true;
            }
            socket.close();
        }

        let state = socket.state();
        if state == tcp::State::CloseWait {
            socket.close();
        }
        if matches!(state, tcp::State::Closed | tcp::State::TimeWait) {
            sockets.remove(handle);
            return false;
        }
        true
    });

    more
}

fn service_udp(s: &mut NetStack) -> bool {
    let NetStack {
        sockets,
        udp_conns,
        wake_tx,
        ..
    } = s;
    let mut more = false;

    udp_conns.retain(|&id, st| {
        let mut app_gone = st.to_app.is_closed();
        let socket = sockets.get_mut::<udp::Socket>(st.handle);
        let mut delivered = 0;

        while !app_gone && socket.can_recv() {
            if delivered >= MAX_RECV_CHUNKS {
                more = true;
                break;
            }
            match st.to_app.try_reserve() {
                Ok(permit) => match socket.recv() {
                    Ok((data, meta)) => {
                        permit.send((endpoint_to_socketaddr(meta.endpoint), data.to_vec()));
                        delivered += 1;
                    }
                    Err(_) => break,
                },
                Err(mpsc::error::TrySendError::Full(())) => {
                    if !st.app_wait {
                        st.app_wait = true;
                        spawn_app_waiter(&st.to_app, wake_tx, Wake::Udp(id));
                    }
                    break;
                }
                Err(mpsc::error::TrySendError::Closed(())) => app_gone = true,
            }
        }

        if app_gone {
            sockets.remove(st.handle);
            return false;
        }
        true
    });

    more
}

/// Moves as many packets as the outbound channel accepts right now. Packets
/// that do not fit stay queued, in order, instead of being dropped: the device
/// then stops handing out tx tokens (see `TX_BACKLOG`) and the run loop waits
/// on `outbound_tx.reserve()`, so TCP sees backpressure rather than loss.
fn flush_tx(s: &mut NetStack, outbound_tx: &mpsc::Sender<Vec<u8>>) {
    while let Some(pkt) = s.device.tx.pop_front() {
        match outbound_tx.try_send(pkt) {
            Ok(()) => {}
            Err(mpsc::error::TrySendError::Full(pkt)) => {
                s.device.tx.push_front(pkt);
                break;
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                s.device.tx.clear();
                break;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration as StdDuration;

    fn udp_ip_packet(payload_len: usize) -> Vec<u8> {
        let total = 20 + 8 + payload_len;
        let mut pkt = vec![0u8; total];
        pkt[0] = 0x45;
        pkt[2] = (total >> 8) as u8;
        pkt[3] = (total & 0xff) as u8;
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 9]);
        pkt[16..20].copy_from_slice(&[198, 18, 0, 1]);
        pkt[20..22].copy_from_slice(&5555u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&9999u16.to_be_bytes());
        let udp_len = (8 + payload_len) as u16;
        pkt[24..26].copy_from_slice(&udp_len.to_be_bytes());
        pkt
    }

    fn test_stack() -> NetStack {
        let mut device = StackDevice::new(1400);
        let iface = Interface::new(
            Config::new(HardwareAddress::Ip),
            &mut device,
            Instant::now(),
        );
        NetStack::new(iface, device, mpsc::channel(1).0, TcpLimits::from_env()).0
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn netstack_keeps_draining_inbound_when_outbound_is_never_read() {
        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(4);
        let (outbound_tx, _outbound_rx_never_read) = mpsc::channel::<Vec<u8>>(1);

        let stack = spawn("198.18.0.1", "fc00::1", 1400, inbound_rx, outbound_tx)
            .expect("netstack should start");

        let udp = stack.open_udp().await.expect("udp socket should open");
        let dst: SocketAddr = "1.1.1.1:53".parse().unwrap();

        for _ in 0..64 {
            let _ = udp.send_to(dst, vec![0u8; 64]).await;
        }

        tokio::time::sleep(StdDuration::from_millis(120)).await;

        for index in 0..64 {
            let send = inbound_tx.send(udp_ip_packet(32));
            tokio::time::timeout(StdDuration::from_secs(3), send)
                .await
                .unwrap_or_else(|_| {
                    panic!("netstack stopped draining inbound at packet {index}: deadlock")
                })
                .expect("inbound channel should stay open");
        }
    }

    fn checksum16(data: &[u8], initial: u32) -> u16 {
        let mut sum = initial;
        let mut chunks = data.chunks_exact(2);
        for chunk in chunks.by_ref() {
            sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
        }
        if let Some(&last) = chunks.remainder().first() {
            sum += (last as u32) << 8;
        }
        while sum >> 16 != 0 {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        !(sum as u16)
    }

    struct Segment {
        src_port: u16,
        dst_port: u16,
        seq: u32,
        flags: u8,
    }

    fn parse_tcp(pkt: &[u8]) -> Option<Segment> {
        if pkt.len() < 20 || pkt[0] >> 4 != 4 {
            return None;
        }
        let ihl = ((pkt[0] & 0x0f) as usize) * 4;
        if pkt[9] != 6 || pkt.len() < ihl + 20 {
            return None;
        }
        let tcp = &pkt[ihl..];
        Some(Segment {
            src_port: u16::from_be_bytes([tcp[0], tcp[1]]),
            dst_port: u16::from_be_bytes([tcp[2], tcp[3]]),
            seq: u32::from_be_bytes([tcp[4], tcp[5], tcp[6], tcp[7]]),
            flags: tcp[13],
        })
    }

    fn build_tcp(
        src: (Ipv4Addr, u16),
        dst: (Ipv4Addr, u16),
        seq: u32,
        ack: u32,
        flags: u8,
    ) -> Vec<u8> {
        let mut tcp = vec![0u8; 20];
        tcp[0..2].copy_from_slice(&src.1.to_be_bytes());
        tcp[2..4].copy_from_slice(&dst.1.to_be_bytes());
        tcp[4..8].copy_from_slice(&seq.to_be_bytes());
        tcp[8..12].copy_from_slice(&ack.to_be_bytes());
        tcp[12] = 5 << 4;
        tcp[13] = flags;
        tcp[14..16].copy_from_slice(&64240u16.to_be_bytes());

        let mut pseudo = Vec::new();
        pseudo.extend_from_slice(&src.0.octets());
        pseudo.extend_from_slice(&dst.0.octets());
        pseudo.push(0);
        pseudo.push(6);
        pseudo.extend_from_slice(&(tcp.len() as u16).to_be_bytes());
        pseudo.extend_from_slice(&tcp);
        let tcp_sum = checksum16(&pseudo, 0);
        tcp[16..18].copy_from_slice(&tcp_sum.to_be_bytes());

        let total = 20 + tcp.len();
        let mut ip = vec![0u8; 20];
        ip[0] = 0x45;
        ip[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        ip[8] = 64;
        ip[9] = 6;
        ip[12..16].copy_from_slice(&src.0.octets());
        ip[16..20].copy_from_slice(&dst.0.octets());
        let ip_sum = checksum16(&ip, 0);
        ip[10..12].copy_from_slice(&ip_sum.to_be_bytes());

        ip.extend_from_slice(&tcp);
        ip
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_vanished_app_makes_the_netstack_tear_the_connection_down() {
        let local = Ipv4Addr::new(198, 18, 0, 1);
        let remote = Ipv4Addr::new(93, 184, 216, 34);
        let remote_port = 80u16;

        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outbound_tx, mut outbound_rx) = mpsc::channel::<Vec<u8>>(256);

        let stack = spawn("198.18.0.1", "fc00::1", 1400, inbound_rx, outbound_tx)
            .expect("netstack should start");

        let dst = SocketAddr::new(IpAddr::V4(remote), remote_port);
        let connect = {
            let stack = stack.clone();
            tokio::spawn(async move { stack.open_tcp(dst).await })
        };

        let deadline = tokio::time::Instant::now() + StdDuration::from_secs(5);

        let (client_port, client_seq) = loop {
            let pkt = tokio::time::timeout_at(deadline, outbound_rx.recv())
                .await
                .expect("the netstack should emit a syn")
                .expect("outbound channel stays open");

            if let Some(seg) = parse_tcp(&pkt) {
                if seg.dst_port == remote_port && seg.flags & 0x02 != 0 && seg.flags & 0x10 == 0 {
                    break (seg.src_port, seg.seq);
                }
            }
        };

        let syn_ack = build_tcp(
            (remote, remote_port),
            (local, client_port),
            5000,
            client_seq.wrapping_add(1),
            0x12,
        );
        inbound_tx
            .send(syn_ack)
            .await
            .expect("inbound accepts the syn-ack");

        let conn = tokio::time::timeout(StdDuration::from_secs(5), connect)
            .await
            .expect("the connect call should finish")
            .expect("the connect task should not panic")
            .expect("the connection should be established");

        drop(conn);

        let deadline = tokio::time::Instant::now() + StdDuration::from_secs(5);
        let mut saw_teardown = false;

        while let Ok(Some(pkt)) = tokio::time::timeout_at(deadline, outbound_rx.recv()).await {
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.flags & 0x01 != 0 || seg.flags & 0x04 != 0 {
                    saw_teardown = true;
                    break;
                }
            }
        }

        assert!(
            saw_teardown,
            "the netstack never closed the socket after the app went away, so it leaks"
        );
    }

    fn quick_limits() -> TcpLimits {
        TcpLimits {
            connect: StdDuration::from_millis(300),
            keepalive: StdDuration::from_secs(60),
            orphan_linger: StdDuration::from_millis(300),
            congestion: tcp::CongestionControl::Cubic,
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_connect_nobody_answers_fails_instead_of_hanging() {
        let (_inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outbound_tx, _outbound_rx) = mpsc::channel::<Vec<u8>>(256);
        let stack = spawn_with_limits(
            "198.18.0.1",
            "fc00::1",
            1400,
            inbound_rx,
            outbound_tx,
            quick_limits(),
        )
        .expect("netstack should start");

        let dst: SocketAddr = "93.184.216.34:80".parse().unwrap();
        let outcome = tokio::time::timeout(StdDuration::from_secs(5), stack.open_tcp(dst))
            .await
            .expect("a connect that is never answered must fail, not hang");

        match outcome {
            Ok(_) => panic!("nothing answered, so the connect cannot succeed"),
            Err(error) => assert!(error.to_string().contains("timed out"), "{error}"),
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn an_orphan_whose_far_end_never_closes_is_reset() {
        let local = Ipv4Addr::new(198, 18, 0, 1);
        let remote = Ipv4Addr::new(93, 184, 216, 34);
        let remote_port = 80u16;

        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outbound_tx, mut outbound_rx) = mpsc::channel::<Vec<u8>>(256);
        let stack = spawn_with_limits(
            "198.18.0.1",
            "fc00::1",
            1400,
            inbound_rx,
            outbound_tx,
            quick_limits(),
        )
        .expect("netstack should start");

        let dst = SocketAddr::new(IpAddr::V4(remote), remote_port);
        let connect = {
            let stack = stack.clone();
            tokio::spawn(async move { stack.open_tcp(dst).await })
        };

        let deadline = tokio::time::Instant::now() + StdDuration::from_secs(5);
        let (client_port, client_seq) = loop {
            let pkt = tokio::time::timeout_at(deadline, outbound_rx.recv())
                .await
                .expect("the netstack should emit a syn")
                .expect("outbound channel stays open");
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.dst_port == remote_port && seg.flags & 0x02 != 0 && seg.flags & 0x10 == 0 {
                    break (seg.src_port, seg.seq);
                }
            }
        };

        let syn_ack = build_tcp(
            (remote, remote_port),
            (local, client_port),
            5000,
            client_seq.wrapping_add(1),
            0x12,
        );
        inbound_tx
            .send(syn_ack)
            .await
            .expect("inbound accepts the syn-ack");

        let conn = tokio::time::timeout(StdDuration::from_secs(5), connect)
            .await
            .expect("the connect call should finish")
            .expect("the connect task should not panic")
            .expect("the connection should be established");
        drop(conn);

        let fin_seq = loop {
            let pkt = tokio::time::timeout_at(deadline, outbound_rx.recv())
                .await
                .expect("the netstack should send a fin")
                .expect("outbound channel stays open");
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.flags & 0x01 != 0 {
                    break seg.seq;
                }
            }
        };
        let ack = build_tcp(
            (remote, remote_port),
            (local, client_port),
            5001,
            fin_seq.wrapping_add(1),
            0x10,
        );
        inbound_tx.send(ack).await.expect("inbound accepts the ack");

        let mut saw_reset = false;
        while let Ok(Some(pkt)) = tokio::time::timeout_at(deadline, outbound_rx.recv()).await {
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.flags & 0x04 != 0 {
                    saw_reset = true;
                    break;
                }
            }
        }
        assert!(
            saw_reset,
            "an orphaned connection whose far end never closes must be reset, not kept"
        );
    }

    #[tokio::test]
    async fn an_address_assigned_for_one_family_keeps_the_other() {
        let mut device = StackDevice::new(1400);
        let mut iface = Interface::new(
            Config::new(HardwareAddress::Ip),
            &mut device,
            Instant::now(),
        );
        apply_addrs(
            &mut iface,
            Some(("172.16.0.2".parse().unwrap(), 32)),
            Some(("2606:4700:110:8a36::1".parse().unwrap(), 128)),
        );
        let (mut stack, _wake_rx) =
            NetStack::new(iface, device, mpsc::channel(1).0, TcpLimits::from_env());

        handle_cmd(
            &mut stack,
            Cmd::SetAddrs {
                v4: Some(("172.16.0.9".parse().unwrap(), 32)),
                v6: None,
            },
        );
        handle_cmd(
            &mut stack,
            Cmd::SetAddrs {
                v4: None,
                v6: Some(("2606:4700:110:8a36::9".parse().unwrap(), 128)),
            },
        );

        let (v4, v6) = current_addrs(&stack.iface);
        assert_eq!(v4.map(|(ip, _)| ip), Some("172.16.0.9".parse().unwrap()));
        assert_eq!(
            v6.map(|(ip, _)| ip),
            Some("2606:4700:110:8a36::9".parse().unwrap())
        );
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn flush_tx_keeps_packets_queued_when_outbound_is_full() {
        let (outbound_tx, mut outbound_rx) = mpsc::channel::<Vec<u8>>(2);
        let mut stack = test_stack();

        for i in 0..10u8 {
            stack.device.tx.push_back(vec![i]);
        }

        flush_tx(&mut stack, &outbound_tx);
        assert_eq!(outbound_rx.len(), 2, "the channel takes what fits");
        assert_eq!(
            stack.device.tx.len(),
            8,
            "packets that do not fit must stay queued, not be dropped"
        );

        assert_eq!(outbound_rx.recv().await.unwrap(), vec![0]);
        assert_eq!(outbound_rx.recv().await.unwrap(), vec![1]);

        flush_tx(&mut stack, &outbound_tx);
        assert_eq!(stack.device.tx.len(), 6);
        assert_eq!(
            outbound_rx.recv().await.unwrap(),
            vec![2],
            "ordering is preserved across flushes"
        );
    }

    #[test]
    fn a_backlogged_device_pushes_back_instead_of_accepting_more() {
        let mut device = StackDevice::new(1400);
        for _ in 0..TX_BACKLOG {
            device.tx.push_back(vec![0]);
        }
        assert!(
            device.transmit(Instant::now()).is_none(),
            "a full backlog must stop smoltcp from emitting more"
        );
        device.tx.pop_front();
        assert!(device.transmit(Instant::now()).is_some());
    }

    #[test]
    fn tx_buffers_are_recycled_from_consumed_rx_packets() {
        let mut device = StackDevice::new(1400);
        device.rx.push_back(vec![0u8; 1400]);
        {
            let (rx, tx) = device.receive(Instant::now()).expect("a queued packet");
            drop(tx);
            rx.consume(|_| ());
        }
        assert_eq!(device.pool.borrow().len(), 1);

        {
            let tx = device.transmit(Instant::now()).expect("room to transmit");
            tx.consume(60, |buf| buf.fill(7));
        }
        assert_eq!(device.pool.borrow().len(), 0, "the pooled buffer was reused");
        assert_eq!(device.tx.back().map(Vec::len), Some(60));
    }

    #[test]
    fn tcp_sockets_run_with_congestion_control() {
        let socket = new_tcp_socket(&quick_limits());
        assert_eq!(
            socket.congestion_control(),
            tcp::CongestionControl::Cubic,
            "without a controller smoltcp bursts the whole window"
        );
    }
}
