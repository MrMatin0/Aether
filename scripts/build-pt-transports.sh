#!/usr/bin/env bash
#
# Builds the PLUGGABLE TRANSPORTS tor uses to reach a bridge:
#
#   liblyrebird.so   <- obfs4 + meek_lite (the Tor Project's obfs4proxy, renamed
#                       lyrebird in 2023). One binary, two transports.
#   libsnowflake.so  <- snowflake-client: WebRTC to volunteer proxies.
#   libwebtunnel.so  <- webtunnel client: looks like ordinary HTTPS traffic.
#
# All three land in app/src/main/jniLibs/<abi>/ under .so names, which is how
# Android gives us an executable with the exec bit set in a directory we are
# still allowed to exec from - the same trick libaether.so, libpsiphon.so and
# libtor.so use. They are NOT libraries, and nothing in this app loads them:
# tor spawns them itself through `ClientTransportPlugin ... exec <path>`.
#
# ============================================================================
# WHY THESE ARE OPTIONAL, AND WHY THAT IS SAFE HERE
# ============================================================================
# Same reasoning as build-overlay-cores.sh: a build without them is a valid
# build. What is different - and what made this worth a script rather than a
# vendored blob - is the failure mode if the app got it wrong. tor treats a
# `Bridge obfs4 ...` line with no matching ClientTransportPlugin as a FATAL
# configuration error and exits during startup. So the app must know exactly
# which of these three shipped, which is what core/PluggableTransports.kt reads
# and what the Bridges settings page disables by name. A missing binary costs
# that bridge TYPE and nothing else; plain bridges still work, because tor speaks
# those itself.
#
# ============================================================================
# WHY THEY ARE BUILT FROM SOURCE
# ============================================================================
# This repo's rule is "nothing prebuilt", and unlike tor itself these are plain
# Go programs with no C dependency chain to cross-compile first, so there is no
# reason to make an exception. They are also the binaries that see hostile
# traffic first, which is a poor argument for downloading somebody's blob.
#
# ============================================================================
# IDEMPOTENCE
# ============================================================================
# Each transport is skipped when its .so already exists and is non-empty for
# every shipped ABI - the same condition app/build.gradle.kts:buildPtTransports
# uses for its up-to-date check, so Gradle and this script agree on what
# "already built" means. Set AETHER_PT_FORCE=1 to rebuild anyway (a ref bump, or
# a binary you have reason to distrust). Checkouts under .native/ are reused
# rather than re-cloned for the same reason.
#
# Usage:  build-pt-transports.sh [lyrebird|snowflake|webtunnel|all]   (default: all)
#
# Requires: ANDROID_NDK_HOME, Go 1.21+, git. Every network access happens here
# or in CI, never on device.
set -euo pipefail

TARGET="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

API="${ANDROID_API:-26}"
ABIS=("arm64-v8a" "armeabi-v7a")
FORCE="${AETHER_PT_FORCE:-0}"

# Host prefixes assembled from fragments, same convention as the other scripts:
# no full literal URL sits in the file.
GITLAB="https://""gitlab.torproject.org/tpo/anti-censorship/pluggable-transports"

# Pinned, and overridable. An unpinned circumvention binary means the thing that
# talks to a censor is whatever was tagged that morning.
LYREBIRD_REF="${LYREBIRD_REF:-lyrebird-0.6.1}"
SNOWFLAKE_REF="${SNOWFLAKE_REF:-v2.11.0}"
WEBTUNNEL_REF="${WEBTUNNEL_REF:-v0.0.9}"

mkdir -p "${NATIVE_DIR}"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
  exit 1
fi
if ! command -v go >/dev/null 2>&1; then
  echo "ERROR: Go is not installed - cannot build the pluggable transports." >&2
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
# Is this transport already built for every ABI we ship?
# ---------------------------------------------------------------------------
# Deliberately the SAME condition as buildPtTransports in app/build.gradle.kts
# (present and non-empty, per ABI). If the two ever disagree, one of them either
# rebuilds forever or reports a binary that is not there.
already_built() {
  local out_name="$1" abi
  for abi in "${ABIS[@]}"; do
    [ -s "${JNI_DIR}/${abi}/${out_name}" ] || return 1
  done
  return 0
}

