#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Dependency and supply-chain scan"
[ -f ai-automation-frontend/package.json ] && section "npm audit (best effort)" && (cd ai-automation-frontend && npm audit --audit-level=moderate 2>&1 | redact | head -180) || true
[ -f ai-automation-backend/pom.xml ] && section "Maven dependency tree summary (best effort)" && (cd ai-automation-backend && mvn -q dependency:tree -Dscope=compile 2>&1 | redact | head -220) || true
section "Lockfile/package markers"
find . -maxdepth 3 \( -name package-lock.json -o -name pnpm-lock.yaml -o -name yarn.lock -o -name pom.xml -o -name '*.gradle' \) ! -path '*/node_modules/*' | sed 's#^./##' | sort | redact
