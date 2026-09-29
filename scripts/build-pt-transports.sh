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
# WHERE THE SOURCE COMES FROM
# ============================================================================
# 1. A git clone of the pinned tag from gitlab.torproject.org, using the
#    canonical `.git` clone URL first and the bare project URL second, each
#    retried. gitlab.torproject.org has started answering some clones from CI
#    runners with a plain HTTP 400 (seen on GitHub Actions for the bare URL),
#    and the old code hid git's stderr and then reported that as "ref not
#    found", which sent the debugging in the wrong direction.
# 2. The Go module proxy (proxy.golang.org, or AETHER_GOPROXY). All three are
#    Go modules, so the proxy serves the exact source of the pinned tag, and
#    the go command checks it against the public checksum database
#    (sum.golang.org) before handing it over - a stronger integrity guarantee
#    than an HTTPS clone, and it does not touch gitlab.torproject.org at all.
# 3. Only if upstream is REACHABLE and the pinned ref genuinely does not exist
#    there: the default branch, loudly. An unreachable upstream is a hard error,
#    never a silent switch to "latest".
#
# Usage:  build-pt-transports.sh [lyrebird|snowflake|webtunnel|all]   (default: all)
#
# Requires: ANDROID_NDK_HOME, Go 1.23+, git. Every network access happens here
# or in CI, never on device.
#
# Optional: AETHER_ABIS="arm64-v8a" (space-separated) builds only those ABIs.
# CI uses it to compile each ABI on its own runner, in parallel.
# Optional: AETHER_GOPROXY overrides the module proxy used by fallback 2.
set -euo pipefail

TARGET="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

API="${ANDROID_API:-26}"
# Both ABIs by default; AETHER_ABIS narrows it (see the header).
read -r -a ABIS <<< "${AETHER_ABIS:-arm64-v8a armeabi-v7a}"
for _abi in "${ABIS[@]}"; do
  case "${_abi}" in
    arm64-v8a|armeabi-v7a) ;;
    *) echo "ERROR: unsupported ABI '${_abi}' in AETHER_ABIS (use arm64-v8a and/or armeabi-v7a)." >&2; exit 2 ;;
  esac
done
unset _abi

# Host prefixes assembled from fragments, same convention as the other scripts:
# no full literal URL sits in the file.
PT_PATH="gitlab.torproject.org/tpo/anti-censorship/pluggable-transports"
GITLAB="https://""${PT_PATH}"
# The Go module paths are the same host/path, without the scheme.
GOMOD_BASE="${PT_PATH}"

# Pinned, and overridable. An unpinned circumvention binary means the thing that
# talks to a censor is whatever was tagged that morning.
#
# EVERY REF HERE MUST BE A TAG THAT ACTUALLY EXISTS UPSTREAM. A missing ref only
# falls back to the default branch when upstream is reachable and confirms the
# tag is absent (see WHERE THE SOURCE COMES FROM). (webtunnel was pinned to a
# v0.0.9 that was never tagged, and was being built from main as a result.)
#
#   lyrebird-0.8.1 : utls security fix (0.6.2), webtunnel hardening (0.7.0),
#                    multiple meek url/front pairs (0.8.0), chrome120 fix.
#   v2.14.1        : pion security fix, covert-dtls, WebRTC offer/answer
#                    validation (issue 40546). Needs Go >= 1.23.
#   v0.0.4         : newest webtunnel tag.
LYREBIRD_REF="${LYREBIRD_REF:-lyrebird-0.8.1}"
SNOWFLAKE_REF="${SNOWFLAKE_REF:-v2.14.1}"
WEBTUNNEL_REF="${WEBTUNNEL_REF:-v0.0.4}"

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
# Source fetching (see WHERE THE SOURCE COMES FROM in the header).
# ---------------------------------------------------------------------------
# Marker written into a source tree that came from the module proxy: there is
# no .git in it, but it is just as reusable as a clone.
SOURCE_MARKER=".aether-pt-source"

# $1 url, $2 ref, $3 dest. Shallow clone of one tag/branch, retried. git's own
# error output is kept: it is the only thing that tells a missing tag apart from
# a server that refused the request.
git_clone_ref() {
  local url="$1" ref="$2" dest="$3" attempt
  for attempt in 1 2 3; do
    rm -rf "${dest}"
    if git -c advice.detachedHead=false clone --quiet --depth 1 --branch "${ref}" \
         "${url}" "${dest}"; then
      return 0
    fi
    echo "    git clone attempt ${attempt} (${url} @ ${ref}) failed"
    [ "${attempt}" -lt 3 ] && sleep 5
  done
  rm -rf "${dest}"
  return 1
}

