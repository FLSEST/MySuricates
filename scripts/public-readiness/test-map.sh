#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Test map"
find . -type f \( -name '*Test.java' -o -name '*Tests.java' -o -name '*.test.*' -o -name '*.spec.*' \) ! -path '*/node_modules/*' ! -path '*/target/*' ! -path '*/dist/*' ! -path '*/build/*' ! -path '*/data/*' | sed 's#^./##' | sort | head -260 | redact
section "Production source counts"
printf 'Java source files: '; find ai-automation-backend/src/main -type f -name '*.java' 2>/dev/null | wc -l || true
printf 'Frontend TS/TSX source files: '; find ai-automation-frontend/src -type f \( -name '*.ts' -o -name '*.tsx' \) 2>/dev/null | wc -l || true
section "Test source counts"
printf 'Java test files: '; find ai-automation-backend/src/test -type f -name '*.java' 2>/dev/null | wc -l || true
printf 'Frontend test/spec files: '; find ai-automation-frontend/src ai-automation-frontend/tests -type f \( -name '*.test.*' -o -name '*.spec.*' \) 2>/dev/null | wc -l || true
