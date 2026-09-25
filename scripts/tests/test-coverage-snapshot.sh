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
# Fixture: 21 ungetestete Klassen (mehr als das alte Top-15-Fenster), davon eine
# ohne jede "public "-Zeile, zwei duenn getestete und eine getestete Klasse ohne
# "public "-Zeile. Die Klassen ohne "public " sind der Regressionsfall zu
# "grep -c ... || echo 0": grep gibt bei 0 Treffern "0" aus UND Exit 1 zurueck,
# das alte "|| echo 0" haengte eine zweite Null an. Im UNTESTED-Abschnitt
# verschob das die Spalten der Messzeile (im Repo 24 von 115 Zeilen), in den
# duennen Tests brach es die Pruefung mit "Ganzzahliger Ausdruck erwartet" ab.
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
# Ungetestet UND ohne public-Zeile: der im Repo haeufige Fall (24 von 115),
# dessen Messzeile die alte Variante verstuemmelt hat
{ echo "package com.automation.controller;"; echo "enum NoPublicUntested { A, B }"; } \
  > "$MAIN/controller/NoPublicUntested.java"
# Records und Interfaces als Top-Level-Typ sind keine Testgegenstaende (59e22c53) — auch ohne public.
{ echo "package com.automation.service.deep;"; echo "public record PlainRecord(String a) {}"; } \
  > "$MAIN/service/deep/PlainRecord.java"
{ echo "package com.automation.service.deep;"; echo "interface PlainPort {"; echo "    void run();"; echo "}"; } \
  > "$MAIN/service/deep/PlainPort.java"
{ echo "package com.automation.service.deep;"; echo "public sealed interface Shape permits Circle {}"; } \
  > "$MAIN/service/deep/Shape.java"
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
check "Zaehlzeile 'UNTESTED gesamt: 21'"      'grep -qx "UNTESTED gesamt: 21" "$TMP/out.txt"'
check "21 gelistete ungetestete Klassen"      '[ "$(wc -l < "$UNTESTED_ROWS")" -eq 21 ]'
check "kleinste Klasse (Rang 20) gelistet"    'grep -q "Untested01.java" "$UNTESTED_ROWS"'
check "groesste Klasse gelistet"              'grep -q "Untested20.java" "$UNTESTED_ROWS"'
check "Klasse ohne public-Zeile gelistet"     'grep -q "^controller/NoPublicUntested\.java${TAB}[0-9]\+$" "$UNTESTED_ROWS"'
check "kein head -15 im Messblock"            '! grep -q "head -15" "$TMP/command.sh"'
check "Record/Interface nicht gelistet"       '! grep -qE "PlainRecord|PlainPort|Shape" "$UNTESTED_ROWS"'
check "Record/Interface als ausgenommen gezaehlt" \
  'grep -qx "Datenklassen ausgenommen (DTO/Entity/Repository/Event/Enum/Record/Interface): 3" "$TMP/out.txt"'

echo "2) Kompaktes, stabiles Zeilenformat (paket/Klasse.java<TAB>LOC)"
check "jede Zeile paket/Klasse.java<TAB>LOC" \
  'ROWS=$(grep -cE "^[a-z][a-zA-Z0-9/]*/[A-Za-z0-9]+\.java${TAB}[0-9]+$" "$UNTESTED_ROWS"); [ "$ROWS" -eq 21 ]'
check "kein com/automation-Praefix"           '! grep -q "com/automation" "$UNTESTED_ROWS"'
check "kein absoluter Pfad in den Zeilen"     '! grep -q "^/" "$UNTESTED_ROWS"'
check "absteigend nach LOC sortiert" \
  'cut -f2 "$UNTESTED_ROWS" | sort -rn | cmp -s - <(cut -f2 "$UNTESTED_ROWS")'
check "erste Zeile ist die groesste Klasse"   'head -1 "$UNTESTED_ROWS" | grep "Untested20.java" >/dev/null'

echo "3) Duenne Tests ebenfalls vollstaendig und ohne Shell-Fehler"
check "Zaehlzeile 'DUENNE TESTS gesamt: 2'"   'grep -qx "DUENNE TESTS gesamt: 2" "$TMP/out.txt"'
check "2 gelistete duenne Tests"              '[ "$(wc -l < "$THIN_ROWS")" -eq 2 ]'
check "ThinOne gelistet"                      'grep -q "^ThinOne\.java${TAB}" "$THIN_ROWS"'
check "ThinTwo gelistet"                      'grep -q "^ThinTwo\.java${TAB}" "$THIN_ROWS"'
check "keine Ganzzahl-Fehlermeldung (stderr)" '! grep -qiE "Ganzzahl|integer expression" "$TMP/err.txt"'

echo "4) Soll-Groesse des Snapshots"
check "Zeile 'ERWARTETE FINDINGS: 23'"        'grep -qx "ERWARTETE FINDINGS: 23" "$TMP/out.txt"'

echo "5) Report-Prompt bindet den Snapshot an die vollstaendige Liste"
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

echo "6) Gate-Verdict prueft den Snapshot deterministisch"
# Platzhalter werden wie in TemplateResolver.resolveShellSafe single-quoted eingesetzt
# (Block fuehrt "shell_escape_variables": true), damit der Block lokal laufen kann.
render_gate() {  # $1 = previous_output, $2 = metrik-output, $3 = report-output
  python3 - "$DEF" "$1" "$2" "$3" <<'PY'
import json, re, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
blocks = [b for b in definition['blocks']
          if b.get('config', {}).get('label', '').startswith('Gate-Verdict')]
assert len(blocks) == 1, f"erwartet genau einen Gate-Block, gefunden: {len(blocks)}"
config = blocks[0]['config']
assert config.get('shell_escape_variables') is True, "Gate-Block braucht shell_escape_variables"
values = {'previous_output': sys.argv[2],
          'block.metrics.output': sys.argv[3],
          'block.report.output': sys.argv[4]}
def substitute(match):
    key = match.group(1).strip()
    assert key in values, f"unerwarteter Platzhalter: {key}"
    return "'" + values[key].replace("'", "'\\''") + "'"
print(re.sub(r'\{\{\s*([^}]+?)\s*}}', substitute, config['command']))
PY
}

