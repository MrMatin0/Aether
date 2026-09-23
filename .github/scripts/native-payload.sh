#!/usr/bin/env bash
#
# Prints the ABI x library matrix of a jniLibs tree to the job summary and
# enforces the payload rules of .github/workflows/build.yml:
#
#   * a MANDATORY core missing  -> always fatal. Proxy mode would still work,
#     full VPN mode would not, and nobody would find out before a user did.
#   * a CHAIN core or a pluggable TRANSPORT missing -> a warning on a branch or
#     a pull request (the app detects the gap and names it in Settings), and
#     FATAL on a `v*` tag: a release may not advertise what it does not ship.
#
# Usage: native-payload.sh [jniLibs-dir]      (default: app/src/main/jniLibs)
# Env:   IS_RELEASE_TAG=true|false, GITHUB_STEP_SUMMARY (optional)
set -euo pipefail

JNI="${1:-app/src/main/jniLibs}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
IS_RELEASE_TAG="${IS_RELEASE_TAG:-false}"

ABIS=(arm64-v8a armeabi-v7a)
MANDATORY=(libaether.so libhev-socks5-tunnel.so libaethertun.so)
CHAIN=(libpsiphon.so libtor.so)
TRANSPORTS=(liblyrebird.so libsnowflake.so libwebtunnel.so)

mandatory_missing=0
optional_missing=0
total_bytes=0

human() {
  numfmt --to=iec-i --suffix=B --format='%.1f' "$1" 2>/dev/null || printf '%s B' "$1"
}

# row <group label> <library> <mandatory|optional>
row() {
  local group="$1" lib="$2" kind="$3" line abi file size
  line="| ${group} | \`${lib}\` |"
  for abi in "${ABIS[@]}"; do
    file="${JNI}/${abi}/${lib}"
    if [ -s "${file}" ]; then
      size="$(stat -c%s "${file}")"
      total_bytes=$((total_bytes + size))
      line+=" ✅ $(human "${size}") |"
    elif [ "${kind}" = mandatory ]; then
      mandatory_missing=$((mandatory_missing + 1))
      line+=" ❌ **missing** |"
      echo "::error title=Native payload::missing mandatory core ${abi}/${lib}"
    else
      optional_missing=$((optional_missing + 1))
      line+=" ⚠️ missing |"
      echo "::warning title=Native payload::${abi}/${lib} is missing - the features that need it are unavailable in this build"
    fi
  done
  echo "${line}" >> "${SUMMARY}"
}

{
  echo "### 🧩 Native payload"
  echo
  echo "| Group | Library | arm64-v8a | armeabi-v7a |"
  echo "|---|---|---:|---:|"
} >> "${SUMMARY}"
for lib in "${MANDATORY[@]}";  do row "🔒 Core"    "${lib}" mandatory; done
for lib in "${CHAIN[@]}";      do row "⛓️ Chain"   "${lib}" optional;  done
for lib in "${TRANSPORTS[@]}"; do row "🌉 Bridges" "${lib}" optional;  done
{
  echo
  echo "Native payload: **$(human "${total_bytes}")** across ${#ABIS[@]} ABIs."
  echo
} >> "${SUMMARY}"

if [ "${mandatory_missing}" -gt 0 ]; then
  echo "::error title=Native payload::${mandatory_missing} mandatory core(s) missing - full VPN mode would not work"
  exit 1
fi
if [ "${optional_missing}" -gt 0 ]; then
  if [ "${IS_RELEASE_TAG}" = "true" ]; then
    echo "::error title=Release gate::a tagged release must ship libpsiphon.so, libtor.so and all three pluggable transports for every ABI"
    exit 1
  fi
  echo "Payload usable, ${optional_missing} optional binaries missing (see warnings)."
else
  echo "Native payload complete for ${ABIS[*]}."
fi
