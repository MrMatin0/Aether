#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# EXPERIMENTAL: builds the Proteus core (libproteus.so) from pinned source.
#
#   https://github.com/unblockable/proteus
#

# Proteus is a Rust program, not a library. Like tor and Psiphon it is shipped
# as an executable under a .so name in jniLibs/<abi>/, so Android extracts it to
# nativeLibraryDir with the exec bit set, and the app runs
#
#   libproteus.so client <psf> --connect <ip:port> --listen 127.0.0.1:1829 ...
#
# See docs/PROTEUS.md. OPTIONAL: a build without it is a valid build; the
# Chain page disables the Proteus modes and names the missing core.
#
# Inputs (all optional):
#   PROTEUS_REPO            git URL           (default: upstream)
#   PROTEUS_REF             full commit SHA   (default: pinned below)
#   PROTEUS_RUST_TOOLCHAIN  rustup toolchain  (default: upstream's rust-toolchain.toml)
#   AETHER_ABIS             "arm64-v8a armeabi-v7a" (default: both)
#   ANDROID_API             min API level     (default: 26, = minSdk)
# Required: ANDROID_NDK_HOME, cargo, cargo-ndk.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTEUS_REPO="${PROTEUS_REPO:-https://github.com/unblockable/proteus.git}"
PROTEUS_REF="${PROTEUS_REF:-75ba9f4006e3e38d352774105864fe9444b65c55}"
ANDROID_API="${ANDROID_API:-26}"
ABIS="${AETHER_ABIS:-arm64-v8a armeabi-v7a}"
SRC="${ROOT}/.native/proteus"
JNI="${ROOT}/app/src/main/jniLibs"

log() { printf '[proteus] %s\n' "$*"; }
die() { printf '[proteus] ERROR: %s\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------ preconditions
command -v git >/dev/null 2>&1 || die "git not found"
command -v cargo >/dev/null 2>&1 || die "cargo not found - install Rust (rustup)"
cargo ndk --version >/dev/null 2>&1 || die "cargo-ndk not found - cargo install cargo-ndk"
[ -n "${ANDROID_NDK_HOME:-}" ] || die "ANDROID_NDK_HOME is not set"
[ -d "${ANDROID_NDK_HOME}" ] || die "ANDROID_NDK_HOME does not exist: ${ANDROID_NDK_HOME}"

LLVM_BIN=""
for d in "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin; do
  if [ -x "${d}/clang" ]; then LLVM_BIN="${d}"; break; fi
done
[ -n "${LLVM_BIN}" ] || die "no LLVM toolchain under ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt"

for abi in ${ABIS}; do
  case "${abi}" in
    arm64-v8a|armeabi-v7a) ;;
    *) die "unsupported ABI '${abi}' (supported: arm64-v8a armeabi-v7a)" ;;
  esac
done

triple_for() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
  esac
}

machine_for() {
  case "$1" in
    arm64-v8a) echo AArch64 ;;
    armeabi-v7a) echo ARM ;;
  esac
}

# ------------------------------------------------------------------- source
# Fetched by SHA, never by a moving branch: the binary in the APK has to be
# the output of a commit someone can point at.
fetch_source() {
  if [ -d "${SRC}/.git" ] \
    && [ "$(git -C "${SRC}" rev-parse HEAD 2>/dev/null || true)" = "${PROTEUS_REF}" ]; then
    log "source already at ${PROTEUS_REF}"
    return 0
  fi
  rm -rf "${SRC}"
  mkdir -p "${SRC}"
  git -C "${SRC}" init -q
  git -C "${SRC}" remote add origin "${PROTEUS_REPO}"
  local n
  for n in 1 2 3; do
    if git -C "${SRC}" fetch -q --depth 1 origin "${PROTEUS_REF}"; then
      git -C "${SRC}" -c advice.detachedHead=false checkout -q FETCH_HEAD
      log "fetched ${PROTEUS_REPO} @ $(git -C "${SRC}" rev-parse HEAD)"
      return 0
    fi
    log "fetch attempt ${n} failed; retrying"
    sleep $((n * 5))
  done
  die "could not fetch ${PROTEUS_REPO} @ ${PROTEUS_REF}"
}

# ---------------------------------------------------------------- toolchain
# Upstream pins its compiler in rust-toolchain.toml (and uses edition 2024).
# Honour that pin explicitly instead of relying on rustup auto-install, and add
# the Android targets to THAT toolchain, not to whatever stable is active.
setup_toolchain() {
  local channel="${PROTEUS_RUST_TOOLCHAIN:-}"
  if [ -z "${channel}" ] && [ -f "${SRC}/rust-toolchain.toml" ]; then
    channel="$(sed -n 's/^[[:space:]]*channel[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
      "${SRC}/rust-toolchain.toml" | head -n1)"
  fi
  if ! command -v rustup >/dev/null 2>&1; then
    log "rustup not found; using the active toolchain ($(rustc --version 2>/dev/null || echo unknown))"
    return 0
  fi
  [ -n "${channel}" ] || channel="stable"
  log "Rust toolchain: ${channel}"
  rustup toolchain install "${channel}" --profile minimal --no-self-update
  local targets=()
  for abi in ${ABIS}; do targets+=("$(triple_for "${abi}")"); done
  rustup target add --toolchain "${channel}" "${targets[@]}"
  export RUSTUP_TOOLCHAIN="${channel}"
  log "using $(rustc --version)"
}

# -------------------------------------------------------------------- build
verify_elf() {
  local abi="$1" file="$2" header machine
  header="$("${LLVM_BIN}/llvm-readelf" -h "${file}")" || die "llvm-readelf could not read ${file}"
  machine="$(machine_for "${abi}")"
  printf '%s\n' "${header}" | grep -Eq "Machine:[[:space:]]+${machine}[[:space:]]*$" \
    || die "${file} is not an ${machine} binary"
  printf '%s\n' "${header}" | grep -Eq "Type:[[:space:]]+(DYN|EXEC)" \
    || die "${file} is not an executable ELF"
}

build_abi() {
  local abi="$1" triple bin out
  triple="$(triple_for "${abi}")"
  log "building ${abi} (${triple}, API ${ANDROID_API})"
  if ! (cd "${SRC}" && ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" CARGO_TARGET_DIR="${SRC}/target" \
      cargo ndk -t "${abi}" --platform "${ANDROID_API}" build --release --locked --bin proteus); then
    log "--locked build failed for ${abi}; retrying once without --locked"
    (cd "${SRC}" && ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" CARGO_TARGET_DIR="${SRC}/target" \
      cargo ndk -t "${abi}" --platform "${ANDROID_API}" build --release --bin proteus)
  fi
  bin="${SRC}/target/${triple}/release/proteus"
  [ -f "${bin}" ] || die "cargo finished but ${bin} does not exist"
  mkdir -p "${JNI}/${abi}"
  out="${JNI}/${abi}/libproteus.so"
  cp "${bin}" "${out}"
  "${LLVM_BIN}/llvm-strip" --strip-unneeded "${out}" \
    || log "llvm-strip failed for ${abi}; keeping the unstripped binary"
  chmod 755 "${out}"
  verify_elf "${abi}" "${out}"
  log "OK ${out} ($(wc -c < "${out}" | tr -d ' ') bytes)"
}

fetch_source
setup_toolchain
for abi in ${ABIS}; do
  build_abi "${abi}"
done
log "done: libproteus.so for ${ABIS}"
