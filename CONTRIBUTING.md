# Contributing

Focused fixes, networking work, accessibility improvements, and translation
updates are welcome. This is an independent project — it does not track an
upstream — so a change here is judged on its own merits, not on whether it
matches another repository.

## Before you start

**Open an issue first for anything substantial.** A behavior change, a new
dependency, an architecture change, or a new user-facing surface: describe it
in an issue before writing code. Small fixes (a typo, a crash, a broken
selector, a one-line correctness bug) can go straight to a pull request.

Check the open issues before starting something new — it may already be
underway, or already decided against.

## Setting up

The build is genuinely multi-part: an Android app, a vendored Aether engine, a
JNI bridge, and two optional overlay cores. The toolchain, the exact versions,
and the step-by-step order are in the "Build from source" section of
[README.md](README.md). Follow it rather than improvising — the versions are
pinned for reasons recorded in the workflow comments, and a mismatched NDK or
JDK fails in ways that do not point back at the cause.

You do not need the overlay cores (Psiphon, Tor) to work on the app itself.
Without them the app builds and runs; only the chain modes are unavailable.

## Branches and commits

- Branch from the current `main`. Keep each change narrowly scoped — one
  concern per branch. A pull request that fixes a bug and reformats three
  unrelated files is harder to review and harder to revert.
- Commit messages follow the style already in the log: a `type(scope): summary`
  subject, then a body that says **why** the change is needed, not what the
  diff already shows.

## Tests

- Run `gradle :app:testReleaseUnitTest` with the project toolchain. Note that
  this project uses the **release** test build type, not debug — a test that
  only passes under debug is not passing here.
- Add or update tests for logic you change. Refactors in particular should
  either come with tests or leave the existing ones untouched and green.
- Pure documentation and prose changes skip CI entirely: the verification
  workflow has a `paths-ignore` list covering `README.md`, `README.fa.md`,
  `docs/**`, `_META/**` and `LICENSE`. That is deliberate, not an oversight.

## Testing network behavior

A green build does not mean the tunnel works. If your change touches the VPN
path, the transports, the chaining, the probe logic, or the TUN bridge, test it
on a **supported physical device** and say what you tested in the pull request:

- the cases that work, and the cases that fail;
- the connection mode and transport you used;
- whether the chain modes (Psiphon, Tor) were exercised, and if not, why not.

Changes that only ever ran on an emulator, or only in one network, should say
so. A reviewer needs to know what was actually covered.

## What CI will check

A pull request runs a verification build: it compiles, runs the unit tests,
checks the APK signature and the native payload, and attaches the built APKs to
the run so a change can be installed on a real device before merge. It
publishes nothing.

It is stricter than it looks. A missing core is a warning on a branch but a
hard failure on a release tag, and the native payload is checked twice —
against what was built, and against what is actually inside the assembled APKs.

## House rules

- **Do not commit generated artifacts.** No APKs, no downloaded native
  binaries, no build caches. In particular, do not commit a prebuilt Psiphon
  AAR: that component is built from source here on purpose, to close a
  provenance gap the upstream project's own audit left open. See
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Do not commit signing material.** `release.keystore`, `keystore.properties`,
  and anything with a password in it stay out of the repository. See
  [docs/SIGNING.md](docs/SIGNING.md).
- **Keep the two READMEs in sync.** If you change user-facing behavior, update
  both [README.md](README.md) and [README.fa.md](README.fa.md).
- **Licenses.** This project is AGPL-3.0-or-later. Before adding a dependency
  or a bundled binary, check its license is compatible and add it to
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Reporting a vulnerability

Do not open a public issue for a security problem. The process is in
[SECURITY.md](SECURITY.md).
