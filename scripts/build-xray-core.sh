#!/usr/bin/env bash
#
# Builds the XRAY core that the Xray chain modes stack on top of Aether /
# Psiphon (and under Tor):
#
#   libxray.so  <- XTLS/Xray-core's ./main, cross-compiled from source with the
#                  NDK toolchain, pinned by tag (default v26.9.30).
#
# It lands in app/src/main/jniLibs/<abi>/ under a .so name, which is how
# Android gives us an executable with the exec bit set in a directory we are
# still allowed to exec from (same trick as libaether.so, libpsiphon.so and
# libtor.so - they are NOT libraries). See core/NativeChild.kt and
# core/XrayCore.kt; what the app runs on it is described in docs/XRAY.md.
#
# ============================================================================
# OPTIONAL, LIKE THE OTHER OVERLAY CORES
# ============================================================================
# Without libxray.so the app works exactly as before and only the five Xray
# chain modes are unavailable, which the Chain page detects and says (the
# cards are disabled with the core named, see core/CoreAvailability.kt). So in
# CI this is its own continue-on-error step: a Go hiccup costs the Xray modes
# for one branch build, never the APK. On a v* tag a missing core is a hard
# failure (rule 7 in .github/workflows/build.yml).
#
# ============================================================================
# WHY FROM SOURCE, PINNED BY TAG
# ============================================================================
# This repository's rule is "nothing prebuilt", and Psiphon honours it the same
# way: the binary is compiled here from a named upstream revision rather than
# downloaded, so what ships is inspectable. XRAY_REF may be a tag, a branch or a
# full 40-character commit SHA; bumping it is a reviewed one-line diff, and
# because CI keys its native cache on this file, that diff is also a rebuild.
#
# v26.9.30 needs Go 1.27 (its go.mod says `go 1.27`), which is why CI installs
# XRAY_GO_VERSION for this step instead of Psiphon's GO_VERSION. It is also
# stricter about configs than v26.7.28 (allowInsecure, the HTTP/2 and QUIC
# transports, plaintext VLESS / Trojan); core/XrayConfig.kt refuses or rewrites
# those before Xray ever sees them. See docs/XRAY.md before bumping again.
#
# ============================================================================
# WHY THESE FLAGS
# ============================================================================
#  - CGO_ENABLED=1 with the NDK clang, GOOS=android. On Android a pure-Go
#    resolver has no /etc/resolv.conf to read; through cgo, name lookups go to
#    bionic like any other app's. That matters for Tor over Xray, where Xray
#    resolves the server's name itself instead of handing it to a hop below.
#  - -checklinkname=0: Xray's dependency tree uses //go:linkname into the
#    runtime, which Go 1.23+ refuses to link without it. Upstream's README
#    ("Reproducible Releases", For Android) builds with exactly this.
#  - -trimpath -buildvcs=false -buildid= -s -w and -gcflags=all=-l=4: upstream's
#    reproducible-release flags, so the binary depends on the source and the
#    Go version, not on the path or the clock of the machine that built it.
#  - core.build=<commit>: what `xray version` (and therefore the diagnostics
#    log line "Xray <version> started") reports.
#  - -Wl,-z,max-page-size=16384: Android 15 devices can use 16 KB pages, and a
#    binary linked for 4 KB simply will not load there.
#
# Usage:  build-xray-core.sh
#
# Requires: ANDROID_NDK_HOME, Go (version per Xray-core's go.mod), git.
# Every network access happens here or in CI, never on device.
#
# Optional: AETHER_ABIS="arm64-v8a" (space-separated) builds only those ABIs.
# Optional: XRAY_REPO / XRAY_REF pick the source.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

API="${ANDROID_API:-26}"
read -r -a ABIS <<< "${AETHER_ABIS:-arm64-v8a armeabi-v7a}"
for _abi in "${ABIS[@]}"; do
  case "${_abi}" in
    arm64-v8a|armeabi-v7a) ;;
    *) echo "ERROR: unsupported ABI '${_abi}' in AETHER_ABIS (use arm64-v8a and/or armeabi-v7a)." >&2; exit 2 ;;
  esac
