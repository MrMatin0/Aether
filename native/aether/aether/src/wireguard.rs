//! Cloudflare WARP over WireGuard.
//!
//! * [`catalog`] - which addresses and ports are worth trying at all.
//! * [`framing`] - the reserved header bytes WARP uses as a client id, and
//!   which socket errors are worth retrying.
//! * [`probe`] - pushing a real IP packet through a tunnel.
//! * [`verify`] - proving one endpoint completes a handshake AND forwards
//!   traffic.
//! * [`tunnel`] - the long lived tunnel and its four tasks.
//!
//! Everything the rest of the crate imported from `crate::wireguard` is
//! re-exported here, so the split is source compatible.

mod catalog;
mod framing;
mod probe;
mod tunnel;
mod verify;

pub use catalog::{
    wg_prefixes_v4, wg_prefixes_v6, wg_seeds_v4, WG_DEFAULT_PORT, WG_PORTS, WG_PREFIXES_V4,
    WG_PREFIXES_V6, WG_SEEDS_V4, WG_SEEDS_V6, WG_ZT_PREFIXES_V4, WG_ZT_PREFIXES_V6,
};
pub use framing::is_transient_socket_error;
pub use tunnel::{EstablishedSession, WgConfig, WgTunnel};
pub use verify::{verify_endpoint, verify_endpoint_keep_session};
