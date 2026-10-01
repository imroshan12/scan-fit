#!/usr/bin/env bash
# Xcode post-build phase: copy the signed presets bundle and its public key into the app bundle, so the
# app has presets on first (offline) launch (ARCHITECTURE §6, ROADMAP Phase 0).
#
# Debug  -> embeds spec/signing/dev_public_key.b64   (public DEV key; anyone can sign with it)
# Release-> embeds spec/signing/prod_public_key.b64  (real key; the build fails without it)
# Produce spec/dist with:  spec/tools/build_all.sh   (CI: PRESETS_SIGNING_KEY=... build_all.sh --release)
set -euo pipefail

SPEC="${SRCROOT}/../spec"
DEST="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"
PYTHON="${PYTHON:-python3}"

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
    echo "error: ${SPEC}/dist/${f} not found. Run spec/tools/build_all.sh first." >&2
    exit 1
  fi
done

# Refuse to bundle a snapshot that will not verify against the key we are about to embed.
if "${PYTHON}" -c "import cryptography" >/dev/null 2>&1; then
  "${PYTHON}" "${SPEC}/tools/build_presets.py" --verify "${KEY}" >/dev/null || {
    echo "error: spec/dist/presets.json does not verify against ${KEY}. Re-run spec/tools/build_all.sh (dev) or sign with the production key (release)." >&2
    exit 1
  }
elif [[ "${CONFIGURATION}" == "Release" ]]; then
  echo "error: Release builds need python3 with 'cryptography' to verify the presets signature (pip install -r spec/tools/requirements.txt)." >&2
  exit 1
else
  echo "warning: skipping build-time presets verification (pip install -r spec/tools/requirements.txt). The app still verifies at launch." >&2
fi

mkdir -p "${DEST}"
cp "${SPEC}/dist/presets.json" "${DEST}/presets.json"
cp "${SPEC}/dist/presets.json.sig" "${DEST}/presets.json.sig"
cp "${KEY}" "${DEST}/presets_public_key.b64"
echo "Embedded presets (${CONFIGURATION}) with $(basename "${KEY}")"
