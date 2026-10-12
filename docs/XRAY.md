# Xray hop

The user's own Xray server (VLESS / VMess / Trojan / Shadowsocks over TCP, WS,
gRPC, XHTTP, HTTPUpgrade or mKCP, with TLS or REALITY) as a hop of the chain.
Xray is **never offered alone**: a bare Xray mode would be a plain v2ray
client, which is not what this app adds.

| Mode | Path (device first) |
|---|---|
| `XRAY_OVER_AETHER` | Xray -> Aether -> internet |
| `XRAY_OVER_PSIPHON` | Xray -> Psiphon -> internet |
| `XRAY_OVER_PSIPHON_OVER_AETHER` | Xray -> Psiphon -> Aether -> internet |
| `TOR_OVER_XRAY` | Tor -> Xray -> internet |
| `TOR_OVER_XRAY_OVER_AETHER` | Tor -> Xray -> Aether -> internet |

## Config

Pasted on the Chain page, under the selected Xray mode: a share link
(`vless://`, `vmess://` in both spellings, `trojan://`, `ss://` in SIP002,
percent-encoded 2022 and legacy forms), a base64 subscription body (its first
link is used), or Xray JSON (one outbound, or a full config whose first proxy
outbound is used). Parsed by `core/XrayConfig.kt` (`XrayLink`), pure Kotlin,
pinned by `XrayConfigTest`. Refused up front: REALITY without a public key,
Shadowsocks with a SIP003 plugin, anything that is not a proxy outbound, and
everything the pinned core itself refuses (next section).

Share-link extras that are carried: `pcs` (`pinnedPeerCertSha256`), `vcn`
(`verifyPeerCertByName`), `ech` (`echConfigList`), `pqv` (REALITY
`mldsa65Verify`), and XHTTP's `extra`.

The config is a credential. It is stored only in the Keystore-sealed
`SecretStore` (`data/XrayConfigStore.kt`), never in the profile, so it is
never exported, never in the service Intent and never in DataStore. The field
is masked and uses a password keyboard; what was understood is shown as
`XrayOutbound.summary`, which carries no UUID, password or key.

## What Xray-core v26.9.30 refuses

Xray refuses the WHOLE config over any one of these, so `XrayLink` deals with
them before Xray ever sees the text (each pinned by `XrayConfigTest`):

| Old config | v26.9.30 | What the app does |
|---|---|---|
| `allowInsecure: true` / `allowInsecure=1` | removed feature, hard error | never written; dropped from pasted JSON. Use `pcs=<sha256>` (and `vcn=`) in the link for a self-signed server. |
| `type=h2` / `http` / `h3`, `quic` | removed (migrated to XHTTP) | refused: "not a config Aether can run" |
| VLESS (no VLESS Encryption) or Trojan without TLS / REALITY | refused unless the server is a private address | refused, except for loopback / RFC 1918 / link-local / ULA / `localhost` servers |
| `proxySettings` | removed (migrated to `sockopt.dialerProxy`) | dropped; the chaining is the app's |

mKCP's `seed` and `header` are still accepted by the parser but no longer do
anything in the core (obfuscation moved to Finalmask), so an mKCP server that
relies on a seed needs a JSON outbound with its Finalmask settings.

## Chaining

`XrayConfig.build` wraps the user's outbound into a config of its own: one
SOCKS inbound on `127.0.0.1:1849` (`TunnelConfig.XRAY_SOCKS_PORT`, not
v2rayNG's 10808/10809), the proxy outbound, and - when a hop is underneath -
`streamSettings.sockopt.dialerProxy = "chain-up"`, a SOCKS outbound on that
hop's port. The whole handshake to the user's server therefore rides WARP
and/or Psiphon. Any `dialerProxy` / `proxySettings` / tag in the paste is
dropped: the paste never decides the chaining.

`ChainStack` starts hops in `ChainMode.hops` order, so every core dials
through a hop that is already proven ready.

## DNS and UDP

An Xray entry needs no `SocksFront`: its inbound speaks UDP ASSOCIATE, and a
routing rule sends inbound UDP to port 53 to a `dns` outbound, answered by
Xray's own resolver over TCP (`tcp://1.1.1.1`, `tcp://8.8.8.8`) through the
proxy. That works over Psiphon too, which carries no UDP. Other UDP is carried
when the server protocol carries it (VLESS / VMess / Trojan inside their TCP
stream); Shadowsocks UDP needs a UDP-capable hop underneath (Aether, not
Psiphon).

## Readiness

Xray's SOCKS inbound acknowledges a CONNECT before its outbound has reached
anything, so a CONNECT probe proves nothing. The hop is READY only when a
DNS-over-TCP query through the inbound gets an answer back
(`Readiness.awaitRoundTrip`), i.e. when the handshake with the user's server
succeeded through the chain. Budgets: `XRAY_PORT_WAIT_MS` (15 s) for the
inbound to bind, `XRAY_READY_WAIT_MS` (60 s) for the first answer.

Failures map to `ChainFailure.XRAY_MISSING` (no or unstartable binary),
`XRAY_CONFIG` (no / unreadable config, or Xray refused it) and
`XRAY_TIMEOUT` (running, but nothing came back), each with its own message.

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

Nothing here has been run on a device yet. Before a release: each of the five
modes with a VLESS-REALITY, a VMess-WS-TLS and a Trojan server; a wrong UUID
(must end in `XRAY_TIMEOUT`, not a false "connected"); no config
(`XRAY_CONFIG`); a build without `libxray.so` (cards disabled).
