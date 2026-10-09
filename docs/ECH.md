# ECH from the core

`fix/ech-from-core`, 2026-10-08, `fix/ech-doh`, 2026-10-09, and
`fix/ech-bootstrap-race`, 2026-10-09. Engine core 2.1.0.

## What was wrong

- **The ECH switch did nothing.** `ConnectionProfile.sendsEch` was
  `ech && !protocol.isMasque`, so `--ech auto` only ever reached WireGuard and
  Gool, and on those the engine has no use for it: `lib.rs` calls
  `resolve_ech()` on the MASQUE paths only (WireGuard has no ClientHello at
  all). MASQUE never got it on purpose. The only visible effect was an "ECH"
  label in the settings summary. The app never set `AETHER_ECH` or
  `AETHER_API_ECH` either.
- **Two ECH implementations.** The WARP API route (`apifront.rs`) had its own
  hand-made key source: `AETHER_API_ECH`, its own cache, its own DNS lookup and
  validity check, independent of the engine's `--ech`.
- **The key lookup that breaks in Iran.** Both looked the key up the same way:
  `cloudflare-ech.com` / `crypto.cloudflare.com` over UDP to 1.1.1.1, 1.0.0.1
  and 8.8.8.8, 3 s each, up to ~18 s, and often for nothing. Without a key the
  API route skipped ECH and fell back to split ClientHellos that still name
  `api.cloudflareclient.com`.
- **One plain-DNS resolver was still a single point of failure**
  (`fix/ech-doh`). After `fix/ech-from-core` the whole key came from ONE
  lookup, `ip.gs` over `udp://8.8.8.8`. Port 53 is exactly what a filtered
  line drops, rewrites or answers late, and DoH was refused outright ("needs
  core 2.3.0"), so on such a line the API route had no key and the tunnel no
  ECH, with nothing left to try.
- **ECH still needed a DNS answer, and the API route was serial**
  (`fix/ech-bootstrap-race`). Even with DoH raced next to port 53, no answer
  meant no ECH at all, and on a filtered line 1.1.1.1:443 and 8.8.8.8:443 are
  as likely to be cut as port 53. Then the camouflaged route made it worse:
  it waited up to 8 s for the lookup, asked the system resolver for
  `api.cloudflareclient.com` with no timeout (leaking the very name ECH
  hides, and taking poisoned answers such as 10.10.34.x for edges), and then
  walked 5 fingerprints x 5 addresses **one after the other**. On a line that
  lets TCP up and blackholes the ClientHello every attempt costs the full 8 s
  handshake timeout: ~200 s for one API call, two of them for MASQUE. That is
  the `key request timed out` in the log: past `WarpKeyFetcher`'s 240 s, and
  far past a Smart Auto rung's 75 s.
- **A refused identity was lost.** `account.rs` rewrapped every two-route
  failure as a plain `Api` error, so `lib.rs` never saw `IdentityRefused` from
  MASQUE key enrollment: a refused MASQUE identity stopped the engine instead
  of being replaced, and a refused device refresh was logged as "could not
  reach the api".
- **The manual key fetch ignored the user's settings.** `WarpKeyFetcher` ran a
  bare `ConnectionProfile()`, so no ECH; a Zero Trust profile could only time
  out (the identity lands in `aether-team-<team>.toml`, which it does not
  watch); and 150 s was short for MASQUE's two API calls over the camouflaged
  route.

## What it does now

**One ECH key per process, from the core (`dns.rs`).** Configured with
upstream 2.3.0's own variables, so the next core sync means the same thing:

| Variable | Upstream flag | Value from the app |
|---|---|---|
| `AETHER_ECH` | `--ech` | `auto` when the switch is on (also sent as `--ech auto`) |
| `AETHER_ECH_DOMAIN` | `--ech-domain` | `ip.gs`, every session |
| `AETHER_ECH_DNS` | `--ech-dns` | `udp://8.8.8.8`, every session |

`ip.gs` is a Cloudflare-fronted name, so its HTTPS record carries Cloudflare's
shared ECH key set, the same keys that encrypt a ClientHello for
`api.cloudflareclient.com` or the MASQUE edge.

**Many lookups, raced (`fix/ech-doh`).** The same idea Xray-based clients use
(`echConfigList: "https://1.1.1.1/dns-query"`): the key is asked of several
resolvers over several transports at once, and the first plausible
ECHConfigList wins.

- Both variables take a **comma separated list**. A resolver is
  `udp://ip[:port]`, `tcp://ip[:port]` or, new, `https://ip[:port][/path]`
  (DNS over HTTPS, RFC 8484 POST, port 443 and `/dns-query` by default).
  A DoH resolver has to be given by IP address: a name would need a lookup of
  its own. A bad entry in a list is left out with a warning; a list with no
  usable entry is an error, as before.
- Next to whatever is configured, every plan also asks
  `https://1.1.1.1/dns-query` and `https://8.8.8.8/dns-query` (`DOH_FALLBACK`),
  and always asks about `cloudflare-ech.com` next to the configured domain.
  With the app's settings that is 3 resolvers x 2 names = 6 lookups.
- DoH by IP address sends **no SNI** and carries the question inside TLS, so
  a DPI box sees one more HTTPS session to 1.1.1.1 / 8.8.8.8 and never the
  name being asked about. The certificate is checked against the bundled web
  roots and the resolver's IP address (reqwest + rustls, the same client the
  direct API route uses), through the upstream proxy when there is one.
