#!/usr/bin/env bash
# Xcode post-build phase: copy the presets bundle into the app bundle, so the app has presets on first (offline) launch
# (ARCHITECTURE §6). Presets are not signed (user decision 2026-10-20): the app checks the file's format when it loads it,
# and spec/tools/build_presets.py has already validated every exam.
# Produce spec/dist with:  spec/tools/build_all.sh   (the only step that needs Python; building in Xcode needs only Xcode)
set -euo pipefail

SPEC="${SRCROOT}/../spec"
DEST="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"
SOURCE="${SPEC}/dist/presets.json"

if [[ ! -s "${SOURCE}" ]]; then
  echo "error: ${SOURCE} not found or empty. Build it once with spec/tools/build_all.sh." >&2
  exit 1
fi
# A cheap shape check so an obviously broken file never ships; the app runs the full format checks.
for key in '"schema_version":' '"presets_version":' '"exams":'; do
  if ! grep -q "${key}" "${SOURCE}"; then
    echo "error: ${SOURCE} is not a presets bundle (no ${key}). Re-run spec/tools/build_all.sh." >&2
    exit 1
  fi
done

mkdir -p "${DEST}"
cp "${SOURCE}" "${DEST}/presets.json"
# Builds from before 2026-10-20 also copied a signature and a key; remove them from incremental build folders.
rm -f "${DEST}/presets.json.sig" "${DEST}/presets_public_key.b64"
echo "Embedded presets (${CONFIGURATION})"
