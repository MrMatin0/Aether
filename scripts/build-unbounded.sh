#!/usr/bin/env bash
#
# Builds the EXPERIMENTAL Unbounded core:
#
#   libunbounded.so <- getlantern/unbounded's `cmd` driver (Go module
#                      github.com/getlantern/broflake), cross-compiled from
#                      source with the NDK toolchain as the DESKTOP consumer in
#                      SOCKS5 mode. Pinned by commit.
#
# It lands in app/src/main/jniLibs/<abi>/ under a .so name for the same reason
# libpsiphon.so and libtor.so do: that is how Android gives us an executable
# with the exec bit set in a directory we are still allowed to exec from. It is
# NOT a library.
#
# ============================================================================
# WHY THIS IS ITS OWN SCRIPT
# ============================================================================
# Unbounded is experimental. build-overlay-cores.sh owns the cores a tagged
# release is REQUIRED to ship (see rule 7 in .github/workflows/build.yml); this
# one is additive on top of that and must never be able to cost a release, or
# Psiphon, or Tor. A build without libunbounded.so is a valid build: the app
# disables the Unbounded mode and names the missing core.
#
# ============================================================================
# WHY cgo
# ============================================================================
# A CGO_ENABLED=0 Go binary on Android resolves names by reading
# /etc/resolv.conf, which Android does not have, and falls back to a resolver
# on loopback that does not exist. Discovery (freddie) and the STUN list are
# reached by NAME, so a pure-Go build starts, logs nothing useful and never
# finds a peer. With cgo the net package uses bionic's getaddrinfo, exactly as
# the Psiphon build does.
#
# Usage:  build-unbounded.sh
#
# Requires: ANDROID_NDK_HOME, Go >= the version in upstream's go.mod (1.26 at
# the pinned commit), git.
#
# Optional: AETHER_ABIS="arm64-v8a" (space-separated) builds only those ABIs.
# Optional: UNBOUNDED_REPO / UNBOUNDED_REF pick the source. UNBOUNDED_REF may be
# a branch, a tag or a full 40-character commit SHA.
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

# Same convention as the other scripts: no full literal URL sits in the file.
GH="https://""github.com"

UNBOUNDED_REPO="${UNBOUNDED_REPO:-getlantern/unbounded}"
UNBOUNDED_REF="${UNBOUNDED_REF:-48a211aa20ca2d31ff2ca40ffd20678e12bd0118}"
UNBOUNDED_SRC="${NATIVE_DIR}/unbounded"
UNBOUNDED_STAMP="${UNBOUNDED_SRC}/.aether-source"

mkdir -p "${NATIVE_DIR}"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
  exit 1
fi
if ! command -v go >/dev/null 2>&1; then
  echo "ERROR: Go is not installed - cannot build the Unbounded core." >&2
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

is_commit_sha() {
  printf '%s' "$1" | grep -qE '^[0-9a-f]{40}$'
}

clone_unbounded() {
  local url="${GH}/${UNBOUNDED_REPO}.git"
  rm -rf "${UNBOUNDED_SRC}"
  echo "==> [unbounded] cloning ${UNBOUNDED_REPO} @ ${UNBOUNDED_REF}"
  if is_commit_sha "${UNBOUNDED_REF}"; then
    mkdir -p "${UNBOUNDED_SRC}"
    local attempt ok=0
    for attempt in 1 2 3; do
      if ( cd "${UNBOUNDED_SRC}" && \
           git init -q && \
           { git remote get-url origin >/dev/null 2>&1 || git remote add origin "${url}"; } && \
           git fetch -q --depth 1 origin "${UNBOUNDED_REF}" && \
           git -c advice.detachedHead=false checkout -q FETCH_HEAD ); then
        ok=1
        break
      fi
      echo "    fetch of ${UNBOUNDED_REF} failed (attempt ${attempt}); retrying in 10s"
      sleep 10
    done
    if [ "${ok}" != 1 ]; then
      echo "ERROR: could not fetch commit ${UNBOUNDED_REF} from ${UNBOUNDED_REPO}." >&2
      exit 1
    fi
  else
    git clone --depth 1 --branch "${UNBOUNDED_REF}" "${url}" "${UNBOUNDED_SRC}"
  fi
  printf '%s@%s\n' "${UNBOUNDED_REPO}" "${UNBOUNDED_REF}" > "${UNBOUNDED_STAMP}"
  echo "    checked out $(cd "${UNBOUNDED_SRC}" && git rev-parse HEAD 2>/dev/null || echo '?')"
}

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
  machine="$(printf '%s\n' "${header}" \
    | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' | head -n1)"
  case "${abi}" in
    arm64-v8a)   printf '%s' "${machine}" | grep -q 'AArch64' ;;
    armeabi-v7a) printf '%s' "${machine}" | grep -qi 'ARM' ;;
  esac || { echo "ERROR: [${abi}] ${path} is built for '${machine}'." >&2; return 1; }
  printf '%s\n' "${header}" | grep -qE '^[[:space:]]*Type:[[:space:]]*(DYN|EXEC)' \
    || { echo "ERROR: [${abi}] ${path} is not an executable ELF." >&2; return 1; }
  echo "    verified $(basename "${path}") for ${abi}: ${machine}, $(stat -c%s "${path}" 2>/dev/null || echo '?') bytes"
}

