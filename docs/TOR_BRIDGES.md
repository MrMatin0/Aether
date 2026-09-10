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

## On by default, and why

Bridges used to default to `OFF`. That was wrong, and not in a matter-of-taste
way: the setting is only ever read for a session whose chain contains Tor, and
the reason somebody picks a Tor chain mode is that the ordinary internet is not
working for them. On those exact networks a bridgeless tor sits at 5% for four
minutes and reports Tor as blocked. So the feature that makes the Tor hop work
was one the user had to go and find first, in a page they had no reason to suspect
existed.

What ships now:

* `ConnectionProfile.torBridgeMode` defaults to `BUILTIN`, with obfs4 selected;
* `ProfileStore` seeds the bundled catalogue into `torBridgeLines` **once**, while
  the key is still absent, so the Bridges page opens on a real selection rather
  than an empty list - and a user who clears it stays cleared;
* a stored `OFF` from 1.4.7 is read as the new default until the profile has been
  written by a build that had another option (`torBridgeChosen`), then honoured
  verbatim. Exactly the migration the obfuscation default got, for the same
  reason: every profile 1.4.7 saved says `OFF`, and that is not the same fact as
  a user who chose to run without bridges.

Turning them off is still one tap, and off means off.

## The transport ladder

A blocked TRANSPORT is not a blocked network. obfs4 is what every censor spends
its effort on, precisely because it is what every client reaches for first, and
the same DPI box is routinely blind to a snowflake WebRTC flow or a webtunnel
session that looks like HTTPS to an ordinary website.

So "which bridges" has an ordered answer (`core/BridgePlan.kt`, pure and
unit-tested):

1. what the profile actually carries - pasted, personal, or a built-in selection
   the user made. Always first: a personal bridge from moat is scarce and was
   requested for a reason;
2. the built-in list for the profile's transport, then the rest of
   obfs4 -> snowflake -> webtunnel -> meek, skipping every transport this build
   cannot launch.

`ChainStack.startTor` walks that ladder: a rung that does not reach
`Bootstrapped 100%` costs a fresh tor process on the next transport instead of
the whole session. tor reads its torrc exactly once at startup, so each rung is a
new process by necessity, not by choice.

Budgets are deliberately asymmetric (`VpnTunables`): the LAST rung gets the full
four minutes, because "is Tor reachable here at all" deserves patience. An earlier
one gets 90 s, because "is THIS transport getting through" does not - a transport
that will work is normally past 50% inside a minute, and one being filtered sits
at 5% forever. The ladder is capped at three rungs; beyond that it stops being a
fallback and starts being a spinner.

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
* `BridgeLine.usable()` filters the list, and the settings page, `BridgePlan` and
  `Torrc.build` all go through it, so they cannot disagree;
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

They are **NOT optional any more**, and that is the single biggest change here.

Until now nothing in this repository ever called `scripts/build-pt-transports.sh`
- not the workflow, not Gradle, not the natives job. So **no APK has ever
contained a pluggable transport**: `PluggableTransports` found none,
`BridgeLine.usable()` correctly dropped every obfuscated bridge line before the
torrc, and 1.4.7 shipped a complete, working Bridges page whose every selection
was silently discarded. The symptom was not an error message. It was a Tor hop
connecting to the public relays on networks that block them.

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
still allows exec from, and `packaging { jniLibs { useLegacyPackaging = true } }`
is what puts them on disk with the exec bit rather than mapping them out of the
APK. One binary serves two transports for lyrebird, which is why the torrc gets
one `ClientTransportPlugin obfs4,meek_lite exec ...` line rather than two.

### Where the build steps now live

`app/build.gradle.kts` hooks both scripts into `preBuild`, because the APK is what
needs these files and a step that exists only in one workflow file is a step that
stops running the moment anything else assembles the app:

| Task | What it does | When it fails the build |
|---|---|---|
| `fetchTorBridges` | `scripts/fetch-tor-bridges.sh` -> `assets/tor/bridges.json` | never - `BridgeCatalog` has a compiled-in floor |
| `buildPtTransports` | `scripts/build-pt-transports.sh all` -> `jniLibs/<abi>/` | when the NDK + Go toolchain is present and the build fails, or when `-PrequirePluggableTransports=true` / `AETHER_REQUIRE_PT=1` and the binaries are absent |

