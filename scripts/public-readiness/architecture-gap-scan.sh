#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Architecture gap scan"
section "Backend package/component shape"
find ai-automation-backend/src/main -maxdepth 6 -type d 2>/dev/null | sed 's#^#dir: #' | head -180 | redact || true
section "Frontend folder/component shape"
find ai-automation-frontend/src -maxdepth 3 -type d 2>/dev/null | sed 's#^#dir: #' | head -180 | redact || true
section "Layering markers count"
for pat in 'Controller' 'Service' 'Repository' 'Entity' 'Component' 'Provider' 'Store' 'Context' 'TODO|FIXME|HACK'; do
  count=$(pgrep_count_total "$pat" ai-automation-backend/src ai-automation-frontend/src)
  printf '%s: %s\n' "$pat" "$count"
done
section "Circular dependency tool availability"
command -v npx >/dev/null && echo "npx available; madge/dependency-cruiser can be introduced as optional gates" || echo "npx missing"
section "Gate status"
echo "gate_status: YELLOW"
echo "gate_category: architecture-gaps"
echo "gate_description: Architecture scan is structural; add deterministic boundary checks for stronger evidence."
echo "todo_item: Define deterministic architecture boundary checks | MEDIUM | architecture,quality | Consider ArchUnit for backend and dependency-cruiser/madge for frontend."