# Reuse a cached checkout only if it was made from the SAME repo@ref.
want_stamp="${UNBOUNDED_REPO}@${UNBOUNDED_REF}"
have_stamp=""
[ -f "${UNBOUNDED_STAMP}" ] && have_stamp="$(head -n1 "${UNBOUNDED_STAMP}")"
if [ -d "${UNBOUNDED_SRC}/.git" ] && [ "${have_stamp}" = "${want_stamp}" ]; then
  echo "==> [unbounded] reusing the checkout in ${UNBOUNDED_SRC} (${want_stamp})"
else
  clone_unbounded
fi

if [ ! -f "${UNBOUNDED_SRC}/cmd/client_default_impl.go" ]; then
  echo "ERROR: ${UNBOUNDED_SRC}/cmd/client_default_impl.go not found - upstream layout changed." >&2
  exit 1
fi

echo "==> [unbounded] go $(go env GOVERSION 2>/dev/null | sed 's/^go//') (GOTOOLCHAIN=${GOTOOLCHAIN:-auto})"

for attempt in 1 2 3; do
  if ( cd "${UNBOUNDED_SRC}" && go mod download ); then
    break
  fi
  echo "    module download attempt ${attempt} failed; retrying in 10s"
  sleep 10
done

for abi in "${ABIS[@]}"; do
  goarch="$(goarch_for_abi "${abi}")"
  clang="$(clang_for_abi "${abi}")"
  if [ -z "${goarch}" ] || [ ! -x "${clang}" ]; then
    echo "ERROR: [${abi}] no Go arch or NDK clang for this ABI." >&2
    exit 1
  fi
  mkdir -p "${JNI_DIR}/${abi}"
  out="${JNI_DIR}/${abi}/libunbounded.so"
  echo "==> [unbounded] building for ${abi} (GOARCH=${goarch}, API ${API})"

  # desktop + socks5 is what makes this binary a consumer with a local SOCKS5
  # listener instead of a volunteer widget. -race is upstream's dev default and
  # is deliberately NOT used here. 16 KB page alignment for Android 15.
  ldflags="-s -w -X main.clientType=desktop -X main.proxyMode=socks5 -extldflags=-Wl,-z,max-page-size=16384"

  build_one() {
    ( cd "${UNBOUNDED_SRC}" && \
      CGO_ENABLED=1 GOOS=android GOARCH="${goarch}" GOARM=7 \
      CC="${clang}" \
      CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
      go build -trimpath -buildvcs=false \
        -ldflags "$1" \
        -o "${out}" ./cmd )
  }

  # pion's Android interface enumeration (wlynxg/anet) uses //go:linkname into
  # the net package, which Go 1.23+ rejects unless -checklinkname=0 is given.
  # Both forms are tried on every attempt, same as the Psiphon build.
  ok=0
  for attempt in 1 2 3; do
    if build_one "${ldflags}"; then ok=1; break; fi
    echo "    plain build failed (attempt ${attempt}); retrying with -checklinkname=0"
    if build_one "${ldflags} -checklinkname=0"; then ok=1; break; fi
    [ "${attempt}" -lt 3 ] && sleep 10
  done
  if [ "${ok}" != 1 ]; then
    echo "ERROR: [${abi}] libunbounded.so could not be built after 3 attempts." >&2
    exit 1
  fi

  "${NDK_TOOLCHAIN}/llvm-strip" "${out}" 2>/dev/null || true
  verify_core_elf "${abi}" "${out}"
done

echo "==> Done. Unbounded core installed:"
find "${JNI_DIR}" -type f -name 'libunbounded.so' -exec ls -la {} + 2>/dev/null || true
