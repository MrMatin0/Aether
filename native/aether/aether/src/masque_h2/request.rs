//! Getting a connection to the edge, and the CONNECT-IP request itself.

use std::net::SocketAddr;

use http::Method;
use tokio::net::TcpStream;

use crate::consts;
use crate::error::{AetherError, Result};

use super::config::H2TunnelConfig;

const HTTPS_PORT: u16 = 443;

/// Dials the edge, through the configured upstream proxy when there is one.
pub async fn dial(peer: SocketAddr) -> Result<TcpStream> {
    match crate::upstream::configured() {
        Some(proxy) => proxy.connect(peer).await,
        None => TcpStream::connect(peer).await.map_err(AetherError::Io),
    }
}

/// The `:authority` to request.
///
/// The port is appended only when the configured authority does not already
/// carry one: it used to be appended unconditionally, so a `host:443` setting
/// produced `host:443:443` and the edge rejected the request.
fn request_authority(authority: &str) -> String {
    let has_port = match authority.rsplit_once(':') {
        // An IPv6 literal keeps its colons inside brackets, so only a trailing
        // all-digits segment counts as a port.
        Some((_, port)) => !port.is_empty() && port.chars().all(|c| c.is_ascii_digit()),
        None => false,
    };

    if has_port {
        authority.to_string()
    } else {
        format!("{authority}:{HTTPS_PORT}")
    }
}

pub(super) fn build_connect_request(cfg: &H2TunnelConfig) -> Result<http::Request<()>> {
    let authority = request_authority(&cfg.authority);
    http::Request::builder()
        .method(Method::CONNECT)
        .uri(format!("https://{authority}"))
        .header("cf-connect-proto", consts::CF_CONNECT_PROTOCOL)
        .header("pq-enabled", "false")
        .header("user-agent", "")
        .body(())
        .map_err(|e| AetherError::Masque(format!("build request: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_bare_host_gets_the_https_port() {
        assert_eq!(request_authority("example.com"), "example.com:443");
    }

    #[test]
    fn a_host_that_already_has_a_port_keeps_it() {
        assert_eq!(request_authority("example.com:443"), "example.com:443");
        assert_eq!(request_authority("example.com:8443"), "example.com:8443");
    }

    #[test]
    fn an_ipv6_literal_is_not_mistaken_for_a_host_port_pair() {
        assert_eq!(request_authority("[2606:4700::1]"), "[2606:4700::1]:443");
        assert_eq!(
            request_authority("[2606:4700::1]:8443"),
            "[2606:4700::1]:8443"
        );
    }
}
