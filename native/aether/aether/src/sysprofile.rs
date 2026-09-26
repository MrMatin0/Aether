use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tier {
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, Copy)]
pub struct Tuning {
    pub tier: Tier,
    pub cpus: usize,
    pub mem_mb: Option<u64>,
    pub scan_concurrency_cap: usize,
    pub udp_socket_buf: usize,
    pub netstack_tcp_rx_buf: usize,
    pub netstack_tcp_tx_buf: usize,
    pub netstack_udp_buf: usize,
    pub channel_capacity: usize,
    pub h2_stream_window: u32,
    pub h2_connection_window: u32,
}

static TUNING: OnceLock<Tuning> = OnceLock::new();

fn detected_cpus() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
}

#[cfg(target_os = "linux")]
fn total_mem_mb() -> Option<u64> {
    let data = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in data.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches("kB").trim().parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

#[cfg(target_os = "android")]
fn total_mem_mb() -> Option<u64> {
    let data = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in data.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches("kB").trim().parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

#[cfg(target_os = "macos")]
fn total_mem_mb() -> Option<u64> {
    let mut size: u64 = 0;
    let mut len = std::mem::size_of::<u64>();
    let name = b"hw.memsize\0";
    let ret = unsafe {
        libc::sysctlbyname(
            name.as_ptr() as *const libc::c_char,
            &mut size as *mut u64 as *mut libc::c_void,
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if ret == 0 {
        Some(size / 1024 / 1024)
    } else {
        None
    }
}

#[cfg(target_os = "windows")]
fn total_mem_mb() -> Option<u64> {
    #[repr(C)]
    struct MemoryStatusEx {
        length: u32,
        memory_load: u32,
        total_phys: u64,
        avail_phys: u64,
        total_page_file: u64,
        avail_page_file: u64,
        total_virtual: u64,
        avail_virtual: u64,
        avail_extended_virtual: u64,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn GlobalMemoryStatusEx(buf: *mut MemoryStatusEx) -> i32;
    }

    let mut status = MemoryStatusEx {
        length: std::mem::size_of::<MemoryStatusEx>() as u32,
        memory_load: 0,
        total_phys: 0,
        avail_phys: 0,
        total_page_file: 0,
        avail_page_file: 0,
        total_virtual: 0,
        avail_virtual: 0,
        avail_extended_virtual: 0,
    };

    let ok = unsafe { GlobalMemoryStatusEx(&mut status) };
    if ok != 0 {
        Some(status.total_phys / 1024 / 1024)
    } else {
        None
    }
}

#[cfg(not(any(
    target_os = "linux",
    target_os = "android",
    target_os = "macos",
    target_os = "windows"
)))]
fn total_mem_mb() -> Option<u64> {
    None
}

fn detect_tier(cpus: usize, mem_mb: Option<u64>) -> Tier {
    if let Ok(v) = std::env::var("AETHER_PERF_PROFILE") {
        match v.trim().to_lowercase().as_str() {
            "low" => return Tier::Low,
            "medium" | "mid" => return Tier::Medium,
            "high" => return Tier::High,
            _ => {}
        }
    }

    let mem_low = mem_mb.map(|m| m <= 384).unwrap_or(false);
    let mem_medium = mem_mb.map(|m| m <= 1536).unwrap_or(false);

    if cpus <= 2 || mem_low {
        Tier::Low
    } else if cpus <= 4 || mem_medium {
        Tier::Medium
    } else {
        Tier::High
    }
}

/// Reads a buffer size in bytes from the environment, ignoring anything
/// outside what a TCP socket can sensibly be given.
fn buffer_override(key: &str, fallback: usize) -> usize {
    std::env::var(key)
        .ok()
        .and_then(|value| value.trim().parse::<usize>().ok())
        .filter(|bytes| (16 * 1024..=64 * 1024 * 1024).contains(bytes))
        .unwrap_or(fallback)
}

/// The smallest receive window a netstack TCP socket is ever given.
const MIN_TCP_RX_BUF: usize = 256 * 1024;
/// How many connections we expect to be moving bulk data at the same time
/// when we size receive windows against physical memory.
const RX_BUDGET_CONNECTIONS: u64 = 64;
/// Those busy connections may pin at most 1/RX_BUDGET_SHARE of RAM together.
const RX_BUDGET_SHARE: u64 = 8;

