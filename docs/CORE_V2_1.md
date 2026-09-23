# Aether core v2.1.0 in this app

What upstream [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether)
v2.1.0 changed, which of it the Android app uses, and what it deliberately
leaves out. Read it after [CORE_V2.md](CORE_V2.md), which covers 2.0.0 and the
"One Tor" rule this document extends to Psiphon.

## How the engine gets to 2.1.0

Exactly as it got to 2.0.0: `scripts/sync-core.sh` replaces `native/aether/aether`
with the upstream tag and three-way merges this app's engine patches back on
top. The engine tree is never hand-copied into a feature branch - a core that
has not been cross-compiled for `arm64-v8a` and `armeabi-v7a` on this NDK is
not a core this app can ship, and that proof only exists in the `Core sync`
workflow.

| Where | Value |
| --- | --- |
| `CORE_TARGET_PIN` (core-sync workflow) | **2.1.0** |
| `MIN_RUST` | 1.98.0 (unchanged from 2.0.0) |
| `native/aether/CORE_VERSION` | written by the sync, never by hand. `app/build.gradle.kts` turns it into `BuildConfig.CORE_VERSION`, which is what Settings shows as "core 2.1.0" and what the scan-mode gate below reads |

**Merge order does not matter.** The app-side changes are safe on a 2.0.0
engine (see "Scan modes in the app"), so the app PR can land before or after
the `Core sync` PR that actually vendors 2.1.0.

### The patch set, and the bug it had

`PATCHED_FILES` in `sync-core.sh` is the list of engine files this app changes
and the sync merges back. Before 1.5.0 it named `prober.rs` and `wg_prober.rs`
only. The one engine file this repo actually changes today is `cli.rs`
(`--precise` / `--ultra` accepted as aliases of `--balanced` / `--ironclad`,
plus its parser tests), and it was not on the list - so the 2.1.0 sync would
have replaced it with upstream's and the app's default scan mode would have
failed every connect with `unknown option`.

Two changes close that from both sides:

1. `cli.rs` is in `PATCHED_FILES`, so the aliases are merged forward like any
   other patch. The sync rebuilds the missing 2.0.0 baseline for it by itself.
2. The app no longer depends on the aliases at all: `ScanMode.engineFlag` now
   sends upstream's own names (`--turbo`, `--balanced`, `--verified`,
   `--ironclad`). A future upstream rewrite of `cli.rs` can drop the patch
   without breaking a single connect. `ScanModeTest` fails if any mode goes
   back to an app-only alias.

The patch's own parser test used to assert that `--stealth` selects
`stealth`. From 2.1.0 upstream makes `--stealth` an alias of `verified`, so the
merged patch would have carried a test that fails `cargo test` on the core it
was merged onto. It now only asserts that `--stealth` is accepted. Only the
`#[cfg(test)]` module changed; the parser and USAGE hunks are as they were.

`prober.rs` and `wg_prober.rs` stay listed, but at 2.0.0 they are byte-identical
to upstream: the manual-range patch did not survive the 2.0.0 sync, and
neither upstream file reads `AETHER_SCAN_CIDRS` / `AETHER_MASQUE_CIDRS` /
`AETHER_WG_CIDRS`. The Settings range field therefore reaches the engine's
environment and is ignored there. Re-applying it is a separate change.

## What 2.1.0 brings, and what the app does with it

| Core 2.1.0 change | Engine surface | In the app |
| --- | --- | --- |
| HTTP/2 gateway scan retries its data-plane probe instead of burning the whole per-probe budget on one lost packet; the first gateway turns up in about a second | `masque_h2.rs` | Automatic. Most visible with "HTTP/2" on in Transport |
| `firewall` and `gfw` noize profiles actually implemented (they used to fall through to `balanced`); the `<c>` tag emits a counter | `--noize firewall\|gfw`, `aethernoize.rs` | Automatic, and a **behaviour change**: FIREWALL is this app's default, so a default install now sends the real firewall profile for the first time. If a network that worked on 1.4.6 stops working, Balanced reproduces the old behaviour. **Test on a real phone before release** |
| `--mark` reaches the registration calls | `account.rs`, `egress.rs` | Automatic for anyone using the Socket mark field |
| Fourth API fingerprint (TLS 1.3 with a fragmented ClientHello); fragmenting splits inside the server name | `apifront.rs`, `fragment.rs` | Automatic |
| Reconnects remember the last eight working gateways; an HTTP/3 gateway is not reused on HTTP/2 | `lastconn.rs`, `lib.rs` | Automatic with Quick reconnect on (the default) |
| gool's inner tunnel follows the inner engine to a new source port | `lib.rs` | Automatic for WARP×2 |
| **Verified** scan mode: dial only edges measured to answer connect-ip; on gool and mim keep the two hops in different ranges, which is what moves the exit | `--verified` (`--stealth` is now an alias of it) | **Settings -> Connection -> Scan: Verified** (`ScanMode.VERIFIED`), offered only when the bundled core is 2.1.0 or newer |
| Exit-country guard | `--exit-loc`, `--exit-loc-secs` | **Not yet.** It looks the exit up through the tunnel every minute; it needs its own UI decision |
| Traffic and uptime counters in the log | `--stats`, `--stats-secs` | **Not yet.** The connection tab already measures this itself |
| Tor: bridges fetched through the tunnel, onionoo relays as bridges, bridge file, HTTP listener, UDP ASSOCIATE for DNS | `--tor-*` | **Not used.** The engine is built without its `tor` feature; see "One Tor" in CORE_V2.md |
| Psiphon built in | `--psiphon`, `--psiphon-reverse`, `--psiphon-only`, `--psiphon-*` | **Not used.** See "One Psiphon" below |

