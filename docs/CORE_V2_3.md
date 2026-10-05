# Engine core 2.3.0 review

Reviewed 2026-10-05 against [CluvexStudio/Aether v2.3.0](https://github.com/CluvexStudio/Aether/releases/tag/v2.3.0)
(published 2026-10-04, compare `v2.1.0...v2.3.0`). The vendored core is 2.1.0.
2.2.0 was tagged the same day and never published as a release; everything in
it is part of 2.3.0.

This is the review behind moving `CORE_TARGET_PIN` in
`.github/workflows/core-sync.yml` from 2.1.0 to 2.3.0. That workflow edit is
in the description of the pull request that added this file, not in its
commits. Until the pin moves, run Core sync by hand with its `target` input set
to `2.3.0`. Neither step vendors the engine by itself: the sync run does that,
and this document says what that run will and will not do on its own.

## What changed upstream

**TLS and the WARP API (PR #141, patterniha).**

- Every TLS handshake (MASQUE over HTTP/2 and HTTP/3, the WARP API calls, the
  DoH lookup of the ECH key) shares one fingerprint, `tls::Fingerprint`, built
  on BoringSSL. Chrome's TLS 1.2 suites, extension order randomised per
  handshake, GREASE on by default (`--disable-grease` turns it off).
- `apifront.rs` is gone, and so is the camouflaged route (random edge
  addresses, split ClientHellos, the `split-tls12` / `split-tls13` /
  `plain-tls13` fingerprints, every `--get-warp-key-*` flag). Every API call
  takes one path: `--enroll-address` (default `api.cloudflareclient.com`,
  optional port), through `--upstream` when one is set.
- The API calls and the DoH lookups run on the new `https.rs` (tokio-boring
  plus h2) instead of reqwest/rustls. They dial with Happy Eyeballs (RFC 8305).
- ECH is offered on the HTTP/2 carrier as well, for the scan, the gateway checks
  and the tunnel, with one session key shared by both carriers. A session that
  asks for ECH and has no usable key **stops** instead of sending the server
  name in the clear. `--ech-dns` and `--ech-domain` choose the resolver and the
  domain, and DoH URLs take `@address=` and `@sni=`.
- `--tls-ciphers` sets the TLS 1.2 suites. A cipher or group name BoringSSL
  does not know now **stops the core at start**, and that includes
  `--tls-groups`, which this app emits.
- `boring`, `boring-sys` and `tokio-boring` moved from 4.22 to **5.2**
  (BoringSSL 2023-05 to 2026-05). `--tls-groups` takes `X25519MLKEM768`.
- `--register <masque|wg|gool|mim|all>` registers identities and exits.

**Release changes (CluvexStudio).**

- `--gool` is now **WireGuard carried inside the MASQUE tunnel**, registered
  through it, for a foreign exit. The WireGuard-in-WireGuard gool is
  `--gool-classic`. `--gool-peer` is new.
- The TLS certificate of the WARP API is verified **only with `--tls-verify`**.
  It is off by default.
- The default HTTP/2 fragment is now 8 to 16 byte chunks with no SNI split.
- Psiphon finds an Android CA store by itself. This app does not use the
  engine's Psiphon.
- SOCKS5 UDP ASSOCIATE resolves names through the tunnel.
- The Android release binary links libc++ statically
  (`BORING_BSSL_RUST_CPPLIB_<triple>=static:-bundle=c++`). boring-sys 5 links
  `libc++_shared.so` otherwise.
- `main.rs` raises `recursion_limit` to 256 for Rust 1.99 with the tor
  feature. `rust-version` stays 1.98, so `MIN_RUST` in core-sync.yml can stay
  1.98.0.

## What this means for the app

| Area | Impact | Handled here |
|---|---|---|
| Gool | `Protocol.GOOL` sent `--gool`. On 2.3.0 that silently becomes WireGuard-in-MASQUE. | **Yes.** `Profile.toArgs` sends `--gool-classic` from core 2.3.0 on, `--gool` before (`GoolFlagTest`). Offering the new MASQUE gool is a product decision for later. |
| libc++ | With boring 5.2, `libaether.so` would need `libc++_shared.so`, which the APK does not ship. The engine would fail on launch while CI stayed green. | **Yes.** `build-natives.sh` sets the static libc++ variable and fails the build if `libaether.so` still lists `libc++_shared.so`. |
| quiche | `sync-core.sh` never moved `native/aether/quiche`. quiche's workspace pins `boring = 4.22`, the engine would pin 5.2, and two `boring-sys` copies do not link. | **Yes.** `quiche/Cargo.toml` is `UPSTREAM_EXTRA_FILES`. The rest of the vendored quiche already matches upstream: `quiche/src` and the crate manifests are byte-identical, and the remaining differences are symlinks the vendoring resolved into files. |
| Drift guard | Eleven patched engine files were missing from `PATCHED_FILES`, plus app-owned `wg_experiments.rs`. The sync would have refused to run (exit 1). With `allow_drift` it would have deleted every one of those patches. | **Yes.** All of them are listed now; `wg_experiments.rs` is `APP_OWNED_FILES`. |
| WARP API trust | The app's `apifront.rs` work (Android trust store, user CAs not trusted, removed roots honoured) goes away with the file. 2.3.0 does not check the API certificate unless `--tls-verify` is passed. | **No. Decide before merging the sync PR.** Sending `--tls-verify` is the safer default, but first confirm the engine's BoringSSL finds a CA store on Android. Without one every registration fails. |
| `--tls-groups` | A typo that the app's charset check lets through is now fatal at start. | No. Low risk, worth a validation list later. |
| ECH on MASQUE | `sendsEch` assumes the MASQUE endpoint refuses ECH. 2.3.0 offers ECH on both carriers, and upstream measured the edge accepting it. | No. That KDoc is stale after 2.3.0. ECH on MASQUE is an opportunity to field-test, not a regression. |
| Spoofing env vars | `AETHER_MASQUE_SNI` and `AETHER_MASQUE_H2_SPOOF` are read only by this app's patches in `quic.rs` and `masque_h2.rs`. If those patches drop, the settings are silently ignored. | Flagged by the sync (see below). |

## What the 2.3.0 sync will do with each app patch

Prediction from comparing the vendored tree, upstream 2.1.0 and upstream 2.3.0
by blob hash. The real run decides; its log and `native/aether/.core-conflicts/`
have the final word.

**Carried over as they are** (upstream did not touch the file between 2.1.0
and 2.3.0):

- `netstack.rs`
- `sysprofile.rs`
- `wireguard.rs`
- `wg_experiments.rs` (app-owned)
- `wg_prober.rs` (no patch at the moment)

**Dropped, copy kept as `*.orphan`:**

- `apifront.rs`, because upstream deleted it. Its job moves to upstream's
  `https.rs`; see "WARP API trust" above for what that loses.

**Three-way merged, outcome only known from the run.** The heavy upstream
rewrites (`tls.rs` grew from 8.5 KB to 54 KB; `account.rs`, `api.rs`,
`masque_h2.rs` and `quic.rs` were reworked for ECH and `https.rs`) make
conflicts likely in:

- `tls.rs`: CUBIC default (`AETHER_QUIC_CC`) and ECH helpers.
- `account.rs` and `api.rs`: registration kept before enrolling, cut-on-the-wire
  detection. Most of this talks to `apifront`, which no longer exists.
- `masque_h2.rs`: backpressure, `SpoofingStream`, custom SNI.
- `quic.rs`: sendmmsg batching, lossless inbound, custom SNI.

Likely clean:

- `Cargo.toml`: arc-swap, smoltcp CC features.
- `cli.rs`: aliases.
- `prober.rs`: quiet window, DoH ranges last, failure tally.
- `socks.rs`: DNS cache, parallel resolvers, UDP associate.

A conflicted file is replaced by pure upstream 2.3.0 so the tree compiles, and
its merge with markers is kept as `.core-conflicts/<path>.merge`. **A clean
textual merge does not mean the code compiles.** Two known risks:

- `quic.rs` can merge cleanly while `masque_h2.rs` falls back to upstream. Then
  `quic.rs` calls `spoof::resolve_sni`, which this repo's `masque_h2.rs` used to
  provide, and the build fails.
- Upstream's new code in `lib.rs` (gool inside MASQUE) calls `wireguard.rs`
  with upstream's signatures, and this repo's `wireguard.rs` changed some of
  them for `wg_experiments`.

The "Prove the new engine compiles" step of Core sync catches both. When it
fails, no PR is opened. Unless the workflow also uploads
`native/aether/.core-conflicts/` as an artifact (the change in the PR
description), the conflict copies die with the runner, which is one more reason
to run this sync locally.

## How to run it

1. Merge the pull request that added this file, and apply the core-sync.yml
   change from its description (the pin, plus the artifact upload).
2. Run it where a compiler is, since a red CI run opens no PR:
   `CORE_TARGET=2.3.0 bash scripts/sync-core.sh`, then
   `bash scripts/fetch-natives.sh && bash scripts/build-natives.sh aether`.
   Or Actions > Core sync > Run workflow with target `2.3.0`.
3. Re-apply every `.core-conflicts/*.merge` / `*.orphan` you want to keep,
   rebuild, delete `.core-conflicts/`, and commit `native/aether` with both
   READMEs.
4. Before merging, decide on `--tls-verify` and field-test the tunnel on a real
   link: MASQUE H2 and H3 with a custom SNI and spoofing, WireGuard with the
   experiments, gool, mim, and a registration from a fresh install.
