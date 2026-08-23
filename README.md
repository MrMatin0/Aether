<div align="center">

# Aether Mobile

### A dark, deliberate Android client for the Aether engine — with the tunnelling core built **inside** the app.

**No v2rayNG. No second app. No copy-pasting a proxy address. One tap.**

[![Version](https://img.shields.io/badge/app-1.3.0-A9E34B?style=for-the-badge&labelColor=12160F)](https://github.com/MrMatin0/Aether/releases/latest)
[![Core](https://img.shields.io/badge/engine%20core-1.7.0-A9E34B?style=for-the-badge&labelColor=12160F)](https://github.com/CluvexStudio/Aether)
[![Android](https://img.shields.io/badge/Android-8.0%2B-A9E34B?style=for-the-badge&labelColor=12160F)](#requirements)
[![License](https://img.shields.io/badge/license-AGPL--3.0-E9B44C?style=for-the-badge&labelColor=12160F)](LICENSE)

🇬🇧 **English** · 🇮🇷 [فارسی](README.fa.md)

</div>

---

## Table of contents

- [The 60-second version](#the-60-second-version)
- [What Aether Mobile actually is](#what-aether-mobile-actually-is)
- [How it works under the hood](#how-it-works-under-the-hood)
- [Where your data goes (read this one)](#where-your-data-goes-read-this-one)
- [The app, screen by screen](#the-app-screen-by-screen)
- [Complete settings reference](#complete-settings-reference)
- [Saved setups & config transfer](#saved-setups--config-transfer)
- [Automation](#automation)
- [Session history](#session-history)
- [Tile, widget and notification](#tile-widget-and-notification)
- [Share the tunnel with your laptop](#share-the-tunnel-with-your-laptop)
- [Language & typography](#language--typography)
- [Design system: Carbon & Signal](#design-system-carbon--signal)
- [Install & update](#install--update)
- [Build it yourself](#build-it-yourself)
- [Security posture](#security-posture)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Version history](#version-history)
- [Credits & license](#credits--license)

---

## The 60-second version

| | |
|---|---|
| **What is it** | A free, open-source Android VPN client that tunnels your device through **Cloudflare WARP**, with heavy anti-censorship tooling on top. |
| **What do I download** | Grab `Aether-1.3.0-universal.apk` from [**Releases**](https://github.com/MrMatin0/Aether/releases/latest). If you know your phone is 64-bit ARM (almost all are), `arm64-v8a` is smaller. |
| **How do I use it** | Install, open, grant the VPN permission Android asks for, press **Connect**. That is it. Leave every setting alone the first time. |
| **Do I need another app** | No. Not v2rayNG, not a config file, not a subscription link, not an account. |
| **What if it does not connect** | Go to **Settings → Engine settings** and set **Obfuscation** to `GFW`, **MTU** to `1280`, **Scan mode** to `Ironclad`. See [Troubleshooting](#troubleshooting). |
| **Cost** | Zero. No ads, no analytics, no telemetry, no accounts. |

---

## What Aether Mobile actually is

The desktop edition of Aether works in two pieces: the **Aether engine** runs and opens a local SOCKS5 proxy on `127.0.0.1:1819`, and then *you* paste that address into a second app (v2rayNG) so your traffic actually goes somewhere.

**Aether Mobile deletes the second piece.** The engine, the tunnel core and the system VPN all live in one APK, wired together at startup:

1. The app launches the same Aether engine internally — it opens SOCKS5 on `127.0.0.1:1819`.
2. The app brings up an Android **`VpnService`**, i.e. a real system TUN interface, so *every* app on the phone is captured.
3. A **built-in tunnel core** (`hev-socks5-tunnel`, or the app's own userspace bridge when per-app filtering is on) forwards each packet from the TUN device into that local SOCKS5 proxy.

So the whole *engine + v2rayNG* chain collapses into one tap.

---

## How it works under the hood

```text
┌──────────────┐
│  Your apps   │  Chrome, Telegram, games, everything
└──────┬───────┘
       │  raw IP packets
┌──────▼───────────────────────┐
│  Android VpnService (TUN)    │  built in · MTU 1280 by default
│  IPv4 + IPv6 default routes  │  split tunneling / per-app blocking applied here
└──────┬───────────────────────┘
       │
┌──────▼───────────────────────┐
│  Tunnel core                 │  hev-socks5-tunnel (default)
│                              │  or the UID-filtering userspace bridge
└──────┬───────────────────────┘
       │  SOCKS5
┌──────▼───────────────────────┐
│  127.0.0.1:1819              │  the Aether engine's local proxy
└──────┬───────────────────────┘
       │
┌──────▼───────────────────────┐
│  Aether engine (core 1.7.0)  │  WireGuard / MASQUE-QUIC · TLS 1.3 · ECH
│  scan · obfuscation · rules  │  fragmentation · Zero Trust · routing
└──────┬───────────────────────┘
       │  encrypted before it leaves the phone
┌──────▼───────────────────────┐
│  Cloudflare WARP edge        │  anycast — your operator's routing picks the datacentre
└──────┬───────────────────────┘
       │
    Internet
```

**Nothing in that chain is a server we run.** Everything left of the WARP edge runs on your phone; the WARP edge is Cloudflare's public infrastructure.

### Connecting is a four-phase pipeline, and the app shows you which phase you are in

| Phase | What is happening | Why it can take a while |
|---|---|---|
| **Engine** | The native engine process starts and begins scanning WARP endpoint ranges. | Scan budget depends on your scan mode: ~60 s on Turbo up to ~360 s on Ironclad. |
| **Tunnel** | The local SOCKS5 port opens; the TUN interface and tunnel core come up. | Usually seconds. |
| **Verify** | Four health checks run: local port, handshake, real internet reachability, exit IP lookup. | This is why "Connected" in Aether means *actually* connected. |
| **Ready** | Session accepted, traffic meter live, exit IP + flag + latency shown. | — |

A visible elapsed clock runs during all of this, so a long Ironclad scan and a hung process no longer look identical.

### Smart Auto: the app fingerprints your network before it picks a protocol

With **Protocol = Smart**, the app does not guess. Before the engine even launches it probes your actual operator path in parallel:

- **UDP health** — live DNS queries to `1.1.1.1` and `8.8.8.8`.
- **SNI-based DPI** — a full TLS handshake with SNI against Cloudflare.
- **TCP latency** per WARP range, so the engine starts with ranges that already answer.
- **Carrier context** — name, country code, network type. No extra permissions required.

It then classifies the network as **Open**, **SNI filtering**, **UDP throttling** or **Hostile**, builds a ranked ladder of *protocol + obfuscation + fragmentation/ECH + live ranges*, and walks it. The first strategy that passes the four-step verification is locked in and reused for automatic reconnects. Every decision is written to the diagnostics log, so you can read exactly why it chose what it chose.

If you pick a protocol **by hand**, Aether never silently swaps it for another one. It runs your protocol on the full scan budget, and if that fails it retries **the same protocol** with anti-DPI hardening (obfuscation on, plus HTTP/2, TLS fragmentation and ECH for MASQUE).

### Staying connected

- A **watchdog** probes the tunnel end-to-end every 30 seconds, multi-attempt and multi-target, and restarts the engine only after three consecutive failures — so a brief network stall does not kill a healthy session.
- The **supervisor** blocks on the engine process itself instead of polling, so crash detection is faster *and* the CPU can stay in deep idle.
- **Smart Reconnect** retries automatically up to a limit you set (3–20) before reporting an error.
- A **system-killed VPN service** comes back with your real profile, not factory defaults.

---

## Where your data goes (read this one)

The most common question about this app is fair and deserves a straight answer: *"a free VPN with no server list — whose server am I on, and who can see my traffic?"*

- **There is no mystery middleman.** Aether does not route you through somebody's VPS. The engine connects your device to **Cloudflare's WARP network** — the same global infrastructure behind the well-known 1.1.1.1 / WARP app used by millions. That *is* the destination: Cloudflare's public edge.
- **Encryption happens on your phone.** Your traffic is wrapped in WireGuard or MASQUE/QUIC **before it leaves the device** and is only decrypted inside Cloudflare's network on its way to the site you asked for. Your ISP or the café Wi-Fi owner sees encrypted noise and nothing else.
- **The developers run no servers and receive nothing.** No accounts, no analytics SDK, no crash reporting to us, no phone-home. There is nothing to send and nowhere to send it.
- **There is no country picker, and there never honestly could be.** WARP addresses are **anycast**: the same IP is announced from every Cloudflare datacentre simultaneously, so which one answers is decided by your operator's routing, not by the app. A country menu could only ever have been a decorative label, so it was removed rather than kept as theatre.
- **Verify it yourself.** The app and the engine are both open source ([this repo](https://github.com/MrMatin0/Aether) · [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether)) and the APKs are built publicly by GitHub Actions straight from this source.
- **The honest caveat.** Like *any* VPN, the operator of the exit network — here, Cloudflare — can technically observe traffic exiting through it ([WARP privacy policy](https://www.cloudflare.com/application/privacy/)). Sites you visit over HTTPS stay end-to-end encrypted regardless. If your threat model cannot accept Cloudflare, then no WARP-based tool is right for you, and that includes this one.

> **TL;DR** — `your phone → (encrypted) → Cloudflare WARP → the website`. The developers are not in that path at all.

---

## The app, screen by screen

v1.3.0 replaced the old hamburger drawer plus duplicated bottom sheet with **three destinations on a bottom bar**, and pinned the primary action directly above that bar where your thumb actually reaches.

### 1 · Connection

The glowing status ring, the state pill, the phase pipeline and the live ledger:

- **Connect ring** — tap to connect or disconnect, with haptic acknowledgement. Fully labelled for TalkBack (it announces state *and* what a tap will do).
- **Phase pipeline + elapsed clock** while connecting.
- **Traffic panel** — live up/down rates and session totals.
- **Info ledger** — exit IP with country flag, **Protocol**, **Endpoint** and live **Latency** (a real TCP ping to `1.1.1.1:53` *through* the tunnel, measured once on connect and then only when you tap it, so battery cost is effectively nil).

### 2 · Settings

Ordered by "how likely is this the reason you came here":

1. **Language** — because if the app is in a language you cannot read, nothing below it is usable.
2. **Engine settings** — everything that decides whether a connection succeeds. See the [full reference](#complete-settings-reference).
3. **Saved setups** — the combination that worked, re-applied in one tap, plus config export/import.
4. **Automation** — when Aether connects by itself, and what it keeps.
5. **Session history** — what the tunnel actually did, after the fact.
6. **Share VPN** and **About** — occasional, so last.

> Engine arguments are read at process launch, so settings that change them are **locked while a session is live** and a notice bar tells you so. Disconnect, change, reconnect.

### 3 · Diagnostics

The full engine console: a bounded 800-line ring buffer, UI updates throttled to about five per second, disk writes batched off the main thread, log file capped at 512 KiB with rotation. It only subscribes while it is on screen. Copy it into a bug report and someone can actually help you.

---

## Complete settings reference

<details open>
<summary><b>Connection basics</b></summary>

| Setting | Options | What it does |
|---|---|---|
| **Protocol** | Smart · MASQUE · WireGuard · Gool | Transport. **Smart** fingerprints the network first (see above). MASQUE is QUIC/HTTP-3 based, WireGuard is UDP, Gool is the engine's chained mode. |
| **Scan mode** | Turbo · Balanced · Thorough · Stealth · **Ironclad** | How hard the engine hunts for a working WARP endpoint. Budgets: 60 s / 150 s / 300 s / 240 s / 360 s. Ironclad is the most persistent, for the worst networks. |
| **IP version** | IPv4 · IPv6 · Both | Which address family the engine may use. |
| **Quick reconnect** | on/off (default **on**) | Reuse the last known-good endpoint instead of rescanning. |
| **MASQUE over HTTP/2** | on/off | Forces the MASQUE transport over H2 instead of H3 — useful where QUIC/UDP is throttled. |

</details>

<details>
<summary><b>Anti-DPI &amp; obfuscation</b></summary>

| Setting | Options | What it does |
|---|---|---|
| **Obfuscation (Noize)** | Off · Light · Firewall · Balanced · GFW · Aggressive | Amnezia-style junk packets and fake handshake signatures, so WireGuard/MASQUE stops presenting a fixed fingerprint to DPI boxes. Start at `GFW` on hostile networks. |
| **TLS fragmentation** | on/off | Splits the TLS ClientHello so SNI-based filters cannot match it in one packet. |
| **Fragment size** | e.g. `16-32` | Chunk-size range for the above. Only used when fragmentation is on. |
| **Fragment delay** | e.g. `2-10` (ms) | Inter-fragment delay range. Same condition. |
| **ECH** | on/off | Encrypted Client Hello — hides the real SNI entirely, where supported. |
| **TLS groups** | e.g. `X25519:P-256` | Restricts the offered curve groups; changes your TLS fingerprint. |
| **MTU** | 1280 · 1380 · 1420 · 1500 · 8500 | TUN MTU. **1280** is the safe default for Iranian mobile networks and aggressive DPI. |

</details>

<details>
<summary><b>Endpoint selection</b></summary>

| Setting | Options | What it does |
|---|---|---|
| **Endpoint mode** | Auto · Manual peer · Custom range | Auto lets the engine scan its built-in WARP ranges. |
| **Manual peer** | `ip:port` | Pins exactly one endpoint and **skips scanning entirely** — connects in seconds when it works. Makes scan mode irrelevant. |
| **Custom range** | `8.6.112.x`, `188.114.96.0/24, 162.159.192.0/24` | The engine scans **only** these. Multi-line and comma-separated input both work. |
| **Keepalive** | 0 · 10 · 25 · 45 s | WireGuard persistent keepalive. `0` = engine default (5 s). |

</details>

<details>
<summary><b>Security &amp; stability</b></summary>

| Setting | Default | What it does |
|---|---|---|
| **Kill switch** | off | If the tunnel drops unexpectedly, a blocking blackhole TUN stays up so **nothing** leaks outside the VPN. |
| **Strict kill switch** | off | Keeps blocking even after a *manual* disconnect, until you lift it yourself. |
| **IPv6 leak protection** | **on** | Keeps the IPv6 default route inside the tunnel. Closes the classic IPv6 leak. |
| **Smart reconnect** | **on** | Automatic engine restarts on failure. |
| **Retry limit** | 5 (range 3–20) | How many automatic restarts before an error is reported. |
| **Split tunneling** | off | Per-app include/exclude, with a built-in app picker. Nothing bypasses the tunnel unless you say so. |
| **Per-app internet blocking** | off | Selected apps get **no internet at all** while the VPN is on. Swaps in a userspace filter bridge that resolves each flow's owning app by UID; the default forwarding path stays untouched when this is off. |
| **Proxy mode** | off | Runs the engine plus a local SOCKS5/HTTP proxy **without** capturing the whole device through the system VPN. For apps that speak SOCKS5 natively (Telegram, Firefox). |

</details>

<details>
<summary><b>DNS &amp; routing rules</b></summary>

| Setting | Accepts | What it does |
|---|---|---|
| **In-tunnel DNS** | up to 8 entries: `1.1.1.1` or `1.1.1.1:53` | Resolvers used *inside* the tunnel. Blank = engine default (`1.1.1.1`, `1.0.0.1`). |
| **Block rules** | up to 256 tokens | Destinations that never reach the network at all. |
| **Direct rules** | up to 256 tokens | Destinations sent straight out, bypassing the tunnel. |

Both rule lists accept the engine's full grammar: `example.com`, `full:host`, `keyword:word`, `regexp:pattern`, `10.0.0.0/8`, `port:25`, `port:3000-3010`, `private`. Block is evaluated first, then direct, otherwise the tunnel is used.

Every entry is validated against a strict allow-list, de-duplicated and hard-capped before it becomes an engine argument — pasted text containing whitespace or shell metacharacters can never split into extra arguments.

</details>

<details>
<summary><b>Zero Trust (WARP for organizations)</b></summary>

Join a Cloudflare Zero Trust organization instead of consumer WARP.

| Setting | Options | Notes |
|---|---|---|
| **Enrolment method** | Off · Service token · E-mail one-time code · Pre-obtained token | Off = consumer WARP (default). |
| **Team name** | e.g. `acme` for `acme.cloudflareaccess.com` | The only non-secret value, and the only one passed as a command-line argument. |
| **Client ID / Client secret** | service-token flow | The secret is **never** in argv. |
| **Work e-mail** | one-time-code flow | |
| **Enrolment token** | pre-obtained JWT from `https://<team>.cloudflareaccess.com/warp` | |
| **Gateway proxy** | off | Routes http/https through the organization's Gateway so its filtering applies. **This adds a hop inside the tunnel and lets your organization log your browsing.** Off unless you need it. |

**How the secrets are handled:** on Android any app can read `/proc/<pid>/cmdline` of a process it can see, so credentials in argv would be far too widely readable. The client id, client secret, enrolment token and e-mail are handed to the engine through its **environment** instead, and at rest they are sealed with a non-exportable **AES-256-GCM** key generated inside the **Android Keystore** — not left in plain DataStore preferences where a backup or a rooted-device dump would expose them. The fields are masked in the UI.

</details>

<details>
<summary><b>Advanced engine tuning</b></summary>

| Setting | Default | What it does |
|---|---|---|
| **No data check** | off | Skips the engine's end-to-end data verification after connect. Faster, less certain. |
| **Validate seconds** | 0 (engine default) | Endpoint validation window, 1–3600. |
| **Reconnect seconds** | 0 (engine default) | Delay between engine-level reconnects, 1–600. |
| **No profile retry** | off | Do not fall back to alternate WireGuard profiles. |
| **Core log level** | Warn | `off` · `error` · `warn` · `info` · `debug`. Raise it before filing a bug. |
| **Reset** | — | One tap restores every setting to its default. |

Only flags the bundled native core actually supports are ever emitted, and every free-form value is validated first.

</details>

---

## Saved setups & config transfer

Two problems, one panel.

**1. The settings that get through change with the network.** Re-deriving eight fields from memory every time is how people give up. Name the combination that worked, tap **Save**, and re-apply it later in one tap. Named setups are listed, individually deletable, and capped.

**2. Moving a working configuration to another phone** used to mean reading fields out loud. Now it is one copyable block of text:

- **Copy config** puts your current profile on the clipboard. Send it to whoever needs it.
- **Paste config** validates the text and applies it. Invalid text is rejected with a message, never half-applied.

Because the export goes through the same profile codec the VPN service uses, **Zero Trust secrets are structurally excluded** from exported text. You cannot accidentally paste your organization's client secret into a group chat.

Applying a setup is gated the same way engine settings are: only while idle, because the engine reads its arguments at launch.

---

## Automation

Three switches that answer the three most common "why was it not on?" complaints:

| Switch | Default | Effect |
|---|---|---|
| **Connect when the app opens** | off | Starts connecting on a cold launch. |
| **Connect after reboot** | off | Reconnects once the device boots. Requires VPN consent to already have been granted. |
| **Keep session history** | **on** | Records finished sessions on this device. Turn it off and nothing is written. |

Underneath sit two shortcuts to the only **system** screens that decide whether a tunnel survives in the background: **Always-on VPN** and **battery optimisation**. No app can toggle those for you, so instead of telling you to go hunting, the buttons take you straight there. (Vendor ROMs rename and remove system activities freely, so if the screen cannot be opened you get an honest message instead of a crash.)

---

## Session history

The live traffic meter dies with the session, which made "did it hold for four hours or four minutes?" and "what did last night cost me in data?" unanswerable.

History keeps the recent sessions with **start time, duration, downloaded and uploaded bytes**, plus a totals line across all of them — the only number that matters on a metered SIM. It is stored locally in the app's private storage, it is one tap to clear, and it is one switch to never record at all.

Durations and byte counts are rendered LTR in a monospaced face even in Persian, so the BiDi algorithm can never scramble them.

---

## Tile, widget and notification

All three are fed by the same connection-state hook in the VPN service, so they can never disagree with each other.

**Quick Settings tile** — swipe down, tap the pencil/edit button, drag the **Aether** tile into your active tiles (once). After that: tap to connect, tap to disconnect, without opening the app.

**Home-screen widget** — live status plus one-tap connect/disconnect. `updatePeriodMillis=0`, so it never wakes the device on a timer; it repaints only when the connection state actually changes.

> The very first connection must be started from the app once, so Android can show its standard VPN permission dialog. If the app is already open, the tile and the widget can request that permission too.

---

## Share the tunnel with your laptop

Your phone can act as a **gateway** for other devices on the same Wi-Fi network or on your phone's hotspot.

1. Connect the VPN in Aether.
2. **Settings → Share VPN** → turn on **Share on this network**.
3. Copy either address shown:

| Type | Address | Where to put it |
|---|---|---|
| **HTTP proxy** | `<phone-ip>:10811` | System proxy settings. Windows: Settings → Network → Proxy → Manual. macOS: Wi-Fi → Details → Proxies → Web/Secure Web Proxy. Android/iOS: Wi-Fi → Modify network → Proxy → Manual. |
| **SOCKS5 proxy** | `<phone-ip>:10810` | Apps and browsers that speak SOCKS: Firefox, Telegram, and similar. |

> ⚠️ While sharing is on, **anyone on that network** can use the proxy. Only enable it on networks you trust — your own hotspot is safest. Sharing stops automatically when the VPN disconnects.

**Why 10810/10811 and not 10808/10809:** those are v2rayNG's defaults. With both apps installed, whichever started second failed to bind, or traffic was silently handed to the other tool's core. Aether also probes for known neighbours (v2rayNG, Clash, Psiphon, Privoxy) before binding and names the responsible app in the error, instead of a useless generic "port busy".

---

## Language & typography

Aether ships a complete **English** and **Persian** UI, and since v1.3.0 the choice lives **in the app** — a two-character pill in the header, one tap from the first screen. Previously the only way to reach the Persian copy was to change the language of the entire phone, which is backwards for this audience: plenty of people run an English Android and want the Persian app, and plenty run a Persian phone but prefer the English technical wording.

- **Android 13+** uses the platform per-app language API, so *every* context follows it: activities, the VPN notification, the Quick Settings tile — and the choice also appears in Android's own app-info screen.
- **Android 8–12** has no such API, so the selection is persisted and layered onto the activity's resources, which also flips layout direction for Persian.

**The type face is [Vazirmatn](https://github.com/rastikerdar/vazirmatn) 33.003, bundled into the APK** for both scripts. Five weights are downloaded once at build time into a gitignored resource source set — deliberately *not* via Google's downloadable-font provider, which resolves over the network through Play Services at runtime and would therefore fall back to the system font in exactly the situation this app exists for: a filtered network with no Play Services and no route to Google. A bundled font always renders.

Persian gets extra line leading, because Vazirmatn's Persian ink extent does not fit the tight Latin display steps. And there is a firm rule about digits: **prose keeps the locale's numerals, instrument readouts do not.** Latency, traffic, durations and the elapsed clock are always Latin digits, LTR, monospaced — one numbering system per readout, never two on one screen.

---

## Design system: Carbon & Signal

v1.3.0 threw out the old deep-navy-plus-neon-cyan look, which was both what every other tunnel app looks like and semantically meaningless (cyan meant "connected", and also icons, links and selected chips).

**Neutrals are warm carbon**, tinted a hair toward the accent hue. Nothing is `#000` or `#FFF`: flat black crushes OLED gradients and pure white glares in a dark UI.

**Colour is semantic and rationed.** Nothing decorative is ever tinted, so when the screen goes lime you know what that means from across the room:

| Token | Colour | Meaning |
|---|---|---|
| **Signal** | `#A9E34B` lime | You are protected. |
| **Ember** | `#E9B44C` amber | Working: connecting, scanning, verifying. |
| **Clay** | `#E8674A` clay | Failed. |
| **Carbon 00 → 40** | `#090B08` → `#333D2B` | Backgrounds, surfaces, hairlines, outlines. |
| **Chalk** | `#EDF2E6` / `#A6B09B` / `#6C7663` | Primary / secondary / tertiary text. |

**Dynamic colour (Material You) was removed on purpose.** Wallpaper-derived schemes broke three things: state colour stopped meaning anything (on a warm wallpaper "safe" could render the same orange as "failed"), contrast became a lottery that could erase hairlines and the log console entirely, and a censorship-circumvention tool needs a recognisable identity — if every phone renders it differently, screenshots in a support thread stop matching what the reporter sees.

The full `surfaceContainer*` family is specified explicitly too, which is why menus and bottom sheets no longer show up as slightly purple grey boxes matching nothing else on screen.

One more thing that went away: **the aurora animation.** Three large radial gradients were being composited full-screen behind every screen for as long as the app was open. The backdrop is now a static fill that never invalidates, so menus, sheets and the connect screen get the whole frame budget.

---

## Install & update

### Requirements

- **Android 8.0 (API 26) or newer.**
- The standard Android **VPN permission** prompt, and notification permission for the ongoing status.
- No Play Services, no root, no account.

### Which APK

| File | For which phone |
|---|---|
| `Aether-1.3.0-arm64-v8a.apk` | Almost all modern phones (64-bit ARM). |
| `Aether-1.3.0-armeabi-v7a.apk` | Older / low-end 32-bit phones. |
| `Aether-1.3.0-universal.apk` | **If you are not sure, this one.** Works on any ARM phone. |

### Updating: do I have to uninstall?

Android installs an update **on top of** the old app only when both APKs carry the **same signing certificate**.

- **From v1.1.0 onward:** just download and install. It updates in place, settings intact. Never uninstall.
- **From v1.0.0 or older:** one final uninstall, because those builds used a throwaway debug key. After that, never again.

This is now enforced by the build, not by hope: CI extracts the signer's SHA-256 fingerprint from every release APK and compares it against a pinned value. **If the certificate ever changes, the build fails instead of publishing an APK users cannot install.** The build also refuses to mint a fresh CI keystore in a repository that has already published with one.

### There is no in-app updater, deliberately

> For the security of our users, for complete transparency, and to guarantee the authenticity of the code they receive, the direct in-app update capability was removed in v1.2.2. All updates come exclusively from the project's official GitHub Releases page, officially signed, which prevents any unwanted download from unknown sources.

What that removal actually deleted: the background GitHub-querying downloader, the in-app "install now" card, the `FileProvider` that handed APKs to the system installer, and — most importantly — the **`REQUEST_INSTALL_PACKAGES` permission**, which was the single highest-risk permission in 1.2.1. **There is no code path left in Aether that can fetch or execute a new binary.**

---

## Build it yourself

You do **not** need Android Studio. GitHub Actions builds everything.

1. Fork or create a repository and push these sources.
2. Open the **Actions** tab and enable workflows if prompted.
3. Every push to `main` builds the app. For installable release files, push a tag:
   ```bash
   git tag v1.3.0
   git push origin v1.3.0
   ```
4. When the run finishes, **Releases** contains the three APKs. Release titles are clean (`Aether v1.3.0`) and the "What's new" text comes from `.github/release-notes.md` — update it together with the version.

### Signing with your own key (recommended for real distribution)

| Secret | Meaning |
|---|---|
| `KEYSTORE_BASE64` | Your `.jks` keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create the base64 with `base64 -w0 my-release-key.jks > keystore.txt`, then add the Secrets under **Settings → Secrets and variables → Actions**. They always take priority over the repository's fallback CI keystore. Locally, a `keystore.properties` file works too (see `keystore.properties.example` and [`docs/SIGNING.md`](docs/SIGNING.md)).

> ♻️ **Keep the same repo, keep the key.** The fallback stable key lives in `.github/ci-keystore.jks.b64` inside *your* repository. When you upload newer sources, replace changed files in the **same** repo and **do not delete** that keystore file — that is exactly what lets a new version install on top of the one already on the phone.
>
> 🔒 A keystore committed to a public repo is public. It guarantees **updatability**, not **authenticity** — anyone could sign an APK with it. This risk is disclosed openly rather than hidden. For serious distribution, set the Secrets above, and always verify the signer fingerprint of what you download.

### How the native parts are built

Upstream Aether publishes no Android binaries, so CI builds them from source. Native blobs and font files are **never committed**.

| Script | Job |
|---|---|
| `scripts/fetch-natives.sh` | Clones `hev-socks5-tunnel` and the Aether engine source. |
| `scripts/build-natives.sh` | Builds hev with `ndk-build` into `libhev-socks5-tunnel.so`, cross-compiles the engine with `cargo-ndk` into `libaether.so`, for `arm64-v8a` and `armeabi-v7a`. |
| `scripts/fetch-fonts.sh` | Fetches the five Vazirmatn weights. Gradle's `fetchVazirmatn` task does this automatically too. |
| `scripts/sync-core.sh` | The engine auto-upgrade (below). |
| `scripts/test-core-sync.sh` | Replays the whole upgrade path offline against a local repository. |
| `scripts/purge-stale-sources.sh` | Deletes paths listed in `.github/removed-sources.txt` before compiling. |
| `scripts/core-rollback.sh` | Restores the previous engine snapshot. |

Pin versions with `HEV_REF`, `AETHER_REPO`, `AETHER_REF`. Before publishing, CI verifies every APK and **refuses the release** unless both native cores are present for every included ABI. Gradle is pinned at 8.9 to match AGP, so the toolchain cannot drift underneath the project.

### The engine upgrades itself, safely

The build owns the engine version. On every run, `sync-core.sh` queries the official [Aether Core repository](https://github.com/CluvexStudio/Aether), compares the latest upstream release against `native/aether/CORE_VERSION`, and upgrades the vendored engine when a newer one exists. Currently pinned at **core 1.7.0**.

- **App patches are rebased, not copied.** The custom range-scanning code (`prober.rs`, `wg_prober.rs`) is carried across upgrades with a real **three-way merge** against the pristine upstream baseline in `native/aether/.upstream-baseline/`, so upstream API changes and the app's additions combine correctly. If a file cannot be merged, the build keeps the **pure upstream** version (guaranteed to compile) and flags the run for review — it never forces a stale copy.
- **A core upgrade can never break a release.** The previous engine is snapshotted first. If the new core does not compile for any reason, CI rolls back automatically, rebuilds and continues. The new `CORE_VERSION` is committed **only after the engine has actually built**.
- **Fail-safe.** If GitHub is unreachable or the upstream layout is unexpected, the build keeps the vendored core and continues. The sync only ever moves **forward**; it never downgrades.
- **New engine capabilities are surfaced, not hidden.** After an upgrade the script scans the new core for command-line capabilities the UI does not expose yet and reports them in the build log, so no engine feature can quietly ship without a matching UI decision.
- **The engine version is visible in the app.** `BuildConfig.CORE_VERSION` is stamped at build time from `native/aether/CORE_VERSION` and shown in **About**, so it can never drift from the engine that is actually shipping.

---

## Security posture

Every release since 1.2.1 has been audited line-by-line and scored out of 100. Reports live in [`docs/`](docs):

| Release | Score | Report |
|---|---|---|
| 1.2.4 | **92 / 100** | [`docs/SECURITY_AUDIT_1.2.4.md`](docs/SECURITY_AUDIT_1.2.4.md) |
| 1.2.3 | **88 / 100** | [`docs/SECURITY_AUDIT_1.2.3.md`](docs/SECURITY_AUDIT_1.2.3.md) |
| 1.2.2 | full line-by-line pass | [`docs/SECURITY_AUDIT_1.2.2.md`](docs/SECURITY_AUDIT_1.2.2.md) |
| 1.2.1 | initial hardening | [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md) · [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md) |

### Current standing, by area

| Area | Result |
|---|---|
| **Keys & secrets** | No API keys, tokens, passwords or private keys in the app. All tunnel key material is generated at runtime inside the engine. Zero Trust secrets are sealed in the Android Keystore (AES-256-GCM) and reach the engine only via its environment. |
| **Cryptography & protocols** | WireGuard and MASQUE/QUIC with TLS 1.3, ECH and ClientHello fragmentation. No custom crypto, no weak or deprecated primitives, no custom `TrustManager`/`HostnameVerifier`, no downgrade path. |
| **Leaks** | Full IPv4 **and** IPv6 default routes captured by the tunnel (classic IPv6 leak closed), in-tunnel DNS actively verified before the UI says "Connected", kill switch available. The only public egress is the anonymous IP-echo probe behind the flag badge, which carries no user data. |
| **Traffic bypass** | User-controlled only. Split tunneling is off by default; nothing leaves the tunnel unless you configure it. |
| **Local storage** | App-private DataStore, `allowBackup` constrained by backup rules that exclude settings and logs, no exported `Provider`. Log file bounded and rotated. |
| **Permissions & manifest** | Minimal. No `REQUEST_INSTALL_PACKAGES`, no `QUERY_ALL_PACKAGES`, no location/contacts/storage. `android:debuggable` absent in release. Only the launcher activity is exported; the widget provider and crash-report activity are not. |
| **Logging** | In-memory only. Logcat output is compiled out of release builds. No keys, DNS queries, hostnames or packet payloads are ever written. |
| **Dependencies & network** | No ad, analytics or crash-reporting SDKs. Native cores built from source in CI. Cleartext traffic denied app-wide; user-installed CAs are not trusted. |
| **Input validation** | Every free-form field (DNS, routing rules, ranges, fragment ranges, TLS groups) is allow-listed, de-duplicated and hard-capped before it can become an engine argument. |

**Crash reports stay on the device.** A fatal exception is written to a file in the app sandbox, shown to you on the next launch with a copy button, and deleted when you close it. The report screen is not exported. Nothing is transmitted anywhere.

**One risk is accepted and disclosed openly rather than hidden:** the fallback CI keystore in the repository is public by design. It guarantees update continuity, not authenticity. Always download from the official Releases page and verify the signer fingerprint.

---

## Troubleshooting

<details open>
<summary><b>It says Connected but nothing loads</b></summary>

This should not happen any more — since 1.2.1 the app stays in **Verifying** until the local port, handshake, real internet reachability and IP lookup all pass. If it still does: open **Diagnostics**, look for watchdog restarts, then try obfuscation `GFW` plus MTU `1280`.

</details>

<details>
<summary><b>It works for a minute or two, then dies</b></summary>

Fixed at the root in 1.2.4: a watchdog probes the tunnel end-to-end every 30 s and restarts the engine on sustained failure, and idle timeouts were raised (TCP 60 s to 5 min, UDP to 120 s). If it persists, raise **Retry limit**, enable **Quick reconnect**, and add Aether to the battery-optimisation exemption list from **Settings → Automation**.

</details>

<details>
<summary><b>It never connects at all on my network</b></summary>

In this order: **Obfuscation → GFW** (then `Aggressive`), **MTU → 1280**, **TLS fragmentation on**, **ECH on**, **Scan mode → Ironclad**, and if UDP is throttled, **Protocol → MASQUE with HTTP/2 on**. If you know a working endpoint, **Endpoint mode → Manual peer** skips scanning entirely and connects in seconds. Save whatever works as a named setup so you never have to rediscover it.

</details>

<details>
<summary><b>Port busy, or a conflict with another VPN app</b></summary>

Aether's sharing bridge uses **10810/10811** specifically to stay off v2rayNG's 10808/10809. It also detects known neighbours (v2rayNG, Clash, Psiphon, Privoxy) before binding and names the culprit in the error message. Close the other tool's tunnel and retry.

</details>

<details>
<summary><b>The tunnel dies when the screen is off</b></summary>

That is the OS, not the app. **Settings → Automation** has direct buttons for the two system screens that control it: **Always-on VPN** and **battery optimisation**. Exempt Aether from battery optimisation and enable always-on VPN.

</details>

<details>
<summary><b>Settings are greyed out</b></summary>

By design. Engine arguments are read at process launch, so changing them mid-session would silently do nothing until the next connect. Disconnect, change, reconnect.

</details>

<details>
<summary><b>The update will not install</b></summary>

If you are coming from v1.0.0 or older, uninstall once — those builds used a throwaway key. From 1.1.0 onward, updates install in place. If a 1.1.0+ build refuses, you are probably mixing APKs from two different repositories with two different keystores; stick to one source.

</details>

<details>
<summary><b>Something else — how do I report it usefully</b></summary>

Set **Core log level** to `debug`, reproduce the problem, open **Diagnostics**, copy the log, and open an issue with your Android version, phone model, carrier/ISP and the settings you used. The log is in-memory and contains no keys, hostnames or payloads.

</details>

---

## FAQ

**Is it free? What is the catch?**
Free, AGPL-3.0, no ads, no analytics, no accounts. The catch is the one stated above: your exit is Cloudflare's WARP network, and Cloudflare can technically see traffic exiting through it.

**Why is there no country or server list?**
Because it would be a lie. WARP addresses are anycast, so a country menu could not have moved you to that country. Details [above](#where-your-data-goes-read-this-one).

**Can I choose my exit country anyway?**
No. What you *can* do is pin a specific endpoint or scan a specific range, and the engine will use exactly that.

**Does it need root?**
No.

**Does it work without Google Play Services?**
Yes, entirely. That is why the font is bundled instead of fetched at runtime.

**Can I use it alongside v2rayNG?**
Yes, though only one system VPN can be active at a time. Port conflicts were resolved in 1.2.2.

**Does it log anything?**
The diagnostics log is in-memory and bounded, rotated on disk at 512 KiB, and contains no keys, DNS queries, hostnames or payloads. Session history (duration plus byte counts) is local and can be switched off. Nothing leaves your device.

**Can I use my company's Cloudflare Zero Trust account?**
Yes, four enrolment methods are supported. See the Zero Trust section of the [settings reference](#complete-settings-reference).

---

## Version history

**Current: app 1.3.0 · version code 10 · engine core 1.7.0 · compileSdk 37 · minSdk 26**

<details open>
<summary><b>v1.3.0 — the redesign release</b></summary>

- **New app shell.** The hamburger drawer and the duplicated advanced-settings bottom sheet are gone, replaced by **three destinations on a bottom bar** (Connection / Settings / Diagnostics) with the primary action pinned above it. This fixed discoverability (nothing on the old home screen implied the answer to a problem was behind a hamburger), duplication (settings existed in two places with two different expand states), and cost (a drawer composes its content while closed; destinations do not).
- **Carbon & Signal palette.** A fixed, hand-built dark scheme with semantic colour. **Dynamic colour removed** — see [the reasoning](#design-system-carbon--signal).
- **Vazirmatn bundled** for both scripts, with extra leading in Persian and a strict digits rule for instrument readouts.
- **In-app language switch** — a pill in the header, platform per-app locale API on Android 13+, resource layering below that.
- **Saved setups plus config copy/paste**, with Zero Trust secrets structurally excluded from exports.
- **Automation** — connect on app launch, connect after reboot, and direct shortcuts to the Always-on VPN and battery-optimisation system screens.
- **Session history** — duration and byte counts per session, with totals, clearable and switchable.
- **Accessibility.** The connect ring and the tab bar are properly labelled: the bar announces "Settings, tab, 2 of 3, selected" instead of three identical buttons.
- **Engine upgraded to core 1.7.0.**

</details>

<details>
<summary><b>v1.2.5 — engine 1.6.0 plus a debug and clean-up pass</b></summary>

- **Advanced settings now actually reach the engine.** In-tunnel DNS, block/direct routing rules and the whole Zero Trust block were saved and displayed, but the profile encoder silently dropped them, so the engine never received `--dns`, `--route-block`, `--route-direct`, `--team` or `--gateway`. Multi-line fields no longer truncate. Zero Trust secrets are read from the Keystore-sealed store instead of travelling inside an Intent.
- **No more CPU drain in per-app blocking mode.** The filter bridge read a non-blocking TUN descriptor, where an idle tunnel returns "0 bytes" forever, and the reader pinned a core for the whole session.
- **Stalled connections in that mode are gone.** A segment dropped because the writer queue was momentarily full still advanced the TCP sequence number, leaving a hole nothing could retransmit.
- The traffic meter works with per-app blocking on; a system-killed service reconnects with your real profile; the tile and widget can grant VPN permission again; crash reports keep the fatal line; the routing cache is bounded; DNS answer parsing is bounds-checked.

</details>

<details>
<summary><b>v1.2.4 — kill switch, leak protection, engine tuning (92/100 audit)</b></summary>

- **Kill switch** and **strict kill switch**; **IPv6 leak protection** (on by default); **smart reconnect** with a configurable retry limit.
- **Per-app internet blocking** via a new userspace filter bridge that resolves each flow's owning app.
- **Advanced engine settings**: fragment size/delay ranges, no-data-check, TLS groups, validate/reconnect seconds, no-profile-retry, core log level.
- **"Works 1–2 minutes then nothing opens" fixed** with an end-to-end watchdog and raised idle timeouts, then fixed *properly* by making the probe multi-attempt and multi-target so self-healing stalls do not kill healthy sessions.
- Desktop-parity info row: Protocol, Endpoint and live Latency under the IP badge.

</details>

<details>
<summary><b>v1.2.3 — engine 1.5.0, Zero Trust, routing rules (88/100 audit)</b></summary>

- **Engine upgraded to core 1.5.0 with the app patches rebased properly.** The previous release pinned `CORE_VERSION` at 1.4 while the vendored sources were actually upstream 1.3.0, so the merge-base logic had nothing valid to compare against and silently dropped the app's own engine patches. The baseline was identified, patches re-applied with a genuine three-way merge, and the baseline cache repaired.
- **Zero Trust (WARP for organizations)** with four enrolment methods and an opt-in Gateway toggle.
- **Routing rules** (block plus direct) with the engine's full grammar, and **custom in-tunnel DNS**.
- **Engine version now visible in About**, stamped from what the build actually vendored.
- Secrets moved out of argv into the environment and sealed with an Android Keystore AES-256-GCM key.

</details>

<details>
<summary><b>v1.2.2 — CI owns the engine, in-app updater removed, big perf pass</b></summary>

- **Automatic core sync on every build**, with three-way merged app patches, snapshot rollback if the new core does not compile, and fail-safe behaviour when GitHub is unreachable.
- **In-app update system removed**, along with `REQUEST_INSTALL_PACKAGES` and the `FileProvider`.
- **Country picker removed** — it could never really choose a country. Endpoint selection handed back to the engine.
- **Protocol switching no longer stalls**; **disconnect is instant again** (a 30–50 s freeze traced to a blocking `Process.waitFor` that coroutine cancellation could not interrupt); **a hand-picked protocol gets a real second chance** with anti-DPI hardening instead of one all-or-nothing attempt.
- **Memory leak in the diagnostics log fixed** (bounded ring buffer, throttled UI, batched writes, rotated file); **idle CPU cut sharply** (the supervisor blocks on the process instead of polling every 2 s); busy-waiting after connect removed; fewer threads during connection.
- **v2rayNG port conflict resolved** (10810/10811 plus neighbour detection).
- **Signature continuity enforced in CI**, so an uninstallable APK can never be published.
- **The aurora animation removed**; the drawer no longer composes while closed; the diagnostics log only subscribes while open.

</details>

<details>
<summary><b>v1.2.1 — honest connection state plus Smart Auto</b></summary>

- **"Connected" now means connected** — a new **Verifying** state until all four health checks pass.
- **Smart Auto introduced.** Auto mode previously sent no protocol flags at all and relied blindly on engine defaults. Now the app fingerprints the network's DPI first and walks a ranked strategy ladder.
- IP and flag appear much faster (three lookup services queried in parallel); Persian BiDi digit-scrambling fixed at the root in `ip:port` fields; new **Reset** button; TLS hostname verification on built-in probes; cleartext HTTP denied app-wide.

</details>

<details>
<summary><b>v1.2.0 — the full advanced feature set</b></summary>

Amnezia-style obfuscation (Noize) with six profiles · new **Ironclad** scan mode · manual peer and custom scan ranges · WireGuard keepalive · adjustable MTU · TLS ClientHello fragmentation · ECH · proxy mode · per-app split tunneling · correct exit-IP and traffic readouts · reliable in-place updates.

</details>

<details>
<summary><b>v1.1.0 — tile, sharing, stable signing</b></summary>

Quick Settings tile · VPN sharing over Wi-Fi/hotspot · advanced settings reachable from the home screen · builds signed with one stable key so updates install in place.

</details>

---

## Credits & license

| Project | Role |
|---|---|
| [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) | The engine core. |
| [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | The TUN-to-SOCKS5 tunnel core. |
| [rastikerdar/vazirmatn](https://github.com/rastikerdar/vazirmatn) | The type face, both scripts. |
| [Cloudflare WARP](https://www.cloudflare.com/application/privacy/) | The exit network. |

Released under **AGPL-3.0**. See [LICENSE](LICENSE).

<div align="center">

**Built for people on networks that fight back.**

[Releases](https://github.com/MrMatin0/Aether/releases/latest) · [Issues](https://github.com/MrMatin0/Aether/issues) · [فارسی](README.fa.md)

</div>
