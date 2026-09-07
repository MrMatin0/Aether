# Chaining Aether with Psiphon and Tor

This app can run three circumvention cores and stack them. This document is the
why: the wiring, the ports, the one non-obvious problem (DNS), what does not
work, and how to build the two new cores.

## The idea

All three cores expose a local SOCKS5 proxy on loopback, and two of them can
dial their own upstream **through** a SOCKS5 proxy:

| Core | Local SOCKS5 | Upstream option |
|---|---|---|
| Aether engine (`libaether.so`) | `127.0.0.1:1819` | none |
| Psiphon (`libpsiphon.so`) | `127.0.0.1:1891` | `UpstreamProxyUrl` (config JSON) |
| Tor (`libtor.so`) | `127.0.0.1:9819` | `Socks5Proxy` (torrc) |

So chaining is a wiring problem, not a protocol problem: start core N, prove it
is ready, then start core N+1 pointed at it.

The Aether engine has **no** upstream-proxy option - it dials WARP endpoints
itself - so it can only ever be the hop that touches the open internet. Tor is
always the entry hop, because putting a fixed proxy *after* Tor would hand every
stream leaving the Tor network to one identifiable place and defeat the point of
using it. That leaves exactly seven buildable combinations, which is why
`ChainMode` is an enum and not three checkboxes:

```
AETHER                        device -> Aether -> internet
PSIPHON                       device -> Psiphon -> internet
TOR                           device -> Tor -> internet
PSIPHON_OVER_AETHER           device -> Psiphon -> Aether -> internet
TOR_OVER_AETHER               device -> Tor -> Aether -> internet
TOR_OVER_PSIPHON              device -> Tor -> Psiphon -> internet
TOR_OVER_PSIPHON_OVER_AETHER  device -> Tor -> Psiphon -> Aether -> internet
```

`ChainMode.hops` is ordered **internet-side first**, so `hops.first()` is the
core that reaches the internet and `hops.last()` is the CHAIN ENTRY: the local
port the TUN forwarder is pointed at.

## Why the cores run as child processes

Same reason the engine does: an executable packaged under `lib/<abi>/` is
extracted to `nativeLibraryDir` with the exec bit set, and that is one of the
few places Android still allows exec from. A `.so` name is the packaging trick,
not a claim that these are libraries.

The alternative for Psiphon would have been `ca.psiphon:psiphontunnel`, an
in-process Go library. Child processes win here: a Go runtime panic kills a
child instead of the VpnService holding the user's TUN, the process supervision
already exists (`NativeChild`), and it avoids pulling a multi-megabyte AAR from
a `raw.githubusercontent` Maven repo into a circumvention app's dependency
graph.

## The DNS problem (read this before changing SocksFront)

`hev-socks5-tunnel` forwards the device's TCP through SOCKS5 `CONNECT` and its
UDP through SOCKS5 `UDP ASSOCIATE`.

* Aether's SOCKS5 implements `UDP ASSOCIATE`. Fine.
* **Psiphon's does not** - it carries UDP through its own `udpgw` side channel.
* **Tor carries no UDP at all**, by design.

Point the forwarder straight at Psiphon or Tor and every TCP connection works
while nothing resolves: the app reports "connected", all four self-test circles
are green, and no site opens. That is the exact failure this codebase already
has a comment about in `Diagnostics`.

So a chain whose entry is not Aether enters through **`SocksFront`**, a loopback
SOCKS5 server on `127.0.0.1:1820`:

* `CONNECT` is replayed byte-for-byte to the upstream core, so `ATYP=DOMAIN`
  survives and names are still resolved inside the tunnel.
* `UDP ASSOCIATE` is accepted, and datagrams for port 53 are answered out of
  band:
  * **Tor**: forwarded to tor's own `DNSPort` (`127.0.0.1:5819`), so resolution
    happens inside the Tor network.
  * **Psiphon**: DNS over TCP (RFC 7766) through the tunnel, to the resolvers
    configured in Settings, or `1.1.1.1` / `8.8.8.8`.
