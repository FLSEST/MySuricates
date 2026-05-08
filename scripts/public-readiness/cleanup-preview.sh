#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Generated artefact hygiene preview"
section "Potential generated-output directories"
for d in .tmp .output docs/public/generated screenshots/raw market/public/mysuricates/_runs; do [ -e "$d" ] && du -sh "$d" 2>/dev/null || echo "absent: $d"; done
section "Loose markdown-like candidates outside docs/reports (review, do not auto-delete)"
find . -maxdepth 2 -type f -name '*.md' ! -path './docs/*' ! -path './reports/*' ! -path './.git/*' | sed 's#^./##' | sort | head -120 | redact
section "Gate status"
echo "gate_status: GREEN"
echo "gate_category: artefact-hygiene"
echo "gate_description: Cleanup preview completed; no deletion performed."
