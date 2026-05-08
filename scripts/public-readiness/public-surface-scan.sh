#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

# This script focuses on the EXTERNAL surface that a public consumer would hit:
# REST endpoint paths, frontend route paths, build-time exposed env vars, documented public APIs.
# Repo shape and component file lists belong in safe-inventory.sh — not duplicated here.

section "Public surface scan"

inventory_section "Backend REST endpoints (paths only, deterministic)"
if [ -d ai-automation-backend/src/main ]; then
  pgrep -h '@(Get|Post|Put|Delete|Patch|Request)Mapping\([^)]*"' ai-automation-backend/src/main \
    | sed -nE 's/.*@(Get|Post|Put|Delete|Patch|Request)Mapping[^"]*"([^"]+)".*/\1 \2/p' \
    | sort -u | head -200 | redact
  count=$(pgrep_count_total '@(Get|Post|Put|Delete|Patch|Request)Mapping' ai-automation-backend/src/main)
  echo "backend_mapping_annotation_count: $count"
else
  echo "backend_src: absent"
fi

inventory_section "Frontend route paths"
if [ -f ai-automation-frontend/src/App.tsx ]; then
  grep -hE "path:[[:space:]]*['\"][^'\"]+['\"]" ai-automation-frontend/src/App.tsx 2>/dev/null \
    | sed -nE "s/.*path:[[:space:]]*['\"]([^'\"]+)['\"].*/\1/p" \
    | sort -u | head -100 | redact
fi

inventory_section "Public build-time env vars (VITE_*)"
if [ -d ai-automation-frontend/src ]; then
  pgrep -ho "VITE_[A-Z0-9_]+" ai-automation-frontend/src | sort -u | head -50
  count=$(pgrep -ho "VITE_[A-Z0-9_]+" ai-automation-frontend/src | sort -u | wc -l | tr -d ' ')
  echo "vite_public_var_count: $count"
fi

inventory_section ".env.example presence"
for f in .env.example ai-automation-backend/.env.example ai-automation-frontend/.env.example; do
  [ -f "$f" ] && echo "present: $f" || echo "absent: $f"
done

inventory_section "Public-API documentation markers"
for f in AGENTS.md API.md docs/api.md README.md; do
  if [ -f "$f" ]; then
    endpoint_refs=$(grep -cE '/api/v[0-9]+' "$f" 2>/dev/null || echo 0)
    printf '%s: api_path_refs=%s\n' "$f" "$endpoint_refs"
  fi
done

section "Gate status"
echo "gate_status: YELLOW"
echo "gate_category: public-surface"
echo "gate_description: External surface (endpoints, routes, build-time env) inventoried; verify documentation, deprecation, and undocumented endpoints before publishing."
