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
# That trade-off is only defensible while somebody actually SEES the warning,
# which for months nobody did - see the release gate in
# .github/workflows/build.yml: on a v* tag a missing core is a hard failure now.
#
# ============================================================================
# WHY THE GO VERSION IS CHECKED HERE AND NOT LEFT TO GO
# ============================================================================
# psiphon-tunnel-core's go.mod carries BOTH a `go` and a `toolchain` directive
# (currently go 1.26.0 / toolchain go1.26.5). actions/setup-go exports
# GOTOOLCHAIN=local whenever a go-version is requested, deliberately, so Go then
# REFUSES to fetch the newer toolchain and stops with
#
#   go: go.mod requires go >= 1.26.0 (running go 1.23.x; GOTOOLCHAIN=local)
#
# before compiling a single package. In CI this step is continue-on-error, so
# that message went into a green run's log and the APK simply shipped without a
# Psiphon core. The requirement is therefore read from upstream's own go.mod and
# compared here, where the failure can name the file to edit.
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
# Requires: ANDROID_NDK_HOME, Go (version per psiphon's go.mod), curl + unzip.
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
RAW="https://""raw.githubusercontent.com"
MAVEN="https://""repo1.maven.org/maven2"
# Byte-identical, independently hosted mirror of Maven Central. A single 403 from
# Sonatype has taken this repository's builds out before; see settings.gradle.kts
# upstream for the same reasoning applied to Gradle's own resolution.
MAVEN_MIRROR="https://""maven-central.storage-download.googleapis.com/maven2"

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
# Shared: is this file actually a runnable core for this ABI?
# ---------------------------------------------------------------------------
# An ELF built for the wrong architecture, or a shared object where the app
# expects something it can exec, fails at exec time ON THE DEVICE with nothing
# useful in the log - and only for the users on that ABI. Both cores are checked
# here instead, where the build can still say what is wrong.
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

  # Go produces a PIE (Type: DYN) for android; tor's libtor.so is a PIE too.
  # EXEC is accepted as well so a non-PIE toolchain is not a false negative.
  if ! printf '%s\n' "${header}" | grep -qE '^[[:space:]]*Type:[[:space:]]*(DYN|EXEC)'; then
    echo "ERROR: [${abi}] ${path} is not an executable ELF (${machine})." >&2
    printf '%s\n' "${header}" >&2
    return 1
  fi

  echo "    verified $(basename "${path}") for ${abi}: ${machine}, $(stat -c%s "${path}" 2>/dev/null || echo '?') bytes"
}

# ---------------------------------------------------------------------------
# 1) Psiphon  (Go + cgo through the NDK)
# ---------------------------------------------------------------------------

# The Go version upstream's go.mod ASKS for: the toolchain directive if there is
# one (it is the suggested toolchain), else the go directive (the minimum).
psiphon_required_go() {
  local mod="${PSIPHON_SRC}/go.mod" want=""
  [ -f "${mod}" ] || return 0
  want="$(sed -n 's/^toolchain go\([0-9][0-9.]*\).*/\1/p' "${mod}" | head -n1)"
  if [ -z "${want}" ]; then
    want="$(sed -n 's/^go \([0-9][0-9.]*\).*/\1/p' "${mod}" | head -n1)"
  fi
  printf '%s' "${want}"
}

