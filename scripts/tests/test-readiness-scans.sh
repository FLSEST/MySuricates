#!/usr/bin/env bash
# Tests for three scan scripts of the evidence stage (TODO 428ee1ec): public-surface-scan.sh,
# architecture-gap-scan.sh, safe-inventory.sh. Each case pins an error that showed up as an audit finding in the
# evidence run of 2026-09-23 although it lay in the script:
#
# * public-surface-scan.sh wrote a second, unlabeled line "0" on zero hits
#   ("grep -c ... || echo 0") and demanded an .env.example in the root that never existed.
# * architecture-gap-scan.sh recommended ArchUnit across the board although boundary tests exist, and
#   emitted a todo_item every time.
# * safe-inventory.sh left the git status empty on a clean tree (indistinguishable from "no output")
#   and counted test files differently from test-map.sh.
#
# Run: bash scripts/tests/test-readiness-scans.sh   (exit 0 = all checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PR="$REPO/scripts/public-readiness"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

GIT="git -c user.name=t -c user.email=t@example.invalid"
FAKE="$TMP/fake"
mkdir -p "$FAKE/ai-automation-backend/src/main/java" "$FAKE/ai-automation-backend/src/test/java/a" \
         "$FAKE/ai-automation-frontend/src" "$FAKE/docs"
echo "# R" > "$FAKE/README.md"
echo "GET /api/v1/x" > "$FAKE/AGENTS.md"
touch "$FAKE/ai-automation-backend/.env.example" "$FAKE/ai-automation-frontend/.env.example"
( cd "$FAKE" && git init -q && $GIT add . && $GIT commit -q -m init )

run() { PUBLIC_READINESS_ROOT="$FAKE" bash "$PR/$1" 2>/dev/null; }

echo "1) public-surface-scan: null Treffer ergeben genau eine beschriftete Zeile"
OUT="$(run public-surface-scan.sh)"
check "README.md: api_path_refs=0"              'grep -qx "README.md: api_path_refs=0" <<< "$OUT"'
check "keine nackte Zeile 0"                    '! grep -qx "0" <<< "$OUT"'
check "AGENTS.md zaehlt 1"                      'grep -qx "AGENTS.md: api_path_refs=1" <<< "$OUT"'
check "keine Wurzel-.env.example erwartet"      '! grep -q "absent: .env.example" <<< "$OUT"'
check "Backend-.env.example gefunden"           'grep -q "present: ai-automation-backend/.env.example" <<< "$OUT"'

echo "2) architecture-gap-scan: ohne Grenztests YELLOW, aber kein todo_item"
OUT="$(run architecture-gap-scan.sh)"
check "backend_boundary_tests: 0"               'grep -q "backend_boundary_tests: 0" <<< "$OUT"'
check "gate YELLOW"                             'grep -q "^gate_status: YELLOW" <<< "$OUT"'
check "kein todo_item"                          '! grep -q "^todo_item:" <<< "$OUT"'
check "kein TODO-Rohmuster mehr"                '! grep -q "^TODO|FIXME|HACK:" <<< "$OUT"'

echo "3) architecture-gap-scan: vorhandene Grenztests werden erkannt"
touch "$FAKE/ai-automation-backend/src/test/java/a/LayerRuleTest.java" \
      "$FAKE/ai-automation-backend/src/test/java/a/PackageCycleRatchetTest.java"
OUT="$(run architecture-gap-scan.sh)"
check "backend_boundary_tests: 2"               'grep -q "backend_boundary_tests: 2" <<< "$OUT"'
check "Beschreibung nennt das Frontend"         'grep -q "gate_description: Backend hat 2 Grenztests" <<< "$OUT"'
printf "export default [{ rules: { 'import/no-cycle': 'error' } }]\n" > "$FAKE/ai-automation-frontend/eslint.config.js"
OUT="$(run architecture-gap-scan.sh)"
check "mit Frontend-Regel GREEN"                'grep -q "^gate_status: GREEN" <<< "$OUT"'

echo "4) safe-inventory: sauberer Baum ist ausdruecklich sauber, keine Namenszaehlung"
( cd "$FAKE" && $GIT add . && $GIT commit -q -m more )
OUT="$(run safe-inventory.sh)"
check "Git-Status meldet sauber"                'grep -q "(sauber — keine Aenderungen gegenueber HEAD)" <<< "$OUT"'
check "kein Abschnitt Test file count"          '! grep -q "## Test file count" <<< "$OUT"'
echo "x" > "$FAKE/neu.txt"
OUT="$(run safe-inventory.sh)"
check "Aenderung erscheint statt sauber"        'grep -q "?? neu.txt" <<< "$OUT"'

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
