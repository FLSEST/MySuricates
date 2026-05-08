#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

WITH_TOOLS=0
for a in "$@"; do
  case "$a" in
    --with-tools|-t) WITH_TOOLS=1 ;;
  esac
done

section "Dead code and unused surface scan"

inventory_section "TODO/FIXME/HACK/deprecated marker counts"
for pat in TODO FIXME HACK deprecated; do
  count=$(pgrep_count_total "$pat" .)
  printf '%s_marker_count: %s\n' "$pat" "$count"
done

inventory_section "TODO/FIXME marker locations (top 50)"
pgrep -n 'TODO|FIXME|HACK' . | redact | head -50 || true

inventory_section "Backup/old/generated-looking files"
candidates=$(find . -type f \( -name '*.bak' -o -name '*.old' -o -name '*~' -o -name '*.orig' -o -name '*.tmp' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | wc -l | tr -d ' ')
echo "backup_file_count: $candidates"
find . -type f \( -name '*.bak' -o -name '*.old' -o -name '*~' -o -name '*.orig' -o -name '*.tmp' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | sed 's#^./##' | head -160 | redact

if [ "$WITH_TOOLS" -eq 1 ]; then
  if [ -f ai-automation-frontend/package.json ] && command -v npx >/dev/null; then
    tool_section "depcheck (frontend)" npx
    (cd ai-automation-frontend && npx --yes depcheck --skip-missing=true 2>&1 | redact | head -180) || true
  fi
  if [ -f ai-automation-backend/pom.xml ]; then
    tool_section "Maven dependency:analyze" mvn
    (cd ai-automation-backend && mvn -q dependency:analyze -DskipTests 2>&1 | redact | head -180) || true
  fi
fi

section "Gate status"
if [ "$WITH_TOOLS" -eq 1 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: dead-code-candidates"
  echo "gate_description: Dead-code scan ran with tools (non-deterministic); manual verification required before cleanup."
  echo "todo_item: Review dead-code and unused-surface candidates | LOW | refactoring,cleanup,review | Validate depcheck/Maven/TODO findings and decide safe cleanup scope."
else
  echo "gate_status: GREEN"
  echo "gate_category: dead-code-inventory"
  echo "gate_description: Dead-code marker inventory completed deterministically. Run with --with-tools for depcheck / mvn dependency:analyze."
fi