# Refuses to start a build that Go itself is going to refuse, with the fix in the
# message. THIS is the check whose absence shipped every release without Psiphon.
assert_go_can_build_psiphon() {
  local want have oldest
  want="$(psiphon_required_go)"
  have="$(go env GOVERSION 2>/dev/null | sed 's/^go//')"
  echo "==> [psiphon] go ${have:-unknown} (go.mod asks for ${want:-unknown}, GOTOOLCHAIN=${GOTOOLCHAIN:-auto})"
  [ -n "${want}" ] && [ -n "${have}" ] || return 0

  oldest="$(printf '%s\n%s\n' "${want}" "${have}" | sort -V | head -n1)"
  [ "${oldest}" = "${want}" ] && return 0   # have >= want

  if [ "${GOTOOLCHAIN:-auto}" = "local" ]; then
    echo "ERROR: psiphon-tunnel-core needs Go >= ${want}, this toolchain is ${have}," >&2
    echo "       and GOTOOLCHAIN=local forbids fetching a newer one. Go would stop with" >&2
    echo "       'go.mod requires go >= ${want}' before compiling anything." >&2
    echo "       FIX: raise GO_VERSION in .github/workflows/build.yml to ${want%.*} or" >&2
    echo "       newer, or run this step with GOTOOLCHAIN=auto." >&2
    echo "       (actions/setup-go sets GOTOOLCHAIN=local whenever go-version is given.)" >&2
    exit 1
  fi
  echo "    Go ${have} is older than ${want}; GOTOOLCHAIN=${GOTOOLCHAIN:-auto} will fetch it."
}

