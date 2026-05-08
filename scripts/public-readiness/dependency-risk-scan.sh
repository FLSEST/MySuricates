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
  count=$(grep -c '<dependency>' ai-automation-backend/pom.xml 2>/dev/null || echo 0)
  echo "backend_dependency_entries: $count"
else
  echo "backend_pom_xml: absent"
fi

if [ "$WITH_TOOLS" -eq 1 ]; then
  if [ -f ai-automation-frontend/package.json ]; then
    tool_section "npm audit" npm
    (cd ai-automation-frontend && npm audit --audit-level=moderate 2>&1 | redact | head -180) || true
  fi
  if [ -f ai-automation-backend/pom.xml ]; then
    tool_section "Maven dependency tree" mvn
    (cd ai-automation-backend && mvn -q dependency:tree -Dscope=compile 2>&1 | redact | head -220) || true
  fi
fi

section "Gate status"
if [ "$WITH_TOOLS" -eq 1 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: dependency-risk"
  echo "gate_description: Dependency scan executed (tool-dependent); review audit output and add stronger supply-chain tooling if needed."
  echo "todo_item: Review dependency and supply-chain scan results | MEDIUM | security,dependencies | Triage npm/Maven findings and decide whether OSV/trivy/gitleaks gates are needed."
else
  echo "gate_status: GREEN"
  echo "gate_category: dependency-inventory"
  echo "gate_description: Dependency inventory completed deterministically. Run with --with-tools for npm audit / mvn dependency:tree."
fi
