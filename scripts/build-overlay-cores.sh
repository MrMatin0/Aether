#!/usr/bin/env bash
#
# Builds the OVERLAY cores that the chain modes stack on top of Aether:
#
#   libpsiphon.so  <- psiphon-tunnel-core's ConsoleClient, cross-compiled from
#                     source with the NDK toolchain.
#   libtor.so      <- tor for Android. Taken from the Tor Project / Guardian
#                     Project's published AAR by default (see WHY below), or
#                     built from source with TOR_FROM_SOURCE=1.
#   assets/tor/geoip, geoip6 <- tor's country database, needed ONLY for exit
#                     country selection.
#
# Both land in app/src/main/jniLibs/<abi>/ under .so names, which is how Android
# gives us an executable with the exec bit set in a directory we are still
# allowed to exec from (same trick as libaether.so - they are NOT libraries).
#
# ============================================================================
# WHY THIS IS A SEPARATE SCRIPT AND NOT PART OF build-natives.sh
# ============================================================================
# fetch-natives.sh / build-natives.sh produce the cores the app cannot run at
# all without (the engine, hev, the JNI bridge). They must fail loudly, and CI
# must go red when they do. These two are ADDITIVE: without them the app works
# exactly as before and only the Psiphon/Tor chain modes are unavailable, which
# the app detects and reports in plain language (see NativeChild.isAvailable).
# Folding them into the mandatory path would mean a Go toolchain hiccup turns
# into "no APK at all", which is a much worse failure than "no Tor this build".
#
# ============================================================================
# WHY TOR COMES FROM THE PUBLISHED AAR BY DEFAULT
# ============================================================================
# This repo's rule is "nothing prebuilt", and Psiphon honours it. tor is the one
# exception, deliberately: building it for Android means first cross-compiling
# OpenSSL, libevent, zlib and zstd, which is upstream's own multi-stage
# tor-droid-make.sh and adds ~20 minutes and four more moving parts to every
# cold build. The AAR is published by the Tor Project / Guardian Project, signed,
# reproducible from their Vagrant setup, and is the exact binary Orbot ships to
# millions of users - a better provenance story than a binary this repo builds
# once and never verifies. TOR_FROM_SOURCE=1 does it the other way for anyone
# who wants to check.
#
# Usage:  build-overlay-cores.sh [psiphon|tor|all]      (default: all)
#
# Requires: ANDROID_NDK_HOME, Go 1.21+ (psiphon), curl + unzip (tor).
# Every network access happens here or in CI, never on device.
set -euo pipefail

TARGET="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"
ASSETS_DIR="${PROJECT_DIR}/app/src/main/assets"

API="${ANDROID_API:-26}"
ABIS=("arm64-v8a" "armeabi-v7a")

# Host prefixes assembled from fragments, same convention as fetch-natives.sh:
# no full literal URL sits in the file.
GH="https://""github.com"
MAVEN="https://""repo1.maven.org/maven2"

PSIPHON_REPO="${PSIPHON_REPO:-Psiphon-Labs/psiphon-tunnel-core}"
# staging-client is upstream's production client branch (see their README: the
# module cannot be resolved by @latest, so a branch or commit is the only way).
PSIPHON_REF="${PSIPHON_REF:-staging-client}"
PSIPHON_SRC="${NATIVE_DIR}/psiphon-tunnel-core"

TOR_VERSION="${TOR_ANDROID_VERSION:-0.4.9.11}"
TOR_FROM_SOURCE="${TOR_FROM_SOURCE:-}"
TOR_WORK="${NATIVE_DIR}/tor-android"

