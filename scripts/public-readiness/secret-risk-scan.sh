#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Secret and leak risk scan (categories only)"
printf 'Note: values are never printed; only filenames/counts/categories.\n'
section "Sensitive filename indicators"
find . -type f \( -iname '*secret*' -o -iname '*token*' -o -iname '*.pem' -o -iname '*.key' -o -iname '*.p12' -o -iname '.env*' -o -iname '*credential*' -o -iname '*.db' -o -iname '*.sqlite*' -o -iname '*.log' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | sed 's#^./##' | head -180 | redact
section "Keyword occurrence counts by category"
for pat in 'api[_-]?key' 'secret' 'token' 'password|passwd' 'bearer' 'private[_ -]?key' 'localhost:[0-9]+' '/home/[A-Za-z0-9_-]+'; do
  count=$(rg -I -i --glob '!node_modules/**' --glob '!target/**' --glob '!dist/**' --glob '!build/**' --glob '!data/**' --glob '!.git/**' --glob '!.output/**' --glob '!*.png' --glob '!*.jpg' --glob '!*.gif' --glob '!*.webp' "$pat" . 2>/dev/null | wc -l | tr -d ' ')
  printf '%s: %s\n' "$pat" "$count"
done
section "Recommended blocker interpretation"
echo "HIGH if .env/private keys/databases/log dumps are intended to be published."
echo "MEDIUM if internal paths or localhost-only assumptions appear in public docs."
echo "LOW if keyword hits are only documentation placeholders after manual review."
