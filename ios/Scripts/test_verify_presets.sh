#!/usr/bin/env bash
# Runs the shared signing vector (spec/fixtures/signing/vector.json, the same cases the Kotlin and Swift loaders pass) through
# Scripts/verify_presets.swift: every case must come out exactly as the vector says. No Python (plutil reads the JSON).
set -euo pipefail
cd "$(dirname "$0")/.."

VECTOR="../spec/fixtures/signing/vector.json"
DIR="$(dirname "${VECTOR}")"
count="$(plutil -extract verify_cases raw -o - "${VECTOR}")"
failed=0

for ((i = 0; i < count; i++)); do
  field() { plutil -extract "verify_cases.${i}.$1" raw -o - "${VECTOR}"; }
  id="$(field id)"; valid="$(field valid)"
  status=0
  /usr/bin/xcrun --sdk macosx swift Scripts/verify_presets.swift \
    "${DIR}/$(field bundle)" "${DIR}/$(field signature)" "${DIR}/$(field public_key)" >/dev/null 2>&1 || status=$?
  if [[ "${valid}" == "true" && "${status}" -eq 0 ]] || [[ "${valid}" == "false" && "${status}" -eq 1 ]]; then
    echo "  ok    ${id}"
  else
    echo "  FAIL  ${id}: expected valid=${valid}, verifier exit ${status}"; failed=1
  fi
done

# Missing files are a usage problem (exit 2), not a verdict on the signature.
status=0; /usr/bin/xcrun --sdk macosx swift Scripts/verify_presets.swift /nonexistent /nonexistent /nonexistent >/dev/null 2>&1 || status=$?
if [[ "${status}" -eq 2 ]]; then echo "  ok    missing files -> exit 2"; else echo "  FAIL  missing files: exit ${status}, expected 2"; failed=1; fi

[[ "${failed}" -eq 0 ]] && echo "✓ presets verifier matches the signing vector (${count} cases)"
exit "${failed}"
