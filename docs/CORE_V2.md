# Aether core v2.0.0 in this app

What upstream [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) v2.0.0
changed, which of it the Android app now exposes, and where each control lands in
the engine. Read it with `native/aether/CORE_VERSION` (what is vendored right
now) and `scripts/sync-core.sh` (how it gets there) open.

## How the core version is decided

The vendored engine under `native/aether` is not edited by hand. `scripts/sync-core.sh`
fetches an upstream release, three-way merges this app's two engine patches
(`aether/src/prober.rs`, `aether/src/wg_prober.rs` - they are what makes the
location picker work through `AETHER_SCAN_CIDRS`), records the result in
`native/aether/CORE_VERSION`, and the `Core sync` workflow only proposes the
result after `libaether.so` has actually cross-compiled from that tree.

Until now the target was "whatever upstream tagged last". It is now a pin:

| Where | What it means |
| --- | --- |
| `CORE_TARGET_PIN` in `.github/workflows/core-sync.yml` | the reviewed core version - **2.0.0** |
| `workflow_dispatch` input `target` | try another version for one run, committing nothing |
| `CORE_TARGET` env | the same override, for running `scripts/sync-core.sh` locally |
| `MIN_RUST` in the same workflow | core 2.0.0 needs Rust >= 1.98; the job fails with that sentence instead of a dependency's error spew |

So `bash scripts/sync-core.sh` with `CORE_TARGET=2.0.0` (or the workflow, on its
schedule or dispatched) is what moves `native/aether` to v2.0.0 and rewrites the
changelog block in both READMEs. The engine tree is deliberately NOT hand-copied
into a feature branch: a core that has not been compiled for `arm64-v8a` and
`armeabi-v7a` on this NDK is not a core this app can ship, and that proof only
exists in CI.

## What v2.0.0 brings, and what the UI does with it

| Core v2.0.0 capability | Engine surface | In the app |
| --- | --- | --- |
| MASQUE-in-MASQUE: two MASQUE hops, so the exit address differs the way `gool` does for WireGuard | `--mim`, `--mim-outer`, `--mim-inner` | Settings -> Transport -> "Core v2.0.0": MASQUE-in-MASQUE switch, with optional outer/inner endpoints. Only meaningful when the resolved protocol is MASQUE, so it is ignored for WireGuard/gool instead of being silently sent |
| QUIC v2 version-negotiation opener - gets HTTP/3 through networks that drop QUIC v1 but pass v2 | on by default, `--no-quic-v2` / `AETHER_QUIC_V2=0` to disable | Same card: "QUIC v2 opener" switch, on by default, so turning it OFF is the deliberate act |
| Tor built into the engine, three placements | `--tor`, `--tor-reverse`, `--tor-only`, `--tor-bind` | Same card: "Engine Tor" selector (off / inside the tunnel / reverse / Tor only) plus the second proxy port. Mutually exclusive with the app's own Tor chain modes, and the UI says so rather than sending both |
| Bridges fetched from bridgedb, country-aware, no CAPTCHA; pluggable transports found on the machine (`pt/` beside the binary, Tor Browser's included); a bridge only counts once a stream has opened through it | default behaviour, `--tor-bridges`, `--no-tor-bridges`, `--tor-bridge <line>`, `AETHER_TOR_COUNTRY` | Same card: bridge source (automatic / straight to bridges / never / my own lines), country hint, and a lines field that falls back to the bridges already configured for the bundled Tor core |
| `--tor` composes with any transport, e.g. `--wg --tor` | flag composition | The engine-Tor selector sits beside the protocol choice instead of replacing it; `--tor-reverse` is the one exception and the app sends MASQUE over HTTP/2 for it, because the engine refuses `--wg`/`--gool` there (Tor carries TCP only) |
| Socket firewall mark, so a tun front end on the same Linux host does not loop the engine's own traffic back in (#106) | `--mark <n>` / `AETHER_MARK` | Same card: "Socket mark" field (hex or decimal). Needs root/CAP_NET_ADMIN, which the field says |
| fd exhaustion fixed: the limit is raised at startup, the client count is capped, leaks closed (#101, #106) | `AETHER_MAX_CLIENTS` | Settings -> Tuning -> "Core resources": concurrent clients |
| idle and half-closed connections time out, dead ones are reset; the relay flushes writes (fixes buffering transports) | `AETHER_HALF_CLOSE_SECS`, `AETHER_TCP_KEEPALIVE_SECS`, `AETHER_TCP_CONNECT_SECS` | Same card: half-closed, keep-alive and connect windows, blank = the engine's own defaults |

Everything above is off or blank by default, so a profile that has never been
opened produces the same argv it produced on core 1.8.0. That is deliberate:
the engine upgrade and a behaviour change should never arrive in the same
session.

## Engine Tor vs the app's Tor chains

The app already carries its own tor binary, bridge catalogue and pluggable
transports (`docs/CHAIN_CORES.md`, `scripts/build-overlay-cores.sh`,
`scripts/build-pt-transports.sh`), and the chain modes in Settings -> Chain are
built on it. Core v2.0.0 adds a second, independent implementation inside the
engine.

They are not stacked. `ConnectionProfile.usesEngineTor` is true only when the
chain is plain `AETHER`, so selecting a Tor chain mode leaves the engine's own
Tor switched off and vice versa - two tor instances on one device would fight
over the same bootstrap, the same bridges and the same battery.

Which to reach for:

* **Engine Tor** - fewer moving parts, bridges fetched automatically, and
  `--tor-reverse` is something the chains cannot do at all: WARP is reached FROM
  a Tor exit, so the local network never sees WARP.
* **App Tor chains** - work on any core build, expose exit country and strict
  nodes, and can be combined with Psiphon.

Note that the engine's Tor needs an engine built with its `tor` cargo feature
(`cargo build --release --features tor`). When the vendored core is built without
it, the engine rejects the flags at startup and the app reports that in
Diagnostics - the UI therefore labels the selector as requiring a Tor-enabled
engine build rather than pretending the choice is always available.
