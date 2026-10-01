#!/usr/bin/env bash
# CI ONLY. Lets a pull request build the *release* configuration without any secret: generates a throwaway Ed25519
# key, writes its public half to spec/signing/prod_public_key.b64 and re-signs spec/dist with it. The Release builds
# then exercise R8, release lint and the embed checks exactly as a real release would. The throwaway key is discarded
# with the runner and the result is never published.
#
# It OVERWRITES spec/signing/prod_public_key.b64, so it refuses to run outside CI (GitHub sets CI=true).
set -euo pipefail
cd "$(dirname "$0")/../.."
PY="${PYTHON:-python3}"

if [[ "${CI:-}" != "true" ]]; then
  echo "error: rehearsal_key.sh overwrites spec/signing/prod_public_key.b64 and only runs in CI (CI=true)." >&2
  exit 1
fi

KEYS="$("$PY" spec/tools/build_presets.py --genkey)"
PRIVATE="$(printf '%s\n' "$KEYS" | sed -n 's/^PRIVATE[^:]*: *//p')"
printf '%s\n' "$KEYS" | sed -n 's/^PUBLIC[^:]*: *//p' > spec/signing/prod_public_key.b64
PRESETS_SIGNING_KEY="$PRIVATE" "$PY" spec/tools/build_presets.py --sign
"$PY" spec/tools/build_presets.py --verify spec/signing/prod_public_key.b64
