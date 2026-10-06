#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

WITH_TOOLS=0
for a in "$@"; do
  case "$a" in
    --with-tools|-t) WITH_TOOLS=1 ;;
  esac
done

section "Dependency and supply-chain scan"

inventory_section "Lockfile and manifest markers"
find . -maxdepth 3 \( -name package-lock.json -o -name pnpm-lock.yaml -o -name yarn.lock -o -name pom.xml -o -name '*.gradle' \) ! -path '*/node_modules/*' | sed 's#^./##' | sort | redact

inventory_section "Frontend dependency counts (from package.json)"
if [ -f ai-automation-frontend/package.json ]; then
  python3 - <<'PY'
import json
d=json.load(open('ai-automation-frontend/package.json'))
deps=d.get('dependencies') or {}
dev=d.get('devDependencies') or {}
peer=d.get('peerDependencies') or {}
print(f"frontend_dependencies: {len(deps)}")
print(f"frontend_devDependencies: {len(dev)}")
print(f"frontend_peerDependencies: {len(peer)}")
PY
else
  echo "frontend_package_json: absent"
fi

inventory_section "Backend dependency counts (from pom.xml)"
if [ -f ai-automation-backend/pom.xml ]; then
  count=$(grep -c '<dependency>' ai-automation-backend/pom.xml 2>/dev/null); count=${count:-0}
  echo "backend_dependency_entries: $count"
else
  echo "backend_pom_xml: absent"
fi

# Vulnerability check (TODO 0f6d23b2): only counts and package names, no raw
# output. npm audit for the frontend, OSV.dev for the Maven coordinates
# (dep_vuln_check.py, Python stdlib only). A failed check counts as open, never
# as clean. For tests without network: NPM_AUDIT_JSON_FILE,
# MVN_DEPS_FILE and OSV_FIXTURE replace the tool calls.
VULN_OPEN=""
if [ "$WITH_TOOLS" -eq 1 ]; then
  TMPD="$(mktemp -d)"; trap 'rm -rf "$TMPD"' EXIT
  if [ -f ai-automation-frontend/package.json ]; then
    tool_section "npm audit (vulnerability counts)" npm
    audit_json="${NPM_AUDIT_JSON_FILE:-$TMPD/npm-audit.json}"
    [ -n "${NPM_AUDIT_JSON_FILE:-}" ] || (cd ai-automation-frontend && timeout 120 npm audit --json >"$audit_json" 2>/dev/null)
    npm_out=$(python3 "$SCRIPT_DIR/dep_vuln_check.py" npm "$audit_json"); rc=$?
    printf '%s\n' "$npm_out"
    if [ "$rc" -ne 0 ]; then VULN_OPEN="$VULN_OPEN npm-audit-unavailable"
    elif [ "$(printf '%s\n' "$npm_out" | awk -F': ' '/^npm_audit_(high|critical):/ {n+=$2} END {print n+0}')" -gt 0 ]; then
      VULN_OPEN="$VULN_OPEN npm-high-critical"
    fi
  fi
  if [ -f ai-automation-backend/pom.xml ]; then
    tool_section "OSV.dev (Maven runtime dependencies)" mvn
    deps_txt="${MVN_DEPS_FILE:-$TMPD/mvn-deps.txt}"
    [ -n "${MVN_DEPS_FILE:-}" ] || (cd ai-automation-backend && timeout 300 mvn -B -q dependency:list \
      -DincludeScope=runtime -DoutputFile="$deps_txt" >/dev/null 2>&1)
    osv_out=$(python3 "$SCRIPT_DIR/dep_vuln_check.py" maven "$deps_txt"); rc=$?
    printf '%s\n' "$osv_out"
    if [ "$rc" -ne 0 ]; then VULN_OPEN="$VULN_OPEN osv-unavailable"
    elif [ "$(printf '%s\n' "$osv_out" | awk -F': ' '/^osv_maven_high_or_unknown_packages:/ {print $2+0}')" -gt 0 ]; then
      VULN_OPEN="$VULN_OPEN maven-high-critical"
    fi
  fi
fi

section "Gate status"
if [ "$WITH_TOOLS" -eq 0 ]; then
  echo "gate_status: GREEN"
  echo "gate_category: dependency-inventory"
  echo "gate_description: Dependency inventory completed deterministically. Run with --with-tools for the vulnerability check (npm audit + OSV.dev)."
elif [ -z "$VULN_OPEN" ]; then
  echo "gate_status: GREEN"
  echo "gate_category: dependency-risk"
  echo "gate_description: Vulnerability check ran (npm audit + OSV.dev): no high/critical findings in frontend or Maven runtime dependencies."
else
  echo "gate_status: YELLOW"
  echo "gate_category: dependency-risk"
  echo "gate_description: Vulnerability check open:${VULN_OPEN} — see the counts above."
  echo "todo_item: Abhaengigkeiten mit Schwachstellen pruefen (${VULN_OPEN# }) | MEDIUM | security,dependencies | Befunde aus npm audit / OSV.dev im Evidenz-Lauf sichten; betroffene Pakete aktualisieren oder begruendet zurueckstellen."
fi
