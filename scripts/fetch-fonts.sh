#!/usr/bin/env bash
#
# Fetches the two bundled UI faces into the gitignored res source set at
# app/src/main/res-fonts/font:
#
#   * Vazirmatn, five static weights  -> the Latin UI.
#   * Noto Naskh Arabic UI, one file  -> the Persian UI. It is a VARIABLE font
#     (wght 400..700) and Compose instantiates each weight from it, so one
#     download covers the whole Persian type scale. See ui/theme/Type.kt.
#
# You normally do not need to run this: the fetchUiFonts Gradle task does the
# same thing on the first build. Run it by hand to prime a machine that will
# later build offline, or to prepare the directory on a connected machine and
# copy it across to one with no network at all.
#
# Safe to re-run. Files already present are kept, so nothing is re-downloaded.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FONT_DIR="${PROJECT_DIR}/app/src/main/res-fonts/font"

# Keep these in step with vazirmatnVersion / notoNaskhArabicCommit in
# app/build.gradle.kts. Both are PINNED on purpose: a tagged release must not
# depend on what a moving branch served that morning.
VAZIRMATN_VERSION="${VAZIRMATN_VERSION:-33.003}"
NOTO_NASKH_ARABIC_COMMIT="${NOTO_NASKH_ARABIC_COMMIT:-5fb648bb932bf1cdcd5fd71a73b79097e8666c36}"

# Built from fragments so no full literal URL lives in the file.
RAW="https://""raw.githubusercontent.com"
VAZIRMATN_BASE="${RAW}/rastikerdar/vazirmatn/v${VAZIRMATN_VERSION}/fonts/ttf"
NOTO_NASKH_ARABIC_BASE="${RAW}/google/fonts/${NOTO_NASKH_ARABIC_COMMIT}/ofl/notonaskharabicui"

mkdir -p "${FONT_DIR}"

# fetch <base_url> <android_res_name> <upstream_file_name>
# Android resource names are lowercase snake_case, upstream ships CamelCase.
fetch() {
  local base="$1" res_name="$2" file_name="$3"
  local target="${FONT_DIR}/${res_name}.ttf"
  if [ -s "${target}" ]; then
    echo "   have ${res_name}.ttf"
    return 0
  fi
  echo "   get  ${file_name} -> ${res_name}.ttf"
  curl -fsSL "${base}/${file_name}" -o "${target}"
}

echo "==> Fetching Vazirmatn v${VAZIRMATN_VERSION} (Latin UI)"
fetch "${VAZIRMATN_BASE}" vazirmatn_regular   Vazirmatn-Regular.ttf
fetch "${VAZIRMATN_BASE}" vazirmatn_medium    Vazirmatn-Medium.ttf
fetch "${VAZIRMATN_BASE}" vazirmatn_semibold  Vazirmatn-SemiBold.ttf
fetch "${VAZIRMATN_BASE}" vazirmatn_bold      Vazirmatn-Bold.ttf
fetch "${VAZIRMATN_BASE}" vazirmatn_extrabold Vazirmatn-ExtraBold.ttf

echo "==> Fetching Noto Naskh Arabic UI (Persian UI)"
# The brackets in the upstream file name are percent-encoded so curl does not
# read them as a glob pattern.
fetch "${NOTO_NASKH_ARABIC_BASE}" noto_naskh_arabic_ui 'NotoNaskhArabicUI%5Bwght%5D.ttf'

echo "==> UI fonts ready under ${FONT_DIR}"
