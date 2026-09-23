#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Safe project inventory"
printf 'Root: %s\n' "$ROOT"
section "Git status summary"
# Leer hiess bisher beides: sauber oder keine Ausgabe (TODO 428ee1ec). Im isolierten Klon
# des Evidenz-Laufs ist der Baum immer sauber — das soll dastehen.
status_out=$(git status --short 2>/dev/null | redact | head -100)
if [ -n "$status_out" ]; then printf '%s\n' "$status_out"; else echo "(sauber — keine Aenderungen gegenueber HEAD)"; fi
section "Repository shape (safe filenames only)"
find . -maxdepth 2 -type f \
 ! -path '*/.git/*' ! -path '*/node_modules/*' ! -path '*/dist/*' ! -path '*/build/*' ! -path '*/target/*' ! -path '*/data/*' ! -path '*/.env*' ! -path '*/.output/*' ! -path '*/.playwright-mcp/*' \
 | sed 's#^./##' | sort | head -260 | redact
section "Public-readiness markers"
for f in README.md LICENSE SECURITY.md CONTRIBUTING.md CODE_OF_CONDUCT.md .env.example docker-compose.yml package.json pnpm-lock.yaml requirements.txt pyproject.toml AGENTS.md CLAUDE.md; do [ -e "$f" ] && echo "present: $f" || true; done
section "Component markers"
find . -maxdepth 3 \( -name pom.xml -o -name package.json -o -name vite.config.ts -o -name playwright.config.ts -o -name cypress.config.ts -o -name Dockerfile -o -name docker-compose.yml \) ! -path '*/node_modules/*' ! -path '*/target/*' | sed 's#^./##' | sort | redact
# Die Zahl der Testdateien fuehrt test-map.sh. Die fruehere Namenszaehlung (*test*, *spec*)
# widersprach ihr um den Faktor vier (TODO 428ee1ec).
section "Existing image/screenshot candidates (filenames only)"
find . -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.webp' -o -iname '*.gif' \) ! -path '*/node_modules/*' ! -path '*/data/*' ! -path '*/.git/*' | sed 's#^./##' | head -120 | redact
section "Gate status"
echo "gate_status: GREEN"
echo "gate_category: public-inventory"
echo "gate_description: Safe inventory completed; review evidence before publication."
