#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Quality gates baseline"
run_gate(){ name="$1"; shift; section "$name"; start=$(date +%s); "$@" > >(redact | head -n 160) 2> >(redact | head -n 160 >&2); rc=$?; end=$(date +%s); printf '\nRESULT %s exit=%s duration=%ss\n' "$name" "$rc" "$((end-start))"; return 0; }
[ -f ai-automation-backend/pom.xml ] && run_gate "backend: mvn test" bash -lc 'cd ai-automation-backend && mvn -q test'
if [ -f ai-automation-frontend/package.json ]; then
  section "frontend scripts available"; python3 - <<'PY'
import json
p='ai-automation-frontend/package.json'
d=json.load(open(p)); print(sorted((d.get('scripts') or {}).keys()))
PY
  for s in lint test build typecheck; do
    if python3 - <<PY
import json,sys
d=json.load(open('ai-automation-frontend/package.json'))
sys.exit(0 if '$s' in (d.get('scripts') or {}) else 1)
PY
    then run_gate "frontend: npm run $s" bash -lc "cd ai-automation-frontend && npm run $s"; fi
  done
fi
