#!/usr/bin/env bash
# Rebuild everything generated from spec/: presets bundle, strings, tokens, analytics.
#
#   spec/tools/build_all.sh                         local/dev and every CI build
#   spec/tools/build_all.sh --release               a final release: fails on any TODO_HI (unreviewed Hindi)
#   spec/tools/build_all.sh --release --allow-todo-hi   internal / closed-testing builds (tags -rc, -beta)
#
# Presets are not signed (user decision 2026-10-20): the apps check the file's format instead (spec/README.md).
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

"$PY" spec/tools/make_presets_load_cases.py --check
"$PY" spec/tools/build_presets.py
if [[ "$RELEASE" == 1 && "$ALLOW_TODO_HI" == 0 ]]; then
  "$PY" spec/tools/gen_strings.py --strict
else
  "$PY" spec/tools/gen_strings.py
fi
"$PY" spec/tools/gen_tokens.py
"$PY" spec/tools/gen_analytics.py
