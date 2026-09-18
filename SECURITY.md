# Security policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting:

<https://github.com/MrMatin0/Aether/security/advisories/new>

That report is visible only to the maintainer. If you would rather not use
GitHub, open a normal issue that says only that you have a security report and
how to reach you — with no details in it — and a private channel will be
opened from there.

Please include:

- what the problem is, and the impact you believe it has;
- how to reproduce it, or why it cannot be reproduced trivially;
- the version or build you tested (`v1.4.6-build.NNN`, or a commit);
- the device, Android version, and connection mode, if the issue is
  runtime behavior rather than source-level.

There is no bug bounty. This is a small project maintained in spare time;
what you get is an honest answer and credit in the advisory if you want it.

## What to expect

An acknowledgement within about a week, and a first assessment shortly after.
If the report is valid, the fix and the disclosure are coordinated with you —
you will not be named without your agreement. If the report is rejected, you
will be told why.

## Scope

**In scope.** The Android application in this repository: the VPN service and
its kill switch, the TUN bridge, the transport and chaining logic, the probe
and diagnostics paths, local storage, the app's own handling of network
traffic and identity, and the build and release scripts that produce a
distributed APK.

**Also in scope, but with a caveat.** The vendored engine under `native/aether`
is a copy of [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) and
is not hand-edited here. A flaw in engine source should go to that project
first — but report it here too if this app exposes or amplifies it.

**Out of scope.** The three underlying circumvention cores (Aether engine,
Psiphon, Tor) as projects in their own right; the security of the network you
are trying to reach through the tunnel; the fact that a VPN does not make you
anonymous against a global adversary; and anything that requires a
compromised device, a rooted device, or an attacker who already has your
unlock code.

## Verification, not trust

Two claims in the documentation are checkable rather than asserted, and you
should check them rather than take them on faith:

- **Signed releases.** The APKs are signed with a fixed key, and the CI
  verifies the signer both for the build and for the assembled APK. The
  fingerprint the CI-mode key is pinned to is committed at
  `.github/expected-signer-ci.txt`; check it yourself against the APK you
  downloaded. Note the limitation in "Known and accepted" below: that key is
  public, so the pin proves updatability, not authorship. See
  [docs/SIGNING.md](docs/SIGNING.md).
- **Rollback protection.** `scripts/core-rollback.sh` and the pinned
  `native/aether/CORE_VERSION` exist so a core can be moved back deliberately,
  with the change visible in the log rather than happening silently.

## Known and accepted

These are design decisions, not vulnerabilities. They are listed so a report
about them is a conversation rather than a surprise:

- **Proxy mode does not tunnel the whole device.** That is what the mode is.
  It is explained in the app.
- **Split tunneling in INCLUDE mode tunnels only the apps you pick.** That is
  the requested behavior.
- **The real-IP probe deliberately leaves the tunnel.** It exists to show you
  your carrier's IP. It carries no user traffic.
- **The app cannot enforce "block connections without VPN"** on devices where
  the user disables always-on VPN. The in-app kill switch covers this at the
  service level instead.
- **Releases are currently signed with the CI keystore that is committed to
  this repository.** That key is public by definition: it guarantees that a
  new release can be installed over an older one, but it establishes nothing
  about who built the APK — anyone can sign with it. Releases are meant to be
  signed with a private key held in repository Secrets, and the CI is written
  to do that, but no such Secrets are configured yet. Verify an APK's signer
  yourself rather than trusting the download.

## A note on the audit documents

`docs/SECURITY_AUDIT*.md` and `docs/SECURITY_REVIEW.md` are historical. They
were written against the **1.2.x** line (July–August 2026); the current
version is 1.4.6, and much of the app has been rewritten since — the entire UI
layer, the session layer, and the tunnel core. Treat those documents as a
record of what was true then, not as a current assessment. A fresh review of
the current line has not been done. Reports that close that gap are welcome.
