#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Dead code and unused surface scan"
section "TODO/FIXME/HACK/deprecated markers"
rg -I -n --glob '!node_modules/**' --glob '!target/**' --glob '!dist/**' --glob '!build/**' --glob '!data/**' --glob '!.git/**' 'TODO|FIXME|HACK|deprecated|unused|dead code' . 2>/dev/null | redact | head -200 || true
section "Backup/old/generated-looking files"
find . -type f \( -name '*.bak' -o -name '*.old' -o -name '*~' -o -name '*.orig' -o -name '*.tmp' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | sed 's#^./##' | head -160 | redact
section "Dependency analysis availability"
command -v npx >/dev/null && echo "npx available" || echo "npx missing"
[ -f ai-automation-frontend/package.json ] && echo "frontend package.json present"
[ -f ai-automation-backend/pom.xml ] && echo "backend pom.xml present"
section "Optional frontend dead-code tools (best effort)"
if [ -f ai-automation-frontend/package.json ] && command -v npx >/dev/null; then
  (cd ai-automation-frontend && npx --yes depcheck --skip-missing=true 2>&1 | redact | head -180) || true
fi
section "Maven dependency analyze (best effort)"
if [ -f ai-automation-backend/pom.xml ]; then
  (cd ai-automation-backend && mvn -q dependency:analyze -DskipTests 2>&1 | redact | head -180) || true
fi