done
unset _abi

# Host prefix assembled from fragments, same convention as fetch-natives.sh and
# build-overlay-cores.sh: no full literal URL sits in the file.
GH="https://""github.com"

XRAY_REPO="${XRAY_REPO:-XTLS/Xray-core}"
XRAY_REF="${XRAY_REF:-v26.9.30}"
XRAY_SRC="${NATIVE_DIR}/xray-core"
XRAY_STAMP="${XRAY_SRC}/.aether-source"

mkdir -p "${NATIVE_DIR}"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
  exit 1
fi
if ! command -v go > /dev/null 2>&1; then
  echo "ERROR: Go is not installed - cannot build the Xray core." >&2
  exit 1
fi
if ! command -v git > /dev/null 2>&1; then
  echo "ERROR: git is not installed - cannot fetch the Xray-core source." >&2
  exit 1
fi

NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  if [ -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin" ]; then
    NDK_TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
    break
  fi
done
if [ -z "${NDK_TOOLCHAIN}" ]; then
  echo "ERROR: could not find the NDK LLVM toolchain under ${ANDROID_NDK_HOME}" >&2
  exit 1
fi
echo "==> NDK toolchain: ${NDK_TOOLCHAIN}"

clang_for_abi() {
  case "$1" in
    arm64-v8a)   echo "${NDK_TOOLCHAIN}/aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "${NDK_TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" ;;
    *) echo "" ;;
  esac
}

goarch_for_abi() {
  case "$1" in
    arm64-v8a)   echo "arm64" ;;
    armeabi-v7a) echo "arm" ;;
    *) echo "" ;;
  esac
}

# ---------------------------------------------------------------------------
# Is this file actually a runnable core for this ABI?
# ---------------------------------------------------------------------------
# Same check as build-overlay-cores.sh: an ELF for the wrong architecture, or a
# shared object where the app expects something it can exec, fails at exec
# time ON THE DEVICE with nothing useful in the log - and only for the users on
# that ABI. Checked here instead, where the build can still say what is wrong.
verify_core_elf() {
  local abi="$1" path="$2" readelf="" header machine

  if [ ! -s "${path}" ]; then
    echo "ERROR: [${abi}] ${path} is missing or empty." >&2
    return 1
  fi

  if [ -x "${NDK_TOOLCHAIN}/llvm-readelf" ]; then
    readelf="${NDK_TOOLCHAIN}/llvm-readelf"
  else
    readelf="$(command -v readelf || true)"
  fi
  if [ -z "${readelf}" ]; then
    echo "    NOTE: no readelf available - skipping the ELF check for $(basename "${path}")"
    return 0
  fi

  header="$("${readelf}" -h "${path}" 2>/dev/null || true)"
  if [ -z "${header}" ]; then
    echo "ERROR: [${abi}] ${path} is not an ELF file at all." >&2
    return 1
  fi

  machine="$(printf '%s\n' "${header}" \
    | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' | head -n1)"
  case "${abi}" in
    arm64-v8a)
      if ! printf '%s' "${machine}" | grep -q 'AArch64'; then
        echo "ERROR: [${abi}] ${path} is built for '${machine}', not AArch64." >&2
        return 1
      fi
      ;;
    armeabi-v7a)
      if ! printf '%s' "${machine}" | grep -qi 'ARM'; then
        echo "ERROR: [${abi}] ${path} is built for '${machine}', not ARM." >&2
        return 1
      fi
      ;;
  esac

  # Go produces a PIE (Type: DYN) for android. EXEC is accepted as well so a
  # non-PIE toolchain is not a false negative.
  if ! printf '%s\n' "${header}" | grep -qE '^[[:space:]]*Type:[[:space:]]*(DYN|EXEC)'; then
    echo "ERROR: [${abi}] ${path} is not an executable ELF (${machine})." >&2
    printf '%s\n' "${header}" >&2
    return 1
  fi

  echo "    verified $(basename "${path}") for ${abi}: ${machine}, $(stat -c%s "${path}" 2>/dev/null || echo '?') bytes"
}

