# Aether Mobile

> A stunning, dark **Material You** Android client for the Aether engine — with the tunnelling core built **inside the app**, so you no longer need v2rayNG.

🇬🇧 English | 🇮🇷 [فارسی](README.fa.md)

---

## What this is

The Windows version of Aether works like this: the app runs the **Aether engine**, which opens a local **SOCKS5 proxy at `127.0.0.1:1819`**. You then paste that address into **v2rayNG** and let it tunnel your traffic.

**Aether Mobile removes the second step entirely.** The app:

1. runs the same Aether engine internally (opens SOCKS5 on `127.0.0.1:1819`),
2. brings up an Android **VpnService** (a system TUN interface),
3. uses a **built-in tunnel core** (`hev-socks5-tunnel`) to forward every packet from the TUN device into that local SOCKS5 proxy.

So the whole “engine + v2rayNG” chain now lives in one app. One tap connects everything.

```
  Your apps  ─►  Android VPN (TUN)  ─►  hev-socks5-tunnel  ─►  127.0.0.1:1819  ─►  Aether engine  ─►  Internet
                     (built in)          (built in, replaces v2rayNG)      (SOCKS5)          (built in)
```

## Highlights

- **Material You dark UI** built with Jetpack Compose. Uses the wallpaper-based **dynamic color** on Android 12+, and falls back to a beautiful deep-navy palette on older devices.
- Animated glowing connect button, drifting ambient background, smooth state transitions.
- All the same options as the desktop app: **protocol** (Auto / MASQUE / WireGuard / Gool), **scan mode** (Turbo / Balanced / Thorough / Stealth), **IP version** (v4 / v6 / both), **quick reconnect**, and **MASQUE over HTTP/2**.
- Auto-reconnect with backoff if the engine drops.
- Bilingual (English + Persian) with automatic RTL.

## Build it on GitHub (no computer setup needed)

You do **not** need Android Studio. GitHub Actions builds everything for you.

1. Create a new GitHub repository and upload the contents of this folder (or push it with git).
2. Go to the repo's **Actions** tab and enable workflows if prompted.
3. Every push to `main` builds the app. To get installable release files, create a version tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
4. When the build finishes, the **Releases** page will contain three APKs (see below). Builds from a normal push are also downloadable from the run's **Artifacts**.

### The three APKs

| File | For which phone |
|------|-----------------|
| `Aether-1.0.0-arm64-v8a.apk` | Almost all modern phones (64-bit ARM) |
| `Aether-1.0.0-armeabi-v7a.apk` | Older / low-end 32-bit phones |
| `Aether-1.0.0-universal.apk` | **If you're not sure, download this one.** Works on any ARM phone |

## Optional: sign your release

By default the APKs are signed with a debug key so they install fine. To sign with your own key, add these repository **Secrets** (Settings → Secrets and variables → Actions):

| Secret | Meaning |
|--------|---------|
| `KEYSTORE_BASE64` | Your `.jks` keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create the base64 with: `base64 -w0 my-release-key.jks > keystore.txt`

## How the native parts are built

Upstream Aether does **not** publish Android binaries, so CI builds them from source:

- `scripts/fetch-natives.sh` clones `hev-socks5-tunnel` (the in-app tunnel) and the Aether engine source.
- `scripts/build-natives.sh` builds hev-socks5-tunnel (Makefile) into `libhev.so` and cross-compiles the Aether engine with `cargo-ndk` into `libaether.so`, for both `arm64-v8a` and `armeabi-v7a`.
- Gradle's CMake step compiles `hev-socks5-tunnel` into `libtun2socks.so`.

You can pin versions via env vars: `HEV_REF`, `AETHER_REPO`, `AETHER_REF`.

## Requirements

- Android 8.0 (API 26) or newer.
- The app asks for VPN permission (standard Android consent) and notification permission (to show the ongoing status).

## Credits & license

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — the engine.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the tunnel core.

Released under **AGPL-3.0**. See [LICENSE](LICENSE).
