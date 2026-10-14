# Xray hop

The user's own Xray server (VLESS / VMess / Trojan / Shadowsocks / Hysteria2 /
WireGuard / SOCKS over TCP, WS, gRPC, XHTTP, HTTPUpgrade, mKCP, Hysteria or
UDP, with TLS or REALITY), either **alone** (`XRAY`) or as a hop of the chain.
Stacked, Xray **connects first**: the user's server is what the network sees,
and WARP, Psiphon or Tor ride inside it.

| Mode | Path (device first) |
|---|---|
| `XRAY` | Xray -> internet |
| `AETHER_OVER_XRAY` | Aether -> Xray -> internet |
| `PSIPHON_OVER_XRAY` | Psiphon -> Xray -> internet |
| `PSIPHON_OVER_AETHER_OVER_XRAY` | Psiphon -> Aether -> Xray -> internet |
| `TOR_OVER_XRAY` | Tor -> Xray -> internet |
| `TOR_OVER_AETHER_OVER_XRAY` | Tor -> Aether -> Xray -> internet |

`XRAY` is listed with the other single-core modes on the Chain page and in the
quick route picker. Its SOCKS inbound (`127.0.0.1:1849`) is the chain entry:
it speaks UDP ASSOCIATE and answers port-53 UDP itself (see below), so it needs
no `SocksFront` and no engine. `ChainMode.fromStored` also reads `XRAY_ONLY`,
`PURE_XRAY`, `XRAY_ALONE` and `V2RAY` as `XRAY`.

The earlier Xray-on-top mode names (`XRAY_OVER_AETHER`, `XRAY_OVER_PSIPHON`,
`XRAY_OVER_PSIPHON_OVER_AETHER`, `TOR_OVER_XRAY_OVER_AETHER`) are still read by
`ChainMode.fromStored` and migrate to the matching Xray-first mode.

## Config

Pasted on the Chain page, under the selected Xray mode: a share link
(`vless://`, `vmess://` in both spellings, `trojan://`, `ss://` in SIP002,
percent-encoded 2022 and legacy forms, `hy2://` / `hysteria2://`, `wireguard://`
/ `wg://`, `socks://` / `socks5://`), a base64 subscription body, or Xray JSON
(`//` and `/* */` comments allowed; one outbound, an array of them, or a full
config). **The first entry this app can run is used**: every line of a paste or
subscription, and every proxy outbound of a JSON config, is tried in order.
Parsed by `core/XrayConfig.kt` (`XrayLink`), pure Kotlin, pinned by
`XrayConfigTest`. Refused up front: REALITY without a public key, Shadowsocks
with a SIP003 plugin, Hysteria2 Salamander `obfs` or port hopping, anything
that is not a proxy outbound, and removed transports (next section).

Share-link extras that are carried: `pcs` / `pinSHA256`
(`pinnedPeerCertSha256`), `vcn` (`verifyPeerCertByName`), `ech`
(`echConfigList`), `pqv` (REALITY `mldsa65Verify`), and XHTTP's `extra`.

The config is stored **unencrypted**, in its own app-private SharedPreferences
file (`aether_xray`, `data/XrayConfigStore.kt`). It used to be sealed with the
Keystore key of `SecretStore`; a value sealed by an earlier build is read once,
moved over and the sealed copy removed. It is still never in the profile, so it
is never exported, never in the service Intent and never in DataStore. The
field is plain text (no masking, no password keyboard) and multi-line, bounded
in height; what was understood is shown as `XrayOutbound.summary`.

## What Xray-core v26.9.30 refuses

Xray refuses the WHOLE config over any one of these, so `XrayLink` and
`XrayConfig` deal with them before Xray ever sees the text (each pinned by
`XrayConfigTest`):

| Old config | v26.9.30 | What the app does |
|---|---|---|
| `allowInsecure: true` / `allowInsecure=1` | removed feature, hard error | never written; dropped from pasted JSON. Use `pcs=<sha256>` / `pinSHA256=<sha256>` (and `vcn=`) in the link for a self-signed server. |
| `type=h2` / `http` / `h3`, `quic` | removed (migrated to XHTTP) | refused: "not a config Aether can run" |
| VLESS (no VLESS Encryption) or Trojan without TLS / REALITY | refused unless the server is a private address | marked `relayed` and pointed at the on-device `dokodemo-door` relay on `127.0.0.1:1850` (`TunnelConfig.XRAY_RELAY_PORT`), whose `freedom` outbound reaches the real server unchanged |
| `proxySettings` | removed (migrated to `sockopt.dialerProxy`) | dropped; the chaining is the app's |

mKCP's `seed` and `header` are still accepted by the parser but no longer do
anything in the core (obfuscation moved to Finalmask), so an mKCP server that
relies on a seed needs a JSON outbound with its Finalmask settings.

## Chaining

