#!/usr/bin/env bash
# Rebuild everything generated from spec/: presets bundle (signed), strings, tokens, analytics.
#
#   spec/tools/build_all.sh            local/dev: signs presets with the public DEV key
#   PRESETS_SIGNING_KEY=<seed> spec/tools/build_all.sh --release   CI/release: signs with the real key, fails on any TODO_HI
#   ... --release --allow-todo-hi      internal / closed-testing builds (tags -rc, -beta): machine-drafted Hindi is allowed
#
# Needs: pip install -r spec/tools/requirements.txt
set -euo pipefail
cd "$(dirname "$0")/../.."
PY="${PYTHON:-python3}"

RELEASE=0 ALLOW_TODO_HI=0
for arg in "$@"; do
  case "$arg" in
    --release) RELEASE=1 ;;
    --allow-todo-hi) ALLOW_TODO_HI=1 ;;
    *) echo "usage: build_all.sh [--release [--allow-todo-hi]]" >&2; exit 2 ;;
  esac
done
if [[ "$ALLOW_TODO_HI" == 1 && "$RELEASE" == 0 ]]; then echo "error: --allow-todo-hi only makes sense with --release" >&2; exit 2; fi

"$PY" spec/tools/make_signing_vector.py --check
if [[ "$RELEASE" == 1 ]]; then
  "$PY" spec/tools/build_presets.py --sign
  "$PY" spec/tools/build_presets.py --verify spec/signing/prod_public_key.b64
  if [[ "$ALLOW_TODO_HI" == 1 ]]; then "$PY" spec/tools/gen_strings.py; else "$PY" spec/tools/gen_strings.py --strict; fi
else
  "$PY" spec/tools/build_presets.py --sign-dev
  "$PY" spec/tools/build_presets.py --verify spec/signing/dev_public_key.b64
  "$PY" spec/tools/gen_strings.py
fi
"$PY" spec/tools/gen_tokens.py
"$PY" spec/tools/gen_analytics.py
