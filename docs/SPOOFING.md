# MASQUE ClientHello spoofing

How the app shapes the TLS handshake toward the MASQUE edge for DPI, and why it
is built the way it is. Added in 2.2.0 (`feat/masque-spoofing`), corrected in
`fix/masque-spoofing-bugs`.

## What the reference repo actually does

The reference (MrMatin0/SPOOOOOOOFING) is a local TCP relay, not a VPN. For
each of its options, its code does the following - not what the option's name
says:

| Reference option | What its code really does |
| --- | --- |
| `sni_split` | splits the first write at a fixed byte 14 (it never locates the SNI) |
| `fake_client_hello` | sends a synthetic record before the real hello; the builder omits the SNI extension type and its length header disagrees with the body |
| `wrong_seq` | writes the stream in several small pieces; no TCP sequence number is ever touched (userspace cannot - the kernel owns them) |
| `custom_decoy` | splits at byte 20; no decoy is generated at all |

Its "Bypass Confirmed" test also passes on a TLS error or a closed connection,
so it proves a TCP connection opened, not that a strategy works.

## What Aether implements instead

Each mode is named for what the code does, and lives on the HTTP/2 carrier
(the one TCP stream the engine writes end to end). Both split modes shape the
**first write only** - the ClientHello - and every later write goes out whole,
so the session is not taxed for its whole life.

| Mode | Behaviour |
| --- | --- |
| Off | plain handshake (default) |
| SNI split | the first write is cut in the middle of the server name, found by parsing the ClientHello (the parser fragmentation already uses). A first write without a parseable SNI is sent whole |
| Stream split | the first write is sent as pieces of 64, 32 and 24 bytes, then the rest - what `wrong_seq` actually performs |

Every piece is a real write of the inner stream. The wrapper never returns
`Pending` on its own (that would need a waker it does not have), and it stops
shaping as soon as the first byte comes back from the server.

## No decoy

The first 2.2.0 build had a "Decoy (experimental)" mode that sent
`16 03 01 00 04 01 00 00 00` before the real hello, described as a
HelloRequest the server must ignore. It was not: handshake type `0x01` is
**ClientHello**, so the record was an *empty ClientHello*, and TLS 1.3 has no
record a server is required to ignore before the ClientHello (HelloRequest
does not exist in 1.3, and in 1.2 only a server sends it). A strict edge can
only answer it with an alert. The implementation also hung the handshake: it
returned `Pending` after writing the decoy without registering a wake-up.

It was removed rather than patched, because there is no correct version of
it. `decoy`, `fake_client_hello` and `custom_decoy` are read as `off` - in the
engine and in saved app profiles - so nobody's config fails to load.

## Custom SNI

`AETHER_MASQUE_SNI` replaces the default `consumer-masque.cloudflareclient.com`
on **both** carriers (HTTP/2 and HTTP/3), including the scanner's verify
probes and the ECH retry, so an edge the scan accepted was tested with the
same name the tunnel dials it with. The swap happens at the four handshake
entry points (`masque_h2::run`, `masque_h2::verify_h2`, `quic::run`,
`quic::verify_masque`) through `spoof::resolve_sni`, which only replaces the
default name: a caller that passes a different SNI on purpose is never
overridden. An invalid value is ignored with a single warning.

## Security boundary

The MASQUE edge is verified by the engine's pinned-key check (tls.rs,
`pin_endpoint`), which does not match the hostname, so a custom SNI does not by
itself fail verification, and a shaped hello only changes how the same bytes
are framed. Nothing here touches tls.rs: whatever verifier it installs for the
edge is installed the same way with spoofing on or off.

Whether an edge *accepts* a custom SNI is a network question - the engine's
existing data-plane check already refuses to expose the proxy until traffic
has actually flowed, so a bad SNI fails loudly, not silently.

## Why env vars, not CLI flags

`AETHER_MASQUE_H2_SPOOF` and `AETHER_MASQUE_SNI` travel as environment
variables. An unknown CLI flag is fatal to an older core; an unknown variable
is ignored. A handshake experiment must never be the reason a tunnel refuses
to start.

| Variable | Values | Default |
| --- | --- | --- |
| `AETHER_MASQUE_H2_SPOOF` | `off`, `sni_split`, `stream_split` (retired: `decoy` -> `off`) | `off` |
| `AETHER_MASQUE_SNI` | a hostname (LDH labels, at least one dot) | `consumer-masque.cloudflareclient.com` |

Invalid values fall back to the defaults with a warning, never to a refusal.

## Composition

Spoofing sits **above** fragmentation: the spoofing wrapper writes into the
(possibly fragmenting) socket, so with SNI split the first write is cut at the
server name first and the fragmenter then chops each of those pieces further.
With fragmentation on, SNI split mostly decides *where* the first cut lands.

WireGuard and WARP×2 have no TLS ClientHello, and on HTTP/3 the hello sits
inside QUIC crypto frames the engine does not split - the app only offers the
split modes where they can take effect, and says so everywhere else. The
custom SNI is the exception: it works on both MASQUE carriers.