# ---------------------------------------------------------------------------
# Go version: checked against upstream's go.mod, with the fix in the message
# ---------------------------------------------------------------------------
# Same reasoning as Psiphon's check in build-overlay-cores.sh: actions/setup-go
# exports GOTOOLCHAIN=local whenever a go-version is requested, and Go then
# stops with "go.mod requires go >= X" before compiling anything - inside a
# continue-on-error step, i.e. as a warning nobody reads.
xray_required_go() {
  local mod="${XRAY_SRC}/go.mod" want=""
  [ -f "${mod}" ] || return 0
  want="$(sed -n 's/^toolchain go\([0-9][0-9.]*\).*/\1/p' "${mod}" | head -n1)"
  if [ -z "${want}" ]; then
    want="$(sed -n 's/^go \([0-9][0-9.]*\).*/\1/p' "${mod}" | head -n1)"
  fi
  printf '%s' "${want}"
}

assert_go_can_build_xray() {
  local want have oldest
  want="$(xray_required_go)"
  have="$(go env GOVERSION 2>/dev/null | sed 's/^go//')"
  echo "==> [xray] go ${have:-unknown} (go.mod asks for ${want:-unknown}, GOTOOLCHAIN=${GOTOOLCHAIN:-auto})"
  [ -n "${want}" ] && [ -n "${have}" ] || return 0

  oldest="$(printf '%s\n%s\n' "${want}" "${have}" | sort -V | head -n1)"
  [ "${oldest}" = "${want}" ] && return 0   # have >= want

  if [ "${GOTOOLCHAIN:-auto}" = "local" ]; then
    echo "ERROR: Xray-core ${XRAY_REF} needs Go >= ${want}, this toolchain is ${have}," >&2
    echo "       and GOTOOLCHAIN=local forbids fetching a newer one." >&2
    echo "       FIX: raise XRAY_GO_VERSION in .github/workflows/build.yml to" >&2
    echo "       $(printf '%s' "${want}" | cut -d. -f1-2) or newer, or run this step with GOTOOLCHAIN=auto." >&2
    exit 1
  fi
  echo "    Go ${have} is older than ${want}; GOTOOLCHAIN=${GOTOOLCHAIN:-auto} will fetch it."
}

# ---------------------------------------------------------------------------
# Source
# ---------------------------------------------------------------------------

# A full 40-hex-digit commit SHA cannot go through `git clone --branch`.
is_commit_sha() {
  printf '%s' "$1" | grep -qE '^[0-9a-f]{40}$'
}

clone_xray() {
  local url="${GH}/${XRAY_REPO}.git" attempt ok=0
  rm -rf "${XRAY_SRC}"
  echo "==> [xray] cloning ${XRAY_REPO} @ ${XRAY_REF}"
  for attempt in 1 2 3; do
    if is_commit_sha "${XRAY_REF}"; then
      # GitHub serves any reachable commit by SHA, so a shallow fetch of exactly
      # the pinned commit works without the history.
      if mkdir -p "${XRAY_SRC}" && ( cd "${XRAY_SRC}" && \
           git init -q && \
           { git remote get-url origin > /dev/null 2>&1 || git remote add origin "${url}"; } && \
           git fetch -q --depth 1 origin "${XRAY_REF}" && \
           git -c advice.detachedHead=false checkout -q FETCH_HEAD ); then
        ok=1
        break
      fi
    else
      # A tag or a branch: refusing to fall back to the default branch is
      # deliberate - silently building main instead of the pinned release is
      # exactly the kind of drift nobody notices.
      if git -c advice.detachedHead=false clone -q --depth 1 --branch "${XRAY_REF}" \
           "${url}" "${XRAY_SRC}"; then
        ok=1
        break
      fi
    fi
    echo "    fetch of ${XRAY_REF} failed (attempt ${attempt}); retrying in 10s"
    rm -rf "${XRAY_SRC}"
    sleep 10
  done
  if [ "${ok}" != 1 ]; then
    echo "ERROR: could not fetch ${XRAY_REF} from ${XRAY_REPO}." >&2
    exit 1
  fi
  printf '%s@%s\n' "${XRAY_REPO}" "${XRAY_REF}" > "${XRAY_STAMP}"
  echo "    checked out $(cd "${XRAY_SRC}" && git rev-parse HEAD 2>/dev/null || echo '?')"
}

