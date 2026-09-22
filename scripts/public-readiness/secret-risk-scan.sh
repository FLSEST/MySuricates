#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

section "Secret and leak risk scan (categories only)"
printf 'Note: values are never printed; only filenames/counts/categories. Gate status is based on tracked publishable files.\n'

tracked_high_risk_files() {
  git ls-files \
    | grep -Ei '(^|/)(\.env($|\.)|[^/]*\.(pem|key|p12|db|sqlite|sqlite3|log)$)' \
    | grep -Eiv '(^|/)\.env\.example$|(^|/)[^/]*\.example$' \
    || true
}

sensitive_filename_candidates() {
  find . -type f \
    \( -iname '*secret*' -o -iname '*token*' -o -iname '*.pem' -o -iname '*.key' -o -iname '*.p12' -o -iname '.env*' -o -iname '*credential*' -o -iname '*.db' -o -iname '*.sqlite*' -o -iname '*.log' \) \
    ! -path '*/node_modules/*' ! -path '*/target/*' ! -path '*/dist/*' ! -path '*/build/*' ! -path '*/.git/*' \
    | sed 's#^./##'
}

section "Tracked high-risk filename indicators"
tracked_high_risk_files | head -180 | redact

section "Ignored/local sensitive filename indicators (not release blockers)"
sensitive_filename_candidates | while IFS= read -r file; do
  if git check-ignore -q -- "$file"; then
    printf '%s\n' "$file"
  fi
done | head -180 | redact

# Die eigentliche Handpruefung der Stichwort-Treffer (TODO 48f4ef25): echte
# Schluesselbloecke im getrackten Bestand. Nur Dateinamen, nie Inhalte.
section "PEM private key blocks in tracked files"
pem_files=$(git grep -lIE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' 2>/dev/null || true)
printf 'pem_private_key_files: %s\n' "$(printf '%s' "$pem_files" | grep -c . || true)"
[ -n "$pem_files" ] && printf '%s\n' "$pem_files" | head -50 | redact

section "Secret scanners in CI"
if grep -qE 'gitleaks' .github/workflows/*.yml 2>/dev/null; then
  echo "ci_secret_scanner: gitleaks ($(grep -lE 'gitleaks' .github/workflows/*.yml | tr '\n' ' ' | sed 's/ $//'))"
else
  echo "ci_secret_scanner: none"
fi

section "Keyword occurrence counts by category"
echo "note: counts are raw keyword hits (the product manages API keys, tokens and secrets), scanner scripts excluded; real key material is covered by the PEM check above and CI secret scanning"
for pat in 'api[_-]?key' 'secret' 'token' 'password|passwd' 'bearer' 'private[_ -]?key' 'localhost:[0-9]+' '/home/[A-Za-z0-9_-]+'; do
  count=$(pgrep_count_total -i --exclude-dir=public-readiness "$pat" .)
  printf '%s: %s\n' "$pat" "$count"
done

section "Recommended blocker interpretation"
echo "HIGH if tracked .env/private keys/databases/log dumps would be published."
echo "MEDIUM if internal paths or localhost-only assumptions appear in public docs."
echo "LOW if keyword hits are only documentation placeholders after manual review."
echo "INFO if sensitive-looking files are ignored local runtime artifacts."

section "Gate status"
sensitive_count=$(tracked_high_risk_files | wc -l | tr -d ' ')
pem_count=$(printf '%s' "$pem_files" | grep -c . || true)
sensitive_count=$(( ${sensitive_count:-0} + ${pem_count:-0} ))
if [ "${sensitive_count:-0}" -gt 0 ]; then
  echo "gate_status: RED"
  echo "gate_category: secret-hygiene"
  echo "gate_description: Tracked high-risk filename indicators exist; remove from Git or replace with safe examples before public exposure."
  echo "todo_item: Remove tracked secret-like files before public release | HIGH | security,public-readiness,review | Tracked .env/DB/log/key/cert artefacts exist; inspect without exposing values."
else
  echo "gate_status: GREEN"
  echo "gate_category: secret-hygiene"
  echo "gate_description: No tracked high-risk filename indicators and no PEM private key blocks in tracked files."
fi
