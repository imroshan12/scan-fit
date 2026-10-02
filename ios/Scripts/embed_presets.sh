#!/usr/bin/env bash
# Xcode post-build phase: copy the signed presets bundle and its public key into the app bundle, so the
# app has presets on first (offline) launch (ARCHITECTURE §6, ROADMAP Phase 0).
#
# Debug  -> embeds spec/signing/dev_public_key.b64   (public DEV key; anyone can sign with it)
# Release-> embeds spec/signing/prod_public_key.b64  (real key; the build fails without it)
# Produce spec/dist with:  spec/tools/build_all.sh   (CI: PRESETS_SIGNING_KEY=... build_all.sh --release)
#
# No Python runs here: the signature is checked by Scripts/verify_presets.swift (CryptoKit), so building or archiving in Xcode
# needs nothing but Xcode. Python is only used to *produce* spec/dist (validate, bundle, sign), not to build the app.
set -euo pipefail

SPEC="${SRCROOT}/../spec"
DEST="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"

if [[ "${CONFIGURATION}" == "Release" ]]; then
  KEY="${SPEC}/signing/prod_public_key.b64"
  if [[ ! -f "${KEY}" ]]; then
    echo "error: ${KEY} is missing. Release builds must embed the production public key (see spec/signing/README.md)." >&2
    exit 1
  fi
  # The DEV key is public: anyone can sign presets with it. Never let it into a release build, even if it was copied here.
  if [[ "$(tr -d '[:space:]' < "${KEY}")" == "$(tr -d '[:space:]' < "${SPEC}/signing/dev_public_key.b64")" ]]; then
    echo "error: ${KEY} is the public DEV key. Release builds must embed the production key (python3 spec/tools/build_presets.py --genkey; see spec/signing/README.md)." >&2
    exit 1
  fi
else
  KEY="${SPEC}/signing/dev_public_key.b64"
fi

for f in presets.json presets.json.sig; do
  if [[ ! -f "${SPEC}/dist/${f}" ]]; then
    echo "error: ${SPEC}/dist/${f} not found. Build it once with spec/tools/build_all.sh (the only step that needs Python)." >&2
    exit 1
  fi
done

# Refuse to bundle a snapshot that will not verify against the key we are about to embed. `--sdk macosx` because Xcode's
# environment points SDKROOT at the iOS SDK, and this script runs on the Mac. A bare PATH is fine: xcrun is called by path.
status=0
/usr/bin/xcrun --sdk macosx swift "${SRCROOT}/Scripts/verify_presets.swift" \
  "${SPEC}/dist/presets.json" "${SPEC}/dist/presets.json.sig" "${KEY}" || status=$?
if [[ "${status}" -eq 1 ]]; then
  echo "error: spec/dist/presets.json does not verify against $(basename "${KEY}"). Re-run spec/tools/build_all.sh (dev) or sign with the production key (release)." >&2
  exit 1
elif [[ "${status}" -ne 0 ]]; then
  echo "error: the presets verifier could not run (exit ${status}); is Xcode's command line toolchain selected (xcode-select -p)?" >&2
  exit 1
fi

mkdir -p "${DEST}"
cp "${SPEC}/dist/presets.json" "${DEST}/presets.json"
cp "${SPEC}/dist/presets.json.sig" "${DEST}/presets.json.sig"
cp "${KEY}" "${DEST}/presets_public_key.b64"
echo "Embedded presets (${CONFIGURATION}) with $(basename "${KEY}")"
