# Unbounded (experimental)

> Status: **experimental, for testing only.** Not part of the release gate, not
> chainable, and dependent on infrastructure this project does not run.

[Unbounded](https://github.com/getlantern/unbounded) is Lantern's browser-based
P2P circumvention stack (shipped to volunteers as *Lantern Action Mode*).
Volunteers in less-censored regions run a widget in their browser; a censored
client finds one through a discovery server, talks to it over WebRTC, and the
volunteer relays the traffic to Lantern's egress over a WebSocket. One QUIC
connection runs end to end on top, so the session survives a volunteer
leaving ("Turbo Tunnel").

```
app -> SocksFront 127.0.0.1:1820 -> Unbounded SOCKS5 127.0.0.1:1892
    -> QUIC over WebRTC -> volunteer peer -> WebSocket -> Lantern egress -> internet
```

## How it is wired

| Piece | Where |
|---|---|
| `Hop.UNBOUNDED`, `ChainMode.UNBOUNDED` | `model/Chain.kt` |
| Child process, readiness, env | `core/UnboundedCore.kt` |
| Lifecycle inside a session | `vpn/session/ChainStack.kt` (`startUnbounded`) |
| Budgets | `vpn/session/VpnTunables.kt` (`UNBOUNDED_*`) |
| Port | `TunnelConfig.UNBOUNDED_SOCKS_PORT` = 1892 |
| Build | `scripts/build-unbounded.sh` -> `jniLibs/<abi>/libunbounded.so` |

The binary is upstream's own `cmd` driver, built as the **desktop consumer in
SOCKS5 mode** (`-X main.clientType=desktop -X main.proxyMode=socks5`), pinned by
commit. It is configured entirely through the environment:

- `FREDDIE=https://freddie.iantem.io` (discovery / signalling)
- `EGRESS=wss://unbounded.iantem.io`
- `PORT=1892`

These are Lantern's production endpoints as published in upstream's
`ui/.env.production.example`. They are not ours and can change.

Readiness is the consumer's own `QUIC connection established, ready to proxy!`
log line, never the open port: upstream opens its SOCKS5 listener on a fixed
two-second timer whether or not any volunteer was found.

DNS works exactly as in a Psiphon-only session: the device's traffic enters
through `SocksFront`, which answers UDP DNS by sending it as DNS-over-TCP
through the tunnel.

## Why it is single-core only

Chaining in this app means "core N dials through core N-1's SOCKS5". Unbounded
has no upstream-proxy option, and could not use one: WebRTC is UDP, and a
SOCKS5 CONNECT hop does not carry UDP. So `Unbounded over Aether` is not a
mode. `Tor over Unbounded` would be possible in principle (Unbounded exposes
SOCKS5), but is left out until the single-core mode has proven itself.

## Known limitations

- **Volunteers.** Throughput and even reachability depend on who is running
  the Lantern widget right now. Expect it to be slow or to find nobody.
- **TCP only.** No UDP through the tunnel (same as Psiphon/Tor modes).
- **STUN list.** Upstream fetches its STUN server list from
  `raw.githubusercontent.com` at start-up. If that host is blocked, NAT
  traversal gets much harder.
- **Discovery can be blocked.** If `freddie.iantem.io` is filtered, the core
  never finds a peer and the session fails with a clear Unbounded timeout.
- **Hostname requests in proxy mode.** Upstream's SOCKS5 server (armon/go-socks5)
  resolves a CONNECT-by-name on the device before dialling. In VPN mode apps
  connect by IP (DNS already went through the tunnel), so this only matters for
  proxy-mode clients that send hostnames.
- **Debug logging.** The upstream driver logs at debug level by design. Its
  output goes to the app-private diagnostics log only, like every other core.

## Building locally

```bash
export ANDROID_NDK_HOME=...
GOTOOLCHAIN=auto bash scripts/build-unbounded.sh
```

CI builds it in the natives job as a separate `continue-on-error` step. A
missing `libunbounded.so` is a warning on every ref, including tags: it never
blocks a release.

## License

getlantern/unbounded is GPL-3.0. It is shipped as a separate executable,
launched as a child process, alongside this AGPL-3.0-or-later app. Review
THIRD_PARTY_NOTICES.md before distributing a build that includes it.
