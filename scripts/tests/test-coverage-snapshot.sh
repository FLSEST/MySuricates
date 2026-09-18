#!/usr/bin/env bash
# Tests fuer den Messblock von workflow-pool/mysuricates-test-coverage.json (TODO 68f37d25).
#
# Das Audit-Center wertet den Envelope des Report-Blocks als vollstaendigen Snapshot:
# ein Finding, das im naechsten Snapshot fehlt, wird RESOLVED. Solange der Messblock
# nur ein Top-N-Fenster liefert, verschwinden Klassen aus dem Snapshot, ohne getestet
# worden zu sein. Dieser Test pinnt deshalb: der Messblock listet ALLE Klassen unter
# der Schwelle, nennt die Gesamtzahl und liefert je Klasse genau eine kompakte Zeile.
#
# Run: bash scripts/tests/test-coverage-snapshot.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEF="$REPO/workflow-pool/mysuricates-test-coverage.json"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# ---------------------------------------------------------------------------
# Fixture: 20 ungetestete Klassen (mehr als das alte Top-15-Fenster), zwei duenn
# getestete und eine Klasse ohne jede "public "-Zeile (die brachte im alten Block
# "grep -c ... || echo 0" zum Ausgeben von zwei Zeilen -> [: Ganzzahl erwartet).
# ---------------------------------------------------------------------------
FIX="$TMP/fixture"
MAIN="$FIX/ai-automation-backend/src/main/java/com/automation"
TESTS="$FIX/ai-automation-backend/src/test/java/com/automation"
mkdir -p "$MAIN/service/deep" "$MAIN/controller" "$TESTS/service/deep" "$FIX/ai-automation-frontend/src"

java_class() {  # $1 = datei, $2 = zeilen, $3 = anzahl public-methoden
  local file="$1" lines="$2" pubs="$3" i
  { echo "package com.automation;"
    for ((i = 0; i < pubs; i++)); do echo "    public void m$i() {}"; done
    for ((i = 0; i < lines - pubs - 1; i++)); do echo "    // filler $i"; done
  } > "$file"
}

for i in $(seq -w 1 20); do
  java_class "$MAIN/service/deep/Untested$i.java" "$((10#$i * 10))" 3
done
# Duenn getestet: 8 public, 2 @Test  -> 25 %
java_class "$MAIN/controller/ThinOne.java" 60 8
{ echo "class ThinOneTest {"; echo "    @Test void a() {}"; echo "    @Test void b() {}"; echo "}"; } \
  > "$TESTS/ThinOneTest.java"
# Duenn getestet: 10 public, 1 @Test -> 10 %
java_class "$MAIN/controller/ThinTwo.java" 70 10
{ echo "class ThinTwoTest {"; echo "    @Test void a() {}"; echo "}"; } > "$TESTS/ThinTwoTest.java"
# Getestet, aber ohne public-Zeile: darf keinen Shell-Fehler ausloesen
{ echo "package com.automation.controller;"; echo "class NoPublic {}"; } > "$MAIN/controller/NoPublic.java"
{ echo "class NoPublicTest {"; echo "    @Test void a() {}"; echo "}"; } > "$TESTS/NoPublicTest.java"
echo "export const x = 1;" > "$FIX/ai-automation-frontend/src/x.ts"
git init -q "$FIX" && ( cd "$FIX" && git -c user.name=t -c user.email=t@example.invalid \
  add -A && git -c user.name=t -c user.email=t@example.invalid commit -q -m init )

# ---------------------------------------------------------------------------
# Messblock aus der Pool-Definition ziehen und gegen das Fixture laufen lassen
# ---------------------------------------------------------------------------
python3 - "$DEF" "$FIX" > "$TMP/command.sh" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
blocks = [b for b in definition['blocks']
          if b.get('config', {}).get('label', '').startswith('Coverage-Metriken')]
assert len(blocks) == 1, f"erwartet genau einen Messblock, gefunden: {len(blocks)}"
print(blocks[0]['config']['command'].replace('{{ project_path }}', sys.argv[2]))
PY
check "Messblock extrahierbar" '[ -s "$TMP/command.sh" ]'

