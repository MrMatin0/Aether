//! MASQUE CONNECT-IP over HTTP/2, for networks where UDP (and therefore QUIC)
//! does not survive the path.
//!
//! * [`config`] - the tunnel config and every runtime knob.
//! * [`tlsconf`] - the TLS handshake this transport hides behind.
//! * [`request`] - dialling the edge and the CONNECT-IP request itself.
//! * [`connect`] - the one path that opens a CONNECT-IP stream, shared by
//!   verification and the live tunnel.
//! * [`stream`] - capsules in and out of an established stream.
//! * [`verify`] - proving an endpoint carries traffic.
//! * [`session`] - the running tunnel.

mod config;
mod connect;
mod request;
mod session;
mod stream;
mod tlsconf;
mod verify;

pub use config::{enabled, h2_peer, H2TunnelConfig};
pub use request::dial;
pub use session::run;
pub use verify::verify_h2;
