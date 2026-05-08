#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Secret and leak risk scan (categories only)"
printf 'Note: values are never printed; only filenames/counts/categories.\n'
section "Sensitive filename indicators"
find . -type f \( -iname '*secret*' -o -iname '*token*' -o -iname '*.pem' -o -iname '*.key' -o -iname '*.p12' -o -iname '.env*' -o -iname '*credential*' -o -iname '*.db' -o -iname '*.sqlite*' -o -iname '*.log' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | sed 's#^./##' | head -180 | redact
section "Keyword occurrence counts by category"
for pat in 'api[_-]?key' 'secret' 'token' 'password|passwd' 'bearer' 'private[_ -]?key' 'localhost:[0-9]+' '/home/[A-Za-z0-9_-]+'; do
  count=$(pgrep_count_total -i "$pat" .)
  printf '%s: %s\n' "$pat" "$count"
done
section "Recommended blocker interpretation"
echo "HIGH if .env/private keys/databases/log dumps are intended to be published."
echo "MEDIUM if internal paths or localhost-only assumptions appear in public docs."
echo "LOW if keyword hits are only documentation placeholders after manual review."
section "Gate status"
sensitive_count=$(find . -type f \( -iname '*secret*' -o -iname '*token*' -o -iname '*.pem' -o -iname '*.key' -o -iname '*.p12' -o -iname '.env*' -o -iname '*credential*' -o -iname '*.db' -o -iname '*.sqlite*' -o -iname '*.log' \) ! -path '*/node_modules/*' ! -path '*/.git/*' 2>/dev/null | wc -l | tr -d ' ')
if [ "${sensitive_count:-0}" -gt 0 ]; then
  echo "gate_status: RED"
  echo "gate_category: secret-hygiene"
  echo "gate_description: Sensitive filename indicators exist; manual review/redaction required before public exposure."
  echo "todo_item: Review sensitive filename indicators before public release | HIGH | security,public-readiness,review | Secret-like files or DB/log/cert artefacts exist; inspect without exposing values."
else
  echo "gate_status: GREEN"
  echo "gate_category: secret-hygiene"
  echo "gate_description: No sensitive filename indicators detected by the safe scan."
fi
