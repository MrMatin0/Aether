# Proteus (experimental)

[Proteus](https://github.com/unblockable/proteus) tunnels traffic with a
*programmable* protocol: the wire format, framing and crypto are described in a
PSF (protocol specification file), and client and server compile the same PSF.
This app ships it as an **experimental**, optional chain core.

| Mode | Path |
|---|---|
| Proteus (experimental) | device → Proteus → internet |
| Tor over Proteus (experimental) | device → Tor → Proteus → internet |

Proteus has no upstream-proxy option, so it is always the internet-facing hop
(like the Aether engine), and it cannot be combined with Aether or Psiphon.

## What you need

Proteus is not a public network. You need **your own server** running
`proteus server` with the **same PSF** and the **same `--persist` setting**.

### Server (Linux VPS)

```sh
git clone https://github.com/unblockable/proteus && cd proteus
git checkout 75ba9f4006e3e38d352774105864fe9444b65c55   # the commit the app is built from
cargo build --release                                   # rustup picks the pinned toolchain
./target/release/proteus server my.psf -l 0.0.0.0:8443  # add --persist true to enable sessions
```

Open that TCP port in the firewall. `tests/fixtures/` in the Proteus repository
has example PSFs (for example `shadowsocks.psf`); **change any password** in an
example before using it.

### App

1. Settings → Chain → pick **Proteus** or **Tor over Proteus**.
2. Under the selected card fill in:
   - **Server**: `1.2.3.4:8443`, `example.com:8443` or `[2001:db8::1]:8443`.
     A hostname is resolved by the app right before connecting (IPv4 preferred),
     because `proteus client --connect` needs a literal address.
   - **PSF**: paste the exact PSF the server runs.
   - **Client mode**: `tunnel` (one long-lived connection, upstream default) or
     `stream` (one connection per app stream).
   - **Session persistence**: must match the server.
3. Connect.

Under the hood the app runs

```
libproteus.so --log-level info client <psf> --connect <ip:port> \
    --listen 127.0.0.1:1829 --mode <tunnel|stream> --persist <true|false>
```

and fronts that local SOCKS5 with the same DNS-capable front Psiphon and Tor
use. The session is only reported as up after a SOCKS5 CONNECT through Proteus
actually succeeds.

## Limitations (read before filing a bug)

- **TCP only.** Proteus's SOCKS5 has no UDP ASSOCIATE. DNS is carried as DNS
  over TCP through the tunnel; UDP apps, QUIC and voice/video calls over UDP
  will not work in these modes.
- **Settings are device-local.** Server / PSF / mode / persist live in a small
  store of their own while the feature is experimental. They are **not** part
  of a profile export or import.
- **No PT mode.** The Tor pluggable-transport mode of Proteus is not used: it
  takes the PSF from the bridge line and refuses an upstream proxy.
- **Errors.** "Proteus could not start" means the server address, the hostname
  lookup or the PSF was rejected. "Did not reach the server in time" means the
  process started but no test connection got through: check that the server is
  up and reachable, and that PSF, mode and persist match. The Diagnostics log
  (tag `proteus`) has proteus's own output.

## Building

`scripts/build-proteus.sh` builds `libproteus.so` for `arm64-v8a` and
`armeabi-v7a` from the pinned commit with `cargo-ndk`. It needs
`ANDROID_NDK_HOME`, Rust via rustup (it installs upstream's pinned toolchain and
the Android targets) and `cargo-ndk`. CI runs it as a non-fatal step: a missing
`libproteus.so` only disables the Proteus modes and never blocks a release.

Overrides: `PROTEUS_REPO`, `PROTEUS_REF`, `PROTEUS_RUST_TOOLCHAIN`,
`AETHER_ABIS`, `ANDROID_API`.

## License

Proteus is distributed under a BSD-3-Clause-style license that also carries a
U.S. Government notice (see upstream `LICENSE`). It is compatible with this
app's AGPL-3.0-or-later; keep upstream's license text with redistributed
binaries. See `THIRD_PARTY_NOTICES.md`.