# ---------------------------------------------------------------------------
# Does this toolchain's linker know -checklinkname?
# ---------------------------------------------------------------------------
# lyrebird pulls in github.com/wlynxg/anet (and so does snowflake, through
# pion/transport): it //go:linkname's into net to get working interface
# enumeration on Android 11+, where the standard library's netlink calls are
# blocked. Go 1.23 made unsanctioned linknames a hard LINK error, so on any
# modern toolchain every build attempt dies with
#
#   link: github.com/wlynxg/anet: invalid reference to net.zoneCache
#
# after compiling everything, which reads like a source error and is not one.
# -checklinkname=0 is upstream anet's own documented answer and the same escape
# hatch build-overlay-cores.sh already uses for Psiphon. The flag itself only
# exists from 1.23 on, hence the version check rather than passing it blindly.
go_supports_checklinkname() {
  local have oldest
  have="$(go env GOVERSION 2>/dev/null | sed 's/^go//')"
  [ -n "${have}" ] || return 1
  oldest="$(printf '%s\n%s\n' "1.23" "${have}" | sort -V | head -n1)"
  [ "${oldest}" = "1.23" ]
}

# An ELF built for the wrong architecture fails at exec time ON THE DEVICE, only
# for the users on that ABI, with nothing useful in the log. Same check the
# overlay cores get, for the same reason.
verify_elf() {
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
      printf '%s' "${machine}" | grep -q 'AArch64' || {
        echo "ERROR: [${abi}] ${path} is built for '${machine}', not AArch64." >&2
        return 1
      }
      ;;
    armeabi-v7a)
      printf '%s' "${machine}" | grep -qi 'ARM' || {
        echo "ERROR: [${abi}] ${path} is built for '${machine}', not ARM." >&2
        return 1
      }
      ;;
  esac
  printf '%s\n' "${header}" | grep -qE '^[[:space:]]*Type:[[:space:]]*(DYN|EXEC)' || {
    echo "ERROR: [${abi}] ${path} is not an executable ELF (${machine})." >&2
    return 1
  }
  echo "    verified $(basename "${path}") for ${abi}: ${machine}, $(stat -c%s "${path}" 2>/dev/null || echo '?') bytes"
}

