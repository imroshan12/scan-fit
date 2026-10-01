#!/usr/bin/env bash
# Rebuild everything generated from spec/: presets bundle (signed), strings, tokens, analytics.
#
#   spec/tools/build_all.sh            local/dev: signs presets with the public DEV key
#   PRESETS_SIGNING_KEY=<seed> spec/tools/build_all.sh --release   CI/release: signs with the real key
#
# Needs: pip install -r spec/tools/requirements.txt
set -euo pipefail
cd "$(dirname "$0")/../.."
PY="${PYTHON:-python3}"

"$PY" spec/tools/make_signing_vector.py --check
if [[ "${1:-}" == "--release" ]]; then
  "$PY" spec/tools/build_presets.py --sign
  "$PY" spec/tools/build_presets.py --verify spec/signing/prod_public_key.b64
  "$PY" spec/tools/gen_strings.py --strict
else
  "$PY" spec/tools/build_presets.py --sign-dev
  "$PY" spec/tools/build_presets.py --verify spec/signing/dev_public_key.b64
  "$PY" spec/tools/gen_strings.py
fi
"$PY" spec/tools/gen_tokens.py
"$PY" spec/tools/gen_analytics.py