/// Caps a tier's receive window by what the device can actually afford.
///
/// The window is a CPU-independent quantity: a slow CPU already limits how
/// fast data is drained, so the only reason to keep the window small is
/// memory. We plan for `RX_BUDGET_CONNECTIONS` connections filling their
/// windows at once and let them use `1 / RX_BUDGET_SHARE` of RAM, never go
/// below `MIN_TCP_RX_BUF`, and round down to a power of two so smoltcp's
/// window scale is used without waste.
fn tcp_rx_within_memory(wanted: usize, mem_mb: Option<u64>) -> usize {
    let capped = match mem_mb {
        Some(mem_mb) => {
            let budget =
                mem_mb.saturating_mul(1024 * 1024) / RX_BUDGET_SHARE / RX_BUDGET_CONNECTIONS;
            wanted.min(usize::try_from(budget).unwrap_or(usize::MAX))
        }
        None => wanted,
    };
    let capped = capped.max(MIN_TCP_RX_BUF);
    1usize << (usize::BITS - 1 - capped.leading_zeros())
}

fn build_tuning() -> Tuning {
    let cpus = detected_cpus();
    let mem_mb = total_mem_mb();
    let tier = detect_tier(cpus, mem_mb);

    // The UDP socket buffer is where QUIC packets wait while the tunnel task
    // is not scheduled. 256 KiB is ~27 ms of traffic at 10 MB/s, which a busy
    // low-end phone overruns easily, and every overrun is a loss that the
    // congestion controller then answers by slowing down.
    let (scan_concurrency_cap, udp_socket_buf, netstack_udp_buf, channel_capacity) = match tier {
        Tier::Low => (4usize, 1024 * 1024, 32 * 1024, 128usize),
        Tier::Medium => (10usize, 2 * 1024 * 1024, 64 * 1024, 512usize),
        Tier::High => (usize::MAX, 7 * 1024 * 1024, 128 * 1024, 1024usize),
    };

    // smoltcp advertises whatever room is left in a socket's receive buffer as
    // that connection's TCP window, so this buffer is a hard ceiling on a
    // download at window / round-trip-time. The old 256 KiB (Low) and 1 MiB
    // (Medium) settled at ~2.3 MB/s and ~9 MB/s over a 110 ms round trip no
    // matter how fast the tunnel underneath was. The receive side, which is
    // where the traffic is, now gets room for tens of MB/s and is capped by
    // memory rather than by CPU count; the send side stays modest. The buffers
    // are allocated zeroed (calloc), so an idle connection does not commit
    // the pages; only connections that actually fill their window pay.
    let (tier_tcp_rx_buf, netstack_tcp_tx_buf) = match tier {
        Tier::Low => (2 * 1024 * 1024, 128 * 1024),
        Tier::Medium => (4 * 1024 * 1024, 256 * 1024),
        Tier::High => (8 * 1024 * 1024, 512 * 1024),
    };
    let netstack_tcp_rx_buf = tcp_rx_within_memory(tier_tcp_rx_buf, mem_mb);

    let netstack_tcp_rx_buf = buffer_override("AETHER_NETSTACK_TCP_RX", netstack_tcp_rx_buf);
    let netstack_tcp_tx_buf = buffer_override("AETHER_NETSTACK_TCP_TX", netstack_tcp_tx_buf);

    // How much unacknowledged data the HTTP/2 edge may have on its way to us.
    // It is a promise rather than a reservation, but it does bound how much
    // arrives before we have drained it, so it follows the tier like the rest.
    // The ceiling it sets on a download is window / round-trip-time, which is
    // why the 64 KiB the h2 crate defaults to caps a 130 ms link at ~500 KB/s.
    let (h2_stream_window, h2_connection_window) = match tier {
        Tier::Low => (2 * 1024 * 1024, 4 * 1024 * 1024),
        Tier::Medium => (8 * 1024 * 1024, 16 * 1024 * 1024),
        Tier::High => (16 * 1024 * 1024, 32 * 1024 * 1024),
    };

    Tuning {
        tier,
        cpus,
        mem_mb,
        scan_concurrency_cap,
        udp_socket_buf,
        netstack_tcp_rx_buf,
        netstack_tcp_tx_buf,
        netstack_udp_buf,
        channel_capacity,
        h2_stream_window,
        h2_connection_window,
    }
}

pub fn tuning() -> &'static Tuning {
    TUNING.get_or_init(build_tuning)
}

pub fn log_summary() {
    let t = tuning();
    let mem = t
        .mem_mb
        .map(|m| format!("{m}MB"))
        .unwrap_or_else(|| "unknown".to_string());
    let cap = if t.scan_concurrency_cap == usize::MAX {
        "unlimited".to_string()
    } else {
        t.scan_concurrency_cap.to_string()
    };
    log::info!(
        "[*] performance profile: {:?} (cpus={} mem={}); scan concurrency cap={}, udp socket buffer={}KB, netstack tcp buffers={}KB rx/{}KB tx, netstack udp buffer={}KB, channel capacity={}, h2 windows={}KB/{}KB",
        t.tier,
        t.cpus,
        mem,
        cap,
        t.udp_socket_buf / 1024,
        t.netstack_tcp_rx_buf / 1024,
        t.netstack_tcp_tx_buf / 1024,
        t.netstack_udp_buf / 1024,
        t.channel_capacity,
        t.h2_stream_window / 1024,
        t.h2_connection_window / 1024,
    );
}

