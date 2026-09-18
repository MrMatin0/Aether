# Third-party notices

Aether (this repository) is distributed under **AGPL-3.0-or-later**. See
`LICENSE`. This file records the third-party components that a build of this
app includes or links against, and the obligations that come with them.

Do not remove upstream copyright or license files when redistributing
binaries or modified source. AGPL source-offer obligations apply to
distributed builds and to modified versions run as a network service.

## Components included in the app

| Component | License | How it reaches the build |
|---|---|---|
| [Aether engine](https://github.com/CluvexStudio/Aether) | AGPL-3.0 | Vendored at `native/aether`, pinned by `native/aether/CORE_VERSION`. License vendored alongside it. |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT, with an Apache-2.0 build script | TUN-to-SOCKS forwarding. The `LICENSE` is MIT; `Android.mk` / `Application.mk` are derived from the Android Open Source Project and carry Apache-2.0. |
| [Psiphon Tunnel Core](https://github.com/Psiphon-Labs/psiphon-tunnel-core) | **GPL-3.0** | **Built from source** by `scripts/build-overlay-cores.sh`, not taken as a prebuilt AAR. See the provenance note below. |
| [Tor](https://www.torproject.org/) / [tor-android](https://github.com/guardianproject/tor-android) | BSD-3-Clause | Consumed from the Tor Project / Guardian Project published build, or built from source with `TOR_FROM_SOURCE=1`. The Guardian Project's `LICENSE` carries the 3-clause BSD text for Orbot and for Tor. |
| [Vazirmatn](https://github.com/rastikerdar/vazirmatn) | SIL OFL 1.1 | Persian and Latin typography. Fetched by `scripts/fetch-fonts.sh`. |
| [quiche](https://github.com/cloudflare/quiche) | BSD-2-Clause | Vendored under `native/aether/quiche`, used by the engine. License vendored with it. |

Psiphon's bootstrap server list is signed and verified at fetch time; see
`scripts/fetch-psiphon-serverlist.sh` and the release gate in
`.github/workflows/build.yml`.

### License compatibility

This app is AGPL-3.0-or-later and links Psiphon, which is GPL-3.0. That
combination is permitted: section 13 of both licenses allows linking the two,
and the combined work may be conveyed under AGPL-3.0. Nothing here requires
releasing the app under GPL-3.0 instead.

Note that Psiphon is GPL, not AGPL. The AGPL's network-use clause (section 13)
comes from the app and the engine, not from Psiphon.

## Provenance of the Psiphon component

Earlier releases of the upstream project committed a prebuilt
`psiphontunnel-2.0.39.aar` under `app/libs/`. Upstream's own audit (`F-8`,
1.3.0) recorded that the file was not verifiable: the checksum proved only that
the committed file had not changed, not that it was the untampered output of
the upstream project. The audit recommended building it from a pinned upstream
commit instead.

This repository does that. `scripts/build-overlay-cores.sh` cross-compiles the
Psiphon ConsoleClient from source with the Android NDK toolchain, so the AAR is
not a committed binary here. Recipients can inspect the build path rather than
trust a hash of an unknown origin.

## Names and endorsement

Project and product names belong to their respective owners. Including a
component here does not imply that its authors endorse or support this build.
