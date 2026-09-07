# Why no release shipped the Psiphon or Tor core

A post-mortem, and the two invariants that keep it fixed. Read this before
changing anything in `scripts/build-overlay-cores.sh` or the overlay-core steps
in `.github/workflows/build.yml`.

## The symptom

Every published APK offered the seven chain modes and then said *"this build does
not include the Psiphon core"* / *"...the Tor core"*. That message was correct.
`libpsiphon.so` and `libtor.so` were genuinely not in the APK, so
[`CoreAvailability`](../app/src/main/java/studio/cluvex/aether/core/CoreAvailability.kt)
reported them absent and
[`ChainStack`](../app/src/main/java/studio/cluvex/aether/vpn/session/ChainStack.kt)
refused every mode that needed them. Nothing in the app was broken.

It is visible from outside the pipeline: the `v1.4.5` arm64 APK is 6.1 MB. A
cross-compiled Psiphon ConsoleClient alone is around 20 MB.

## The cause

`psiphon-tunnel-core`'s `staging-client` branch has this in `go.mod`:

```
go 1.26.0
toolchain go1.26.5
```

`build.yml` pinned `GO_VERSION: "1.23"` and installs it with
`actions/setup-go@v6`. Since [actions/setup-go#460] that action exports
`GOTOOLCHAIN=local` for the whole job whenever `go-version` is given - on purpose,
so the requested version is not silently upgraded. With `GOTOOLCHAIN=local`, Go
refuses to fetch a newer toolchain and stops before compiling a single package:

```
go: go.mod requires go >= 1.26.0 (running go 1.23.x; GOTOOLCHAIN=local)
```

[actions/setup-go#460]: https://github.com/actions/setup-go/pull/460

## Why nobody noticed for months

The overlay-core steps are `continue-on-error: true`, and that is the right
call: a build without them is a valid Aether build, and a Go toolchain hiccup
must not turn into "no APK at all". But the consequences were only ever reported
as `::warning::` lines, and a warning in a run that finishes **green** is not a
signal. The build was green, the release published, and the feature was gone.

The cache made it permanent. `actions/cache` saves on job *success*, so the
first run that failed to build Psiphon stored an incomplete `jniLibs` under the
key, and a cache hit skipped the build step that would have fixed it. That half
is already handled (restore/save split with a completeness guard); the missing
piece was that the incompleteness could still be *published*.

## Invariant 1: the Go version is derived, not remembered

`scripts/build-overlay-cores.sh` reads the `toolchain` (or `go`) directive out of
the cloned `go.mod`, compares it with `go env GOVERSION`, and - when the runner's
Go is older and `GOTOOLCHAIN=local` - fails with the file and the value to
change. The Psiphon step also runs with `GOTOOLCHAIN=auto`, so the next time
upstream bumps its requirement Go fetches the toolchain instead of the feature
disappearing.

`GO_VERSION` in `build.yml` is still expected to satisfy upstream on its own.
`GOTOOLCHAIN=auto` is the escape hatch, not the plan.

## Invariant 2: a `v*` tag cannot publish without both cores

A missing `libpsiphon.so` or `libtor.so` is:

* a **warning** on a branch push, a `workflow_dispatch` or a pull request - the
  APK is still built, installable, and honest about what it contains;
* a **hard failure** on a `refs/tags/v*` ref, checked twice: in the `natives`
  job right after the cores are built, and in the `app` job against the actual
  entries inside every assembled APK.

So the trade-off in rule 5 of `build.yml` ("one missing core costs exactly that
core") survives, and rule 7 stops it from costing a release.

## Invariant 3: a core is verified before it is packaged

Both `libpsiphon.so` and `libtor.so` are ELF-checked per ABI - correct machine
type (`AArch64` / `ARM`) and an executable/PIE object - before they are accepted
into `app/src/main/jniLibs`. These files are executables shipped under a `.so`
name (see `NativeChild`), and a wrong-architecture one fails at `exec` time on
the device with nothing useful in the log, for that ABI's users only.

`libtor.so` is also *located* in the extracted AAR (`jni/<abi>/`, `libs/<abi>/`,
`prefab/`, then a path-scoped `find`) rather than expected at one fixed path:
`tor-android` feeds its binaries into a Gradle-KTS build as a `libs/*.so`
fileTree, and an upstream repackaging should cost a lookup, not the core.

## What is still optional, and why that is fine

* **The Psiphon client config.** `PropagationChannelId` / `SponsorId` are issued
  by the Psiphon network and are not ours to commit. The app takes one pasted in
  Settings > Chain, or `assets/psiphon.config` written by CI from the
  `PSIPHON_CONFIG_B64` secret. Without either, the Psiphon modes say so on the
  settings page instead of failing three minutes into a connect attempt.
* **`assets/tor/geoip`.** Only Tor exit-country selection needs it, and the app
  disables that field with a reason when it is absent - rather than pretending a
  setting works that `tor` silently ignores. It now has a fallback source, so
  losing it takes two independent failures.

See [CHAINING.md](CHAINING.md) for the ports, the DNS front and the hop order.
