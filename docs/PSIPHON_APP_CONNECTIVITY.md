# Root-cause analysis - Psiphon exit: Instagram, YouTube and the AI apps report "no internet" while the app says Connected

Version: **1.4.7** (fix pass on top of 1.4.6).
Report: with a Psiphon chain mode connected, some apps (Instagram, YouTube, and
others of the same family) do not work, while the app itself shows a healthy
tunnel that passed its own four-step self-test.

## Why the upstream commit could not simply be taken

The fix that was pointed at upstream (`QW-AI-Code/Aether@86b88ba`) is the whole
1.2.9 drop: the Gemini assistant, log encryption, LAN-share authentication and
an engine bump to core 1.9.0. It contains nothing about this symptom. The real
upstream work is `docs/PSIPHON_APP_CONNECTIVITY.md` in the 1.2.7-r3 pass, and it
touches `transport/PsiphonSocksFront.kt` - a file this fork does not have. This
fork runs psiphon-tunnel-core's **console client as a child process** with its
own DNS-capable front (`core/SocksFront.kt`), so the defects had to be found
again in this tree. They were, and there are three of them.

## 1. The TUN advertised IPv6 on an exit that has none

`TunFactory.establishSession` always called `addAddress(TUN_IPV6)`.

The `::/0` **route** is correct - it is what stops IPv6 leaking past the tunnel.
The **address** is the damage: it is the one thing that tells Android's resolver
that this network has IPv6, and from that moment `getaddrinfo` hands AAAA
records to every app and Happy Eyeballs prefers them. A Psiphon exit is
IPv4-only in practice, so every one of those is a connection that must fail
first - and it also fails the platform's own network validation, which is what
makes an app say "no internet" before it has tried anything.

The decisive clue in the field is always the same: tether the phone to a laptop
and the same sites open instantly in the laptop's browser, in the same session.
A tethered proxy path is TCP-only and IPv4-only. So the failure is not the
tunnel, the exit or the ISP - it is exactly what the tether path strips.

**Fix.** With a Psiphon exit the TUN carries **no IPv6 address** while still
routing `::/0`. `netd` decides whether to return AAAA by testing for a usable
IPv6 *source* address, so the platform filters AAAA per network for every app by
itself. If a ROM refuses that interface shape, the addressed one is
re-established automatically and the AAAA guard in §3 carries the fix alone.

## 2. `::/0` was optional, and an uncaptured IPv6 flow is a country block

The v6 default route was only added when `ipv6LeakProtection` was on. With it
off, an IPv6 flow the app never captured leaves with the phone's real address,
and the site answers with a regional block - while the app still shows
Connected. That is strictly worse than having no IPv6 at all.

**Fix.** On an IPv4-only exit `::/0` is routed unconditionally. Everywhere else
the user's setting is honoured exactly as before.

## 3. AAAA was forwarded to a resolver that answers it

`SocksFront` forwarded every port-53 datagram to its resolvers, including AAAA,
so even with §1 fixed the app could still be handed IPv6 addresses.

**Fix.** The front asks the upstream **once, up front, on a background thread**
whether it can dial IPv6 at all (a SOCKS5 CONNECT to two anycast IPv6
literals). Only when the exit has **proven** it cannot are AAAA queries answered
locally with an empty `NOERROR` - NODATA, which is exactly what a resolver on an
IPv4-only network returns. Three states, and the middle one matters: an
inconclusive probe behaves exactly like the old code, because guessing would
take IPv6 away from an exit that has it.

Why up front and once: deciding it lazily makes the first app flows of every
session pay for the discovery, which is the latency a user reads as "it connects
but nothing opens".

## 4. Every DNS name cost a new tunnelled connection

`resolveViaSocksTcp` opened a fresh TCP connection **and** a fresh SOCKS5
handshake **per name**, through the chain, with eight workers and no reuse.

This is the signature of the second field report, "only Telegram and Instagram
work", and it is a DNS failure with a fingerprint: Telegram dials hard-coded
datacentre IPs and needs no resolver at all, a warm Instagram has its addresses
cached, and a browser needs twenty to thirty fresh names to render one page.

**Fix.** Connections are pooled per resolver and reused (`DNS_IDLE_MS`,
`DNS_POOL_MAX`), so a page load costs one round trip per name on a warm
connection. Reuse is only safe with one extra check, and it is enforced: the
answer's transaction id must match the question's. If an earlier query on that
connection timed out, its late answer is the next thing in the stream, and
handing that to the caller would answer one name with another's addresses. Any
failure closes the connection instead of returning it to the pool.

## What this does NOT fix: QUIC / UDP 443

Stated plainly, because presenting a tuning change as a cure is how a bug gets
closed twice. `SocksFront` still drops UDP/443:

* its upstream is psiphon-tunnel-core's local SOCKS5, which implements CONNECT
  only - there is no path for a datagram;
* SOCKS5 UDP ASSOCIATE has no way to return an ICMP port-unreachable, so a
  dropped datagram is indistinguishable from a dead link to a client that pins
  HTTP/3 for its own origins (Cronet-based apps do exactly that);
* the default forwarder is the in-process hev core, so there is no userspace
  packet path to synthesize an ICMP error on either - the filter bridge only
  runs when per-app blocking is configured.

Carrying it needs a udpgw-style relay on the Psiphon hop. Until then the drop is
at least **visible**: undeliverable datagrams are counted per session, and the
first UDP/443 one writes a line naming what it means. Browsers race HTTP/3
against TCP and are unaffected; the apps in this report should now work over
TCP, which is what §1-§4 unblock.

## Files touched

* `app/src/main/java/studio/cluvex/aether/vpn/session/TunFactory.kt`
* `app/src/main/java/studio/cluvex/aether/core/SocksFront.kt`
* `app/src/main/java/studio/cluvex/aether/core/DnsMessage.kt` (new)
* `app/src/test/java/studio/cluvex/aether/core/DnsMessageTest.kt` (new)
* `docs/PSIPHON_APP_CONNECTIVITY.md` (this file)

## Before tagging v1.4.7

`app/build.gradle.kts` still reads `appVersionName = "1.4.6"` /
`appBaseVersionCode = 14`. Both have to move together, or Android sees the new
APK as the same build and refuses to install it as an update. Deliberately left
out of this change so that the release identity is bumped once, by the release,
rather than by a bug fix.
