#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/docs-check.sh (TODO 3e57b1be).
#
# Das Skript meldet, welche oeffentlichen Dokumente vorhanden sind, und setzt das
# Gate auf YELLOW, sobald eines der Pflichtdokumente fehlt. Bis 2026-09-20 pruefte
# es fuenf Dateinamen, die es in diesem Projekt nie gab (docs/architecture.md,
# docs/agentic-development.md, docs/public-readiness.md, docs/security.md,
# docs/use-cases.md) — das Gate stand dadurch dauerhaft auf YELLOW und war als
# Signal wertlos. Fall 4 haelt genau diese Regression fest.
#
# Die Tests arbeiten auf einem Miniatur-Repository, damit sie nicht mitwandern,
# wenn das echte Repo waechst.
#
# Run: bash scripts/tests/test-docs-check.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/docs-check.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Miniatur-Repo mit allen Pflichtdokumenten.
FAKE="$TMP/fake"
mkdir -p "$FAKE/docs"
for f in README.md README.de.md LICENSE NOTICE SECURITY.md CONTRIBUTING.md; do
  echo "# $f" > "$FAKE/$f"
done
for f in GETTING-STARTED.md architektur.md vision.md workflow-pool-kuratierung.md; do
  echo "# $f" > "$FAKE/docs/$f"
done

run_gate() { PUBLIC_READINESS_ROOT="$FAKE" bash "$SCRIPT" 2>/dev/null; }

echo "Fall 1: vollstaendiges Repo -> GREEN"
OUT="$(run_gate)"
check "gate_status GREEN"            'grep -q "^gate_status: GREEN" <<< "$OUT"'
check "kein todo_item"               '! grep -q "^todo_item:" <<< "$OUT"'
check "alle zehn als present"        '[ "$(grep -c "^present: " <<< "$OUT")" -eq 10 ]'
check "kein missing"                 '! grep -q "^missing: " <<< "$OUT"'

echo "Fall 2: LICENSE fehlt -> YELLOW mit todo_item"
rm "$FAKE/LICENSE"
OUT="$(run_gate)"
check "gate_status YELLOW"           'grep -q "^gate_status: YELLOW" <<< "$OUT"'
check "todo_item ausgegeben"         'grep -q "^todo_item: " <<< "$OUT"'
check "LICENSE als missing gemeldet" 'grep -q "^missing: LICENSE$" <<< "$OUT"'

echo "Fall 3: nur eine Nicht-Pflichtdatei fehlt -> weiterhin GREEN"
echo "# LICENSE" > "$FAKE/LICENSE"
rm "$FAKE/NOTICE"
OUT="$(run_gate)"
check "gate_status GREEN"            'grep -q "^gate_status: GREEN" <<< "$OUT"'
check "NOTICE als missing gemeldet"  'grep -q "^missing: NOTICE$" <<< "$OUT"'

echo "Fall 4: Regression — keine Phantom-Dateinamen mehr in der Erwartungsliste"
echo "# NOTICE" > "$FAKE/NOTICE"
OUT="$(run_gate)"
for phantom in docs/architecture.md docs/agentic-development.md docs/public-readiness.md \
               docs/security.md docs/use-cases.md; do
  check "nicht erwartet: $phantom"   '! grep -q "^\(present\|missing\): '"$phantom"'$" <<< "$OUT"'
done
check "vollstaendiges Repo bleibt GREEN" 'grep -q "^gate_status: GREEN" <<< "$OUT"'

echo "Fall 5: Pflichtdokument in docs/ fehlt -> YELLOW"
rm "$FAKE/docs/vision.md"
OUT="$(run_gate)"
check "gate_status YELLOW"           'grep -q "^gate_status: YELLOW" <<< "$OUT"'
check "docs/vision.md als missing"   'grep -q "^missing: docs/vision.md$" <<< "$OUT"'

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
