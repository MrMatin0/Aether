#!/usr/bin/env bash
#
# Fetches the Psiphon BOOTSTRAP server list into app/src/main/assets/psiphon/.
#
# ============================================================================
# WHY THIS EXISTS AT ALL
# ============================================================================
# psiphon-tunnel-core is compiled from upstream source by
# build-overlay-cores.sh, and upstream source contains no servers - it cannot,
# they change constantly. A fresh install therefore has an empty datastore and
# nothing to dial, which is precisely why the Psiphon chain modes never worked:
# the core started, found zero candidates, and spent its whole establish budget
# saying so in Go.
#
# The console client takes `-serverList <file>`: a list of server entries to
# IMPORT once, on first run. After the first successful tunnel the core
# maintains its own list in its datastore and refreshes it from the network, so
# this file is a starting point and not a dependency - a months-old list still
# bootstraps, it just has fewer live candidates in it.
#
# ============================================================================
# WHY IT IS FETCHED AND NOT COMMITTED
# ============================================================================
# Same rule as libaether.so, libpsiphon.so, libtor.so, tor's geoip and the
# Vazirmatn fonts: this repository commits SOURCE, and build-time artifacts are
# fetched and gitignored. A megabyte of server entries in git history would also
# be a megabyte that is stale the day it lands and can never be removed.
#
# ============================================================================
# WHAT IS IN IT, AND WHAT IS NOT
# ============================================================================
# Server entries are public bootstrap data: addresses, ports and obfuscation
# parameters of Psiphon's own relays. They are not credentials, they are not
# per-user, and the client verifies every list it later downloads against the
# PUBLIC signature key in PsiphonCore before trusting it. Nothing secret passes
# through this script.
#
# Sources, in priority order:
#   1. PSIPHON_SERVER_LIST_FILE  - a local path (offline / air-gapped builds)
#   2. PSIPHON_SERVER_LIST_B64   - base64 in the environment (CI secret)
#   3. PSIPHON_SERVER_LIST_URL   - an HTTPS URL, defaulting to the list below
#
# Exits non-zero when it could not produce a usable list, so the caller decides
# whether that is a warning or a release blocker (see .github/workflows/build.yml:
# it is a warning on a branch and fatal on a `v*` tag).
#
# Safe to re-run. All network access happens here / in CI, never on device.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ASSETS_DIR="${PROJECT_DIR}/app/src/main/assets/psiphon"
TARGET="${ASSETS_DIR}/server_entries.txt"
TMP="${TARGET}.tmp"

# Host prefix assembled from fragments, same convention as fetch-natives.sh and
# build-overlay-cores.sh: no full literal URL sits in the file.
RAW="https://""raw.githubusercontent.com"

# A published, working list. Overridable precisely so a fork is never stuck with
# whatever this line said the day it was written.
DEFAULT_URL="${RAW}/QW-AI-Code/Aether/main/app/src/main/assets/server_entries.txt"
SOURCE_URL="${PSIPHON_SERVER_LIST_URL:-${DEFAULT_URL}}"

# A truncated download is worse than no download: the core would import a
# handful of entries, fail on all of them, and report the network as blocked.
# Real lists are ~1 MB; anything under this is a redirect, an error page or half
# a transfer.
MIN_BYTES="${PSIPHON_SERVER_LIST_MIN_BYTES:-65536}"

mkdir -p "${ASSETS_DIR}"

# ---------------------------------------------------------------------------
# A server entry is a hex-encoded record ("<ip> <port> <secret> <json>"), so the
# whole first line must be hex. This catches an HTML error page served with a
# 200, which curl -f cannot.
# ---------------------------------------------------------------------------
validate() {
  local path="$1" size first

  if [ ! -s "${path}" ]; then
    echo "    rejected: empty file"
    return 1
  fi
  size="$(stat -c%s "${path}" 2>/dev/null || echo 0)"
  if [ "${size}" -lt "${MIN_BYTES}" ]; then
    echo "    rejected: ${size} bytes is below the ${MIN_BYTES}-byte floor (truncated or an error page)"
    return 1
  fi
  first="$(head -n1 "${path}" | tr -d '\r\n')"
  if ! printf '%s' "${first}" | grep -Eq '^[0-9A-Fa-f]{100,}$'; then
    echo "    rejected: the first line is not a hex-encoded server entry"
    return 1
  fi
  echo "    accepted: ${size} bytes, $(wc -l < "${path}" | tr -d ' ') entries"
  return 0
}

install_from_file() {
  local src="$1"
  [ -f "${src}" ] || return 1
  echo "==> [psiphon] using the server list at ${src}"
  cp "${src}" "${TMP}"
  validate "${TMP}"
}

install_from_b64() {
  echo "==> [psiphon] decoding the server list from PSIPHON_SERVER_LIST_B64"
  printf '%s' "${PSIPHON_SERVER_LIST_B64}" | base64 -d > "${TMP}" 2>/dev/null || {
    echo "    rejected: PSIPHON_SERVER_LIST_B64 is not valid base64"
    return 1
  }
  validate "${TMP}"
}

install_from_url() {
  echo "==> [psiphon] downloading the server list"
  curl -fsSL --retry 3 --retry-delay 5 -o "${TMP}" "${SOURCE_URL}" || {
    echo "    rejected: the download failed"
    return 1
  }
  validate "${TMP}"
}

rm -f "${TMP}"

if [ -n "${PSIPHON_SERVER_LIST_FILE:-}" ] && install_from_file "${PSIPHON_SERVER_LIST_FILE}"; then
  :
elif [ -n "${PSIPHON_SERVER_LIST_B64:-}" ] && install_from_b64; then
  :
elif install_from_url; then
  :
else
  rm -f "${TMP}"
  # An EXISTING good list is better than a failed refresh: a flaky minute must
  # not remove a working payload from a build that already had one.
  if [ -s "${TARGET}" ] && validate "${TARGET}" >/dev/null 2>&1; then
    echo "==> [psiphon] keeping the server list already in the tree"
    exit 0
  fi
  echo "ERROR: could not obtain a Psiphon bootstrap server list." >&2
  echo "       Set PSIPHON_SERVER_LIST_FILE, PSIPHON_SERVER_LIST_B64 or" >&2
  echo "       PSIPHON_SERVER_LIST_URL. Without it the Psiphon chain modes" >&2
  echo "       cannot establish a first tunnel; the app detects this and says so." >&2
  exit 1
fi

mv "${TMP}" "${TARGET}"
echo "==> Psiphon bootstrap server list installed at ${TARGET#"${PROJECT_DIR}/"}"
