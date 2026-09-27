# Psiphon CDN fronting

Aether's Psiphon hop can tunnel through big CDN edges (Akamai, Fastly), ported
from [shirokhorshid-android](https://github.com/shirokhorshid/shirokhorshid-android).
This page is what the Chain page options do, where the code lives, and what can
go wrong.

## What it is

Fronted meek wraps the Psiphon tunnel in HTTPS to a CDN edge. The IP and the
TLS SNI a censor sees belong to the CDN, while the Host header inside the
encrypted stream routes the request to a Psiphon meek server behind that CDN.
Blocking it means blocking the edge itself, and with it every ordinary site the
edge serves.

Upstream Psiphon only fronts through the providers and addresses its server
entries carry. shirokhorshid's fork of psiphon-tunnel-core adds:

- `FRONTED-MEEK-CDN-OSSH`, `FRONTED-MEEK-CDN-HTTP-OSSH`, `FRONTED-MEEK-CDN-QUIC-OSSH`
- `FrontedMeekDialOverrides`: per provider / dial-address rules that replace the
  dial address, SNI, verify names, ALPN and TLS profile of a fronted dial
- `FrontedMeekCDNScanSpec` / `FrontedMeekCDNScanUseBuiltInSpec`: probe a set of
  candidate edge IPs and SNIs before dialling, and use the ones that answer

## Build

`scripts/build-overlay-cores.sh` now builds `libpsiphon.so` from
`shirokhorshid/psiphon-tunnel-core` pinned at commit
`df55f0ac0eed3d6846501744b7f086a42fcadfaf` (branch `shirokhorshid`). The fork
keeps the upstream module path and `ConsoleClient`, so nothing else in the build
changes. To go back to plain upstream:

```sh
PSIPHON_REPO=Psiphon-Labs/psiphon-tunnel-core PSIPHON_REF=staging-client \
  scripts/build-overlay-cores.sh psiphon
```

With plain upstream keep the protocol on **Automatic**: upstream rejects the
`FRONTED-MEEK-CDN-*` names in `LimitTunnelProtocols` and would refuse the
config. The other CDN keys are simply ignored.

Bumping the pin is a reviewed diff on purpose: the fork is a third party
moving branch, and at the pinned commit it is a few months behind upstream
`staging-client`.

## Settings (Chain page, under the selected Psiphon mode)

| Setting | Effect on the Psiphon config |
|---|---|
| Protocol: **Automatic** (default) | Edge overrides and scan spec are added; `LimitTunnelProtocols` and tactics are left as issued. Same behaviour as before for everything else. |
| Protocol: **Direct protocols** | `LimitTunnelProtocols` = the ordinary set (SSH, OSSH, TLS-OSSH, unfronted meek, QUIC-OSSH, Shadowsocks, fronted meek incl. CDN variants), `DisableTactics` = true. |
| Protocol: **CDN fronting only** | `LimitTunnelProtocols` = the three `FRONTED-MEEK-CDN-*` protocols, `DisableTactics` = true. |
| Custom CDN edges | IPv4 or CIDR (/8 to /32), any separator, max 64. Becomes `FrontedMeekCDNScanSpec.IPCandidates`. |
| SNI host names | Max 16. The first one is the SNI for the Akamai edge overrides (blank = the edge IP); all go into `FrontedMeekCDNScanSpec.SNIServerNames`. |

When Psiphon runs over Aether (Psiphon over Aether, and the three-core chain)
its upstream is a SOCKS5 hop that carries no UDP, so the QUIC protocols are
dropped from the limit.

### Built-in overrides

In order (the core uses the first match):

1. `fastly-provider` / `fastly-address`: Fastly fronts (by provider id, or by a
   dial address matching fastly/pypi/python/github) are dialled at `pypi.org`
   with SNI `pypi.org`, ALPN h2 + http/1.1.
2. Nine Akamai edges (`edge-a-1` ... `edge-original`) matching any dial address,
   ALPN http/1.1, verified against the Akamai certificate names.

All use TLS profile `Chrome-83`, probability 1.0, and the core's built-in scan
spec stays on.

## Code

- `core/PsiphonCdnFronting.kt`: pure Kotlin planner (edges, protocol lists,
  input validation). Tested by `PsiphonCdnFrontingTest`.
- `core/PsiphonCore.kt`: `applyCdnFronting` writes the plan into the config;
  the core's `cdn fronting ...` Info notices (scan active / progress / found /
  exhausted) go to the diagnostics log.
- `model/PsiphonProtocol.kt` and three `ConnectionProfile` fields, carried by
  `ProfileCodec` and `ProfileStore` (keys `psiphonProtocol`, `psiphonCdnIps`,
  `psiphonCdnSni`).
- `ui/engine/ChainSection.kt`: the picker and the two fields.

## Known limits

- The built-in edge IPs come from shirokhorshid and will go stale; the custom
  edge field is the way around that without a new build.
- CDN fronting is slow compared to direct protocols. Use it when nothing else
  connects.
- Not yet verified on a device by this repo; check the diagnostics log for the
  `CDN fronting:` and `cdn fronting scan` lines when testing.
