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
# There is no root .env: container instances get their environment from
# scripts/instance.sh (deploy/instances/<name>.env), the rest reads the two files here
# (TODO 428ee1ec).
for f in ai-automation-backend/.env.example ai-automation-frontend/.env.example; do
  [ -f "$f" ] && echo "present: $f" || echo "absent: $f"
done
[ -f scripts/instance.sh ] && echo "container_env: scripts/instance.sh -> deploy/instances/<name>.env"

inventory_section "Public-API documentation markers"
# grep -c prints "0" on zero hits AND exits with 1 — an "|| echo 0" therefore wrote
# a second, unlabeled line (TODO 428ee1ec). The API reference counts as API docs.
for f in AGENTS.md docs/api-referenz.md docs/en/api-referenz.md README.md; do
  if [ -f "$f" ]; then
    endpoint_refs=$(grep -cE '/api/v[0-9]+' "$f" 2>/dev/null)
    printf '%s: api_path_refs=%s\n' "$f" "${endpoint_refs:-0}"
  fi
done
echo "note: README.md links the API reference instead of listing paths; api_path_refs=0 there is expected"

# Endpoints that reach down to the host or hand out operational data (TODO
# bcfec862): their protection is described in the trust model of docs/deployment.md.
# Here we only establish deterministically that each one is named there.
inventory_section "Host-reaching endpoints documented in the trust model (docs/deployment.md)"
for ep in '/api/v1/debug/' '/api/v1/system/open-path' '/api/v1/system/browse' '/api/v1/system/backups/{filename}'; do
  if grep -qF -- "$ep" docs/deployment.md 2>/dev/null; then doc=yes; else doc=no; fi
  printf '%s: documented=%s\n' "$ep" "$doc"
done
echo "note: all of them require the API key (ApiKeyAuthFilter); guards per endpoint in docs/deployment.md, section 'Host-nahe Endpunkte hinter dem API-Key'"

section "Gate status"
echo "gate_status: YELLOW"
echo "gate_category: public-surface"
echo "gate_description: External surface (endpoints, routes, build-time env) inventoried; verify documentation, deprecation, and undocumented endpoints before publishing."
