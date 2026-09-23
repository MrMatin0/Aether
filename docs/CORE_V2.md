# Aether core v2.0.0 in this app

What upstream [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) v2.0.0
changed, which of it the Android app exposes, and where each control lands in
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

The UI does not group these under a version label. Each control sits on the
page that matches what it does, next to the settings it interacts with.

| Core v2.0.0 capability | Engine surface | In the app |
| --- | --- | --- |
| MASQUE-in-MASQUE: two MASQUE hops, so the exit address differs the way `gool` does for WireGuard | `--mim`, `--mim-outer`, `--mim-inner` | Settings -> Connection -> Protocol: **MASQUE×2**, beside Smart, MASQUE, WireGuard and WARP×2 (`Protocol.MIM`). Its optional outer/inner endpoints appear under the picker while it is selected; a pinned manual endpoint becomes the outer hop |
| QUIC v2 version-negotiation opener - gets HTTP/3 through networks that drop QUIC v1 but pass v2 | on by default, `--no-quic-v2` / `AETHER_QUIC_V2=0` to disable | Settings -> Transport: "QUIC v2 opener" switch, on by default, so turning it OFF is the deliberate act |
| Tor built into the engine (Arti) | `--tor`, `--tor-reverse`, `--tor-only`, bridges | **Not used.** See "One Tor" below |
| Socket firewall mark, so a tun front end on the same Linux host does not loop the engine's own traffic back in (#106) | `--mark <n>` / `AETHER_MARK` | Settings -> Transport: "Socket mark" field (hex or decimal). Needs root/CAP_NET_ADMIN, which the field says |
| fd exhaustion fixed: the limit is raised at startup, the client count is capped, leaks closed (#101, #106) | `AETHER_MAX_CLIENTS` | Settings -> Tuning -> "Core resources": concurrent clients |
| idle and half-closed connections time out, dead ones are reset; the relay flushes writes (fixes buffering transports) | `AETHER_HALF_CLOSE_SECS`, `AETHER_TCP_KEEPALIVE_SECS`, `AETHER_TCP_CONNECT_SECS` | Same card: half-closed, keep-alive and connect windows, blank = the engine's own defaults |

Everything above is off, blank or the engine's own default unless the user
changes it, so a profile that has never been opened produces the same argv it
produced on core 1.8.0.

### Migration

MASQUE-in-MASQUE used to be a separate switch (`mim=true`) that only took
effect while the protocol was MASQUE. A stored or transported profile with
`protocol=MASQUE` and `mim=true` is read as `Protocol.MIM`; with any other
protocol the old switch was idle and stays ignored. `ProfileStore` deletes the
retired key on the next save.

## One Tor

The app carries its own tor binary, bridge catalogue and pluggable transports
(`docs/CHAIN_CORES.md`, `scripts/build-overlay-cores.sh`,
`scripts/build-pt-transports.sh`), and the chain modes in Settings -> Chain
(Tor, Tor over Aether, Tor over Psiphon, Tor over Psiphon over Aether) are built
on it, configured from Settings -> Chain and Settings -> Bridges.

Core v2.0.0 can embed a second, independent Tor (Arti) inside the engine. An
earlier build exposed it as an "Engine Tor" selector next to the chain modes,
which meant two Tor implementations in one app: two bridge settings, two
bridge lists, two bootstrap budgets, and a runtime rule that switched one off
whenever the other was selected. That is gone:

* `scripts/build-natives.sh` builds the engine **without** its `tor` cargo
  feature, so `libaether.so` contains no Tor at all.
* `ConnectionProfile` has no engine-Tor fields, and `toArgs()` / `toEnv()` never
  emit `--tor*` or `AETHER_TOR*` - `ConnectionProfileArgsTest` holds that for
  every protocol and chain.
* Engine-Tor keys in old payloads (`engineTor`, `engineTorBridges`,
  `engineTorBridgeLines`, `engineTorCountry`, `engineTorBind`) are ignored on
  read and removed from the DataStore on the next save.

If you need Tor, pick a Tor chain mode.
