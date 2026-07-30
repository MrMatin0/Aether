Aether Mobile v1.2.3

Engine upgraded to core v1.5.0, everything new in that core is now in the UI, the
About panel shows the engine version like the Windows edition does, and a fresh
0-to-100 security audit scored 88/100.

Engine
- Core upgraded to v1.5.0. The previous release pinned CORE_VERSION at "1.4" while
  the vendored sources were actually upstream v1.3.0, which defeated the automatic
  merge-base logic and silently dropped the app's own engine patches. The real
  baseline was identified and the patches were re-applied with a genuine
  three-way merge; the baseline cache was repaired for future upgrades.
- Upstream turned the endpoint-range constants into Zero-Trust-aware functions.
  Both conflicts were resolved by adopting upstream's new ordering while keeping
  the app's manual-range override intact.

New in the UI (from core v1.5.0)
- Zero Trust (WARP for organizations): join a Cloudflare Zero Trust organization
  with a service token, an e-mail one-time code, or a pre-obtained enrolment
  token. The organization Gateway proxy is a separate opt-in toggle.
- Routing rules: "block" (never reaches the network) and "direct" (bypasses the
  tunnel), supporting example.com, full:, keyword:, regexp:, CIDR, port: ranges
  and private.
- Custom DNS inside the tunnel.

About
- New "Engine (core) version" row under the app version, stamped at build time
  from the core the build actually vendored - parity with the Windows edition.

Security (88/100, docs/SECURITY_AUDIT_1.2.3.md)
- Organization secrets never travel as command-line arguments (readable via
  /proc on Android); they are passed to the engine through its environment.
- The service-token secret and enrolment JWT are sealed with a non-exportable
  AES-256-GCM key from the Android Keystore instead of sitting in the plain
  preferences file. Credential fields are masked in the UI.
- DNS and routing input is allow-listed, de-duplicated and capped, so pasted
  text cannot inject extra engine arguments.
- The Gateway toggle states plainly that it lets the organization log browsing.

Install
- Version code 7, same signing certificate as 1.2.2, so 1.2.2 users can install
  this directly over their existing app and keep all their settings.
