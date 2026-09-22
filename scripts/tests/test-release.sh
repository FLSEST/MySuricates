#!/usr/bin/env bash
# Tests fuer scripts/release.sh (TODO dd82775b). Das Skript laeuft dabei wirklich — in einem
# Wegwerf-Repo mit einer Mini-pom.xml und einer Mini-package.json, damit die Pruefungen nicht
# mitwandern, wenn die echte Version steigt.
#
# Kern der Sache: Der getaggte Commit traegt die Version OHNE -SNAPSHOT (sonst meldet jede
# Installation eines Releases SNAPSHOT, SystemIdentityService), und danach geht es auf der
# naechsten -SNAPSHOT weiter.
#
# Run: bash scripts/tests/test-release.sh   (exit 0 = alle Checks ok)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

command -v npm >/dev/null || { echo "npm fehlt — Test kann das Release nicht fahren"; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- Wegwerf-Repo: so viel Projekt, wie release.sh anfasst ------------------------------------------
mkdir -p "$TMP/scripts" "$TMP/ai-automation-backend" "$TMP/ai-automation-frontend"
cp "$REPO/scripts/release.sh" "$TMP/scripts/release.sh"
cat > "$TMP/ai-automation-backend/pom.xml" <<'POM'
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <version>3.5.16</version>
    </parent>
    <artifactId>ai-automation-backend</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</project>
POM
cat > "$TMP/ai-automation-frontend/package.json" <<'PKG'
{ "name": "mini", "version": "0.1.0", "private": true }
PKG
printf '{ "name": "mini", "version": "0.1.0", "lockfileVersion": 3, "packages": {} }\n' \
  > "$TMP/ai-automation-frontend/package-lock.json"

git -C "$TMP" init -q
git -C "$TMP" config user.email test@example.com
git -C "$TMP" config user.name Test
git -C "$TMP" add -A
git -C "$TMP" commit -qm "Start"
git -C "$TMP" tag v0.1.0

pom_version() { sed -n '/<\/parent>/,$ { s/.*<version>\(.*\)<\/version>.*/\1/p; }' "$TMP/ai-automation-backend/pom.xml" | head -1; }
# npm version schreibt die Datei einzeilig zurueck — deshalb nicht zeilenweise schneiden
json_version() { sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1; }
pkg_version() { json_version < "$TMP/ai-automation-frontend/package.json"; }
at_tag() { git -C "$TMP" show "$1:ai-automation-backend/pom.xml" | sed -n '/<\/parent>/,$ { s/.*<version>\(.*\)<\/version>.*/\1/p; }' | head -1; }

echo "1) Patch-Release nach v0.1.0"
OUT="$( (cd "$TMP" && bash scripts/release.sh patch) 2>&1 )"; RC=$?
check "Skript laeuft durch"                     '[ "$RC" -eq 0 ]'
check "Tag v0.1.1 angelegt"                     'git -C "$TMP" rev-parse -q --verify refs/tags/v0.1.1 >/dev/null'
check "im Tag ohne -SNAPSHOT"                   '[ "$(at_tag v0.1.1)" = "0.1.1" ]'
check "package.json im Tag auf 0.1.1"           '[ "$(git -C "$TMP" show v0.1.1:ai-automation-frontend/package.json | json_version)" = "0.1.1" ]'
check "danach 0.1.2-SNAPSHOT in der pom.xml"    '[ "$(pom_version)" = "0.1.2-SNAPSHOT" ]'
check "package.json zieht mit: 0.1.2-SNAPSHOT"  '[ "$(pkg_version)" = "0.1.2-SNAPSHOT" ]'
check "zwei Commits: Release und Weiter an"     '[ "$(git -C "$TMP" log --format=%s -2 | tr "\\n" "|")" = "Weiter an v0.1.2|Release v0.1.1|" ]'
check "Arbeitsbaum danach sauber"               '[ -z "$(git -C "$TMP" status --porcelain)" ]'

echo "2) Minor-Release zaehlt vom letzten Tag, nicht von der Entwicklungsversion"
OUT3="$( (cd "$TMP" && bash scripts/release.sh minor) 2>&1 )"; RC3=$?
check "Skript laeuft durch"                     '[ "$RC3" -eq 0 ]'
check "v0.2.0 statt v0.2.1"                     'git -C "$TMP" rev-parse -q --verify refs/tags/v0.2.0 >/dev/null'
check "im Tag ohne -SNAPSHOT"                   '[ "$(at_tag v0.2.0)" = "0.2.0" ]'
check "danach 0.2.1-SNAPSHOT"                   '[ "$(pom_version)" = "0.2.1-SNAPSHOT" ]'
check "package.json ebenso 0.2.1-SNAPSHOT"      '[ "$(pkg_version)" = "0.2.1-SNAPSHOT" ]'

echo "3) Schmutziger Arbeitsbaum"
printf 'x\n' >> "$TMP/ai-automation-frontend/package.json"
OUT4="$( (cd "$TMP" && bash scripts/release.sh patch) 2>&1 )"; RC4=$?
check "bricht ab"                               '[ "$RC4" -ne 0 ]'
check "kein Tag v0.2.1"                         '! git -C "$TMP" rev-parse -q --verify refs/tags/v0.2.1 >/dev/null'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
