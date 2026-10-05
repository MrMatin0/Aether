# Modifications relative to the upstream project

This repository is an independent, modified distribution of **Aether Mobile**.
It was forked from [`QW-AI-Code/Aether`](https://github.com/QW-AI-Code/Aether),
which in turn builds on the Aether engine
([`CluvexStudio/Aether`](https://github.com/CluvexStudio/Aether)).

This file exists because the GNU AGPL-3.0 (section 5) requires a modified
version to carry prominent notices stating that it has been changed, and the
dates of those changes. It is the modification record for this repository.

## Fork point

- Upstream repository: `QW-AI-Code/Aether`
- Common ancestor: `bc4f91d`, 2026-08-13
- Changes in this repository: **316 commits**, 2026-08-15 → 2026-09-18
- Changes only upstream has: 13 commits, 2026-08-21 → 2026-09-15

This repository has diverged substantially. It is **not** a mirror, and it does
not track upstream. The two have been developed independently since the fork
point; upstream commits are not merged into this line.

## Application identity

The app is published under a different identity from upstream, so the two
builds can be installed side by side and neither is mistaken for the other:

| | Upstream | This repository |
|---|---|---|
| `applicationId` | `studio.cluvex.aether` | `io.github.mrmatin0.aether` |
| `versionName` | (upstream line) | `1.4.6` |

The Kotlin namespace remains `studio.cluvex.aether` because it only determines
the generated `R` and `BuildConfig` packages, not the installed identity.

## Scope of changes

The 316 commits replace most of the user-facing application layer. The
high-level shape of the divergence, by area:

### Rewritten or replaced

- **The entire UI layer.** A new design system ("Carbon & Signal"), a rebuilt
  component layer, and a rewritten app shell, settings, sharing, about and
  onboarding flow. The diagnostics panel was rebuilt on the new components.
  A Vazirmatn type scale was added with Persian-safe leading and
  locale-aware digit handling.
- **The connection tab and app shell, "Aurora"** (2026-10-02,
  `feat/home-aurora-redesign`). The connect orb is a glass power core with a
  conic progress arc and a comet sweep; the hero sits in a sonar field; a
  status capsule, a session-stats card and a single location card (flag,
  localized country, route path, IP and latency) replace the separate route,
  traffic and IP surfaces; a floating dock with a live status dot replaces
  the flat navigation bar. Layout and state contracts are unchanged.
  Review fixes (2026-10-02) preserve connected-core text contrast across the
  gradient and clock background in both themes, remove the unused radial
  animation after connection, and add regression tests for both contracts.
  The IP footer puts its label above the address so long addresses cannot
  consume the label's entire width on narrow cards.
- **Language handling.** An in-app English/Persian switch, with the first-run
  flow and the settings surface both wired to it.
- **The VPN session layer.** `vpn/session` and the tunnel core
  (`core/tunnel`) were restructured; the TUN bridge handshake, teardown and
  fragment handling were repaired, and the packet path had copies removed.
- **The component, engine, probe, log and moat packages** under
  `studio/cluvex/aether/` — 116 Kotlin source files were added and 39 removed
  relative to the fork point.

### Added

- **Chained transport modes.** Aether can now stack its own engine with
  Psiphon and with Tor. See `docs/CHAINING.md`, `docs/CHAIN_CORES.md` and
  `docs/TOR_BRIDGES.md`.
- **Overlay core build tooling.** `scripts/build-overlay-cores.sh`,
  `scripts/build-pt-transports.sh`, `scripts/fetch-tor-bridges.sh` and
  `scripts/fetch-psiphon-serverlist.sh`.
- **A centralised dependency catalogue** (`gradle/libs.versions.toml`), which
  upstream does not have. This project deliberately tracks pre-release
  dependency lines; the file documents the stable equivalents to fall back to.
- **Live traffic reporting** — a speed readout in the notification shade and an
  in-app data-usage meter, driven from the session rather than the UI.
- **Persian-language documentation** (`README.fa.md`).

### Removed

- **The committed Psiphon AAR** (`app/libs/psiphontunnel-2.0.39.aar`, 44 MB)
  and its `PROVENANCE.md`. Upstream's own audit (`F-8`, 1.3.0) flagged that
  binary as unverifiable — a hash of a committed file proves only that it has
  not changed, not where it came from, and the audit recommended building it
  from a pinned upstream commit instead. **This repository does exactly that:**
  `scripts/build-overlay-cores.sh` cross-compiles the Psiphon ConsoleClient
  from source with the NDK toolchain. No prebuilt AAR is committed here. This
  supersedes upstream's `app/libs/PROVENANCE.md`, which no longer applies.
- **Historical audit and stall documents** under `docs/`, whose findings are
  either fixed in this line or superseded by it. The current audit position is
  in `docs/SECURITY_AUDIT.md`.

## Engine patches

The vendored engine is upstream's, with the changes below carried on top of
it. Every file named here is listed in `PATCHED_FILES` in
`scripts/sync-core.sh`, which three-way merges it onto every new core instead
of overwriting it; `wg_experiments.rs` exists only here and is listed in
`APP_OWNED_FILES`, which carries it over verbatim. Until 2026-10-05 only
`cli.rs`, `prober.rs` and `wg_prober.rs` were listed, so a core sync would have
refused to run, or with `CORE_SYNC_ALLOW_DRIFT=1` deleted the rest. See
`docs/CORE_V2_3.md` for how each of them fares on core 2.3.0.

- `native/aether/aether/src/cli.rs`: `--precise` and `--ultra` are accepted
  as aliases of `--balanced` and `--ironclad` (1.4.6), with parser tests.
- `native/aether/aether/src/prober.rs` (2026-09-24, `fix/masque-scan`): a
  MASQUE scan that aims for a number of gateways stops waiting once no new
  one has answered for its quiet window after the first, instead of only after
  reaching the target; and the DNS-over-HTTPS ranges are probed after every
  other candidate instead of in every round of the sweep. See
  `docs/MASQUE_SCAN.md`.
- `native/aether/aether/src/prober.rs` (2026-09-26,
  `fix/masque-scan-congestion`): failed scan candidates are tallied by reason
  and a scan that ends without a gateway logs the most common reasons at
  warn, instead of every failure going to trace only.
- `native/aether/aether/src/tls.rs` (2026-09-26,
  `fix/masque-scan-congestion`): the MASQUE QUIC congestion controller is
  CUBIC (quiche's default, as upstream) unless `AETHER_QUIC_CC=bbr2` or
  `reno` asks otherwise. PR #106 had made BBR2 (gcongestion) the default for
  the scan and the tunnel without a field test, and MASQUE scans stopped
  finding gateways after it. Also (2026-09-25) ECH helpers that work on any
  `SslRef`, for the API route below.
- `native/aether/aether/src/account.rs` and `apifront.rs` (2026-09-25): an
  ECH route to the WARP API first, so the API name never shows in plaintext;
  the Android system trust store for the API handshake (first CA directory
  with roots only, user-installed CAs not trusted by default, roots the user
  disabled honoured); no ECH key set cached unless it can be used; a
  registration saved before enrolment; a route cut on the wire, including a
  silently dropped handshake, handed over at once instead of retried; and no
  short connect timeout through an upstream proxy. `api.rs` follows them.
  Upstream 2.3.0 deletes `apifront.rs`.
- `native/aether/aether/src/netstack.rs` (2026-09-26): TCP congestion control
  in smoltcp (CUBIC by default, `AETHER_TCP_CC`), no outbound drops under
  pressure, event-driven backpressure, fewer allocations. `Cargo.toml` turns
  on smoltcp's `socket-tcp-cubic` and `socket-tcp-reno` for it.
- `native/aether/aether/src/sysprofile.rs` (2026-09-26): netstack TCP receive
  windows sized for the link (2 / 4 / 8 MiB, capped by RAM) rather than the
  CPU tier.
- `native/aether/aether/src/quic.rs` (2026-09-26, 2026-09-28): batched
  `sendmmsg`, lossless inbound, no per-packet copies; `AETHER_MASQUE_SNI`
  honoured on the HTTP/3 carrier.
- `native/aether/aether/src/masque_h2.rs` (2026-09-26 to 2026-09-28):
  backpressure instead of dropped inbound datagrams, zero-copy batches; the
  `SpoofingStream` ClientHello shaping (`AETHER_MASQUE_H2_SPOOF`) and
  `AETHER_MASQUE_SNI` on the HTTP/2 carrier.
- `native/aether/aether/src/socks.rs` (2026-09-26): a TTL-bounded DNS cache,
  resolvers asked in parallel, UDP associate that does not block on a name,
  cheaper relays.
- `native/aether/aether/src/wireguard.rs` (2026-09-26, 2026-10-04): the
  boringtun queue drained, the `Tunn` lock never held across an await, socket
  buffers from the perf profile; the WireGuard experiments wired into the
  tunnel tasks and endpoint verification.
- `native/aether/aether/src/wg_experiments.rs` (2026-10-04, app-owned):
  opt-in ArcSwap transport rotation and bounded plaintext padding
  (`AETHER_WG_PORT_HOP`, `AETHER_WG_DATA_PADDING`). `Cargo.toml` adds
  `arc-swap` for it.

## Core sync tooling (2026-10-05)

- `scripts/sync-core.sh` lists every engine patch above, carries app-owned
  files, moves quiche's workspace manifest (`quiche/Cargo.toml`, which pins
  boring) with the core, and keeps the conflicted merge of any patch it could
  not rebase under `native/aether/.core-conflicts/`. `scripts/test-core-sync.sh`
  covers it.
- `scripts/build-natives.sh` links libc++ statically into `libaether.so`, which
  boring-sys 5 (core 2.3.0) needs, and fails the build if the engine still
  depends on `libc++_shared.so`.
- `Protocol.GOOL` sends `--gool-classic` from core 2.3.0 on, where `--gool`
  means WireGuard inside MASQUE.

## What this repository inherits unchanged

- The Aether engine, vendored under `native/aether` and pinned by
  `native/aether/CORE_VERSION` (engine **2.1.0** as of 2026-09-24; 2.3.0 is
  reviewed in `docs/CORE_V2_3.md`). `scripts/sync-core.sh` moves it; apart
  from the engine patches listed above it is not hand-edited.
- hev-socks5-tunnel, for TUN-to-SOCKS forwarding.
- Tor, via the Tor Project / Guardian Project build.
- The AGPL-3.0 license and the copyright notices of the Aether Mobile
  contributors.

## Compliance notes

- This repository remains **AGPL-3.0-or-later**. See `LICENSE` and
  `THIRD_PARTY_NOTICES.md`.
- The engine's own `LICENSE` is vendored alongside it at
  `native/aether/LICENSE`; do not remove it.
- If you distribute a build of this app, or run a modified version as a
  network service, the AGPL source-offer obligations apply. Serving the
  corresponding source for your build is your responsibility.

## Reporting

Corrections to this record are welcome as an issue or pull request against
this repository.