( cd "$FIX" && bash "$TMP/command.sh" ) > "$TMP/out.txt" 2> "$TMP/err.txt"
UNTESTED_ROWS="$TMP/untested.txt"
awk '/^UNTESTED gesamt:/{f=1;next} f && /^[[:space:]]*$/{f=0} f' "$TMP/out.txt" > "$UNTESTED_ROWS"
THIN_ROWS="$TMP/thin.txt"
awk '/^DUENNE TESTS gesamt:/{f=1;next} f && /^[[:space:]]*$/{f=0} f' "$TMP/out.txt" > "$THIN_ROWS"

echo "1) Vollstaendigkeit statt Top-N-Fenster"
check "Zaehlzeile 'UNTESTED gesamt: 20'"      'grep -qx "UNTESTED gesamt: 20" "$TMP/out.txt"'
check "20 gelistete ungetestete Klassen"      '[ "$(wc -l < "$UNTESTED_ROWS")" -eq 20 ]'
check "kleinste Klasse (Rang 20) gelistet"    'grep -q "Untested01.java" "$UNTESTED_ROWS"'
check "groesste Klasse gelistet"              'grep -q "Untested20.java" "$UNTESTED_ROWS"'
check "kein head -15 im Messblock"            '! grep -q "head -15" "$TMP/command.sh"'

echo "2) Kompaktes, stabiles Zeilenformat (paket/Klasse.java<TAB>LOC)"
check "jede Zeile paket/Klasse.java<TAB>LOC" \
  'ROWS=$(grep -cE "^[a-z][a-zA-Z0-9/]*/[A-Za-z0-9]+\.java${TAB}[0-9]+$" "$UNTESTED_ROWS"); [ "$ROWS" -eq 20 ]'
check "kein com/automation-Praefix"           '! grep -q "com/automation" "$UNTESTED_ROWS"'
check "kein absoluter Pfad in den Zeilen"     '! grep -q "^/" "$UNTESTED_ROWS"'
check "absteigend nach LOC sortiert" \
  'cut -f2 "$UNTESTED_ROWS" | sort -rn | cmp -s - <(cut -f2 "$UNTESTED_ROWS")'
check "erste Zeile ist die groesste Klasse"   'head -1 "$UNTESTED_ROWS" | grep -q "Untested20.java"'

echo "3) Duenne Tests ebenfalls vollstaendig und ohne Shell-Fehler"
check "Zaehlzeile 'DUENNE TESTS gesamt: 2'"   'grep -qx "DUENNE TESTS gesamt: 2" "$TMP/out.txt"'
check "2 gelistete duenne Tests"              '[ "$(wc -l < "$THIN_ROWS")" -eq 2 ]'
check "ThinOne gelistet"                      'grep -q "^ThinOne\.java${TAB}" "$THIN_ROWS"'
check "ThinTwo gelistet"                      'grep -q "^ThinTwo\.java${TAB}" "$THIN_ROWS"'
check "keine Ganzzahl-Fehlermeldung (stderr)" '! grep -qiE "Ganzzahl|integer expression" "$TMP/err.txt"'

echo "4) Report-Prompt bindet den Snapshot an die vollstaendige Liste"
PROMPT="$TMP/prompt.txt"
python3 - "$DEF" > "$PROMPT" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
report = definition['metadata']['audit']['reportBlock']
print(next(b['config']['command'] for b in definition['blocks'] if b.get('name') == report))
PY
check "Prompt nennt 'UNTESTED gesamt'"        'grep -q "UNTESTED gesamt" "$PROMPT"'
check "Prompt nennt kein Top-15-Fenster"      '! grep -q "Top 15" "$PROMPT"'
check "Prompt begruendet snapshotComplete"    'grep -q "snapshotComplete" "$PROMPT"'
check "Prompt kennt den Unvollstaendig-Fall"  'grep -q "snapshotComplete\": *false\|snapshotComplete\":false" "$PROMPT"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