`XrayConfig.build` wraps the user's outbound into a config of its own: one
SOCKS inbound on `127.0.0.1:1849` (`TunnelConfig.XRAY_SOCKS_PORT`, not
v2rayNG's 10808/10809), the proxy outbound, and - when a plaintext VLESS /
Trojan config to a public server needs it - the on-device relay on
`127.0.0.1:1850` (`TunnelConfig.XRAY_RELAY_PORT`). Any `dialerProxy` /
`proxySettings` / tag in the paste is dropped: the paste never decides the
chaining.

`XRAY` alone has no engine and nothing on top: `ChainStack.start` brings Xray
up and publishes its inbound as the entry, after the usual bounded DNS warm-up.

When Xray sits UNDER the Aether engine (`ChainMode.hopsBeforeEngine`), the
session runs in two stages (`ChainStack.startBeforeEngine` and
`ChainStack.start`): Xray comes up first, the engine is launched with
`AETHER_UPSTREAM=socks5://127.0.0.1:1849` so both its TCP and its UDP (WARP's
MASQUE / WireGuard) dial through Xray, and then any hops above the engine
(Psiphon via `UpstreamProxyUrl`, Tor via `Socks5Proxy`) start on top of it.

**Xray does not gate the next hop.** As soon as Xray accepted the config and
its inbound is bound, the hop above it is started and does its own handshake
through Xray, in parallel with Xray's handshake with the user's server. Some
configs never answer the readiness probe below but do carry the next hop's
traffic, and they only come alive this way.

## DNS and UDP

Xray's SOCKS inbound speaks UDP ASSOCIATE, which is what lets the Aether engine
send WARP's UDP through it (`AETHER_UPSTREAM`), and a routing rule sends
inbound UDP to port 53 to a `dns` outbound, answered by Xray's own resolver
over TCP (`tcp://1.1.1.1:53`, `tcp://8.8.8.8:53`) through the proxy. That is
also what lets `XRAY` alone be the entry without a front.

## Readiness

Xray counts as up once the config was accepted and its inbound is bound
(`XRAY_PORT_WAIT_MS`, 15 s). Nothing waits for more than that.

The round-trip check (a DNS-over-TCP query through the inbound that has to get
an answer back, `Readiness.awaitRoundTrip`) still runs, but **in the background
and non-fatal** (`ChainStack.watchXrayHandshake`, budget `XRAY_READY_WAIT_MS`,
60 s). It only logs whether the user's server answered and, when it did not,
marks the Xray row as `unverified`. It never stops a core or fails the session.

Failures map to `ChainFailure.XRAY_MISSING` (no or unstartable binary),
`XRAY_CONFIG` (no / unreadable config, or Xray refused it) and
`XRAY_TIMEOUT` (running, but the inbound never bound), each with its own
message. A server that is reachable-but-wrong (bad UUID, REALITY key, SNI) now
shows up as the next hop's own timeout (engine, `PSIPHON_TIMEOUT`,
`TOR_TIMEOUT`), with the background check's warning in the log; with `XRAY`
alone it shows up as an `unverified` Xray row and the watchdog's failed probes.

## Building the core

```bash
export ANDROID_NDK_HOME=<ndk>
GOTOOLCHAIN=auto bash scripts/build-xray-core.sh      # both ABIs, v26.9.30
XRAY_REF=v26.10.10 bash scripts/build-xray-core.sh   # another tag / branch / SHA
```

XTLS/Xray-core `./main`, pinned by tag (default `v26.9.30`, which needs
**Go 1.27**), CGO through the NDK clang, `-checklinkname=0` (as upstream builds
for Android), reproducible flags, 16 KB page alignment, ELF-verified per ABI,
written to `app/src/main/jniLibs/<abi>/libxray.so`. Optional: without it the
Xray modes are disabled on the Chain page with the core named.

Every Xray-core release since v26.3.27 is marked pre-release upstream; the pin
is moved deliberately, after reading what the new tag stopped accepting.

### CI

`.github/workflows/build.yml` builds it in the natives job:

1. The native cache key is `jnilibs-v5-...` and hashes
   `scripts/build-xray-core.sh`, so moving `XRAY_REF` is a rebuild, not a stale
   cache hit. "Decide what still has to be built" tracks `xray` next to
   `psiphon` / `tor`, and a cache without `libxray.so` rebuilds.
2. `XRAY_GO_VERSION` (1.27) is set up AFTER the Psiphon build, so Psiphon keeps
   the `GO_VERSION` it is proven on. "Build the Xray core" (`id: xray_core`)
   is `continue-on-error`, with `GOTOOLCHAIN: auto`.
3. `libxray.so` is in the three chain-core checks ("Verify the built payload",
   "Check the native payload", "Verify native libraries inside the APKs"): a
   warning on a branch, fatal on a `v*` tag (rule 7).

## Licence

Xray-core is MPL-2.0. It runs as a separate executable; the build fetches its
source at the pinned tag. See `THIRD_PARTY_NOTICES.md`.

## Not yet verified

Nothing here has been run on a device yet. Before a release: `XRAY` alone and
each of the five stacked modes with a VLESS-REALITY, a VMess-WS-TLS and a
Trojan server; a config that fails the round-trip probe but carries WARP /
Psiphon / Tor (must now connect); a wrong UUID (must end in the next hop's
timeout with an `unverified` Xray row, not a false "connected"); no config
(`XRAY_CONFIG`); a build without `libxray.so` (cards disabled); an upgrade
from a build that sealed the config (must still find it, and connect).