envelope_report() {  # $1 = anzahl ruleKeys, $2 = snapshotComplete (true|false)
  local i findings=""
  for ((i = 0; i < $1; i++)); do
    findings="$findings{\"ruleKey\":\"test-coverage.untested-class\",\"subject\":\"p/C$i.java\",\"severity\":\"MEDIUM\",\"title\":\"t\"},"
  done
  printf '### Coverage-Coach\n\ngate_status: YELLOW\n\n<!-- mysuricates:audit-result:v1\n{"snapshotComplete":%s,"findings":[%s]}\n-->\n' \
    "$2" "${findings%,}"
}

GATE_METRICS="$(printf 'UNTESTED gesamt: 20\nDUENNE TESTS gesamt: 0\nERWARTETE FINDINGS: 20\n')"
GATE_PREV="$(printf 'Kurzfassung\ngate_status: YELLOW\n')"

run_gate() {  # $1 = report-output, schreibt Gate-Ausgabe nach $TMP/gate.txt
  render_gate "$GATE_PREV" "$GATE_METRICS" "$1" > "$TMP/gate.sh"
  bash "$TMP/gate.sh" > "$TMP/gate.txt" 2> "$TMP/gate-err.txt"
}

run_gate "$(envelope_report 20 true)"
check "20/20 Findings: nicht RED"            '! grep -q "gate_status: RED" "$TMP/gate.txt"'
check "20/20 Findings: Status aus Report"    'grep -qx "gate_status: YELLOW" "$TMP/gate.txt"'
check "20/20 Findings: test-coverage"        'grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
check "Gate laeuft ohne stderr"              '[ ! -s "$TMP/gate-err.txt" ]'

run_gate "$(envelope_report 18 true)"
check "18/20 Findings: RED"                  'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "18/20 Findings: audit-incomplete"     'grep -qx "gate_category: audit-incomplete" "$TMP/gate.txt"'
check "18/20 Findings: Zahlen im Grund"      'grep "^gate_description:" "$TMP/gate.txt" | grep "18" >/dev/null && grep "^gate_description:" "$TMP/gate.txt" | grep "20" >/dev/null'

run_gate "$(printf '### Coverage-Coach\n\ngate_status: GREEN\n')"
check "kein Envelope: RED"                   'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "kein Envelope: audit-invalid"         'grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'

run_gate "$(envelope_report 20 false)"
check "snapshotComplete false: RED"          'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "snapshotComplete false: audit-invalid" 'grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'

GATE_METRICS="$(printf 'UNTESTED gesamt: 20\n')" run_gate "$(envelope_report 20 true)"
check "ohne ERWARTETE FINDINGS: RED"         'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "ohne ERWARTETE FINDINGS: incomplete"  'grep -qx "gate_category: audit-incomplete" "$TMP/gate.txt"'

echo "7) Gate zaehlt wie die Ingestion nur den letzten Envelope (1cf996df)"
# previous_report sind die ersten 3000 Zeichen des Vorlaufs, bei kurzen Reports samt Envelope. Gibt das
# Modell ihn wieder, steht vor dem eigenen ein alter Envelope, ganz oder abgeschnitten. Gezaehlt wird
# nur der letzte, wie in AuditResultParser; ein offener letzter gilt als fehlend.
CUT_ECHO="$(printf '%s\n' '<!-- mysuricates:audit-result:v1' \
  '{"snapshotComplete":true,"findings":[{"ruleKey":"test-coverage.untested-class","subject":"p/C0.java"' \
  '[...gekürzt]')"
run_gate "$(envelope_report 18 true; envelope_report 20 true)"
check "alter Envelope (18) vor dem eigenen (20): nicht RED" \
  '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
run_gate "$(printf '%s\n' "$CUT_ECHO"; envelope_report 20 true)"
check "abgeschnittener alter vor dem eigenen: nicht RED" \
  '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
run_gate "$(envelope_report 20 true; printf '%s\n' '<!-- mysuricates:audit-result:v1' '{"snapshotComplete":true,"findings":[')"
check "eigener abgeschnitten nach vollstaendigem altem: RED" \
  'grep -qx "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'
# Beginnt und endet der Envelope in derselben Zeile, zaehlt er ebenso; was danach kommt, gehoert nicht dazu.
GATE_METRICS="$(printf 'UNTESTED gesamt: 1\nDUENNE TESTS gesamt: 0\nERWARTETE FINDINGS: 1\n')" \
  run_gate "$(printf '%s\n' '### Coverage-Coach' 'gate_status: YELLOW' \
  '<!-- mysuricates:audit-result:v1 {"snapshotComplete":true,"findings":[{"ruleKey":"test-coverage.untested-class","subject":"p/C0.java","severity":"MEDIUM","title":"t"}]} -->' \
  'Nachsatz ohne Envelope')"
check "einzeiliger Envelope wird gezaehlt, der Nachsatz nicht" \
  '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
check "Gate laeuft auch hier ohne stderr" '[ ! -s "$TMP/gate-err.txt" ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
