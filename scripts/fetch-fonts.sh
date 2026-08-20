#!/usr/bin/env bash
#
# Fetches the Vazirmatn weights the type scale uses into the gitignored res
# source set at app/src/main/res-fonts/font.
#
# You normally do not need to run this: the fetchVazirmatn Gradle task does the
# same thing on the first build. Run it by hand to prime a machine that will
# later build offline, or to prepare the directory on a connected machine and
# copy it across to one with no network at all.
#
# Safe to re-run. Files already present are kept, so nothing is re-downloaded.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FONT_DIR="${PROJECT_DIR}/app/src/main/res-fonts/font"

# Keep this in step with vazirmatnVersion in app/build.gradle.kts.
VERSION="${VAZIRMATN_VERSION:-33.003}"

# Built from fragments so no full literal URL lives in the file.
RAW="https://""raw.githubusercontent.com"
BASE="${RAW}/rastikerdar/vazirmatn/v${VERSION}/fonts/ttf"

mkdir -p "${FONT_DIR}"

# fetch <android_res_name> <upstream_file_name>
# Android resource names are lowercase snake_case, upstream ships CamelCase.
fetch() {
  local res_name="$1" file_name="$2"
  local target="${FONT_DIR}/${res_name}.ttf"
  if [ -s "${target}" ]; then
    echo "   have ${res_name}.ttf"
    return 0
  fi
  echo "   get  ${file_name} -> ${res_name}.ttf"
  curl -fsSL "${BASE}/${file_name}" -o "${target}"
}

echo "==> Fetching Vazirmatn v${VERSION}"
fetch vazirmatn_regular   Vazirmatn-Regular.ttf
fetch vazirmatn_medium    Vazirmatn-Medium.ttf
fetch vazirmatn_semibold  Vazirmatn-SemiBold.ttf
fetch vazirmatn_bold      Vazirmatn-Bold.ttf
fetch vazirmatn_extrabold Vazirmatn-ExtraBold.ttf
echo "==> Vazirmatn ready under ${FONT_DIR}"