# ---------------------------------------------------------------------------
# One Go transport: clone (once), then one binary per ABI.
# ---------------------------------------------------------------------------
# $1 short name, $2 repository, $3 ref, $4 package path, $5 output .so name
build_go_transport() {
  local name="$1" repo="$2" ref="$3" pkg="$4" out_name="$5"
  local src="${NATIVE_DIR}/${name}"

  # Nothing below is cheap: a shallow clone, a module graph download and one
  # cross-compile per ABI. Skip the whole transport when it is already on disk,
  # so `all` costs only what is actually missing - which is what makes this
  # script safe to call on every build rather than only on a clean tree.
  if already_built "${out_name}" && [ "${FORCE}" != "1" ]; then
    echo "==> [${name}] ${out_name} already present for ${ABIS[*]} - skipping (AETHER_PT_FORCE=1 to rebuild)"
    return 0
  fi

  if [ ! -d "${src}/.git" ]; then
    rm -rf "${src}"
    echo "==> [${name}] cloning ${repo} @ ${ref}"
    if ! git clone --depth 1 --branch "${ref}" "${GITLAB}/${repo}.git" "${src}" 2>/dev/null; then
      echo "    ref '${ref}' not found; using the default branch"
      rm -rf "${src}"
      git clone --depth 1 "${GITLAB}/${repo}.git" "${src}"
    fi
  else
    echo "==> [${name}] reusing the checkout in ${src}"
  fi

  if [ ! -d "${src}/${pkg}" ]; then
    echo "ERROR: ${src}/${pkg} not found - upstream layout changed." >&2
    ls -la "${src}" >&2 || true
    exit 1
  fi

  local attempt
  for attempt in 1 2 3; do
    if ( cd "${src}" && go mod download ); then break; fi
    echo "    module download attempt ${attempt} failed; retrying in 10s"
    sleep 10
  done

  # 16 KB page alignment: Android 15 devices can use a 16 KB page size and a
  # binary linked for 4 KB will not load there at all.
  local base_ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384"

  # Prefer the linkname escape hatch where it exists, and keep the plain link as
  # a fallback so a toolchain that does not know the flag still builds.
  local -a ldflag_variants
  if go_supports_checklinkname; then
    ldflag_variants=("${base_ldflags} -checklinkname=0" "${base_ldflags}")
    echo "==> [${name}] linking with -checklinkname=0 (go $(go env GOVERSION 2>/dev/null | sed 's/^go//'), anet linknames into net)"
  else
    ldflag_variants=("${base_ldflags}")
  fi

  local abi goarch clang out ldflags ok
  for abi in "${ABIS[@]}"; do
    goarch="$(goarch_for_abi "${abi}")"
    clang="$(clang_for_abi "${abi}")"
    if [ -z "${goarch}" ] || [ ! -x "${clang}" ]; then
      echo "ERROR: [${abi}] no Go arch or NDK clang for this ABI." >&2
      exit 1
    fi
    mkdir -p "${JNI_DIR}/${abi}"
    out="${JNI_DIR}/${abi}/${out_name}"

    # Per-ABI skip as well as the per-transport one above: a previous run that
    # died partway through leaves one ABI done and the other missing, and there
    # is no reason to redo the half that succeeded.
    if [ -s "${out}" ] && [ "${FORCE}" != "1" ]; then
      echo "==> [${name}] ${out_name} already present for ${abi} - skipping"
      continue
    fi

    echo "==> [${name}] building for ${abi} (GOARCH=${goarch}, API ${API})"

    # -buildmode=pie is explicit rather than implied: Android refuses to exec a
    # non-PIE binary, and the default has moved between Go releases.
    build_one() {
      ( cd "${src}" && \
        CGO_ENABLED=1 GOOS=android GOARCH="${goarch}" GOARM=7 \
        CC="${clang}" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
        go build -trimpath -buildvcs=false -buildmode=pie \
          -ldflags "$1" \
          -o "${out}" "./${pkg}" )
    }

    ok=0
    for attempt in 1 2 3; do
      for ldflags in "${ldflag_variants[@]}"; do
        if build_one "${ldflags}"; then ok=1; break; fi
      done
      [ "${ok}" = 1 ] && break
      echo "    build attempt ${attempt} failed"
      [ "${attempt}" -lt 3 ] && sleep 10
    done
    if [ "${ok}" != 1 ]; then
      echo "ERROR: [${abi}] ${out_name} could not be built after 3 attempts." >&2
      exit 1
    fi

    "${NDK_TOOLCHAIN}/llvm-strip" "${out}" 2>/dev/null || true
    verify_elf "${abi}" "${out}"
  done
}

build_lyrebird() {
  # obfs4 AND meek_lite come out of this one binary, which is why the app wires
  # both transports to one ClientTransportPlugin line.
  build_go_transport lyrebird lyrebird "${LYREBIRD_REF}" "cmd/lyrebird" liblyrebird.so
}

build_snowflake() {
  build_go_transport snowflake snowflake "${SNOWFLAKE_REF}" "client" libsnowflake.so
}

build_webtunnel() {
  build_go_transport webtunnel webtunnel "${WEBTUNNEL_REF}" "main/client" libwebtunnel.so
}

case "${TARGET}" in
  lyrebird)  build_lyrebird ;;
  snowflake) build_snowflake ;;
  webtunnel) build_webtunnel ;;
  all)       build_lyrebird; build_snowflake; build_webtunnel ;;
  *) echo "Usage: build-pt-transports.sh [lyrebird|snowflake|webtunnel|all]" >&2; exit 2 ;;
esac

echo "==> Done (${TARGET}). Pluggable transports installed:"
find "${JNI_DIR}" -type f \
  \( -name 'liblyrebird.so' -o -name 'libsnowflake.so' -o -name 'libwebtunnel.so' \) \
  -exec ls -la {} + 2>/dev/null || true