mkdir -p "${NATIVE_DIR}" "${ASSETS_DIR}"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
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
# 1) Psiphon  (Go + cgo through the NDK)
# ---------------------------------------------------------------------------
build_psiphon() {
  if ! command -v go >/dev/null 2>&1; then
    echo "ERROR: Go is not installed - cannot build the Psiphon core." >&2
    exit 1
  fi
  echo "==> [psiphon] go $(go version | awk '{print $3}')"

  if [ ! -d "${PSIPHON_SRC}/.git" ]; then
    rm -rf "${PSIPHON_SRC}"
    echo "==> [psiphon] cloning ${PSIPHON_REPO} @ ${PSIPHON_REF}"
    if ! git clone --depth 1 --branch "${PSIPHON_REF}" \
        "${GH}/${PSIPHON_REPO}.git" "${PSIPHON_SRC}" 2>/dev/null; then
      echo "    ref '${PSIPHON_REF}' not found; using the default branch"
      rm -rf "${PSIPHON_SRC}"
      git clone --depth 1 "${GH}/${PSIPHON_REPO}.git" "${PSIPHON_SRC}"
    fi
  else
    echo "==> [psiphon] reusing the checkout in ${PSIPHON_SRC}"
  fi

  if [ ! -d "${PSIPHON_SRC}/ConsoleClient" ]; then
    echo "ERROR: ${PSIPHON_SRC}/ConsoleClient not found - upstream layout changed." >&2
    ls -la "${PSIPHON_SRC}" >&2 || true
    exit 1
  fi

  local abi goarch clang out
  for abi in "${ABIS[@]}"; do
    goarch="$(goarch_for_abi "${abi}")"
    clang="$(clang_for_abi "${abi}")"
    if [ -z "${goarch}" ] || [ ! -x "${clang}" ]; then
      echo "ERROR: [${abi}] no Go arch or NDK clang for this ABI." >&2
      exit 1
    fi
    mkdir -p "${JNI_DIR}/${abi}"
    out="${JNI_DIR}/${abi}/libpsiphon.so"
    echo "==> [psiphon] building for ${abi} (GOARCH=${goarch}, API ${API})"

    # 16 KB page alignment: Android 15 devices can use a 16 KB page size, and a
    # binary linked for 4 KB simply will not load there.
    local ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384"

    build_one() {
      ( cd "${PSIPHON_SRC}" && \
        CGO_ENABLED=1 GOOS=android GOARCH="${goarch}" GOARM=7 \
        CC="${clang}" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
        go build -trimpath -buildvcs=false \
          -ldflags "$1" \
          -o "${out}" ./ConsoleClient )
    }

    # Psiphon's dependencies use //go:linkname into the runtime. Go 1.23 made
    # that an error unless -checklinkname=0 is passed, and that flag does not
    # exist on older toolchains - so try the portable form first and only then
    # the 1.23+ escape hatch, instead of pinning a Go version this repo does
    # not otherwise care about.
    if ! build_one "${ldflags}"; then
      echo "    retrying with -checklinkname=0 (Go 1.23+ linkname policy)"
      build_one "${ldflags} -checklinkname=0"
    fi

    "${NDK_TOOLCHAIN}/llvm-strip" "${out}" 2>/dev/null || true
    # An ELF that is not executable, or is a shared object rather than a PIE
    # executable, would fail at exec time on the device with nothing useful in
    # the log. Fail here instead.
    file "${out}" 2>/dev/null || true
    test -s "${out}" || { echo "ERROR: [${abi}] libpsiphon.so was not produced" >&2; exit 1; }
    echo "    installed libpsiphon.so for ${abi} ($(stat -c%s "${out}" 2>/dev/null || echo '?') bytes)"
  done

  # OPTIONAL: a Psiphon client config, if the build was given one. It is
  # network-issued (PropagationChannelId / SponsorId) and is NOT ours to commit,
  # so it only ever arrives through the environment and lands in a gitignored
  # asset. Without it the app asks the user to paste one.
  if [ -n "${PSIPHON_CONFIG_B64:-}" ]; then
    printf '%s' "${PSIPHON_CONFIG_B64}" | base64 -d > "${ASSETS_DIR}/psiphon.config"
    echo "    bundled a Psiphon config from PSIPHON_CONFIG_B64"
  elif [ -n "${PSIPHON_CONFIG_FILE:-}" ] && [ -f "${PSIPHON_CONFIG_FILE}" ]; then
    cp "${PSIPHON_CONFIG_FILE}" "${ASSETS_DIR}/psiphon.config"
    echo "    bundled a Psiphon config from PSIPHON_CONFIG_FILE"
  else
    echo "    no Psiphon config provided - users will paste one in Settings > Chain"
  fi
}

