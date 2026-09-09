# Tor bridges

How this app gets Tor into the network when the Tor network itself is blocked:
the wiring, the three ways a bridge can arrive, the one rule that is a hard
requirement rather than a nicety, and what is honestly not solved.

## The problem

Tor's public relay list is public. A censor downloads it and blocks it, and in
Iran that is exactly what has happened: `ChainMode.TOR` fails on many networks
not because Tor is slow but because every relay address is on a list. A **bridge**
is a relay that is not in that list. A **pluggable transport** goes further and
changes what the traffic to it looks like, so a DPI box cannot recognise Tor even
when it can reach the address.

This app already had two answers to that problem (`Tor over Aether`, `Tor over
Psiphon`) and they are still the fastest ones to try. Bridges are the third, and
the only one that works when what you have is a bridge somebody sent you and no
working tunnel at all.

## What a session looks like

```
device -> SocksFront -> tor -> [obfs4 / snowflake / webtunnel / meek] -> bridge -> Tor network
```

Nothing about the chain changes. `UseBridges 1` tells tor to enter through the
bridges it was given instead of through a guard relay it picked itself; the
transport binary is a child of tor, not of this app.

With a chain underneath (`Tor over Aether`), tor's `Socks5Proxy` line is exported
to the transport as `TOR_PT_PROXY`, and both lyrebird and snowflake honour it. So
"obfs4 over Aether" is one configuration and not two features fighting.

## Three sources, three failure modes

The Bridges page is built around the fact that these fail differently, and that
the user has to be able to pick the next one when the current one stops working.

| Source | What it is | How it fails |
|---|---|---|
| **Built-in** | The public list Tor Browser ships, per transport | Everybody has it, so a censor can have it too. First to be blocked. |
| **Personal** | Addresses moat hands to this device alone | Needs a working path to bridges.torproject.org, and the pool is rate-limited per subnet. |
| **Pasted** | A line from email, Telegram or a friend | Only as good as where it came from. |

Built-in bridges are compiled INTO the app (`core/BridgeCatalog.kt`), refreshed
at build time by `scripts/fetch-tor-bridges.sh`, and refreshable at runtime. Same
argument as `PsiphonCore.builtInConfig`: a feature whose first use needs a
successful network request on a censored network is a feature that does not work
when it is needed. Later sources win only when they parse and contain something,
so a truncated asset or a captive-portal page cannot empty the picker.

## The rule that is not defensive programming

> tor treats a `Bridge <transport> ...` line with **no matching
> `ClientTransportPlugin`** as a FATAL configuration error and exits during
> startup.

It does not ignore the line. So a build that ships no `liblyrebird.so` and offers
obfs4 anyway does not degrade to a direct connection - it produces a Tor hop that
dies instantly, with nothing in the log about bridges. That is why:

* `core/PluggableTransports.kt` reads the payload once and reports which binaries
  this build actually has;
* the Bridges page **disables** a type it cannot run and names the missing binary;
* `BridgeLine.usable()` filters the list, and BOTH the settings page and
  `Torrc.build` go through it, so they cannot disagree;
* `UseBridges 1` is only written when at least one line survived, because
  `UseBridges` with an empty bridge list is a tor that cannot reach anything.

Plain (vanilla) bridges need no plugin at all, which is why they are a
first-class option here rather than an afterthought: they are the only kind that
work in a build with no transports.

## Reaching the bridge server

Tor Browser fetches bridges through **domain fronting** - a meek request that
looks like traffic to a large CDN - precisely because a network that blocks Tor
also blocks the site that hands out bridges. This build has no meek binary of its
own to front with, and pretending otherwise would ship a button that only works
on networks where it is not needed.

What this app has instead is a tunnel of its own. So every moat request is tried
**through the live session's SOCKS5 entry** first when there is one, and
**directly** otherwise, falling back from one to the other:

* on a network that blocks Tor, direct is usually blocked too - that is the whole
  point of a bridge;
* a tunnel whose exit is somewhere moat rate-limits can fail where direct works.

The UI reports which path produced the answer, because "this only worked through
Aether" is what tells a user not to disconnect before they have their bridges.
The hostname is sent to the proxy as a DOMAIN (`ATYP=0x03`), so a local resolver
that lies about `bridges.torproject.org` - the cheapest way to block it - is never
consulted.

## The moat API

Reference: `doc/moat.md` in `tpo/anti-censorship/rdsys`. Everything answers HTTP
200, **including errors**, so the error object is the only failure signal -
`MoatPayloads` throws on it rather than reading it as "no bridges found".

| Endpoint | Used for |
|---|---|
| `/moat/circumvention/builtin` | Refresh the public list |
| `/moat/circumvention/settings` | "What works in my country", including bridges handed over on the spot (`source: bridgedb`) |
| `/moat/circumvention/defaults` | Fallback when moat has no advice for that country |
| `/moat/fetch` + `/moat/check` | The captcha flow that hands out a personal bridge |