- All lookups run **at the same time**; the others are dropped the moment one
  answers. The wait is at most 8 s, instead of one lookup after the other.
- A failure names what **every** lookup answered, so a log says whether port
  53 was silent, DoH was reset, or a resolver had no ech parameter.

The key is cached and shared: the WARP API's ECH route and the MASQUE tunnel
offer the same key, and a key an edge hands back as retry config replaces it
for both. A failed lookup is not repeated for a minute. A key that is not a
plausible ECHConfigList is never handed out.

With neither variable set (a bare CLI run) the old UDP cascade is kept and
the DoH lookups race next to it.

**ECH with no DNS answer at all (`fix/ech-bootstrap-race`, `apifront.rs`).**
The API route waits 4 s (`ECH_KEY_WAIT`) for the core's key. Without one it
offers `BOOTSTRAP_ECH_CONFIG`: an ECHConfigList shaped exactly like the one
Cloudflare publishes (version 0xfe0d, DHKEM(X25519) with a real public key,
HKDF-SHA256 / AES-128-GCM, public name `cloudflare-ech.com`) whose private
key exists nowhere. The edge cannot open it, so it rejects ECH the way
RFC 9849 requires: it finishes the outer handshake as `cloudflare-ech.com`
(a certificate boring checks against that public name) and hands back the
keys it serves right now as retry configs. The existing retry path keeps
those for the process (so the MASQUE tunnel gets them too) and the second
handshake is a real ECH one. This is how a browser recovers from a stale
key; here it is also how the first key arrives. The bootstrap set is only
ever offered, never kept as the session key.

**The API route races (`fix/ech-bootstrap-race`).**

- With ECH on, the API name is **not looked up** at all (5 random edge
  addresses instead). Without it the system resolver gets 2 s, and only public
  IPv4 answers count as edges (no 10.10.34.x, no private, no CGNAT).
- The edges and the ECH key are worked out side by side.
- The handshakes race: at most 4 at a time, one started every 300 ms, every
  ECH attempt queued first and given a 3 s head start before plaintext
  fingerprints may start.
- The request itself is sent **once**, over the first route whose handshake
  is done (one turn at a time), so a registration never makes two devices.
  The next established route only sends if that one fails or is refused by
  the edge.
- No new attempt starts after 35 s; everything ends by 55 s. One API call over
  the camouflaged route now fits inside a Smart Auto rung, and MASQUE's two
  plus the direct last resort fit inside the key fetch's 240 s.

**The switch reaches every protocol.**

| | Switch off | Switch on |
|---|---|---|
| WARP API, every protocol | direct route, then the camouflaged one (ECH first, key from the core or the bootstrap set) | camouflaged route with ECH **first**, plaintext direct route only as a last resort |
| MASQUE / MiM over HTTP/3 | no ECH on the tunnel | the tunnel handshake offers ECH |
| MASQUE / MiM over HTTP/2 | - | API part only (2.1.0 has no ECH on the H2 carrier) |
| WireGuard / Gool | - | API part only (no ClientHello) |

A refused identity (401/404/410) is final on either route and stays an
`IdentityRefused`, so the engine registers a fresh identity for it.

The manual key fetch runs the saved profile (ECH, IP family, obfuscation) as
consumer WARP, with a turbo scan, no quick reconnect, and 240 s.

## Field test before release

- A fresh registration for each protocol from the diagnostics tab, on a
  filtered link, with the switch on and off. Success looks like
  `fetched ECHConfigList (... bytes) for cloudflare-ech.com via https://1.1.1.1:443/dns-query`
  (or any other resolver of the plan) and
  `ech accepted by ...; the api name stayed encrypted`.
- A link where DNS gives nothing (port 53 and DoH both cut). Success looks
  like `offering the bootstrap key set`, then
  `retrying ... with the ... byte ech key set it handed back` and
  `ech accepted by ...`.
- A link where port 53 is blocked outright: the key must still arrive, over
  DoH or from the edge. A link where 1.1.1.1:443 is blocked too: 8.8.8.8, the
  UDP lookup or the bootstrap set must still win.
- MASQUE over HTTP/3 with the switch on: core 2.1.0's scan probes do not offer
  ECH, the tunnel does. Upstream measured the edge accepting it (2.3.0), but
  this core has not been field-tested with it. If an edge refuses, the tunnel
  log says so on every reconnect; switching ECH off restores the old
  behaviour exactly.
- `cargo test -- --ignored the_ech_key_comes_back_over_doh` checks both DoH
  resolvers, and
  `cargo test -- --ignored the_bootstrap_key_set_brings_back_the_live_keys`
  checks the bootstrap path, from a machine with network access.

## Core 2.3.0

Upstream 2.3.0 deletes `apifront.rs` and rewrites `dns.rs` around the same
three variables. The sync keeps both as conflicts for review (`dns.rs` is in
`PATCHED_FILES`). When resolving that conflict, keep this file's DoH, the
lists and the race: without them the lookup is back to one resolver on port
53. Carry the bootstrap key set and the raced, single-send API route over to
upstream's `https.rs` as well: without them the API is back to needing a DNS
answer for ECH and to a serial sweep. `lib.rs`'s own log line
"ECH disabled (warp masque endpoint does not accept ECH)" is upstream 2.1.0's
and stale; it goes with the sync.