* Any other UDP is dropped. There is nothing honest to do with it.

## Ports

| Port | Who | Why not the default |
|---|---|---|
| 1819 | Aether engine SOCKS5 | unchanged |
| 1820 | `SocksFront` (chain entry when the entry core is not Aether) | adjacent to 1819 on purpose |
| 1891 | Psiphon SOCKS5 | **not 1080** - that is psiphon-tunnel-core's default, i.e. what an installed Psiphon app owns |
| 9819 | Tor SOCKS5 | **not 9050** - Orbot |
| 5819 | Tor DNSPort | **not 5400** - Orbot |
| 10810 / 10811 | LAN share SOCKS5 / HTTP | unchanged (not 10808/9 - v2rayNG) |

## What does not work

* **No UDP through Psiphon or Tor.** QUIC / HTTP3 falls back to TCP (browsers do
  this automatically); UDP-only apps - most video calling - will not work in a
  Psiphon or Tor chain. Use Aether alone for those.
* **Tor is slow.** Three relays, plus every hop underneath it. A first bootstrap
  on a hostile mobile network regularly takes over two minutes, which is why the
  budget is four.
* **No pluggable transports** (obfs4, snowflake, meek). They need their own
  binaries, and "Tor over Aether" / "Tor over Psiphon" solves the same problem
  with cores this app already ships.
* **Exit country needs geoip.** Without `assets/tor/geoip` tor cannot map relays
  to countries; the app says so instead of pretending the setting works.

## Building the cores

They are **optional**. A build without them is a valid build: the app detects
their absence and the affected chain modes report *"this build does not include
Psiphon/Tor"* instead of failing three minutes into a connect attempt.

```bash
export ANDROID_NDK_HOME=/path/to/ndk
bash scripts/build-overlay-cores.sh all      # or: psiphon | tor
```

* **Psiphon** is compiled from source (`psiphon-tunnel-core`'s ConsoleClient)
  with the NDK toolchain, matching this repo's rule that nothing is vendored as
  a blob.
* **Tor** is taken from the Tor Project / Guardian Project's published AAR by
  default, because building tor for Android means building OpenSSL, libevent,
  zlib and zstd for Android first. It is the same signed binary Orbot ships.
  `TOR_FROM_SOURCE=1` builds it with upstream's own `tor-droid-make.sh` instead.

CI runs the script as a `continue-on-error` step, so an upstream layout change
costs the chain feature for one build rather than the whole APK.

## The Psiphon config

Psiphon needs a client config: `PropagationChannelId` and `SponsorId` are issued
by the Psiphon network, and the server-discovery URLs and signing keys travel
with them. **They are not in this repository** - they are not ours to ship. The
app looks for one in this order:

1. what the user pasted into **Settings > Chain**;
2. `assets/psiphon.config`, which `build-overlay-cores.sh` writes from
   `PSIPHON_CONFIG_B64` or `PSIPHON_CONFIG_FILE` at build time (gitignored).

With neither, the Psiphon chain modes say so. Everything the app overrides in
that config - ports, data directory, client platform, upstream proxy, egress
region - is listed in `PsiphonCore.buildConfig`; the rest is left exactly as
issued.

## Files

| File | Role |
|---|---|
| `model/Chain.kt` | the seven modes and the hop order |
| `core/ChainRuntime.kt` | live chain state + the entry port everything else reads |
| `core/NativeChild.kt` | child-process supervision shared by all cores |
| `core/PsiphonCore.kt` | config JSON, launch, readiness from `Tunnels` notices |
| `core/TorCore.kt` | torrc (pure, unit-tested), launch, `Bootstrapped NN%` |
| `core/SocksFront.kt` | the DNS-capable SOCKS5 front |
| `vpn/session/ChainStack.kt` | start order, readiness gates, teardown order |
| `scripts/build-overlay-cores.sh` | builds/fetches both cores |
