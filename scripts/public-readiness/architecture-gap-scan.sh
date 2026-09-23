#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Architecture gap scan"
section "Backend package/component shape"
find ai-automation-backend/src/main -maxdepth 6 -type d 2>/dev/null | sed 's#^#dir: #' | head -180 | redact || true
section "Frontend folder/component shape"
find ai-automation-frontend/src -maxdepth 3 -type d 2>/dev/null | sed 's#^#dir: #' | head -180 | redact || true
section "Layering markers count"
# TODO/FIXME/HACK zaehlt hier nicht mehr: das Rohmuster traf auch jede TODO-ID in Kommentaren
# und widersprach so dead-code-scan.sh, das die Marker-Inventur fuehrt (TODO 428ee1ec).
for pat in 'Controller' 'Service' 'Repository' 'Entity' 'Component' 'Provider' 'Store' 'Context'; do
  count=$(pgrep_count_total "$pat" ai-automation-backend/src ai-automation-frontend/src)
  printf '%s: %s\n' "$pat" "$count"
done
# Vorhandene Grenzpruefungen erkennen, statt pauschal "fuehrt ArchUnit ein" zu empfehlen
# (TODO 428ee1ec): Das Backend misst Schichten, Paketzyklen und Modul-Zuordnung ueber eigene
# Tests unter architecture/, das Frontend hat bislang keine deterministische Pruefung.
section "Boundary checks present"
backend_checks=$(find ai-automation-backend/src/test -type f \( -name '*LayerRule*Test.java' -o -name '*PackageCycle*Test.java' \
  -o -name '*PackageEdges*Test.java' -o -name '*ModuleGate*Test.java' -o -name '*Arch*Test.java' \) 2>/dev/null | sort)
backend_count=$(printf '%s' "$backend_checks" | grep -c . || true)
echo "backend_boundary_tests: ${backend_count}"
printf '%s\n' "$backend_checks" | grep . | sed 's#.*/#  #' || true
frontend_checks=""
for f in ai-automation-frontend/.dependency-cruiser.js ai-automation-frontend/.dependency-cruiser.cjs ai-automation-frontend/.madgerc; do
  [ -f "$f" ] && frontend_checks="$frontend_checks $f"
done
if grep -qE "import/no-cycle|boundaries/" ai-automation-frontend/eslint.config.js 2>/dev/null; then
  frontend_checks="$frontend_checks eslint:import-boundaries"
fi
echo "frontend_boundary_checks: ${frontend_checks:- none}"
section "Gate status"
if [ "$backend_count" -gt 0 ] && [ -n "$frontend_checks" ]; then
  echo "gate_status: GREEN"
  echo "gate_category: architecture-gaps"
  echo "gate_description: Backend und Frontend haben deterministische Grenzpruefungen."
elif [ "$backend_count" -gt 0 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: architecture-gaps"
  echo "gate_description: Backend hat ${backend_count} Grenztests; im Frontend fehlt eine deterministische Grenzpruefung."
else
  echo "gate_status: YELLOW"
  echo "gate_category: architecture-gaps"
  echo "gate_description: Keine deterministischen Grenzpruefungen gefunden."
fi
