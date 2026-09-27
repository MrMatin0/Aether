# MASQUE ClientHello spoofing

How the app shapes the TLS handshake toward the MASQUE edge for DPI, and why it
is built the way it is. Added in 2.2.0 (`feat/masque-spoofing`).

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
(the one TCP stream the engine writes end to end):

| Mode | Behaviour |
| --- | --- |
| Off | plain handshake (default) |
| SNI split | first write is cut in the middle of the server name, found by parsing the ClientHello (the parser fragmentation already uses) |
| Stream split | first write is sent as a few small pieces - what `wrong_seq` actually performs |
| Decoy (experimental) | a well-formed, empty **HelloRequest** record is sent before the real ClientHello. RFC 8446 §4.1.1 requires the server to ignore it, so only a middlebox parsing what it does not own can react to it |

A custom **SNI** can also be set for both MASQUE carriers (H2 and H3).

## Security boundary

The MASQUE edge certificate is verified by **SPKI pin**, not by hostname, so a
spoofed SNI or a shaped hello cannot open the tunnel to interception: the
connection validates end to end or it does not open. Nothing here touches the
pin logic.

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
| `AETHER_MASQUE_H2_SPOOF` | `off`, `sni_split`, `stream_split`, `decoy` | `off` |
| `AETHER_MASQUE_SNI` | a hostname (LDH labels, at least one dot) | `consumer-masque.cloudflareclient.com` |

Invalid values fall back to the defaults with a warning, never to a refusal.

## Composition

Spoofing runs **under** fragmentation: with SNI split the first write is cut
at the server name and the fragmenter then chops those pieces further; the
decoy record is always sent whole, before the fragmenter sees the real hello.

WireGuard and WARP×2 have no TLS ClientHello, and on HTTP/3 the hello sits
inside QUIC crypto frames the engine does not split - the app only offers the
modes where they can take effect, and says so everywhere else.
