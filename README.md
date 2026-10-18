# Aether+ for Android! :)

**An Android client for censorship circumvention, powered by Aether, Psiphon, Xray, and Tor**

[English](README.md) · [فارسی](README.fa.md) · [Downloads](https://github.com/MrMatin0/Aether/releases)

Aether+ brings endpoint discovery, encrypted tunneling, and configurable routing to a native Kotlin and Jetpack Compose application. Use a device-wide VPN, connect individual proxy-aware applications, or combine supported cores when a single transport cannot reach the network.

> **About this repository:** this is the `MrMatin0/Aether` Android fork, not the upstream command-line engine. It installs as **Aether+** with application ID `io.github.mrmatin0.aether`, allowing it to coexist with builds using the upstream application ID. The Kotlin namespace remains `studio.cluvex.aether`.
>
> The name **Aether** on its own is used throughout this README for the *engine* — the circumvention core at [`CluvexStudio/Aether`](https://github.com/CluvexStudio/Aether) — and for the chain modes built on it. The *app* is Aether+.

## Contents

- [Highlights](#highlights)
- [Install and connect](#install-and-connect)
- [Connection modes](#connection-modes)
- [Privacy and security](#privacy-and-security)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Credits and license](#credits-and-license)

## Highlights

- **Multiple transports:** Smart Auto selection, MASQUE over HTTP/3 or HTTP/2, WireGuard, and nested WireGuard (`gool`) through the Aether engine.
- **Adaptive endpoint discovery:** Turbo, Precise, and Ultra scanning, plus a manually selected endpoint or custom IP ranges.
- **Four connection cores:** Aether, Psiphon, and Tor, available individually or in predefined chains, plus **your own Xray server** (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard, SOCKS) connecting first underneath Aether, Psiphon, and/or Tor, when the required binaries and assets are included.
- **Device-wide or selective access:** Android `VpnService`, proxy-only mode, per-app split tunneling, and routing controls.
- **Connection controls:** DNS and MTU settings, Smart DNS (one server, with the flag of the country it answers from), reconnection options, optional kill switch, IPv6 leak-protection setting, and advanced transport tuning.
- **Everyday usability:** English and Persian UI, RTL support, bundled Vazirmatn typography, live traffic information, session history, diagnostics, Quick Settings tile, and home-screen widget.
- **Optional LAN sharing:** expose SOCKS5 and HTTP proxies to other devices on a trusted local network.

Feature availability is not a guarantee of connectivity. Reachability depends on your network, the selected core, and the contents of the installed APK.

## Install and connect

### Requirements

- **Android 8.0 or newer** (API 26+).
- An **ARM64** (`arm64-v8a`) or **32-bit ARM** (`armeabi-v7a`) device.
- Permission to install an APK from your chosen download application.
- Android VPN consent when using device-wide mode. Root is not required.

### Installation

1. Open this repository's [Releases](https://github.com/MrMatin0/Aether/releases) page. Prefer a tagged release over a build marked **Pre-release** for everyday use.
2. Download the APK matching your device: `arm64-v8a` for ARM64 or `armeabi-v7a` for 32-bit ARM. Choose `universal` if unsure; it includes both ARM variants, **not x86/x86_64**.
3. Install the APK and open **Aether+**. Complete or skip onboarding and choose your language.
4. Start with the Aether core and default connection settings. Tap the connection control and approve Android's VPN request.
5. Wait for connection establishment, then check that an application can actually load content. If the connection fails, review diagnostics before adding extra chain layers.

Updates require the same application ID and a compatible signing certificate. A build signed with a different key cannot simply replace your installed copy.

## Connection modes

### VPN, proxy, and LAN sharing

**VPN mode** routes device traffic through Android's VPN interface, subject to your split-tunneling and routing settings. **Proxy-only mode** does not capture the whole device: configure each proxy-aware application to use the active local proxy. Do not assume unrelated applications are protected in this mode.

**LAN sharing** is separate and disabled by default. Enable it only on a trusted Wi-Fi or hotspot network and use the address and live ports displayed in the sharing panel. It exposes proxy access to reachable devices; do not enable it on an untrusted network.

### Aether transport and scanner selection

Start with **Auto** unless you are diagnosing a specific network restriction. Advanced settings expose MASQUE, WireGuard, `gool`, HTTP/2 selection, obfuscation, TLS fragmentation, and endpoint controls.

- **Turbo:** prioritize finding a responding endpoint quickly.
- **Precise:** compare candidates and select a better-performing endpoint; the default scan mode.
- **Ultra:** require a real traffic check before accepting an endpoint; useful on difficult networks and slower by design.

The app allows approximately 60, 190, and 340 seconds respectively for these scans. A manually pinned endpoint bypasses scanning. Overlay cores have their own startup budgets.

### Psiphon, Tor, and Xray chains

The supported combinations are:

- Aether
- Psiphon
- Tor
- Psiphon over Aether
- Tor over Aether
- Tor over Psiphon
- Tor over Psiphon over Aether
- Aether over Xray
- Psiphon over Xray
- Psiphon over Aether over Xray
- Tor over Xray
- Tor over Aether over Xray

Here, **X over Y** means X establishes its upstream connections through Y's local SOCKS5 proxy. For example, Tor over Aether carries Tor's relay connections through Aether; it does not put a fixed Aether proxy after the Tor exit.

Psiphon includes a built-in client configuration; ordinary users do **not** need to obtain or paste a configuration. The APK still needs the Psiphon executable and bootstrap server list. Tor requires its executable, and exit-country selection additionally requires its GeoIP assets.

**Xray is your own server, connects first, and is never offered alone.** Paste a share link (`vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://`, `wireguard://`, `socks://`), a subscription body, or Xray JSON (comments are fine) under the selected Xray mode on the Chain page; the first entry Aether+ can run is used. Xray connects to your server first, and Aether, Psiphon, or Tor dial out through it (`AETHER_UPSTREAM`, `UpstreamProxyUrl`, `Socks5Proxy`), so the network sees only your Xray server. The config is a credential: it is stored encrypted with the Android Keystore on the device, and it is never part of the profile, an export, or a backup.

**Important limitations:**

- Chains containing Psiphon or Tor do not support general UDP traffic in this implementation. DNS is handled separately through the tunnel. UDP-only calls and games may fail; prefer Aether alone for those workloads.
- Tor can take several minutes to bootstrap. More layers add latency and are not automatically safer or more reliable.
- Bundled Tor integration does not include pluggable transports such as obfs4 or Snowflake.
- Country selection depends on reachable exits. Psiphon can retry with automatic selection if the requested country fails; Tor's strict country setting can prevent connection entirely.
- The bundled Xray-core no longer accepts `allowInsecure` (use the link's `pcs` / `pinSHA256` certificate pin instead) or the HTTP/2 and QUIC transports. Plaintext VLESS / Trojan links to a public server are forwarded through an on-device `dokodemo-door` loopback relay (`127.0.0.1:1850`) so Xray accepts them unchanged. The Chain page says so when a pasted config cannot run.
- Some APK builds can omit overlay cores or assets. The app reports unavailable modes; tagged releases require the chain binaries and Psiphon bootstrap list.

## Privacy and security

Aether+ is a circumvention client, **not a promise of anonymity or undetectable traffic**. The selected network providers, DNS resolvers, applications, and any configured Zero Trust organization remain part of your trust model. Enabling an organization's Gateway can apply its filtering and logging policies.

- Android backup is disabled in the manifest. Diagnostics and crash reports can still contain sensitive information: review and redact them before sharing.
- Kill-switch settings are opt-in. Proxy-only mode, direct routes, and excluded applications must not be mistaken for device-wide protection.
- Obtain APKs from this repository's release page. Releases are signed with a private release key; a build signed with a different key cannot replace your installed copy.
- The Android app does not contain an in-app APK installer. Install updates yourself from the repository's release page.

Never publish credentials, enrollment tokens, private keys, Xray share links, or unredacted logs in an issue. For a suspected vulnerability, use GitHub's private vulnerability reporting option if the repository exposes one; otherwise arrange a private channel with the maintainer before disclosing sensitive details.

## Troubleshooting

- **Connected, but applications cannot load content:** check proxy-only mode, routing and split-tunneling rules, DNS settings, and whether the application needs UDP. Try Aether alone to isolate chain limitations.
- **Scanning appears stuck:** Precise and Ultra have longer budgets. Let the attempt finish, examine diagnostics, and try a different protocol or network instead of repeatedly restarting the same scan.
- **Psiphon, Tor, or Xray is unavailable:** install a build containing the required core. For Psiphon, also check the bootstrap server list; changing connection settings cannot repair a missing APK asset.
- **Xray says the config cannot run, or the server does not answer:** check the link against the limitations above (self-signed servers need `pcs`), then the server address, UUID / password, and TLS / REALITY settings. A wrong credential ends in a timeout, never a false "connected".
- **APK will not install as an update:** confirm the application ID, signing certificate, architecture, and version. Avoid uninstalling until you have considered the loss of application data.

For a useful bug report, include the app version, Android version, device ABI, selected protocol and chain, reproduction steps, expected versus actual behavior, and redacted diagnostics. State which release APK you used.

## Contributing

Focused fixes, networking tests, accessibility improvements, and translation updates are welcome. The full guide — setup, branch and commit conventions, what CI checks, and the house rules — is in [CONTRIBUTING.md](CONTRIBUTING.md).

Do not commit generated APKs, downloaded native binaries, build caches, private configuration, or signing secrets. Preserve upstream attribution and review dependency license obligations when changing bundled components.

To report a security problem, do not open a public issue — see [SECURITY.md](SECURITY.md).

## Credits and license

This is a modified distribution of Aether Mobile, forked from
[`QW-AI-Code/Aether`](https://github.com/QW-AI-Code/Aether) and modified
extensively since. The component-by-component license inventory is in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This project builds on the work of Aether Mobile contributors and these upstream projects:

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether): the Aether engine.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel): TUN-to-SOCKS forwarding.
- [Psiphon Tunnel Core](https://github.com/Psiphon-Labs/psiphon-tunnel-core): Psiphon connectivity.
- [Tor Android](https://github.com/guardianproject/tor-android) and the [Tor Project](https://www.torproject.org/): Tor integration.
- [Xray-core](https://github.com/XTLS/Xray-core) (Project X, MPL-2.0): the Xray hop.
- [Vazirmatn](https://github.com/rastikerdar/vazirmatn): Persian and Latin typography.

The repository's [LICENSE](LICENSE) declares **GNU AGPL version 3 or later**, with no warranty. Bundled and downloaded third-party components retain their own licenses and notices; review those obligations before redistribution. Project and product names belong to their respective owners; integration does not imply endorsement.
