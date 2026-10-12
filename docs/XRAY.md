# Xray hop

The user's own Xray server (VLESS / VMess / Trojan / Shadowsocks over TCP, WS,
gRPC, XHTTP, HTTPUpgrade, mKCP or HTTP/2, with TLS or REALITY) as a hop of the
chain. Xray is **never offered alone**: a bare Xray mode would be a plain v2ray
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
Shadowsocks with a SIP003 plugin, anything that is not a proxy outbound.

The config is a credential. It is stored only in the Keystore-sealed
`SecretStore` (`data/XrayConfigStore.kt`), never in the profile, so it is
never exported, never in the service Intent and never in DataStore. The field
is masked and uses a password keyboard; what was understood is shown as
`XrayOutbound.summary`, which carries no UUID, password or key.

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
GOTOOLCHAIN=auto bash scripts/build-xray-core.sh      # both ABIs
XRAY_REF=v26.9.30 bash scripts/build-xray-core.sh    # another tag / branch / SHA
```

XTLS/Xray-core `./main`, pinned by tag (default `v26.7.28`), CGO through the
NDK clang, `-checklinkname=0` (as upstream builds for Android), reproducible
flags, 16 KB page alignment, ELF-verified per ABI, written to
`app/src/main/jniLibs/<abi>/libxray.so`. Optional: without it the Xray modes
are disabled on the Chain page with the core named.

### CI

`.github/workflows/build.yml` needs the following, which has to be committed
by someone whose token has the `workflow` scope:

1. Natives job: add `'scripts/build-xray-core.sh'` to the cache key's
   `hashFiles(...)` (and bump `jnilibs-v4` to `jnilibs-v5`); in "Decide what
   still has to be built" track `xray` like `psiphon` / `tor` and include it
   in `chain`.
2. Run "Set up Go" when `psiphon != 'true' || xray != 'true'`, then add a step
   `Build the Xray core` (`id: xray_core`, `if: steps.payload.outputs.xray != 'true'`,
   `continue-on-error: true`, `run: bash scripts/build-xray-core.sh`,
   env `ANDROID_API` and `GOTOOLCHAIN: auto`).
3. Add `libxray.so` to the three chain-core loops ("Verify the built
   payload", "Check the native payload", "Verify native libraries inside the
   APKs"), so it is a warning on a branch and fatal on a `v*` tag (rule 7).

## Licence

Xray-core is MPL-2.0. It runs as a separate executable; the build fetches its
source at the pinned tag. See `THIRD_PARTY_NOTICES.md`.

## Not yet verified

Nothing here has been run on a device yet. Before a release: each of the five
modes with a VLESS-REALITY, a VMess-WS-TLS and a Trojan server; a wrong UUID
(must end in `XRAY_TIMEOUT`, not a false "connected"); no config
(`XRAY_CONFIG`); a build without `libxray.so` (cards disabled).
