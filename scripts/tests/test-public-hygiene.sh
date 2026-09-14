#!/usr/bin/env bash
# Tests fuer scripts/ci/public-hygiene.sh und scripts/git-hooks/pre-commit (TODOs 5dbabe7c, 3fcc2cfe).
# Run: bash scripts/tests/test-public-hygiene.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Das verbotene Literal zur Laufzeit zusammensetzen, damit diese Datei selbst sauber bleibt.
FORBIDDEN="$(printf '%s%s' '/home/' 'flo')"
GIT="git -c user.name=t -c user.email=t@example.invalid"

# Frisches Repo mit den Gate-Dateien, Hook via core.hooksPath (wie scripts/install-hooks.sh).
make_repo() {  # $1 = pfad
  git init -q "$1"
  mkdir -p "$1/scripts"
  cp -r "$REPO/scripts/ci" "$REPO/scripts/lib" "$REPO/scripts/git-hooks" "$1/scripts/"
  ( cd "$1" && $GIT add . && $GIT commit -q -m init && git config core.hooksPath scripts/git-hooks )
}

echo "1) Hook blockiert im Haupt-Checkout"
make_repo "$TMP/main"
( cd "$TMP/main" && printf 'x=%s/x\n' "$FORBIDDEN" > bad.txt && git add bad.txt )
OUT=$(cd "$TMP/main" && $GIT commit -q -m bad 2>&1); RC=$?
check "commit abgelehnt (exit != 0)"        '[ "$RC" -ne 0 ]'
check "Gate meldet FAILED"                  'grep -q "Public-Hygiene-Gate FAILED" <<<"$OUT"'
check "keine fehlende Funktion"             '! grep -qi "not found\|nicht gefunden" <<<"$OUT"'

echo "2) Hook blockiert im Worktree (git exportiert dort GIT_DIR)"
( cd "$TMP/main" && git reset -q && rm -f bad.txt && git worktree add -q "$TMP/wt" -b wt-test )
( cd "$TMP/wt" && printf 'x=%s/x\n' "$FORBIDDEN" > bad.txt && git add bad.txt )
OUT=$(cd "$TMP/wt" && $GIT commit -q -m bad 2>&1); RC=$?
check "commit im Worktree abgelehnt"        '[ "$RC" -ne 0 ]'
check "Gate meldet FAILED im Worktree"      'grep -q "Public-Hygiene-Gate FAILED" <<<"$OUT"'
check "keine fehlende Funktion im Worktree" '! grep -qi "not found\|nicht gefunden" <<<"$OUT"'

echo "3) Gate mit exportiertem GIT_DIR und cwd scripts/ci findet die Regeln"
OUT=$(cd "$TMP/wt/scripts/ci" && GIT_DIR="$(git rev-parse --absolute-git-dir)" bash public-hygiene.sh --staged 2>&1); RC=$?
check "Verstoss erkannt (exit 1)"           '[ "$RC" -eq 1 ]'
check "Regeldatei gefunden"                 '! grep -q "hygiene-rules.sh" <<<"$OUT"'

echo "4) Fehlende Regeldatei ist ein harter Fehler, kein stilles OK"
rm "$TMP/wt/scripts/lib/hygiene-rules.sh"
OUT=$(cd "$TMP/wt" && bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "exit 2"                              '[ "$RC" -eq 2 ]'
check "meldet fehlende Regeldatei"          'grep -q "hygiene-rules.sh" <<<"$OUT"'
check "meldet kein OK"                      '! grep -q "Public-Hygiene-Gate OK" <<<"$OUT"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
