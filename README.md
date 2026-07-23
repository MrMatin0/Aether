# Aether Mobile

> A stunning, dark **Material You** Android client for the Aether engine — with the tunnelling core built **inside the app**, so you no longer need v2rayNG.

🇬🇧 English | 🇮🇷 [فارسی](README.fa.md)

---

## What's new in v1.2.0

This release ships the full advanced feature set and makes in-place updates reliable.

- **Amnezia-style anti-DPI obfuscation (Noize)** — Off / Light / Firewall / Balanced / GFW / Aggressive, to defeat protocol fingerprinting on heavily filtered networks.
- **New "Ironclad" scan mode** — the most persistent endpoint search, for the hardest networks (adds to Turbo / Balanced / Thorough / Stealth).
- **Endpoint selection** — Auto (scan the built-in ranges), Manual peer (pin one `ip:port`), or **Custom range** (type your own IP range(s) and the engine scans *exactly* those, e.g. `8.6.112.x`, `188.114.96.0/24`).
- **WireGuard keepalive**, **adjustable MTU** (default 1280, best for Iranian mobile / aggressive DPI), **TLS ClientHello fragmentation**, and **Encrypted Client Hello (ECH)**.
- **Proxy mode** — run the engine + a local SOCKS5/HTTP proxy *without* capturing the whole device through the system VPN.
- **Per-app split tunneling** — pick exactly which apps use (or skip) the tunnel, with a built-in app picker.
- **Fixes:** correct "your IP" readout (no more cellular IPv6) and correct upload/download traffic figures.
- **Reliable in-place updates** — the signing key is persisted inside your repo, so as long as you keep building in the **same** repository, new versions install right on top of the old one with no uninstall. See [Updates & app signing](#updates--app-signing).
- **Fixed the advanced-settings sheet** — it now scrolls cleanly to the last control without clipping behind the navigation bar.
- **Fixed full-device VPN mode** — CI now packages and verifies the embedded TUN-to-SOCKS core and all of its runtime dependencies in every APK.

## What's new in v1.1.0

- **Quick Settings tile** — connect/disconnect right from the notification shade without opening the app. Add it once: swipe down → tap the pencil/edit button in Quick Settings → drag the **Aether** tile into your tiles.
- **Share the VPN with your laptop or another phone** — side menu → **Share VPN**. See [Share the VPN](#share-the-vpn-with-your-laptop-or-another-phone) below.
- **Advanced settings on the home screen** — the tune button (top-right) opens all advanced options in a bottom sheet.
- **In-place updates** — builds are now signed with one stable key so future updates install right on top. See [Updates & app signing](#updates--app-signing).

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

## How this VPN actually works — and where your data goes (plain English)

A lot of users ask: *“It's a free VPN with no server list… whose server am I connecting to? Who sees my data?”* Fair question. Here's the honest, simple answer:

- **There is no secret middleman server.** Aether does not route you through some stranger's VPS. The engine connects your device to **Cloudflare's WARP network** — the same worldwide infrastructure behind the famous **1.1.0.1 / WARP** app used by millions. That IS the “destination server”: Cloudflare's public edge, not something run by us.
- **Your traffic is encrypted on your phone** using the WireGuard or MASQUE protocol **before it leaves the device**, and is decrypted only inside Cloudflare's network on its way to the website you're visiting. Anyone in between (your ISP, the Wi‑Fi owner) sees only encrypted noise.
- **The app developers run no servers and receive none of your traffic.** There are no analytics, no accounts, no logging back to us — nothing to send, nowhere to send it. What the engine really adds is smart *endpoint scanning and obfuscation* so WARP keeps working on heavily filtered networks.
- **Trust, but verify:** the entire app and the engine are open source ([this repo](https://github.com/QW-AI-Code) + [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether)). Anyone can read the code and confirm the above; the APKs are built publicly by GitHub Actions straight from this source.
- **The honest caveat:** like ANY VPN, the operator of the exit network — here, Cloudflare — can technically see the traffic that exits through it (Cloudflare publishes its [privacy policy](https://www.cloudflare.com/application/privacy/) for WARP). Websites you visit over HTTPS stay end-to-end encrypted regardless. If your threat model can't accept Cloudflare, no WARP-based tool is for you.

**TL;DR:** your data goes: *your phone → (encrypted) → Cloudflare WARP → the website*. The developers are not in that path at all.

## Highlights

- **Material You dark UI** built with Jetpack Compose. Uses the wallpaper-based **dynamic color** on Android 12+, and falls back to a beautiful deep-navy palette on older devices.
- Animated glowing connect button, drifting ambient background, smooth state transitions.
- All the same options as the desktop app **and more**: **protocol** (Auto / MASQUE / WireGuard / Gool), **scan mode** (Turbo / Balanced / Thorough / Stealth / **Ironclad**), **IP version** (v4 / v6 / both), **quick reconnect** and **MASQUE over HTTP/2** — plus **Amnezia-style obfuscation (Noize)**, **manual endpoint / custom scan range**, **keepalive**, **MTU**, **TLS fragmentation**, **ECH**, **proxy mode** and **per-app split tunneling**. All reachable both from the side menu and straight from the **home screen** (tune button, top-right).
- **Quick Settings tile** for one-swipe connect/disconnect.
- **VPN sharing over Wi‑Fi/hotspot** — built-in HTTP + SOCKS5 proxy for your other devices.
- Auto-reconnect with backoff if the engine drops.
- Bilingual (English + Persian) with automatic RTL.

## Quick Settings tile (one-swipe on/off)

1. Swipe down from the top of the screen to open Quick Settings.
2. Tap the **pencil / edit** button and drag the **Aether** tile into your active tiles (needed once).
3. From then on: **tap the tile to connect, tap again to disconnect** — no need to open the app. The very first connection must still be started from the app once, so Android can show its standard VPN permission dialog.

## Share the VPN with your laptop or another phone

Your phone can act as a **gateway** for other devices on the same Wi‑Fi network or on your phone's hotspot:

1. Connect the VPN in Aether.
2. Open the side menu → **Share VPN** → turn on **Share on this network**.
3. The panel shows two addresses (tap the copy icon next to either):
   - **HTTP proxy — `<your-phone-ip>:10809`** → enter this in the other device's **system proxy** settings (Windows: Settings → Network → Proxy → Manual; macOS: Wi‑Fi → Details → Proxies → Web/Secure Web Proxy; Android/iOS: Wi‑Fi → Modify network → Proxy → Manual).
   - **SOCKS5 proxy — `<your-phone-ip>:10808`** → for apps/browsers that support SOCKS (e.g. Firefox, Telegram).
4. Done — the other device's traffic now goes through your phone's tunnel.

> ⚠️ While sharing is on, **anyone on that network** can use the proxy. Only enable it on networks you trust (your own hotspot is safest). Sharing stops automatically when the VPN disconnects.

## Updates & app signing (do I have to uninstall old versions?)

Android installs an update **on top of** the old app only if both are signed with the **same key**. Older builds of this project fell back to a **temporary debug key that changed between builds**, which is why updating used to demand a full uninstall.

**Fixed in v1.1.0.** The CI now always signs with one **stable key**:

- If you set the keystore Secrets (table below), your own key is used — recommended.
- Otherwise, the very first build generates a CI keystore and **commits it to the repo** (`.github/ci-keystore.jks.b64`); every later build reuses that exact key.

What that means for users:

- **From v1.1.0 onward:** just download the new APK and install — it updates in place, data intact. No uninstalling, ever.
- **Upgrading from v1.0.0 (or older):** one final uninstall is required, because those builds were signed with the old throwaway key. After that, never again.

> ♻️ **Beginner tip — keep the same repo & keep the key.** The stable key lives in `.github/ci-keystore.jks.b64` inside *your* repository. When you upload a newer source drop, add/replace the changed files in the **same** repo and **do not delete** that keystore file — that is exactly what lets a new version install on top of the one already on your phone. If you ever start a brand-new repo (or the file gets removed), the next build makes a fresh key, so that one time you'll need a single uninstall; after that it's permanent again.

> 🔒 **Security note:** a keystore committed to a public repo is public — it guarantees *updatability*, not *authenticity* (anyone could sign an APK with it). If you distribute this app seriously, set the Secrets below; they always take priority over the repo keystore.

| Secret | Meaning |
|--------|---------|
| `KEYSTORE_BASE64` | Your `.jks` keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create the base64 with: `base64 -w0 my-release-key.jks > keystore.txt`, then add the Secrets under Settings → Secrets and variables → Actions.

## Build it on GitHub (no computer setup needed)

You do **not** need Android Studio. GitHub Actions builds everything for you.

1. Create a new GitHub repository and upload the contents of this folder (or push it with git).
2. Go to the repo's **Actions** tab and enable workflows if prompted.
3. Every push to `main` builds the app. To get installable release files, create a version tag:
   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```
4. When the build finishes, the **Releases** page will contain three APKs (see below). Release titles are clean (`Aether v1.2.0`) and the “What's new” text comes from `.github/release-notes.md` — update that file together with the version.

### The three APKs

| File | For which phone |
|------|-----------------|
| `Aether-1.2.0-arm64-v8a.apk` | Almost all modern phones (64-bit ARM) |
| `Aether-1.2.0-armeabi-v7a.apk` | Older / low-end 32-bit phones |
| `Aether-1.2.0-universal.apk` | **If you're not sure, download this one.** Works on any ARM phone |

## How the native parts are built

Upstream Aether does **not** publish Android binaries, so CI builds them from source:

- `scripts/fetch-natives.sh` clones `hev-socks5-tunnel` (the in-app tunnel) and the Aether engine source.
- `scripts/build-natives.sh` builds hev with `ndk-build` into `libhev-socks5-tunnel.so` and cross-compiles the Aether engine with `cargo-ndk` into `libaether.so`, for both `arm64-v8a` and `armeabi-v7a`.
- Before publishing, CI checks every APK and refuses the release unless both native cores are actually present for every included ABI.

You can pin versions via env vars: `HEV_REF`, `AETHER_REPO`, `AETHER_REF`.

## Requirements

- Android 8.0 (API 26) or newer.
- The app asks for VPN permission (standard Android consent) and notification permission (to show the ongoing status).

## Credits & license

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — the engine.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the tunnel core.

Released under **AGPL-3.0**. See [LICENSE](LICENSE).