build_psiphon() {
  if ! command -v go >/dev/null 2>&1; then
    echo "ERROR: Go is not installed - cannot build the Psiphon core." >&2
    exit 1
  fi

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

  # Read the requirement out of the tree we just cloned, not out of a comment.
  assert_go_can_build_psiphon

  # Pull the module graph once, with retries. A dependency download failing
  # halfway through the first ABI used to look exactly like a compile error.
  local attempt
  for attempt in 1 2 3; do
    if ( cd "${PSIPHON_SRC}" && go mod download ); then
      break
    fi
    echo "    module download attempt ${attempt} failed; retrying in 10s"
    sleep 10
  done

  local abi goarch clang out ldflags ok
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
    ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384"

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
    # that an error unless -checklinkname=0 is passed, so BOTH forms are tried
    # on every attempt rather than the plain one three times.
    ok=0
    for attempt in 1 2 3; do
      if build_one "${ldflags}"; then ok=1; break; fi
      echo "    plain build failed (attempt ${attempt}); retrying with -checklinkname=0"
      if build_one "${ldflags} -checklinkname=0"; then ok=1; break; fi
      [ "${attempt}" -lt 3 ] && sleep 10
    done
    if [ "${ok}" != 1 ]; then
      echo "ERROR: [${abi}] libpsiphon.so could not be built after 3 attempts." >&2
      exit 1
    fi

    "${NDK_TOOLCHAIN}/llvm-strip" "${out}" 2>/dev/null || true
    verify_core_elf "${abi}" "${out}"
  done

  # OPTIONAL: a Psiphon client config, if the build was given one. It is
  # network-issued (PropagationChannelId / SponsorId) and is NOT ours to commit,
  # so it only ever arrives through the environment and lands in a gitignored
  # asset. Without it the app asks the user to paste one.
  #
  # NOTE: CI writes this in the APP job, not here - the natives job is cached and
  # this path is not part of the cache. Kept for local builds.
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

# tor-android's build feeds its binaries in as a libs/*.so fileTree, and the AAR
# layout has moved before (jni/, libs/, and prefab for CMake consumers). Look in
# every place it has ever been, then fall back to a path-scoped find, so an
# upstream repackaging costs a lookup rather than the Tor core.
locate_tor_binary() {
  local abi="$1" candidate
  for candidate in \
      "${TOR_WORK}/x/jni/${abi}/libtor.so" \
      "${TOR_WORK}/x/libs/${abi}/libtor.so" \
      "${TOR_WORK}/x/prefab/modules/tor/libs/android.${abi}/libtor.so"; do
    if [ -f "${candidate}" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  candidate="$(find "${TOR_WORK}/x" -type f -name 'libtor.so' -path "*${abi}*" 2>/dev/null | head -n1)"
  if [ -n "${candidate}" ]; then
    printf '%s' "${candidate}"
    return 0
  fi
  return 1
}

# geoip is what makes "Tor exit country" mean anything: without it tor logs a
# warning and silently ignores ExitNodes, so the setting pretends to work. The
# AAR is the normal source; torproject/tor is the fallback, because losing the
# feature to a repackaging is worse than one extra download.
install_tor_geoip() {
  mkdir -p "${ASSETS_DIR}/tor"
  local name found url
  for name in geoip geoip6; do
    found="$(find "${TOR_WORK}" -type f -name "${name}" 2>/dev/null | head -n1)"
    if [ -n "${found}" ]; then
      cp "${found}" "${ASSETS_DIR}/tor/${name}"
      echo "    bundled tor ${name} (from the AAR)"
      continue
    fi
    url="${RAW}/torproject/tor/tor-${TOR_VERSION}/src/config/${name}"
    if curl -fsSL --retry 3 -o "${ASSETS_DIR}/tor/${name}.tmp" "${url}" 2>/dev/null &&
       [ "$(stat -c%s "${ASSETS_DIR}/tor/${name}.tmp" 2>/dev/null || echo 0)" -gt 102400 ]; then
      mv "${ASSETS_DIR}/tor/${name}.tmp" "${ASSETS_DIR}/tor/${name}"
      echo "    bundled tor ${name} (from torproject/tor ${TOR_VERSION})"
    else
      rm -f "${ASSETS_DIR}/tor/${name}.tmp"
      echo "    NOTE: no ${name} - Tor exit country selection will be unavailable"
    fi
  done
}

install_tor_from_aar() {
  local aar="${TOR_WORK}/tor-android-${TOR_VERSION}.aar"
  local path="info/guardianproject/tor-android/${TOR_VERSION}/tor-android-${TOR_VERSION}.aar"
  rm -rf "${TOR_WORK}"
  mkdir -p "${TOR_WORK}/x"
  echo "==> [tor] downloading tor-android ${TOR_VERSION}"

  local base got=0
  for base in "${MAVEN}" "${MAVEN_MIRROR}"; do
    if curl -fsSL --retry 3 -o "${aar}" "${base}/${path}"; then
      got=1
      break
    fi
    echo "    ${base} did not serve the AAR; trying the next source"
  done
  if [ "${got}" != 1 ]; then
    echo "ERROR: could not download tor-android ${TOR_VERSION} from any source." >&2
    exit 1
  fi

  ( cd "${TOR_WORK}/x" && unzip -q "${aar}" )

  local abi src
  for abi in "${ABIS[@]}"; do
    if ! src="$(locate_tor_binary "${abi}")"; then
      echo "ERROR: [${abi}] libtor.so not found in the AAR (layout changed?)." >&2
      find "${TOR_WORK}/x" -name '*.so' >&2 || true
      exit 1
    fi
    mkdir -p "${JNI_DIR}/${abi}"
    cp "${src}" "${JNI_DIR}/${abi}/libtor.so"
    verify_core_elf "${abi}" "${JNI_DIR}/${abi}/libtor.so"
  done

  install_tor_geoip
}

install_tor_from_source() {
  echo "==> [tor] building from source (TOR_FROM_SOURCE=1)"
  rm -rf "${TOR_WORK}"
  git clone --depth 1 --recursive "${GH}/guardianproject/tor-android.git" "${TOR_WORK}"
  local abi src
  for abi in "${ABIS[@]}"; do
    ( cd "${TOR_WORK}" && ./tor-droid-make.sh build -a "${abi}" )
    src="${TOR_WORK}/external/lib/${abi}/libtor.so"
    if [ ! -f "${src}" ]; then
      src="$(find "${TOR_WORK}" -type f -name 'libtor.so' -path "*${abi}*" 2>/dev/null | head -n1)"
    fi
    if [ -z "${src}" ] || [ ! -f "${src}" ]; then
      echo "ERROR: [${abi}] libtor.so missing after the source build." >&2
      exit 1
    fi
    mkdir -p "${JNI_DIR}/${abi}"
    cp "${src}" "${JNI_DIR}/${abi}/libtor.so"
    verify_core_elf "${abi}" "${JNI_DIR}/${abi}/libtor.so"
  done
  install_tor_geoip
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