With no toolchain and no strict flag it prints a loud warning naming exactly which
`<abi>/<binary>` is missing and what to run. That is the honest outcome for a
machine that cannot cross-compile Go, and it is never silent again.

### The workflow patch this needs

CI cross-compiles the natives in the `natives` job and hands them to the `app`
job as an artifact, so by the time Gradle runs the transports have to already be
in `jniLibs`. That means `.github/workflows/build.yml` needs the step below.
**It is not in this branch** - the credential that pushed it cannot write workflow
files - so apply it by hand:

1. Bump the natives cache key `jnilibs-v4-` to `jnilibs-v5-` and add
   `'scripts/build-pt-transports.sh'` to its `hashFiles(...)`. Every v4 entry was
   written by a build that could not have contained the transports, so inheriting
   one would restore a payload without them and skip the step that builds them.
2. In the "Decide what still has to be built" step, add a `pt` flag next to
   `psiphon` and `tor` (`liblyrebird.so libsnowflake.so libwebtunnel.so`, both
   ABIs) and include it in the `build=false` condition.
3. Change the Go setup step's condition to
   `if: steps.payload.outputs.psiphon != 'true' || steps.payload.outputs.pt != 'true'`.
4. After the Tor core step, add - **without** `continue-on-error`:

   ```yaml
   - name: Build the Tor pluggable transports
     if: steps.payload.outputs.pt != 'true'
     run: bash scripts/build-pt-transports.sh all
     env:
       ANDROID_API: ${{ env.ANDROID_API }}
       GOTOOLCHAIN: auto
   ```

5. In "Verify the built payload", check the three binaries for both ABIs and
   `exit 1` when any is missing - on every ref, not only on a `v*` tag. A missing
   chain core reduces the app; a missing transport breaks its default
   configuration.
6. In the `app` job, set `AETHER_REQUIRE_PT: "1"` on the assemble step, add the
   three binaries to "Check the native payload" and to "Verify native libraries
   inside the APKs" as hard requirements, and run `scripts/fetch-tor-bridges.sh`
   as its own step (a warning if it fails) next to the Psiphon server list - the
   natives cache covers `assets/tor`, so on a cache hit nothing else refreshes it.

Until step 4 lands, a CI build still succeeds and warns, exactly as a developer
machine without Go does.

## What is not solved

* **No domain fronting.** See above. Fetching bridges needs a path that already
  works, which is a real limitation and is said in the UI rather than hidden.
* **No Conjure, no WebTunnel bridge discovery.** moat hands out obfs4 and
  snowflake; a webtunnel bridge has to be pasted, so the webtunnel rung of the
  ladder only exists for a build whose asset carries some.
* **The ladder cannot rescue a network that blocks everything.** Three rungs of
  90 s, then a patient last attempt, and then the honest answer is Tor over Aether
  or Tor over Psiphon.
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
| `model/Profile.kt` | the default: bridges on, built-in list, obfs4 |
| `core/BridgeLine.kt` | the parser (pure, unit-tested) |
| `core/BridgePlan.kt` | the ladder: what is tried, in what order (pure, unit-tested) |
| `core/BridgeCatalog.kt` | built-in bridges: compiled in, asset, or refreshed |
| `core/PluggableTransports.kt` | which PT binaries this build ships |
| `core/BridgeService.kt` | the suspend API the settings page calls, and the route policy |
| `core/moat/JsonLite.kt` | the JSON reader |
| `core/moat/MoatPayloads.kt` | the wire format (pure, unit-tested) |
| `core/moat/MoatClient.kt` | one bounded HTTPS request, direct or through the tunnel |
| `data/BridgeStore.kt` | the refreshed catalogue and the personal bridges |
| `data/ProfileStore.kt` | seeds the built-in list once, migrates a stored OFF |
| `core/TorCore.kt` | `Torrc` emits `UseBridges` / `ClientTransportPlugin` / `Bridge` |
| `vpn/session/ChainStack.kt` | walks the ladder until tor bootstraps |
| `ui/engine/BridgeSection.kt` | the page |
| `ui/engine/BridgeRequestDialog.kt` | the captcha step |
| `app/build.gradle.kts` | runs both build scripts from `preBuild` |
| `scripts/build-pt-transports.sh` | the three transport binaries |
| `scripts/fetch-tor-bridges.sh` | the built-in list |
