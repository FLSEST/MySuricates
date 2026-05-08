#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

# Modes:
#   default     -> inventory only (deterministic, fast)
#   --with-tools -> inventory + tool execution (mvn/npm runs, non-deterministic, slow)
WITH_TOOLS=0
for a in "$@"; do
  case "$a" in
    --with-tools|-t) WITH_TOOLS=1 ;;
  esac
done

section "Quality gates"

inventory_section "Backend project markers"
if [ -f ai-automation-backend/pom.xml ]; then
  echo "backend_pom: present"
  test_files=$(find ai-automation-backend/src/test -type f -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
  echo "backend_test_files: $test_files"
else
  echo "backend_pom: absent"
fi

inventory_section "Frontend project markers"
if [ -f ai-automation-frontend/package.json ]; then
  echo "frontend_package_json: present"
  python3 - <<'PY'
import json
d=json.load(open('ai-automation-frontend/package.json'))
scripts=sorted((d.get('scripts') or {}).keys())
print('frontend_scripts:', ','.join(scripts))
for needed in ('lint','test','build','typecheck'):
    print(f"frontend_script_{needed}: {'present' if needed in scripts else 'absent'}")
PY
  spec_files=$(find ai-automation-frontend/src ai-automation-frontend/tests -type f \( -name '*.test.*' -o -name '*.spec.*' \) 2>/dev/null | wc -l | tr -d ' ')
  echo "frontend_test_files: $spec_files"
else
  echo "frontend_package_json: absent"
fi

if [ "$WITH_TOOLS" -eq 1 ]; then
  run_gate(){ name="$1"; shift; section "$name"; start=$(date +%s); "$@" > >(redact | head -n 160) 2> >(redact | head -n 160 >&2); rc=$?; end=$(date +%s); printf '\nRESULT %s exit=%s duration=%ss\n' "$name" "$rc" "$((end-start))"; return 0; }
  if [ -f ai-automation-backend/pom.xml ]; then
    tool_section "backend: mvn test" mvn
    run_gate "backend: mvn test" bash -lc 'cd ai-automation-backend && mvn -q test'
  fi
  if [ -f ai-automation-frontend/package.json ]; then
    tool_section "frontend: npm scripts" npm
    for s in lint test build typecheck; do
      if python3 - <<PY
import json,sys
d=json.load(open('ai-automation-frontend/package.json'))
sys.exit(0 if '$s' in (d.get('scripts') or {}) else 1)
PY
      then run_gate "frontend: npm run $s" bash -lc "cd ai-automation-frontend && npm run $s"; fi
    done
  fi
fi

section "Gate status"
if [ "$WITH_TOOLS" -eq 1 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: quality-baseline"
  echo "gate_description: Quality gates executed (tool-dependent); inspect individual command results and promote only verified claims."
  echo "todo_item: Review quality-gate baseline and decide required CI gates | MEDIUM | testing,quality,public-readiness | Validate mvn/npm results and define minimum gates before public claims."
else
  echo "gate_status: GREEN"
  echo "gate_category: quality-inventory"
  echo "gate_description: Quality-gate inventory completed deterministically. Run with --with-tools to execute mvn/npm gates."
fi