**About that captcha.** When the Tor Project retired BridgeDB in October 2024,
`/fetch` started returning a static image and `/check` stopped verifying the
answer (see the tor-relays announcement and
`tpo/applications/tor-browser#42086`). The endpoints still hand out bridges, so
this app still walks the documented flow - but the dialog does not insist on the
answer, and it says why. If verification ever returns, a wrong answer comes back
as a 419 and the page says exactly that.

JSON is parsed by `core/moat/JsonLite.kt`, ~150 lines, no dependency. Not
regexes: Go's `encoding/json` HTML-escapes by default, so a snowflake `url=`
parameter arrives carrying `\u0026`, and handing that to tor verbatim produces a
bridge that silently does not work. Unescaping is part of parsing, so parsing it
properly is the smaller job. `org.json` was not an option either - it is a stub
in the local unit-test JVM, and these payloads are the ones that most need tests.

## Nothing reaches the torrc unparsed

`core/BridgeLine.kt` is the only path from text to a `Bridge` line. A torrc is
line-based, so an embedded newline in a "bridge line" is not a bad bridge, it is a
free torrc option - and `ExitNodes`, `Socks5Proxy` and `HTTPTunnelPort` are all
things worth setting on somebody else's tor. So every line is tokenised, every
token is validated, and the line that is written is REBUILT from the tokens that
survived. `\`, `"`, `#` and `|` are rejected outright: the first three are torrc
syntax, and the fourth is what `ProfileCodec` folds bridge lines on.

That fold deserves its own note. The codec is line-framed, and its existing
free-text fold joins lines with a **comma** - which a snowflake bridge line is
full of (`fronts=a,b`, eight comma-separated `ice=` servers). Folding on a comma
would corrupt exactly the transport a user reaches for when obfs4 is already
blocked.

## Building the transports

They are **optional**, like the overlay cores.

```bash
export ANDROID_NDK_HOME=/path/to/ndk
bash scripts/build-pt-transports.sh all     # or: lyrebird | snowflake | webtunnel
bash scripts/fetch-tor-bridges.sh           # needs no NDK
```

| Binary | Transports | Source |
|---|---|---|
| `liblyrebird.so` | `obfs4`, `meek_lite` | lyrebird (obfs4proxy, renamed in 2023) |
| `libsnowflake.so` | `snowflake` | snowflake `client` |
| `libwebtunnel.so` | `webtunnel` | webtunnel `main/client` |

All three are plain Go with no C dependency chain, so unlike tor itself there is
no reason to take a prebuilt binary. They are packaged under `.so` names for the
same reason the cores are: `nativeLibraryDir` is one of the few places Android
still allows exec from. One binary serves two transports for lyrebird, which is
why the torrc gets one `ClientTransportPlugin obfs4,meek_lite exec ...` line
rather than two.

## What is not solved

* **No domain fronting.** See above. Fetching bridges needs a path that already
  works, which is a real limitation and is said in the UI rather than hidden.
* **No Conjure, no WebTunnel bridge discovery.** moat hands out obfs4 and
  snowflake; a webtunnel bridge has to be pasted.
* **Bridges do not make Tor fast.** Three relays plus an obfuscation layer plus
  whatever is underneath it. Snowflake in particular varies by an order of
  magnitude depending on which volunteer answers.
* **A bridge in a screenshot is a burned bridge.** Personal bridges are handed to
  one person; publishing one gets it blocked for everybody who was given it.
* **Still no UDP.** Unchanged by bridges: see `docs/CHAINING.md`.

## Files

| File | Role |
|---|---|
| `model/Bridges.kt` | the transports, the binaries that speak them, and provenance |
| `core/BridgeLine.kt` | the parser (pure, unit-tested) |
| `core/BridgeCatalog.kt` | built-in bridges: compiled in, asset, or refreshed |
| `core/PluggableTransports.kt` | which PT binaries this build ships |
| `core/BridgeService.kt` | the suspend API the settings page calls, and the route policy |
| `core/moat/JsonLite.kt` | the JSON reader |
| `core/moat/MoatPayloads.kt` | the wire format (pure, unit-tested) |
| `core/moat/MoatClient.kt` | one bounded HTTPS request, direct or through the tunnel |
| `data/BridgeStore.kt` | the refreshed catalogue and the personal bridges |
| `core/TorCore.kt` | `Torrc` emits `UseBridges` / `ClientTransportPlugin` / `Bridge` |
| `ui/engine/BridgeSection.kt` | the page |
| `ui/engine/BridgeRequestDialog.kt` | the captcha step |
| `scripts/build-pt-transports.sh` | the three transport binaries |
| `scripts/fetch-tor-bridges.sh` | the built-in list |
