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

## What this repository inherits unchanged

- The Aether engine, vendored under `native/aether` and pinned by
  `native/aether/CORE_VERSION`. It is not hand-edited; `scripts/sync-core.sh`
  moves it. As of this writing it is pinned to engine **2.0.0**.
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