build_xray() {
  # Reuse a cached checkout only if it was made from the SAME repo@ref.
  local want_stamp have_stamp=""
  want_stamp="${XRAY_REPO}@${XRAY_REF}"
  [ -f "${XRAY_STAMP}" ] && have_stamp="$(head -n1 "${XRAY_STAMP}")"
  if [ -d "${XRAY_SRC}/.git" ] && [ "${have_stamp}" = "${want_stamp}" ]; then
    echo "==> [xray] reusing the checkout in ${XRAY_SRC} (${want_stamp})"
  else
    if [ -d "${XRAY_SRC}" ]; then
      echo "==> [xray] cached checkout is '${have_stamp:-unknown}', want '${want_stamp}'; re-cloning"
    fi
    clone_xray
  fi

  if [ ! -d "${XRAY_SRC}/main" ] || [ ! -f "${XRAY_SRC}/go.mod" ]; then
    echo "ERROR: ${XRAY_SRC}/main or go.mod not found - upstream layout changed." >&2
    ls -la "${XRAY_SRC}" >&2 || true
    exit 1
  fi

  assert_go_can_build_xray

  # Pull the module graph once, with retries. A dependency download failing
  # halfway through the first ABI otherwise looks exactly like a compile error.
  local attempt
  for attempt in 1 2 3; do
    if ( cd "${XRAY_SRC}" && go mod download ); then
      break
    fi
    echo "    module download attempt ${attempt} failed; retrying in 10s"
    sleep 10
  done

  local commit
  commit="$(cd "${XRAY_SRC}" && git rev-parse --short=7 HEAD 2>/dev/null || echo unknown)"

  local abi goarch clang out ldflags ok
  for abi in "${ABIS[@]}"; do
    goarch="$(goarch_for_abi "${abi}")"
    clang="$(clang_for_abi "${abi}")"
    if [ -z "${goarch}" ] || [ ! -x "${clang}" ]; then
      echo "ERROR: [${abi}] no Go arch or NDK clang for this ABI." >&2
      exit 1
    fi
    mkdir -p "${JNI_DIR}/${abi}"
    out="${JNI_DIR}/${abi}/libxray.so"
    echo "==> [xray] building ${XRAY_REF} (${commit}) for ${abi} (GOARCH=${goarch}, API ${API})"

    ldflags="-X github.com/xtls/xray-core/core.build=${commit} -s -w -buildid= -checklinkname=0"
    ldflags="${ldflags} -extldflags=-Wl,-z,max-page-size=16384"

    ok=0
    for attempt in 1 2 3; do
      if ( cd "${XRAY_SRC}" && \
           CGO_ENABLED=1 GOOS=android GOARCH="${goarch}" GOARM=7 \
           CC="${clang}" \
           CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
           go build -trimpath -buildvcs=false -gcflags="all=-l=4" \
             -ldflags "${ldflags}" \
             -o "${out}" ./main ); then
        ok=1
        break
      fi
      echo "    build failed (attempt ${attempt}); retrying in 10s"
      [ "${attempt}" -lt 3 ] && sleep 10
    done
    if [ "${ok}" != 1 ]; then
      echo "ERROR: [${abi}] libxray.so could not be built after 3 attempts." >&2
      exit 1
    fi

    "${NDK_TOOLCHAIN}/llvm-strip" "${out}" 2>/dev/null || true
    verify_core_elf "${abi}" "${out}"
  done
}

build_xray

echo "==> Done. Xray core installed:"
find "${JNI_DIR}" -type f -name 'libxray.so' -exec ls -la {} + 2>/dev/null || true
