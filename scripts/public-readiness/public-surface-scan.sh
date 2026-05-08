#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Public surface scan"
section "API/controller/page filenames"
find ai-automation-backend/src/main ai-automation-frontend/src -type f \( -iname '*Controller*' -o -iname '*Route*' -o -iname '*Page*' -o -iname '*Workflow*' -o -iname '*Chain*' -o -iname '*Skill*' \) 2>/dev/null | sed 's#^./##' | sort | head -240 | redact
section "External-facing terminology markers"
for pat in 'workflow' 'chain' 'agentic' 'skill' 'schedule' 'execution' 'project' 'todo'; do
  count=$(rg -I -i --glob '!node_modules/**' --glob '!target/**' --glob '!dist/**' --glob '!build/**' "$pat" README.md docs ai-automation-backend/src/main ai-automation-frontend/src 2>/dev/null | wc -l | tr -d ' ')
  printf '%s: %s\n' "$pat" "$count"
done