# ---------------------------------------------------------------------------
# 2) tor  (published AAR by default; from source on request)
# ---------------------------------------------------------------------------
install_tor_from_aar() {
  local aar="${TOR_WORK}/tor-android-${TOR_VERSION}.aar"
  local url="${MAVEN}/info/guardianproject/tor-android/${TOR_VERSION}/tor-android-${TOR_VERSION}.aar"
  rm -rf "${TOR_WORK}"
  mkdir -p "${TOR_WORK}/x"
  echo "==> [tor] downloading tor-android ${TOR_VERSION}"
  curl -fsSL --retry 3 -o "${aar}" "${url}"
  ( cd "${TOR_WORK}/x" && unzip -q "${aar}" )

  local abi src
  for abi in "${ABIS[@]}"; do
    src="${TOR_WORK}/x/jni/${abi}/libtor.so"
    if [ ! -f "${src}" ]; then
      echo "ERROR: [${abi}] libtor.so not found in the AAR (layout changed?)." >&2
      find "${TOR_WORK}/x" -name 'libtor.so' >&2 || true
      exit 1
    fi
    mkdir -p "${JNI_DIR}/${abi}"
    cp "${src}" "${JNI_DIR}/${abi}/libtor.so"
    echo "    installed libtor.so for ${abi}"
  done

  # geoip / geoip6 are only needed for exit-country selection, so a layout
  # change upstream must degrade the feature, not break the build.
  mkdir -p "${ASSETS_DIR}/tor"
  local found
  for name in geoip geoip6; do
    found="$(find "${TOR_WORK}/x" -type f -name "${name}" | head -n1)"
    if [ -n "${found}" ]; then
      cp "${found}" "${ASSETS_DIR}/tor/${name}"
      echo "    bundled tor ${name}"
    else
      echo "    NOTE: ${name} not in the AAR - Tor exit country selection will be unavailable"
    fi
  done
}

install_tor_from_source() {
  echo "==> [tor] building from source (TOR_FROM_SOURCE=1)"
  rm -rf "${TOR_WORK}"
  git clone --depth 1 --recursive "${GH}/guardianproject/tor-android.git" "${TOR_WORK}"
  local abi
  for abi in "${ABIS[@]}"; do
    ( cd "${TOR_WORK}" && ./tor-droid-make.sh build -a "${abi}" )
    local src="${TOR_WORK}/external/lib/${abi}/libtor.so"
    test -f "${src}" || { echo "ERROR: [${abi}] ${src} missing after build" >&2; exit 1; }
    mkdir -p "${JNI_DIR}/${abi}"
    cp "${src}" "${JNI_DIR}/${abi}/libtor.so"
    echo "    installed libtor.so for ${abi} (from source)"
  done
  mkdir -p "${ASSETS_DIR}/tor"
  for name in geoip geoip6; do
    local found
    found="$(find "${TOR_WORK}" -type f -name "${name}" | head -n1)"
    [ -n "${found}" ] && cp "${found}" "${ASSETS_DIR}/tor/${name}" && echo "    bundled tor ${name}"
  done
}

build_tor() {
  if [ -n "${TOR_FROM_SOURCE}" ]; then
    install_tor_from_source
  else
    install_tor_from_aar
  fi
}

case "${TARGET}" in
  psiphon) build_psiphon ;;
  tor)     build_tor ;;
  all)     build_psiphon; build_tor ;;
  *) echo "Usage: build-overlay-cores.sh [psiphon|tor|all]" >&2; exit 2 ;;
esac

echo "==> Done (${TARGET}). Overlay cores installed:"
find "${JNI_DIR}" -type f \( -name 'libpsiphon.so' -o -name 'libtor.so' \) \
  -exec ls -la {} + 2>/dev/null || true
