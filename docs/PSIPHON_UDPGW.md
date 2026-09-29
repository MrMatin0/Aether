# UDP through Psiphon (udpgw)

A Psiphon-entry chain (`PSIPHON`, `PSIPHON_OVER_AETHER`) now carries
non-DNS UDP - QUIC, voice/video calls, games - instead of dropping it. Tor
entries are unchanged: tor has no UDP.

## How

Psiphon's local SOCKS5 is CONNECT-only, but the Psiphon **server** runs a
udpgw relay. A TCP port forward to its `UDPInterceptUdpgwServerAddress`
(`127.0.0.1:7300` in psiphond's generated config) is intercepted before any
destination check and parsed as the udpgw protocol; each message becomes a
real UDP datagram at the exit, and replies come back the same way
(`psiphon/server/udp.go`, `psiphon/server/config.go`). psiphon-tunnel-core's
own server test does exactly this through the client's local SOCKS proxy.

```
hev (UDP ASSOCIATE) -> SocksFront -> UdpgwMux
    -> Psiphon SOCKS5 CONNECT 127.0.0.1:7300 -> Psiphon server udpgw -> UDP
```

DNS keeps its existing path (DNS over TCP, pooled, with the AAAA guard).

## Wire format

```
| size u16 LE | flags u8 | conn_id u16 LE | addr 4|16 | port u16 BE | payload |
```

Flags: keepalive `0x01`, rebind `0x02`, dns `0x04`, ipv6 `0x08`. Max payload
32768 bytes. Size and conn_id are little-endian, the port is network order.

## Design rules

* **One session per front.** The server keeps ONE udpgw channel per client
  and replaces the old one when a new one opens. A session per app flow would
  kill every other flow's UDP, so `UdpgwMux` multiplexes all associations over
  one connection by conn_id.
* **Rebind on first use.** A flow is (association, dst ip, dst port). Its
  first datagram carries the rebind flag so the server drops anything it
  still holds under a recycled id. Replies are matched on conn_id AND address.
* **Degrade to the old behaviour.** No session = the datagram is dropped and
  counted exactly as before. Dials run outside the lock and back off to 30 s.
* **Lazy.** The session opens on the first non-DNS datagram, not at start.
* **IP only.** Domain-addressed datagrams, and IPv6 destinations on an exit
  proven IPv4-only, are not sent.

## Not verified yet

This was written without an Android or Gradle toolchain. CI compiles it and
runs `PsiphonUdpgwTest` (codec layout plus a loopback echo of the mux); it
has NOT been checked on a device against the live Psiphon network. In
particular:

* Public Psiphon servers apply traffic rules to UDP ports
  (`isPortForwardPermitted`), and udpgw has no error reply - a refused port
  looks exactly like a dropped datagram.
* UDP over SSH over TCP adds latency and head-of-line blocking; calls may be
  usable but not good.

`SocksFront` logs `udpgw session open` on success, and the stop line carries
`udpgw sessions=… datagrams up=… down=…`. `down=0` with `up>0` on a device
means the server is dropping that traffic.

`docs/CHAINING.md` and the READMEs still describe Psiphon chains as UDP-less;
update them once a device test confirms `down>0`.
