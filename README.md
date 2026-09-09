# Aether for Android

**An Android client for censorship circumvention, powered by Aether, Psiphon, and Tor.**

[English](README.md) · [فارسی](README.fa.md) · [Downloads](https://github.com/MrMatin0/Aether/releases) · [Build workflow](https://github.com/MrMatin0/Aether/actions/workflows/build.yml)

Aether brings endpoint discovery, encrypted tunneling, and configurable routing to a native Kotlin and Jetpack Compose application. Use a device-wide VPN, connect individual proxy-aware applications, or combine supported cores when a single transport cannot reach the network.

> **About this repository:** this is the `MrMatin0/Aether` Android fork, not the upstream command-line engine. It installs as **Aether (Fork)** with application ID `io.github.mrmatin0.aether`, allowing it to coexist with builds using the upstream application ID. The Kotlin namespace remains `studio.cluvex.aether`.

## Contents

- [Highlights](#highlights)
- [Install and connect](#install-and-connect)
- [Connection modes](#connection-modes)
- [Privacy and security](#privacy-and-security)
- [Build from source](#build-from-source)
- [Architecture](#architecture)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Documentation](#documentation)
- [Credits and license](#credits-and-license)

## Highlights

- **Multiple transports:** Smart Auto selection, MASQUE over HTTP/3 or HTTP/2, WireGuard, and nested WireGuard (`gool`) through the Aether engine.
- **Adaptive endpoint discovery:** Turbo, Precise, and Ultra scanning, plus a manually selected endpoint or custom IP ranges.
- **Three connection cores:** Aether, Psiphon, and Tor, available individually or in predefined chains when the required binaries and assets are included.
- **Device-wide or selective access:** Android `VpnService`, proxy-only mode, per-app split tunneling, and routing controls.
- **Connection controls:** DNS and MTU settings, reconnection options, optional kill switch, IPv6 leak-protection setting, and advanced transport tuning.
- **Everyday usability:** English and Persian UI, RTL support, bundled typography per script (Noto Naskh Arabic UI for Persian, Vazirmatn for Latin), live traffic information, session history, diagnostics, Quick Settings tile, and home-screen widget.
- **Optional LAN sharing:** expose SOCKS5 and HTTP proxies to other devices on a trusted local network.

Feature availability is not a guarantee of connectivity. Reachability depends on your network, the selected core, and the contents of the installed APK.

## Install and connect

### Requirements

- **Android 8.0 or newer** (API 26+).
- An **ARM64** (`arm64-v8a`) or **32-bit ARM** (`armeabi-v7a`) device.
- Permission to install an APK from your chosen download application.
- Android VPN consent when using device-wide mode. Root is not required.

### Installation

1. Open this fork's [Releases](https://github.com/MrMatin0/Aether/releases) page. Prefer a tagged release over a build marked **Pre-release** for everyday use.
2. Download the APK matching your device: `arm64-v8a` for ARM64 or `armeabi-v7a` for 32-bit ARM. Choose `universal` if unsure; it includes both ARM variants, **not x86/x86_64**.
3. Install the APK and open **Aether (Fork)**. Complete or skip onboarding and choose your language.
4. Start with the Aether core and default connection settings. Tap the connection control and approve Android's VPN request.
5. Wait for connection establishment, then check that an application can actually load content. If the connection fails, review diagnostics before adding extra chain layers.

Updates require the same application ID and a compatible signing certificate. A build signed with a different key cannot simply replace your installed copy. See [signing guidance](docs/SIGNING.md) before changing distribution keys.

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

### Psiphon and Tor chains

The supported combinations are:

- Aether
- Psiphon
- Tor
- Psiphon over Aether
- Tor over Aether
- Tor over Psiphon
- Tor over Psiphon over Aether

Here, **X over Y** means X establishes its upstream connections through Y's local SOCKS5 proxy. For example, Tor over Aether carries Tor's relay connections through Aether; it does not put a fixed Aether proxy after the Tor exit. See [chaining internals](docs/CHAINING.md) for the exact wiring.

Psiphon includes a built-in client configuration; ordinary users do **not** need to obtain or paste a configuration. The APK still needs the Psiphon executable and bootstrap server list. Tor requires its executable, and exit-country selection additionally requires its GeoIP assets.

**Important limitations:**

- Chains containing Psiphon or Tor do not support general UDP traffic in this implementation. DNS is handled separately through the tunnel. UDP-only calls and games may fail; prefer Aether alone for those workloads.
- Tor can take several minutes to bootstrap. More layers add latency and are not automatically safer or more reliable.
- Bundled Tor integration does not include pluggable transports such as obfs4 or Snowflake.
- Country selection depends on reachable exits. Psiphon can retry with automatic selection if the requested country fails; Tor's strict country setting can prevent connection entirely.
- Development APKs can omit overlay cores or assets. The app reports unavailable modes; tagged-release CI requires the chain binaries and Psiphon bootstrap list.

## Privacy and security

Aether is a circumvention client, **not a promise of anonymity or undetectable traffic**. The selected network providers, DNS resolvers, applications, and any configured Zero Trust organization remain part of your trust model. Enabling an organization's Gateway can apply its filtering and logging policies.

- Android backup is disabled in the manifest. Diagnostics and crash reports can still contain sensitive information: review and redact them before sharing.
- Kill-switch settings are opt-in. Proxy-only mode, direct routes, and excluded applications must not be mistaken for device-wide protection.
- Obtain APKs from a source you trust. This repository supports a committed CI signing key as a fallback. **That key is public: it can preserve update compatibility but cannot establish publisher authenticity.** Production distributors should use a private release key and plan migration for existing installations.
- The Android app does not contain an in-app APK installer. Install updates yourself from the repository's release page.
- Existing security-review documents record findings at particular revisions; they are not certification of the current build.

Never publish credentials, enrollment tokens, private keys, or unredacted logs in an issue. For a suspected vulnerability, use GitHub's private vulnerability reporting option if the repository exposes one; otherwise arrange a private channel with the maintainer before disclosing sensitive details.

## Build from source

### Toolchain

The commands below follow the repository's Linux CI layout. Use Linux or a Linux development environment with the Android SDK configured.

The checked-in configuration currently specifies:

- JDK **17**.
- Gradle **9.7.1**, declared in [the wrapper descriptor](gradle/wrapper/gradle-wrapper.properties).
- Android SDK platform **37** and NDK **26.3.11579264**.
- Rust stable, the two Android targets below, and `cargo-ndk` **4.1.2**.
- Go **1.26** for Psiphon, with `GOTOOLCHAIN=auto` to satisfy the upstream module's toolchain directive when newer.
- Git, Bash, C/C++ build tools, CMake, Make, curl, unzip, and Python 3.

Install Android SDK command-line tools and required build tools, accept SDK licenses, and make `sdkmanager` available. Dependency and asset downloads need network access. The build uses AGP **9.4.0-alpha04**, Kotlin **2.4.20-RC3**, and preview AndroidX dependencies: use the versions in [the version catalog](gradle/libs.versions.toml), not assumptions about a stable Android toolchain.

> The repository contains a Gradle wrapper descriptor but does **not** ship `gradlew` or `gradle-wrapper.jar`. Install the declared Gradle version and use `gradle`, as CI does. Commands here are derived from the checked-in build configuration, not a claim of a separately verified local build.

### 1. Prepare the checkout and tools

```bash
git clone https://github.com/MrMatin0/Aether.git
cd Aether

# Set this to your Android SDK installation.
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
sdkmanager --licenses
sdkmanager --install "platforms;android-37" "platform-tools" "ndk;26.3.11579264"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.3.11579264"
export ANDROID_API=26

rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --version 4.1.2 --locked

gradle --version
java -version
```

### 2. Build the required native components

```bash
bash scripts/fetch-natives.sh
bash scripts/build-natives.sh all
```

These scripts use the vendored engine in `native/aether`, fetch hev-socks5-tunnel, and produce `libaether.so`, `libhev-socks5-tunnel.so`, and the project's JNI bridge `libaethertun.so` for both ARM ABIs under `app/src/main/jniLibs/`.

### 3. Add Psiphon, Tor, and assets

For a full-featured build, run each core build separately so failures are visible:

```bash
GOTOOLCHAIN=auto bash scripts/build-overlay-cores.sh psiphon
bash scripts/build-overlay-cores.sh tor
bash scripts/fetch-psiphon-serverlist.sh
bash scripts/fetch-fonts.sh
```

Psiphon is compiled from upstream source. Tor is extracted from the published `tor-android` AAR by default; `TOR_FROM_SOURCE=1` selects the alternative source-build path. See [chain-core build notes](docs/CHAIN_CORES.md).

Overlay cores are optional for an Aether-only development build. Omitting their binaries or bootstrap assets makes the corresponding modes unavailable. Font retrieval also runs automatically before the Android build; fetching fonts explicitly is useful when preparing dependencies in advance. Both bundled UI faces and their licenses are described in [the font notes](docs/FONTS.md).

### 4. Assemble and test

```bash
# Development APKs
gradle :app:assembleDebug -PgithubRepo=MrMatin0/Aether

# Local JVM unit tests; the project uses the release test build type.
gradle :app:testReleaseUnitTest

# Release APKs; configure signing as described below.
gradle :app:assembleRelease -PgithubRepo=MrMatin0/Aether
```

APKs are written to `app/build/outputs/apk/debug/` or `app/build/outputs/apk/release/`. Test reports are under `app/build/reports/tests/`. ARM64, 32-bit ARM, and universal ARM APKs are configured. These unit tests do not replace VPN, DNS, reconnection, and chain testing on a physical Android device.

For a private release key, start with [keystore.properties.example](keystore.properties.example) and follow [SIGNING.md](docs/SIGNING.md). Local builds support `keystore.properties` or `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`; CI accepts the keystore through `KEYSTORE_BASE64`. Keep private signing material out of Git. Without a configured private key, the checked-in CI fallback may be used; see the security warning above.

The [build workflow](.github/workflows/build.yml) builds native components, assembles APKs, runs unit tests, and checks signing and packaged payloads. Pushes to the default branch can publish prereleases; `v*` tags publish releases and enforce the full chain payload. Documentation-only pull requests are excluded from that build workflow, but default-branch pushes are not.

## Architecture

```text
app/src/main/java/studio/cluvex/aether/
  core/       Core processes, proxies, diagnostics, routing, traffic tracking
  data/       Application data and preferences
  model/      Connection profiles, routing models, chain definitions
  ui/         Compose screens, controls, and theme
  vpn/        Android VPN service and session orchestration
  widget/     Home-screen widget
app/src/test/ Local unit tests
native/aether/ Vendored Rust engine and quiche source
scripts/      Native builds, assets, signing helpers, core maintenance
docs/         Technical documentation and review history
.github/      Build and core-sync workflows, release metadata
```

In device-wide mode, Android supplies a TUN interface. The in-process hev tunnel and JNI bridge forward traffic into the selected local SOCKS5 entry. Aether, Psiphon, and Tor run as supervised child processes; `SocksFront` handles the DNS adaptation needed for Psiphon/Tor entries.

The executables are packaged under `.so` filenames so Android extracts them into an executable native-library directory. Preserve `useLegacyPackaging = true` unless this launch mechanism is redesigned.

## Troubleshooting

- **Connected, but applications cannot load content:** check proxy-only mode, routing and split-tunneling rules, DNS settings, and whether the application needs UDP. Try Aether alone to isolate chain limitations.
- **Scanning appears stuck:** Precise and Ultra have longer budgets. Let the attempt finish, examine diagnostics, and try a different protocol or network instead of repeatedly restarting the same scan.
- **Psiphon or Tor is unavailable:** install a build containing the required core. For Psiphon, also check the bootstrap server list; changing connection settings cannot repair a missing APK asset.
- **APK will not install as an update:** confirm the application ID, signing certificate, architecture, and version. Avoid uninstalling until you have considered the loss of application data.
- **Gradle or native build fails:** compare your installed versions with the checked-in toolchain, verify `ANDROID_NDK_HOME`, and check access to dependency repositories. A plain Android build without the native preparation steps is not a complete runnable VPN build.
- **UI font download fails:** run `bash scripts/fetch-fonts.sh` with network access, or supply the expected font files in `app/src/main/res-fonts/font/` as described by the build error and in [the font notes](docs/FONTS.md).

For a useful bug report, include the app version, Android version, device ABI, selected protocol and chain, reproduction steps, expected versus actual behavior, and redacted diagnostics. State whether you used a release APK or built from source.

## Contributing

Focused fixes, networking tests, accessibility improvements, and translation updates are welcome.

1. Open an issue for a substantial behavior or architecture change before implementing it.
2. Create a branch from the current `main` and keep the change narrowly scoped.
3. Add or update relevant tests and run `gradle :app:testReleaseUnitTest` with the project toolchain.
4. Test affected network behavior on a supported physical device; include both success and failure cases.
5. Keep this README and [the Persian version](README.fa.md) in sync, then open a pull request explaining the change and validation performed.

Do not commit generated APKs, downloaded native binaries, build caches, private configuration, or signing secrets. Preserve upstream attribution and review dependency license obligations when changing bundled components.

## Documentation

- [Chaining architecture and limitations](docs/CHAINING.md)
- [Chain-core packaging and build notes](docs/CHAIN_CORES.md)
- [Bundled UI fonts and licenses](docs/FONTS.md)
- [APK signing and update compatibility](docs/SIGNING.md)
- [Security review history](docs/)
- [Vendored engine documentation](native/aether/README.md)
- [Dependency versions](gradle/libs.versions.toml)
- [Build and release workflow](.github/workflows/build.yml)

Engine documentation covers a separate command-line component. Its desktop, Docker, and Termux instructions are not installation instructions for this Android APK. Where historical notes differ from current behavior, consult the relevant implementation and workflow.

## Credits and license

This project builds on the work of Aether Mobile contributors and these upstream projects:

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether): the Aether engine.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel): TUN-to-SOCKS forwarding.
- [Psiphon Tunnel Core](https://github.com/Psiphon-Labs/psiphon-tunnel-core): Psiphon connectivity.
- [Tor Android](https://github.com/guardianproject/tor-android) and the [Tor Project](https://www.torproject.org/): Tor integration.
- [Noto Naskh Arabic UI](https://github.com/notofonts/arabic): Persian and Arabic-script typography.
- [Vazirmatn](https://github.com/rastikerdar/vazirmatn): Latin typography.

The repository's [LICENSE](LICENSE) declares **GNU AGPL version 3 or later**, with no warranty. Bundled and downloaded third-party components retain their own licenses and notices; review those obligations before redistribution. Project and product names belong to their respective owners; integration does not imply endorsement.