# $1 go module path, $2 ref (tag), $3 dest. Pulls the module source for that
# ref out of the Go module proxy, checksum-verified by the go command against
# sum.golang.org, and copies it to dest (the module cache is read-only).
fetch_from_module_proxy() {
  local module="$1" ref="$2" dest="$3" tmp out dir version
  tmp="$(mktemp -d)"
  # Run outside any module so no go.mod around us can influence resolution.
  if ! out="$(cd "${tmp}" && \
      env GO111MODULE=on GOFLAGS= \
        ${AETHER_GOPROXY:+"GOPROXY=${AETHER_GOPROXY}"} \
        go mod download -json "${module}@${ref}" 2>&1)"; then
    echo "    module proxy could not serve ${module}@${ref}:" >&2
    printf '%s\n' "${out}" | sed 's/^/      /' >&2
    rm -rf "${tmp}"
    return 1
  fi
  rm -rf "${tmp}"

  dir="$(printf '%s\n' "${out}" | sed -n 's/^[[:space:]]*"Dir":[[:space:]]*"\(.*\)",\{0,1\}[[:space:]]*$/\1/p' | head -n1)"
  version="$(printf '%s\n' "${out}" | sed -n 's/^[[:space:]]*"Version":[[:space:]]*"\(.*\)",\{0,1\}[[:space:]]*$/\1/p' | head -n1)"
  if [ -z "${dir}" ] || [ ! -f "${dir}/go.mod" ]; then
    echo "    module proxy returned no usable source directory for ${module}@${ref}" >&2
    printf '%s\n' "${out}" | sed 's/^/      /' >&2
    return 1
  fi

  rm -rf "${dest}"
  mkdir -p "${dest}"
  cp -R "${dir}/." "${dest}/"
  chmod -R u+w "${dest}"
  printf '%s@%s (ref %s, via module proxy)\n' "${module}" "${version:-?}" "${ref}" \
    > "${dest}/${SOURCE_MARKER}"
  echo "    fetched ${module}@${version:-${ref}} from the Go module proxy (checksum-verified)"
}

# $1 url, $2 ref. Exit 0: reachable and the ref exists. 1: reachable, ref absent.
# 2: upstream unreachable / refused the request.
upstream_ref_state() {
  local url="$1" ref="$2" out
  if ! out="$(git ls-remote "${url}" "refs/tags/${ref}" "refs/heads/${ref}" 2>/dev/null)"; then
    return 2
  fi
  [ -n "${out}" ] && return 0
  return 1
}

# $1 short name, $2 repository, $3 ref, $4 go module path, $5 dest
fetch_source() {
  local name="$1" repo="$2" ref="$3" module="$4" src="$5"
  local url state

  echo "==> [${name}] fetching ${repo} @ ${ref}"

  # 1) git, canonical .git URL first.
  for url in "${GITLAB}/${repo}.git" "${GITLAB}/${repo}"; do
    if git_clone_ref "${url}" "${ref}" "${src}"; then
      echo "    cloned ${url} @ ${ref}"
      return 0
    fi
  done

  # 2) Go module proxy, same pinned ref.
  echo "    git could not fetch ${repo} @ ${ref}; trying the Go module proxy"
  if fetch_from_module_proxy "${module}" "${ref}" "${src}"; then
    return 0
  fi

  # 3) Default branch ONLY if upstream answers and says the pin does not exist.
  state=0
  upstream_ref_state "${GITLAB}/${repo}.git" "${ref}" || state=$?
  if [ "${state}" = 1 ]; then
    echo "    WARNING: ref '${ref}' does not exist upstream; building the DEFAULT BRANCH of ${repo}." >&2
    echo "             Fix the pin ($(printf '%s' "${name}" | tr '[:lower:]' '[:upper:]')_REF) - this is no longer a pinned build." >&2
    rm -rf "${src}"
    if git clone --quiet --depth 1 "${GITLAB}/${repo}.git" "${src}"; then
      return 0
    fi
  fi

  rm -rf "${src}"
  echo "ERROR: [${name}] could not fetch ${repo} @ ${ref} from git or the Go module proxy." >&2
  echo "       gitlab.torproject.org refused or could not be reached, and ${module}@${ref}" >&2
  echo "       was not available from the module proxy either. Set AETHER_GOPROXY to a" >&2
  echo "       reachable proxy, or place a checkout at ${src} and rerun." >&2
  return 1
}

# ---------------------------------------------------------------------------
# One Go transport: fetch (once), then one binary per ABI.
# ---------------------------------------------------------------------------
# $1 short name, $2 repository, $3 ref, $4 package path, $5 output .so name,
# $6 go module path
build_go_transport() {
  local name="$1" repo="$2" ref="$3" pkg="$4" out_name="$5" module="$6"
  local src="${NATIVE_DIR}/${name}"

  if [ -d "${src}/.git" ] || [ -f "${src}/${SOURCE_MARKER}" ]; then
    echo "==> [${name}] reusing the checkout in ${src}"
  else
    rm -rf "${src}"
    fetch_source "${name}" "${repo}" "${ref}" "${module}" "${src}" || exit 1
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
  build_go_transport lyrebird lyrebird "${LYREBIRD_REF}" "cmd/lyrebird" liblyrebird.so \
    "${GOMOD_BASE}/lyrebird"
}

build_snowflake() {
  # snowflake is a v2 module, so its module path carries the /v2 suffix.
  build_go_transport snowflake snowflake "${SNOWFLAKE_REF}" "client" libsnowflake.so \
    "${GOMOD_BASE}/snowflake/v2"
}

build_webtunnel() {
  build_go_transport webtunnel webtunnel "${WEBTUNNEL_REF}" "main/client" libwebtunnel.so \
    "${GOMOD_BASE}/webtunnel"
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