`sync-core.sh` lists `--exit-loc` and `--stats` in `NEW_CAPS`, so every sync
keeps reporting them as engine features without a UI decision until one is
made. `--verified` is in the list too and is now satisfied by `model/Profile.kt`.

### Scan modes in the app

| App mode | Flag sent | Engine mode | Offered from core |
| --- | --- | --- | --- |
| Turbo | `--turbo` | turbo | any |
| Precise (default) | `--balanced` | balanced | any |
| Verified (new) | `--verified` | verified | **2.1.0** |
| Very precise | `--ironclad` | ironclad | any |

**Why Verified is gated.** Until the `Core sync` workflow lands 2.1.0 the
vendored engine is 2.0.0, and 2.0.0 refuses `--verified` as an unknown option
- which is fatal, the process never opens a socket. So `ScanMode` carries
`sinceCore = "2.1.0"` and compares it with `BuildConfig.CORE_VERSION`:

* `ScanMode.offeredBy(core)` - the picker lists Verified only on 2.1.0+.
* `ScanMode.effectiveFor(core)` - a stored or imported Verified on an older
  core is sent as `--balanced` and waited on like Precise, so a profile from a
  newer build can never produce an argv the bundled engine rejects.
* An unparseable core version (`unknown`) counts as older than everything.

The connect timeout for Verified is 120 s: the engine's own budget is 60 s
(`prober.rs`, `ScanMode::Verified`), doubled because on gool and mim it scans
for two hops in two ranges.

Stored profiles are unaffected: they persist the enum NAME (`PRECISE`,
`ULTRA`, ...), not the flag. A stored `STEALTH` from a pre-1.4.6 build still
migrates to Precise, not to Verified - it was chosen as "quiet and patient",
and upstream reusing the word does not change what the user asked for.

## One Psiphon

Core 2.1.0 can drive Psiphon itself: it spawns a `psiphon-tunnel-core`
ConsoleClient built from CluvexStudio's own fork and branch. This app already
has a Psiphon - `PsiphonCore`, built from source by
`scripts/build-overlay-cores.sh` and driving every Psiphon chain mode - and it
is built from source precisely to close the upstream audit finding F-8. Wiring
the engine's copy in would mean two Psiphons, two configs, two egress-region
settings, and a second binary from a second source to audit.

So, as with Tor:

* No engine Psiphon binary is built or packaged; `psiphon-build.sh` is not run.
* `ConnectionProfile.toArgs()` / `toEnv()` never emit `--psiphon*` or
  `AETHER_PSIPHON*` (nor `--tor*` / `AETHER_TOR*`). `EngineOverlayGuardTest`
  holds that for every protocol, scan mode and chain mode, scanned and pinned,
  on core 2.0.0 and 2.1.0.
* The engine code for it is compiled in (upstream gates it behind no cargo
  feature) but stays inert: without one of those flags it never starts.

If you need Psiphon, pick a Psiphon chain mode.

## Tests that cover this

| Test | Holds |
| --- | --- |
| `ScanModeTest` | mode order, upstream flag names, no app-only aliases, Verified gated on 2.1.0 with Precise fallback, timeouts outlast engine budgets, version comparison |
| `EngineOverlayGuardTest` | One Tor, One Psiphon: nothing `--tor*` / `--psiphon*` / `AETHER_TOR*` / `AETHER_PSIPHON*` reaches the engine |
| `cli.rs` `every_scan_flag_selects_a_mode_this_core_understands` (engine, part of the patch) | the aliases still parse, on 2.0.0 and after the 2.1.0 merge |
