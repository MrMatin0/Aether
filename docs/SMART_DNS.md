# Smart DNS (sanctions bypass through a proxying resolver)

## The problem

A WARP exit is geolocated to the user's own country. Aether opens sites Iran
filters (YouTube, Telegram, Instagram), but sites that geo-block Iran (Gemini
and most AI services, a lot of developer tooling) still see an Iranian address
and refuse the session.

## How it works

A Smart DNS provider answers the names it covers with the address of its own
TLS-passthrough (SNI) proxy abroad, and every other name normally.

```
app -> DNS query -> TUN -> hev -> engine (UDP ASSOCIATE) -> WARP -> Smart DNS
     <- gemini.google.com = <provider's proxy IP>
app -> TLS to proxy IP -> TUN -> hev -> engine -> WARP -> proxy (DE/US) -> site
```

The site sees the proxy's country. Uncovered names keep the WARP exit, which is
what still opens filtered sites. The proxy relays TLS untouched: it sees which
site (SNI), never the content, and cannot impersonate the site. It does see
every name the device resolves.

## Why the old DNS setting could not do this

On a VPN session the TUN advertised a hardcoded `1.1.1.1, 8.8.8.8`
(`TunnelConfig.DNS_SERVERS`). hev relays the device's UDP queries to that
address as-is through the engine's SOCKS5 UDP ASSOCIATE, so the engine's
`--dns` (the Transport page's DNS field) only ever applied to names the engine
resolves itself (SOCKS5 domain requests: proxy mode, LAN sharing). It never
changed the device's resolver.

## What this change does

- `ConnectionProfile.smartDns / smartDnsServers / smartDnsDirect /
  smartDnsProxies`, persisted (`ProfileStore`) and transported (`ProfileCodec`).
- Active only on the Aether-only chain with at least one valid server
  (`usesSmartDns`). Servers: unicast IPv4, port 53 only, up to 8
  (`sanitizedSmartDns`); `addDnsServer` takes no port. `0/8`, loopback,
  link-local `169.254/16`, multicast / reserved (`224+`) and
  `255.255.255.255` are rejected. Parsed once per profile instance.
- The TUN advertises the Smart DNS servers (`TunnelConfig.dnsServersFor`).
- `--dns` uses the same servers, so engine-side lookups agree with the device
  (`engineUsesSmartDns`) - except in direct mode, see below.
- The TUN carries no IPv6 address while active (`::/0` still routed), so apps
  get no AAAA answers that would reach a covered site over IPv6 and skip the
  proxy. A ROM that refuses that interface gets the addressed one back; the
  session then flags it (`SmartDnsRuntime.ipv6Fallback`) and the Routing page
  shows a warning, since the feature is bypassed for covered sites with an
  AAAA record.
- UI: a Smart DNS card on the Routing page (EN + FA).

## Direct mode

For providers that only accept registered / Iranian source addresses. Such a
provider checks the source on its SNI proxy as well as on its resolver, so
direct mode sends BOTH straight out of the phone:

- the Smart DNS servers, and
- the provider's proxy addresses (`smartDnsProxies`: IPv4 or CIDR no wider
  than /16, up to 32). Only covered names resolve to them, so uncovered
  (filtered) sites never leave the WARP exit.

These go FIRST in `--route-direct` (`routeDirectRules`), so a user list at the
256-rule cap can no longer silently push them out; if the cap cuts user rules
instead, the connect log says how many (`droppedRouteDirectRules`).

The device's queries reach the direct path because the engine's UDP ASSOCIATE
(`socks.rs`, `handle_udp_associate`) runs the routing rules on every datagram
hev relays, and a bare address is a /32 rule there. TCP to the proxy address
goes through `handle_connect`, which applies the same rules.

In direct mode the engine's own `--dns` stays on the ordinary resolvers: the
engine always resolves through the tunnel, where a whitelisting provider is
silent, and that would fail every socks5h request and the self-test.

## Limits

- Only names the provider covers move abroad. IP-checker sites usually are not
  covered and will keep showing the WARP (Iranian Cloudflare) address. Test
  with a covered site (e.g. gemini.google.com).
- Android Private DNS in *hostname* (strict) mode bypasses the TUN resolvers.
  Off or Automatic works.
- Apps or browsers with their own DoH resolver bypass it.
- Services with account/phone-region or multi-step checks (e.g. Google Flow)
  may still refuse.
- In direct mode the proxy addresses have to be entered by hand; resolve a
  covered site with the provider's DNS to find them.
- With a Psiphon or Tor entry the exit is already abroad; the setting is kept
  but ignored.

## Test checklist

1. Chain = Aether only, Private DNS = Off/Automatic.
2. Enable Smart DNS, enter the provider's servers, connect.
3. Log shows `Smart DNS active: resolvers=[...]` and `dns=[...] (smart)`.
4. gemini.google.com opens; youtube.com still opens.
5. If covered sites do not open with a whitelisting provider: turn on direct
   mode AND enter its proxy addresses; the log then lists `proxies=[...]` and
   `engineDns=ordinary`.