#[cfg(unix)]
pub fn raise_fd_limit() {
    let mut limit = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut limit) } != 0 {
        return;
    }

    #[cfg_attr(not(target_os = "macos"), allow(unused_mut))]
    let mut wanted = limit.rlim_max;
    #[cfg(target_os = "macos")]
    {
        wanted = wanted.min(macos_max_files_per_proc());
    }

    if wanted <= limit.rlim_cur {
        return;
    }

    let raised = libc::rlimit {
        rlim_cur: wanted,
        rlim_max: limit.rlim_max,
    };
    if unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &raised) } == 0 {
        log::debug!(
            "[*] open file limit raised from {} to {}",
            limit.rlim_cur,
            wanted
        );
    }
}

#[cfg(not(unix))]
pub fn raise_fd_limit() {}

#[cfg(target_os = "macos")]
fn macos_max_files_per_proc() -> libc::rlim_t {
    const OPEN_MAX: libc::rlim_t = 10240;

    let mut value: libc::c_int = 0;
    let mut len = std::mem::size_of::<libc::c_int>();
    let name = b"kern.maxfilesperproc\0";
    let ret = unsafe {
        libc::sysctlbyname(
            name.as_ptr() as *const libc::c_char,
            &mut value as *mut libc::c_int as *mut libc::c_void,
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if ret == 0 && value > 0 {
        value as libc::rlim_t
    } else {
        OPEN_MAX
    }
}

#[cfg(unix)]
pub fn open_file_limit() -> Option<usize> {
    let mut limit = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut limit) } != 0
        || limit.rlim_cur == libc::RLIM_INFINITY
    {
        return None;
    }
    usize::try_from(limit.rlim_cur).ok()
}

#[cfg(not(unix))]
pub fn open_file_limit() -> Option<usize> {
    None
}

pub fn cap_concurrency(requested: usize) -> usize {
    requested.min(tuning().scan_concurrency_cap)
}

pub fn udp_socket_buf_bytes() -> usize {
    tuning().udp_socket_buf
}

/// The receive buffer of a netstack TCP socket, which is also the window that
/// connection advertises, and so the ceiling on what it can pull down.
pub fn netstack_tcp_rx_buf_bytes() -> usize {
    tuning().netstack_tcp_rx_buf
}

pub fn netstack_tcp_tx_buf_bytes() -> usize {
    tuning().netstack_tcp_tx_buf
}

pub fn netstack_udp_buf_bytes() -> usize {
    tuning().netstack_udp_buf
}

pub fn channel_capacity() -> usize {
    tuning().channel_capacity
}

pub fn h2_stream_window_bytes() -> u32 {
    tuning().h2_stream_window
}

pub fn h2_connection_window_bytes() -> u32 {
    tuning().h2_connection_window
}

#[cfg(test)]
mod tests {
    use super::*;

    const KIB: usize = 1024;
    const MIB: usize = 1024 * 1024;

    /// Bytes per second a single connection can reach with this window.
    fn ceiling(window: usize, rtt_ms: usize) -> usize {
        window * 1000 / rtt_ms
    }

    #[test]
    fn an_unknown_memory_size_keeps_the_tier_window() {
        assert_eq!(tcp_rx_within_memory(8 * MIB, None), 8 * MIB);
        assert_eq!(tcp_rx_within_memory(2 * MIB, None), 2 * MIB);
    }

    #[test]
    fn a_tiny_device_is_capped_by_memory_but_not_starved() {
        assert_eq!(tcp_rx_within_memory(8 * MIB, Some(384)), 512 * KIB);
        assert_eq!(tcp_rx_within_memory(8 * MIB, Some(16)), MIN_TCP_RX_BUF);
    }

    #[test]
    fn the_window_is_always_a_power_of_two() {
        for mem in [200u64, 384, 1000, 1536, 3000, 6000] {
            let w = tcp_rx_within_memory(8 * MIB, Some(mem));
            assert!(w.is_power_of_two(), "{w} for {mem} MB");
        }
    }

    #[test]
    fn low_and_medium_windows_no_longer_cap_a_110ms_link_at_2_or_9_mb_per_second() {
        // A 2-core phone with 3 GB of RAM lands in Low.
        let low = tcp_rx_within_memory(2 * MIB, Some(3 * 1024));
        assert!(ceiling(low, 110) > 15 * MIB, "low window {low}");
        // A 4-core phone with 4 GB of RAM lands in Medium.
        let medium = tcp_rx_within_memory(4 * MIB, Some(4 * 1024));
        assert!(ceiling(medium, 110) > 30 * MIB, "medium window {medium}");
    }
}
