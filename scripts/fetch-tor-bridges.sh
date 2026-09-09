#!/usr/bin/env bash
#
# Refreshes the BUILT-IN Tor bridge list into app/src/main/assets/tor/bridges.json.
#
# ============================================================================
# WHY THIS EXISTS
# ============================================================================
# Built-in bridges are the public addresses Tor Browser ships: the ones a user
# can try with no network access and no captcha. They change - bridges get
# blocked, replaced and re-hosted - and a list from the day the code was written
# is a list of addresses a censor has had years to find.
#
# So the app carries a compiled-in copy (core/BridgeCatalog.kt) as a floor, this
# script refreshes it at build time, and the app can refresh it again at runtime
# from the same endpoint. All three go through one parser and later sources win
# only when they parse.
#
# ============================================================================
# WHY IT IS NOT FATAL
# ============================================================================
# The compiled-in list keeps working, and a build that could not reach
# bridges.torproject.org is a build made on a filtered network - which is where
# this app is developed. A failure here is a warning, never a broken build. Same
# treatment as the fonts and the geoip database.
#
# ============================================================================
# WHAT IS IN IT
# ============================================================================
# Public data, served unauthenticated by the Tor Project's own moat distributor
# (see doc/moat.md in tpo/anti-censorship/rdsys). No credentials, nothing
# per-user: exactly the same bridge lines Tor Browser has in its preferences.
# The PERSONAL bridges this app can also request are never written to a file in
# the repository - they live in the app's own store on the device.
#
# Sources, in priority order:
#   1. TOR_BRIDGES_FILE  - a local path (offline / air-gapped builds)
#   2. TOR_BRIDGES_B64   - base64 in the environment
#   3. TOR_BRIDGES_URL   - an HTTPS URL, defaulting to moat
#
# Safe to re-run. All network access happens here / in CI, never on device.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ASSETS_DIR="${PROJECT_DIR}/app/src/main/assets/tor"
TARGET="${ASSETS_DIR}/bridges.json"
TMP="${TARGET}.tmp"

# Host assembled from fragments, same convention as the other fetch scripts.
MOAT="https://""bridges.torproject.org/moat/circumvention/builtin"
SOURCE_URL="${TOR_BRIDGES_URL:-${MOAT}}"

mkdir -p "${ASSETS_DIR}"

# ---------------------------------------------------------------------------
# A real answer is a JSON object whose values are arrays of bridge lines. This
# catches the two things curl -f cannot: an HTML block page served with a 200,
# and a valid-but-empty document.
# ---------------------------------------------------------------------------
validate() {
  local path="$1" size

  if [ ! -s "${path}" ]; then
    echo "    rejected: empty file"
    return 1
  fi
  size="$(stat -c%s "${path}" 2>/dev/null || echo 0)"
  if [ "${size}" -lt 200 ]; then
    echo "    rejected: ${size} bytes is too small to be a bridge list"
    return 1
  fi
  if ! python3 - "${path}" <<'PY'
import json, sys

with open(sys.argv[1], "rb") as handle:
    data = json.load(handle)
if not isinstance(data, dict) or not data:
    raise SystemExit("not a transport map")
# At least one transport has to carry at least one plausible bridge line, or the
# asset would replace a working compiled-in list with nothing.
for transport, lines in data.items():
    if isinstance(lines, list) and any(
        isinstance(line, str) and ":" in line for line in lines
    ):
        print("    accepted: %d transports, %s" % (len(data), ", ".join(sorted(data))))
        raise SystemExit(0)
raise SystemExit("no bridge lines in any transport")
PY
  then
    echo "    rejected: not a usable moat built-in list"
    return 1
  fi
  return 0
}

rm -f "${TMP}"

if [ -n "${TOR_BRIDGES_FILE:-}" ] && [ -f "${TOR_BRIDGES_FILE}" ]; then
  echo "==> [tor] using the bridge list at ${TOR_BRIDGES_FILE}"
  cp "${TOR_BRIDGES_FILE}" "${TMP}"
elif [ -n "${TOR_BRIDGES_B64:-}" ]; then
  echo "==> [tor] decoding the bridge list from TOR_BRIDGES_B64"
  printf '%s' "${TOR_BRIDGES_B64}" | base64 -d > "${TMP}" 2>/dev/null || true
else
  echo "==> [tor] downloading the built-in bridge list"
  curl -fsSL --retry 3 --retry-delay 5 -o "${TMP}" "${SOURCE_URL}" || true
fi

if [ -f "${TMP}" ] && validate "${TMP}"; then
  mv "${TMP}" "${TARGET}"
  echo "==> Built-in bridge list installed at ${TARGET#"${PROJECT_DIR}/"}"
  exit 0
fi

rm -f "${TMP}"

# An EXISTING good list beats a failed refresh: a flaky minute must not remove a
# working payload from a build that already had one.
if [ -s "${TARGET}" ] && validate "${TARGET}" >/dev/null 2>&1; then
  echo "==> [tor] keeping the bridge list already in the tree"
  exit 0
fi

echo "NOTE: could not obtain a fresh built-in bridge list." >&2
echo "      The app will use the list compiled into core/BridgeCatalog.kt, which" >&2
echo "      works but is only as fresh as the source. Set TOR_BRIDGES_URL," >&2
echo "      TOR_BRIDGES_FILE or TOR_BRIDGES_B64 to supply one." >&2
exit 1
