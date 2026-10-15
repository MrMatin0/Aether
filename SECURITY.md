# Security policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Use GitHub's private vulnerability reporting: <https://github.com/MrMatin0/Aether/security/advisories/new>.

Please include the impact, reproduction steps, tested version or commit, and device/Android details when relevant. There is no bug bounty. Reports are acknowledged on a best-effort basis and disclosure is coordinated with the reporter.

## Verification, not trust

Release APKs are signed in CI with a private repository secret and checked against the certificate fingerprint pinned in `.github/expected-signer.txt`. The build fails if signing secrets are absent or the certificate changes. This protects update compatibility, but users should still obtain APKs from a trusted release source and verify the published fingerprint.

The application ID remains `io.github.mrmatin0.aether`; release version `1.5.5` uses a higher version code so Android treats it as an update. Never publish a build signed by a newly generated key: existing installations would reject it.

## Signing-key handling

The keystore and all signing credentials are distribution secrets. They must stay in GitHub Actions Secrets or secure local storage, never in the repository, issues, pull requests, logs, or APK assets. CI decodes the keystore only in the runner's temporary directory and does not persist it.

See [docs/SIGNING.md](docs/SIGNING.md) for the required secrets and verification procedure.

## Scope

In scope are the Android application, VPN service, TUN bridge, transport and chaining logic, diagnostics, local storage, network handling, and build/release scripts. The vendored engine under `native/aether` should also be reported here when this application exposes or amplifies an issue.

Out of scope are the underlying circumvention projects as independent products, the network being accessed, and issues requiring a compromised or rooted device.

## Historical audit documents

`docs/SECURITY_AUDIT*.md` and `docs/SECURITY_REVIEW.md` are historical records, not certification of the current build. Treat their findings as tied to the revisions named in those documents.
