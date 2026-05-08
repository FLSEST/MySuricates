#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Screenshot and demo-data planning scan"
section "UI route/page candidates"
find ai-automation-frontend/src/pages ai-automation-frontend/src/components 2>/dev/null -type f \( -name '*.tsx' -o -name '*.ts' \) | sed 's#^#file: #' | head -220 | redact || true
section "Existing screenshots/images (filenames only)"
find . -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.webp' -o -iname '*.gif' \) ! -path '*/node_modules/*' ! -path '*/data/*' ! -path '*/.git/*' | sed 's#^./##' | sort | head -160 | redact
section "Demo-data risk markers"
find . -type f \( -iname '*demo*' -o -iname '*fixture*' -o -iname '*mock*' -o -iname '*sample*' \) ! -path '*/node_modules/*' ! -path '*/target/*' ! -path '*/dist/*' ! -path '*/data/*' ! -path '*/.git/*' | sed 's#^./##' | head -160 | redact
