# ECH Engine for VPN-less WARP Key Acquisition

`feat/ech-warp-key-rewrite`, 2026-10-15. Engine core 2.1.0.

## Why previous ECH implementations failed without a VPN

1. **Blocked DNS / DoH resolvers.** Standard UDP/TCP port 53 to `1.1.1.1` and
   `8.8.8.8` is dropped or poisoned on filtered networks, and unfragmented
   `reqwest` DoH to `https://1.1.1.1/dns-query` or `https://8.8.8.8/dns-query`
   is either IP-blocked or reset on TLS handshake.
2. **Single-prefix edge exhaustion.** The camouflaged API route (`apifront.rs`)
   only sampled random edges inside a single `/24` (`141.101.113.0/24`). On ISPs
   that null-route or blackhole that `/24`, every attempt timed out regardless
   of TLS fingerprint.
3. **Static outer SNI & single-mode fragmentation.** The bootstrap ECH config
   always presented a single static outer `public_name` (`cloudflare-ech.com`)
   and only used basic SNI-split fragmentation, which stateful DPI boxes could
   reassemble or fingerprint.

## How the rewritten ECH engine works

### 1. Camouflaged Edge DoH (`dns.rs` `query_edge_doh`)

Alongside any configured `AETHER_ECH_DNS` / `AETHER_ECH_DOMAIN` lookups,
`fetch_ech_config()` races **camouflaged edge DoH** directly against
Cloudflare's dedicated DoH edge prefixes (`162.159.36.1`, `162.159.46.1`,
`162.159.36.2`, `162.159.46.2` on port 443):

- Uses BoringSSL with Chrome TLS 1.2/1.3 extension permutation, ECH GREASE
  (`SSL_set_enable_ech_grease`), and `FragmentingStream` (SNI-split ClientHello)
  so stateless DPI keyword matchers never see the cover SNI (`one.one.one.one`
  or `cloudflare-dns.com`) in a single TCP segment.
- Sends an RFC 8484 `application/dns-message` POST for the `HTTPS` (type 65)
  records of `cloudflare-ech.com`, `crypto.cloudflare.com`, and `ip.gs`,
  validating the DNS transaction ID, question section, and RFC 9849 (`0xfe0d`)
  `ECHConfigList` wire layout before caching the key for the process.

### 2. Zero-DNS Decoy `ECHConfigList` Synthesis & `retry_configs` Harvesting (`dns.rs`, `apifront.rs`)

When every DNS and DoH path is completely cut, `apifront.rs` needs no DNS
answer at all:

- `dns::synthesize_ech_config_list` constructs valid RFC 9849 (`0xfe0d`)
  `ECHConfigList` payloads on the fly using a deterministic X25519 public key
  (`DECOY_ECH_PUBKEY`, whose private key exists nowhere) and rotates the outer
  cover `public_name` across attempt slots (`cloudflare-ech.com`,
  `crypto.cloudflare.com`, `one.one.one.one`).
- When offered to any Cloudflare edge, the edge cannot decrypt the inner
  ClientHello (`api.cloudflareclient.com`) and rejects ECH per RFC 9849 §6.1.6,
  handing back its live `ECHConfigList` in the TLS `encrypted_client_hello`
  `retry_configs` extension (`SSL_get0_ech_retry_configs`).
- `establish_ech` captures `retry_configs`, caches them in `dns::remember` for
  all subsequent handshakes, and immediately retries the edge with real ECH.
- If a middlebox interferes with the `ECH_REJECTED` alert before `retry_configs`
  arrive, `establish_ech` falls back to a direct camouflaged `query_edge_doh`
  probe against that same edge IP before retrying.

### 3. Multi-Prefix Cloudflare Edge Pool & Dual ECH Fingerprints (`apifront.rs`)

- **7 distinct Cloudflare AS13335 `/24` prefixes** (`EDGE_PREFIXES`):
  `141.101.113.0/24`, `162.159.36.0/24`, `162.159.46.0/24`, `104.16.132.0/24`,
  `172.64.155.0/24`, `188.114.96.0/24`, and `162.159.192.0/24`.
- **Two ECH fingerprints queued first** with a 3 s head start (`ECH_HEAD_START`)
  before any plaintext fallback:
  - `Fingerprint::Ech`: TLS 1.3 + ECH + Chrome extension permutation + SNI-split
    `FragmentingStream`.
  - `Fingerprint::EchStreamSplit`: TLS 1.3 + ECH + `SpoofingStream`
    (`SpoofMode::StreamSplit`, cutting the first write at 64/32/24 bytes) layered
    over `FragmentingStream`.
- Plaintext fallback fingerprints (`SplitLegacy`, `SplitModern`, `Modern`,
  `ChromeLike`) emit synthetic ECH GREASE (`set_enable_ech_grease`) so their
  extension layout still resembles an ECH ClientHello.
- Handshakes race 4 at a time (`PARALLEL_ATTEMPTS = 4`, staggered by 300 ms),
  serialized through a single-turn semaphore on `send()` so a registration
  request is never sent twice.

### 4. Subprocess-Scoped ECH for Manual WARP Key Fetch (`WarpKeyFetcher.kt`, `account.rs`)

- Normal VPN/proxy tunnel sessions (`ConnectionProfile`) do **not** emit
  `--ech` or `AETHER_ECH*` (preserving the clean removal from PR #162).
- `WarpKeyFetcher` injects `WarpKeyFetcher.keyEnv(profile)` (`AETHER_ECH=auto`,
  `AETHER_ECH_DOMAIN`, `AETHER_ECH_DNS`) into the side-port key-fetch subprocess.
- When `AETHER_ECH` is set, `account::call_api` runs the camouflaged ECH route
  **first** and only falls back to the direct plaintext route as a last resort.
