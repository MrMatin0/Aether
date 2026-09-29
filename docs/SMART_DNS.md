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

- `ConnectionProfile.smartDns / smartDnsServers / smartDnsDirect`, persisted
  (`ProfileStore`) and transported (`ProfileCodec`).
- Active only on the Aether-only chain with at least one valid server
  (`usesSmartDns`). Servers: IPv4, port 53 only, up to 8
  (`sanitizedSmartDns`); `addDnsServer` takes no port.
- The TUN advertises the Smart DNS servers (`TunnelConfig.dnsServersFor`).
- `--dns` uses the same servers, so engine-side lookups agree with the device.
- The TUN carries no IPv6 address while active (`::/0` still routed), so apps
  get no AAAA answers that would reach a covered site over IPv6 and skip the
  proxy.
- Optional *direct* mode appends the servers to `--route-direct`, for providers
  that only answer registered / Iranian source IPs.
- UI: a Smart DNS card on the Routing page (EN + FA).

## Limits

- Only names the provider covers move abroad. IP-checker sites usually are not
  covered and will keep showing the WARP (Iranian Cloudflare) address. Test
  with a covered site (e.g. gemini.google.com).
- Android Private DNS in *hostname* (strict) mode bypasses the TUN resolvers.
  Off or Automatic works.
- Apps or browsers with their own DoH resolver bypass it.
- Services with account/phone-region or multi-step checks (e.g. Google Flow)
  may still refuse.
- Some providers whitelist source IPs; the WARP exit may not be registered with
  them. That is what direct mode is for.
- With a Psiphon or Tor entry the exit is already abroad; the setting is kept
  but ignored.

## Test checklist

1. Chain = Aether only, Private DNS = Off/Automatic.
2. Enable Smart DNS, enter the provider's servers, connect.
3. Log shows `Smart DNS active: resolvers=[...]` and `dns=[...] (smart)`.
4. gemini.google.com opens; youtube.com still opens.
5. If covered sites do not open: try *direct* mode; if they still fail, the
   provider does not accept the source IP or does not cover that name.
